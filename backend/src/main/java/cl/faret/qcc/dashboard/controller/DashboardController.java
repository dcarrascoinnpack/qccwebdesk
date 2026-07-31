package cl.faret.qcc.dashboard.controller;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.faret.qcc.dashboard.dto.DashboardFiltrosResponse;
import cl.faret.qcc.dashboard.dto.DashboardResumenResponse;
import cl.faret.qcc.dashboard.service.DashboardService;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/resumen")
    public ResponseEntity<DashboardResumenResponse> resumen(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaDesde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaHasta,
            @RequestParam(required = false) Long inspectorId,
            @RequestParam(required = false) String turno,
            @RequestParam(required = false) Long procesoId) {

        return ResponseEntity.ok(dashboardService.resumen(fechaDesde, fechaHasta, inspectorId, turno, procesoId));
    }

    @GetMapping("/filtros")
    public ResponseEntity<DashboardFiltrosResponse> filtros() {
        return ResponseEntity.ok(dashboardService.filtros());
    }

    @PostMapping("/validar-todo")
    public ResponseEntity<Map<String, Integer>> validarTodo(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaDesde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaHasta,
            @RequestParam(required = false) Long inspectorId,
            @RequestParam(required = false) String turno,
            @RequestParam(required = false) Long procesoId) {

        int afectados = dashboardService.validarTodo(fechaDesde, fechaHasta, inspectorId, turno, procesoId);
        return ResponseEntity.ok(Map.of("actualizados", afectados));
    }

    @PostMapping("/rechazar-todo")
    public ResponseEntity<Map<String, Integer>> rechazarTodo(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaDesde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaHasta,
            @RequestParam(required = false) Long inspectorId,
            @RequestParam(required = false) String turno,
            @RequestParam(required = false) Long procesoId) {

        int afectados = dashboardService.rechazarTodo(fechaDesde, fechaHasta, inspectorId, turno, procesoId);
        return ResponseEntity.ok(Map.of("actualizados", afectados));
    }
}
