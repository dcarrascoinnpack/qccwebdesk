package cl.faret.qcc.dashboard.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import cl.faret.qcc.auth.entity.Usuario;
import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.dashboard.dto.ControlesPorProcesoResponse;
import cl.faret.qcc.dashboard.dto.CumplimientoInspectorResponse;
import cl.faret.qcc.dashboard.dto.DashboardResumenResponse;
import cl.faret.qcc.dashboard.dto.NoConformidadPorInspectorResponse;
import cl.faret.qcc.dashboard.dto.TendenciaCumplimientoResponse;
import cl.faret.qcc.registroscontrol.dto.RegistroControlItemResponse;
import cl.faret.qcc.registroscontrol.entity.Proceso;
import cl.faret.qcc.registroscontrol.entity.RegistroControl;
import cl.faret.qcc.registroscontrol.repository.ProcesoRepository;
import cl.faret.qcc.registroscontrol.repository.RegistroControlRepository;
import cl.faret.qcc.registroscontrol.service.RegistroControlEnriquecimientoService;
import cl.faret.qcc.registroscontrol.service.RegistroControlFiltros;
import cl.faret.qcc.registroscontrol.service.RegistroControlModeracionService;
import cl.faret.qcc.registroscontrol.service.RegistrosControlService;

// Reemplaza DashboardRepository.cs Y RegistrosProduccionRepository.cs: en el Photino son el mismo
// reporte clonado literalmente (mismo SQL, palabra por palabra, solo cambiando el filtro de
// `area`) -- confirmado en la investigacion ("Dashboard Producción" filtra
// area IN ('CALIDAD','CALIDAD INNPACK'), RegistrosProduccion filtra area = 'PRODUCCION'). En vez
// de repetir esa duplicacion en Java, este servicio queda parametrizado por `areas` y lo usan
// tanto DashboardService (Inspecciones Calidad) como RegistrosProduccionService (Inspecciones
// Produccion).
//
// A diferencia del original (SQL crudo por sub-reporte, con inyeccion SQL real en BuildFiltros),
// aca se trae una sola vez el conjunto de registros del periodo filtrado (Specification
// parametrizada) y las metricas/listados se calculan en memoria sobre ese mismo conjunto --
// razonable para el volumen de esta tabla (~1000 filas).
//
// "*Hoy" (controlesHoy/mermaHoy/registrosConObservacionHoy) se calculan siempre sobre HOY,
// independiente del rango fechaDesde/fechaHasta del filtro general (son KPIs fijos del dia, igual
// que en el Photino). El resto usa el filtro general, con ventana por defecto de 6 dias si no se
// especifica (mismo default que CargarUltimosRegistros en el Photino).
@Service
public class ResumenOperacionalService {

    private static final int DIAS_VENTANA_DEFECTO = 6;
    private static final int LIMITE_ULTIMOS_REGISTROS = 20;

    private final RegistroControlRepository registroControlRepository;
    private final UsuarioRepository usuarioRepository;
    private final ProcesoRepository procesoRepository;
    private final RegistroControlEnriquecimientoService enriquecimientoService;
    private final RegistrosControlService registrosControlService;
    private final RegistroControlModeracionService moderacionService;

    public ResumenOperacionalService(
            RegistroControlRepository registroControlRepository,
            UsuarioRepository usuarioRepository,
            ProcesoRepository procesoRepository,
            RegistroControlEnriquecimientoService enriquecimientoService,
            RegistrosControlService registrosControlService,
            RegistroControlModeracionService moderacionService) {
        this.registroControlRepository = registroControlRepository;
        this.usuarioRepository = usuarioRepository;
        this.procesoRepository = procesoRepository;
        this.enriquecimientoService = enriquecimientoService;
        this.registrosControlService = registrosControlService;
        this.moderacionService = moderacionService;
    }

