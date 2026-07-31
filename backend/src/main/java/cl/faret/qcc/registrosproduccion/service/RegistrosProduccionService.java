package cl.faret.qcc.registrosproduccion.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.dashboard.dto.DashboardFiltrosResponse;
import cl.faret.qcc.dashboard.dto.DashboardResumenResponse;
import cl.faret.qcc.dashboard.dto.ProcesoFiltroResponse;
import cl.faret.qcc.dashboard.dto.UsuarioFiltroResponse;
import cl.faret.qcc.dashboard.service.ResumenOperacionalService;
import cl.faret.qcc.registroscontrol.repository.ProcesoRepository;
import cl.faret.qcc.registroscontrol.service.RegistroControlModeracionService;

// Reemplaza RegistrosProduccionHandler.cs / RegistrosProduccionRepository.cs ("Inspecciones
// Produccion" en la UI). Confirmado en la investigacion: es un clon literal de Dashboard (mismas 8
// acciones, mismo SQL palabra por palabra) que solo cambia el filtro de area
// (area='PRODUCCION' en vez de area IN ('CALIDAD','CALIDAD INNPACK')). Por eso reutiliza
// ResumenOperacionalService en vez de duplicar el calculo -- la duplicacion completa entre ambos
// modulos es justamente uno de los hallazgos de la investigacion (mantenimiento duplicado en el
// Photino, con el historial de bugs arreglados dos veces por separado).
//
// Reutiliza los DTOs de `dashboard.dto` (DashboardResumenResponse, DashboardFiltrosResponse, etc.)
// tal cual: son genericos ("resumen operacional por area"), no exclusivos de la pantalla
// Dashboard -- crear una copia identica solo para este modulo hubiera sido la misma duplicacion
// que se busca evitar.
//
// Diferencia real de fidelidad respecto a Dashboard/RegistrosControl: en el Photino,
// validarRegistro/rechazarRegistro/eliminarRegistro individuales de este modulo SI estan
// restringidos a `area='PRODUCCION'` (WHERE id=@id AND area='PRODUCCION'); si el id pertenece a
// otra area, no se replica -- 404, no una operacion sin efecto que igual responde exito.
@Service
public class RegistrosProduccionService {

    private static final List<String> AREAS_PRODUCCION = List.of("PRODUCCION");

    private final ResumenOperacionalService resumenOperacionalService;
    private final RegistroControlModeracionService moderacionService;
    private final UsuarioRepository usuarioRepository;
    private final ProcesoRepository procesoRepository;

    public RegistrosProduccionService(
            ResumenOperacionalService resumenOperacionalService,
            RegistroControlModeracionService moderacionService,
            UsuarioRepository usuarioRepository,
            ProcesoRepository procesoRepository) {
        this.resumenOperacionalService = resumenOperacionalService;
        this.moderacionService = moderacionService;
        this.usuarioRepository = usuarioRepository;
        this.procesoRepository = procesoRepository;
    }

    public DashboardResumenResponse resumen(
            LocalDate fechaDesde, LocalDate fechaHasta, Long inspectorId, String turno, Long procesoId) {
        return resumenOperacionalService.resumen(
                AREAS_PRODUCCION, fechaDesde, fechaHasta, inspectorId, turno, procesoId);
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
        return resumenOperacionalService.validarTodo(
                AREAS_PRODUCCION, fechaDesde, fechaHasta, inspectorId, turno, procesoId);
    }

    public int rechazarTodo(LocalDate fechaDesde, LocalDate fechaHasta, Long inspectorId, String turno, Long procesoId) {
        return resumenOperacionalService.rechazarTodo(
                AREAS_PRODUCCION, fechaDesde, fechaHasta, inspectorId, turno, procesoId);
    }

    public void validarRegistro(Long id) {
        moderacionService.validarRegistro(id, AREAS_PRODUCCION);
    }

    public void rechazarRegistro(Long id) {
        moderacionService.rechazarRegistro(id, AREAS_PRODUCCION);
    }

    public void eliminarRegistro(Long id) {
        moderacionService.eliminarRegistro(id, AREAS_PRODUCCION);
    }
}
