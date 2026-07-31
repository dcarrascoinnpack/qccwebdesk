package cl.faret.qcc.dashboard.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.dashboard.dto.DashboardFiltrosResponse;
import cl.faret.qcc.dashboard.dto.DashboardResumenResponse;
import cl.faret.qcc.dashboard.dto.ProcesoFiltroResponse;
import cl.faret.qcc.dashboard.dto.UsuarioFiltroResponse;
import cl.faret.qcc.registroscontrol.repository.ProcesoRepository;

// Reemplaza DashboardHandler.cs / DashboardRepository.cs ("Inspecciones Calidad" en la UI). El
// calculo real (resumen/validarTodo/rechazarTodo) vive en ResumenOperacionalService, compartido con
// RegistrosProduccionService -- en el Photino ambos modulos son el mismo reporte clonado
// literalmente, solo cambiando el filtro de `area` (ver comentario de ResumenOperacionalService).
//
// Correccion respecto a una version anterior de este servicio: el filtro de area
// IN ('CALIDAD','CALIDAD INNPACK') faltaba (se detecto al investigar RegistrosProduccion y
// confirmar, por comparacion de codigo, que el Dashboard real del Photino si filtra por esa
// columna). Sin este filtro, el resumen mezclaba registros de Calidad y de Produccion.
@Service
public class DashboardService {

    private static final List<String> AREAS_CALIDAD = List.of("CALIDAD", "CALIDAD INNPACK");

    private final ResumenOperacionalService resumenOperacionalService;
    private final UsuarioRepository usuarioRepository;
    private final ProcesoRepository procesoRepository;

    public DashboardService(
            ResumenOperacionalService resumenOperacionalService,
            UsuarioRepository usuarioRepository,
            ProcesoRepository procesoRepository) {
        this.resumenOperacionalService = resumenOperacionalService;
        this.usuarioRepository = usuarioRepository;
        this.procesoRepository = procesoRepository;
    }

    public DashboardResumenResponse resumen(
            LocalDate fechaDesde, LocalDate fechaHasta, Long inspectorId, String turno, Long procesoId) {
        return resumenOperacionalService.resumen(AREAS_CALIDAD, fechaDesde, fechaHasta, inspectorId, turno, procesoId);
    }

    public DashboardFiltrosResponse filtros() {
        List<UsuarioFiltroResponse> usuarios = usuarioRepository.findByActivoTrueOrderByNombreCompletoAsc().stream()
                .map(u -> new UsuarioFiltroResponse(u.getId(), u.getNombreCompleto()))
                .toList();
        List<ProcesoFiltroResponse> procesos = procesoRepository.findByActivoTrueOrderByNombreAsc().stream()
                .map(p -> new ProcesoFiltroResponse(p.getId(), p.getNombre()))
                .toList();
        return new DashboardFiltrosResponse(usuarios, procesos);
    }

    public int validarTodo(LocalDate fechaDesde, LocalDate fechaHasta, Long inspectorId, String turno, Long procesoId) {
        return resumenOperacionalService.validarTodo(AREAS_CALIDAD, fechaDesde, fechaHasta, inspectorId, turno, procesoId);
    }

    public int rechazarTodo(LocalDate fechaDesde, LocalDate fechaHasta, Long inspectorId, String turno, Long procesoId) {
        return resumenOperacionalService.rechazarTodo(AREAS_CALIDAD, fechaDesde, fechaHasta, inspectorId, turno, procesoId);
    }
}
