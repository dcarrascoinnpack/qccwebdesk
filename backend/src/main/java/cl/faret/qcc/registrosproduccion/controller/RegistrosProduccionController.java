package cl.faret.qcc.registrosproduccion.controller;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.faret.qcc.dashboard.dto.DashboardFiltrosResponse;
import cl.faret.qcc.dashboard.dto.DashboardResumenResponse;
import cl.faret.qcc.registrosproduccion.service.RegistrosProduccionService;

@RestController
@RequestMapping("/api/v1/registros-produccion")
public class RegistrosProduccionController {

    private final RegistrosProduccionService registrosProduccionService;

    public RegistrosProduccionController(RegistrosProduccionService registrosProduccionService) {
        this.registrosProduccionService = registrosProduccionService;
    }

    @GetMapping("/resumen")
    public ResponseEntity<DashboardResumenResponse> resumen(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaDesde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaHasta,
            @RequestParam(required = false) Long inspectorId,
            @RequestParam(required = false) String turno,
            @RequestParam(required = false) Long procesoId) {

        return ResponseEntity.ok(
                registrosProduccionService.resumen(fechaDesde, fechaHasta, inspectorId, turno, procesoId));
    }

    @GetMapping("/filtros")
    public ResponseEntity<DashboardFiltrosResponse> filtros() {
        return ResponseEntity.ok(registrosProduccionService.filtros());
    }

    @PostMapping("/validar-todo")
    public ResponseEntity<Map<String, Integer>> validarTodo(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaDesde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaHasta,
            @RequestParam(required = false) Long inspectorId,
            @RequestParam(required = false) String turno,
            @RequestParam(required = false) Long procesoId) {

        int afectados = registrosProduccionService.validarTodo(fechaDesde, fechaHasta, inspectorId, turno, procesoId);
        return ResponseEntity.ok(Map.of("actualizados", afectados));
    }

    @PostMapping("/rechazar-todo")
    public ResponseEntity<Map<String, Integer>> rechazarTodo(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaDesde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaHasta,
            @RequestParam(required = false) Long inspectorId,
            @RequestParam(required = false) String turno,
            @RequestParam(required = false) Long procesoId) {

        int afectados = registrosProduccionService.rechazarTodo(fechaDesde, fechaHasta, inspectorId, turno, procesoId);
        return ResponseEntity.ok(Map.of("actualizados", afectados));
    }

    @PostMapping("/{id}/validar")
    public ResponseEntity<Void> validar(@PathVariable Long id) {
        registrosProduccionService.validarRegistro(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/rechazar")
    public ResponseEntity<Void> rechazar(@PathVariable Long id) {
        registrosProduccionService.rechazarRegistro(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/eliminar")
    public ResponseEntity<Void> eliminar(@PathVariable Long id) {
        registrosProduccionService.eliminarRegistro(id);
        return ResponseEntity.noContent().build();
    }
}
