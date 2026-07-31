package cl.faret.qcc.laboratorio.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import cl.faret.qcc.auth.entity.Usuario;
import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.laboratorio.dto.CatalogoItemResponse;
import cl.faret.qcc.laboratorio.dto.LaboratorioCatalogosResponse;
import cl.faret.qcc.laboratorio.dto.LaboratorioResumenResponse;
import cl.faret.qcc.laboratorio.dto.RegistroEnsayoItemResponse;
import cl.faret.qcc.laboratorio.entity.EnsayoLaboratorio;
import cl.faret.qcc.laboratorio.entity.Material;
import cl.faret.qcc.laboratorio.entity.RegistroEnsayo;
import cl.faret.qcc.laboratorio.repository.EnsayoLaboratorioRepository;
import cl.faret.qcc.laboratorio.repository.MaterialRepository;
import cl.faret.qcc.laboratorio.repository.RegistroEnsayoRepository;
import cl.faret.qcc.registroscontrol.entity.Proceso;
import cl.faret.qcc.registroscontrol.entity.RegistroControl;
import cl.faret.qcc.registroscontrol.repository.ProcesoRepository;
import cl.faret.qcc.registroscontrol.repository.RegistroControlRepository;
import cl.faret.qcc.registroscontrol.service.RegistroControlEnriquecimientoService;
import cl.faret.qcc.registroscontrol.service.RegistroControlFiltros;
import cl.faret.qcc.registroscontrol.service.TurnoCalculator;

// Reemplaza LaboratorioHandler.cs / LaboratorioRepository.cs.
//
// Corrige 2 problemas reales encontrados en la investigacion:
// - Inyeccion SQL en BuildFiltros (concatenacion directa de id/fechas/ensayo/material) -- aca todo
//   pasa por Specification/parametros JPA.
// - Duplicacion de filas por el LEFT JOIN a registro_adjuntos sin acotar a un unico adjunto (los
//   otros 3 modulos del Cluster B si usaban MIN(id)) -- aca se reutiliza
//   RegistroControlEnriquecimientoService, que ya toma un unico adjunto por registro.
@Service
public class LaboratorioService {

    private static final int DIAS_VENTANA_DEFECTO = 30;
    private static final int LIMITE_REGISTROS_DEFECTO = 300;

    private final RegistroEnsayoRepository registroEnsayoRepository;
    private final EnsayoLaboratorioRepository ensayoLaboratorioRepository;
    private final MaterialRepository materialRepository;
    private final RegistroControlRepository registroControlRepository;
    private final ProcesoRepository procesoRepository;
    private final UsuarioRepository usuarioRepository;
    private final RegistroControlEnriquecimientoService enriquecimientoService;

    public LaboratorioService(
            RegistroEnsayoRepository registroEnsayoRepository,
            EnsayoLaboratorioRepository ensayoLaboratorioRepository,
            MaterialRepository materialRepository,
            RegistroControlRepository registroControlRepository,
            ProcesoRepository procesoRepository,
            UsuarioRepository usuarioRepository,
            RegistroControlEnriquecimientoService enriquecimientoService) {
        this.registroEnsayoRepository = registroEnsayoRepository;
        this.ensayoLaboratorioRepository = ensayoLaboratorioRepository;
        this.materialRepository = materialRepository;
        this.registroControlRepository = registroControlRepository;
        this.procesoRepository = procesoRepository;
        this.usuarioRepository = usuarioRepository;
        this.enriquecimientoService = enriquecimientoService;
    }