    public DashboardResumenResponse resumen(List<String> areas, LocalDate fechaDesde, LocalDate fechaHasta,
            Long inspectorId, String turno, Long procesoId) {

        LocalDate hoy = LocalDate.now();
        LocalDate desde = fechaDesde != null ? fechaDesde : hoy.minusDays(DIAS_VENTANA_DEFECTO - 1);
        LocalDate hasta = fechaHasta != null ? fechaHasta : hoy;

        Specification<RegistroControl> filtroHoy =
                RegistroControlFiltros.construir(null, hoy, hoy, null, null, null, null, null, areas);
        Specification<RegistroControl> filtroPeriodo =
                filtroPeriodo(areas, desde, hasta, inspectorId, turno, procesoId);

        List<RegistroControl> registrosHoy = registroControlRepository.findAll(filtroHoy);
        List<RegistroControl> registrosPeriodo = registroControlRepository.findAll(filtroPeriodo);

        List<Long> idsPeriodo = registrosPeriodo.stream().map(RegistroControl::getId).toList();
        Map<Long, List<String>> tiposDefectoPeriodo = enriquecimientoService.tipoDefectoPorRegistro(idsPeriodo);

        BigDecimal mermaHoy = registrosHoy.stream()
                .filter(RegistroControl::isRequiereMerma)
                .map(RegistroControl::getCantidadMerma)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long registrosConObservacionHoy = registrosHoy.stream()
                .filter(r -> StringUtils.hasText(r.getObservacion()))
                .count();
        long noConformidadesDetectadas =
                idsPeriodo.stream().filter(tiposDefectoPeriodo::containsKey).count();

        Map<Long, String> nombresUsuarios = usuarioRepository
                .findByIdIn(registrosPeriodo.stream().map(RegistroControl::getUsuarioId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Usuario::getId, Usuario::getNombreCompleto));
        Map<Long, String> nombresProcesos = procesoRepository
                .findAllById(registrosPeriodo.stream().map(RegistroControl::getProcesoId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Proceso::getId, Proceso::getNombre));

        List<CumplimientoInspectorResponse> cumplimientoPorInspector =
                calcularCumplimientoPorInspector(registrosPeriodo, tiposDefectoPeriodo, nombresUsuarios);
        List<NoConformidadPorInspectorResponse> noConformidadesPorInspector =
                calcularNoConformidadesPorInspector(registrosPeriodo, tiposDefectoPeriodo, nombresUsuarios);
        List<ControlesPorProcesoResponse> controlesPorProceso =
                calcularControlesPorProceso(registrosPeriodo, nombresProcesos);
        List<TendenciaCumplimientoResponse> tendenciaCumplimiento =
                calcularTendenciaCumplimiento(registrosPeriodo, tiposDefectoPeriodo);

        List<RegistroControlItemResponse> ultimosRegistros = registrosControlService.listar(
                        null, desde, hasta, null, turno, null, 1, LIMITE_ULTIMOS_REGISTROS, areas)
                .getItems();

        return new DashboardResumenResponse(
                registrosHoy.size(),
                registrosPeriodo.size(),
                noConformidadesDetectadas,
                mermaHoy,
                registrosConObservacionHoy,
                cumplimientoPorInspector,
                noConformidadesPorInspector,
                controlesPorProceso,
                tendenciaCumplimiento,
                cumplimientoPorInspector,
                ultimosRegistros);
    }

    // A diferencia del Photino (validarTodo/rechazarTodo sin ninguna clausula WHERE ademas del
    // area, actualizando todo el historico de esa area -- bug real confirmado en la
    // investigacion), aca se aplican ademas los mismos filtros que el resumen/listado en pantalla.
    public int validarTodo(List<String> areas, LocalDate fechaDesde, LocalDate fechaHasta, Long inspectorId,
            String turno, Long procesoId) {
        return moderacionService.validarTodo(
                filtroConDefault(areas, fechaDesde, fechaHasta, inspectorId, turno, procesoId));
    }

    public int rechazarTodo(List<String> areas, LocalDate fechaDesde, LocalDate fechaHasta, Long inspectorId,
            String turno, Long procesoId) {
        return moderacionService.rechazarTodo(
                filtroConDefault(areas, fechaDesde, fechaHasta, inspectorId, turno, procesoId));
    }

    private Specification<RegistroControl> filtroConDefault(List<String> areas, LocalDate fechaDesde,
            LocalDate fechaHasta, Long inspectorId, String turno, Long procesoId) {
        LocalDate hoy = LocalDate.now();
        LocalDate desde = fechaDesde != null ? fechaDesde : hoy.minusDays(DIAS_VENTANA_DEFECTO - 1);
        LocalDate hasta = fechaHasta != null ? fechaHasta : hoy;
        return filtroPeriodo(areas, desde, hasta, inspectorId, turno, procesoId);
    }

    private Specification<RegistroControl> filtroPeriodo(List<String> areas, LocalDate desde, LocalDate hasta,
            Long inspectorId, String turno, Long procesoId) {
        return RegistroControlFiltros.construir(
                null, desde, hasta, null, turno, null, procesoId, inspectorId, areas);
    }

    private List<CumplimientoInspectorResponse> calcularCumplimientoPorInspector(
            List<RegistroControl> registros, Map<Long, List<String>> tiposDefecto, Map<Long, String> nombres) {

        return registros.stream()
                .collect(Collectors.groupingBy(RegistroControl::getUsuarioId))
                .entrySet().stream()
                .map(entry -> {
                    long total = entry.getValue().size();
                    long conFalla = entry.getValue().stream()
                            .filter(r -> tiposDefecto.containsKey(r.getId()))
                            .count();
                    return new CumplimientoInspectorResponse(
                            entry.getKey(), nombres.get(entry.getKey()), total,
                            calcularPorcentaje(total - conFalla, total));
                })
                .sorted(Comparator.comparing(CumplimientoInspectorResponse::getUsuarioNombre,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private List<NoConformidadPorInspectorResponse> calcularNoConformidadesPorInspector(
            List<RegistroControl> registros, Map<Long, List<String>> tiposDefecto, Map<Long, String> nombres) {

        return registros.stream()
                .collect(Collectors.groupingBy(RegistroControl::getUsuarioId))
                .entrySet().stream()
                .map(entry -> new NoConformidadPorInspectorResponse(
                        entry.getKey(),
                        nombres.get(entry.getKey()),
                        entry.getValue().stream().filter(r -> tiposDefecto.containsKey(r.getId())).count()))
                .sorted(Comparator.comparing(NoConformidadPorInspectorResponse::getUsuarioNombre,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private List<ControlesPorProcesoResponse> calcularControlesPorProceso(
            List<RegistroControl> registros, Map<Long, String> nombresProcesos) {

        return registros.stream()
                .collect(Collectors.groupingBy(RegistroControl::getProcesoId))
                .entrySet().stream()
                .map(entry -> new ControlesPorProcesoResponse(
                        entry.getKey(), nombresProcesos.get(entry.getKey()), entry.getValue().size()))
                .sorted(Comparator.comparing(ControlesPorProcesoResponse::getProcesoNombre,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private List<TendenciaCumplimientoResponse> calcularTendenciaCumplimiento(
            List<RegistroControl> registros, Map<Long, List<String>> tiposDefecto) {

        Map<LocalDate, List<RegistroControl>> porFecha = registros.stream()
                .collect(Collectors.groupingBy(RegistroControl::getFechaRegistro, TreeMap::new, Collectors.toList()));

        return porFecha.entrySet().stream()
                .map(entry -> {
                    long total = entry.getValue().size();
                    long conFalla = entry.getValue().stream()
                            .filter(r -> tiposDefecto.containsKey(r.getId()))
                            .count();
                    return new TendenciaCumplimientoResponse(entry.getKey(), calcularPorcentaje(total - conFalla, total));
                })
                .toList();
    }

    // El Photino devuelve 100% cuando total<=0 (CalcularPorcentaje, DashboardRepository.cs:461-467)
    // -- se corrige aca a 0%, para no mostrar "100% de cumplimiento" cuando en realidad no hubo
    // controles en el periodo. Es una correccion, no una replica exacta.
    private double calcularPorcentaje(long cumplidos, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return Math.round(cumplidos * 10000.0 / total) / 100.0;
    }
}