    public LaboratorioResumenResponse resumen(
            LocalDate fechaDesde, LocalDate fechaHasta, Long ensayoId, Long materialId, boolean sinLimite) {

        LocalDate hoy = LocalDate.now();
        boolean fechasEspecificadas = fechaDesde != null || fechaHasta != null;

        long ensayosHoy = registroEnsayoRepository.count(
                filtro(idsRegistroControlEnRango(hoy, hoy), null, null));

        LocalDate desde = fechaDesde != null ? fechaDesde : hoy.minusDays(DIAS_VENTANA_DEFECTO - 1);
        LocalDate hasta = fechaHasta != null ? fechaHasta : hoy;

        List<Long> idsPeriodo = idsRegistroControlEnRango(desde, hasta);
        List<RegistroEnsayo> ensayosPeriodo =
                registroEnsayoRepository.findAll(filtro(idsPeriodo, ensayoId, materialId));

        boolean mostrandoHistorico = false;
        if (ensayosPeriodo.isEmpty() && !fechasEspecificadas) {
            List<Long> idsTodos = idsRegistroControlEnRango(null, null);
            ensayosPeriodo = registroEnsayoRepository.findAll(filtro(idsTodos, ensayoId, materialId));
            mostrandoHistorico = !ensayosPeriodo.isEmpty();
        }

        long tiposEnsayo = ensayosPeriodo.stream().map(RegistroEnsayo::getEnsayoId).distinct().count();
        long materialesAnalizados = ensayosPeriodo.stream()
                .map(RegistroEnsayo::getMaterialId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();

        List<RegistroEnsayoItemResponse> items = mapearItems(ensayosPeriodo, sinLimite);

        LocalDate fechaUltimoRegistro = mostrandoHistorico && !items.isEmpty()
                ? items.get(0).getFechaRegistro()
                : null;

        return new LaboratorioResumenResponse(
                ensayosHoy, ensayosPeriodo.size(), tiposEnsayo, materialesAnalizados,
                mostrandoHistorico, fechaUltimoRegistro, items);
    }

    public LaboratorioCatalogosResponse catalogos() {
        List<CatalogoItemResponse> ensayos = ensayoLaboratorioRepository.findByActivoTrueOrderByNombreAsc().stream()
                .map(e -> new CatalogoItemResponse(e.getId(), e.getNombre()))
                .toList();
        List<CatalogoItemResponse> materiales = materialRepository.findByActivoTrueOrderByNombreAsc().stream()
                .map(m -> new CatalogoItemResponse(m.getId(), m.getNombre()))
                .toList();
        return new LaboratorioCatalogosResponse(ensayos, materiales);
    }

    private List<Long> idsRegistroControlEnRango(LocalDate desde, LocalDate hasta) {
        Specification<RegistroControl> filtro =
                RegistroControlFiltros.construir(null, desde, hasta, null, null, null, null, null);
        return registroControlRepository.findAll(filtro).stream().map(RegistroControl::getId).toList();
    }

    private Specification<RegistroEnsayo> filtro(List<Long> registroIds, Long ensayoId, Long materialId) {
        return (root, query, cb) -> {
            Predicate predicado = root.get("registroId").in(registroIds);
            if (ensayoId != null) {
                predicado = cb.and(predicado, cb.equal(root.get("ensayoId"), ensayoId));
            }
            if (materialId != null) {
                predicado = cb.and(predicado, cb.equal(root.get("materialId"), materialId));
            }
            return predicado;
        };
    }

    private List<RegistroEnsayoItemResponse> mapearItems(List<RegistroEnsayo> ensayos, boolean sinLimite) {
        if (ensayos.isEmpty()) {
            return List.of();
        }

        List<Long> registroIds = ensayos.stream().map(RegistroEnsayo::getRegistroId).distinct().toList();
        Map<Long, RegistroControl> registros = registroControlRepository.findAllById(registroIds).stream()
                .collect(Collectors.toMap(RegistroControl::getId, r -> r));

        Map<Long, String> nombresUsuarios = usuarioRepository
                .findByIdIn(registros.values().stream().map(RegistroControl::getUsuarioId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Usuario::getId, Usuario::getNombreCompleto));
        Map<Long, String> nombresProcesos = procesoRepository
                .findAllById(registros.values().stream().map(RegistroControl::getProcesoId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Proceso::getId, Proceso::getNombre));
        Map<Long, String> nombresEnsayos = ensayoLaboratorioRepository
                .findAllById(ensayos.stream().map(RegistroEnsayo::getEnsayoId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(EnsayoLaboratorio::getId, EnsayoLaboratorio::getNombre));
        Map<Long, String> nombresMateriales = materialRepository
                .findAllById(ensayos.stream()
                        .map(RegistroEnsayo::getMaterialId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(Material::getId, Material::getNombre));
        var adjuntos = enriquecimientoService.primerAdjuntoPorRegistro(registroIds);

        return ensayos.stream()
                .sorted(Comparator.comparing((RegistroEnsayo e) -> {
                    RegistroControl rc = registros.get(e.getRegistroId());
                    return rc != null ? rc.getFechaRegistro() : null;
                }, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(sinLimite ? Long.MAX_VALUE : LIMITE_REGISTROS_DEFECTO)
                .map(e -> {
                    RegistroControl rc = registros.get(e.getRegistroId());
                    return new RegistroEnsayoItemResponse(
                            e.getId(),
                            e.getRegistroId(),
                            rc != null ? rc.getFechaRegistro() : null,
                            rc != null ? rc.getHoraRegistro() : null,
                            rc != null ? TurnoCalculator.calcular(rc.getHoraRegistro()) : null,
                            rc != null ? rc.getNp() : null,
                            rc != null ? nombresUsuarios.get(rc.getUsuarioId()) : null,
                            rc != null ? nombresProcesos.get(rc.getProcesoId()) : null,
                            nombresEnsayos.get(e.getEnsayoId()),
                            e.getMaterialId() != null ? nombresMateriales.get(e.getMaterialId()) : null,
                            e.getValor(),
                            e.getObservacion(),
                            adjuntos.containsKey(e.getRegistroId())
                                    ? adjuntos.get(e.getRegistroId()).getRutaArchivo()
                                    : null);
                })
                .toList();
    }
}
