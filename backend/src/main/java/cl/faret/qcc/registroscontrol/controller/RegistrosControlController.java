package cl.faret.qcc.registroscontrol.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.faret.qcc.registroscontrol.dto.RegistroControlListResponse;
import cl.faret.qcc.registroscontrol.service.RegistroControlModeracionService;
import cl.faret.qcc.registroscontrol.service.RegistrosControlService;

@RestController
@RequestMapping("/api/v1/registros-control")
public class RegistrosControlController {

    private final RegistrosControlService registrosControlService;
    private final RegistroControlModeracionService moderacionService;

    public RegistrosControlController(
            RegistrosControlService registrosControlService,
            RegistroControlModeracionService moderacionService) {
        this.registrosControlService = registrosControlService;
        this.moderacionService = moderacionService;
    }

    @GetMapping
    public ResponseEntity<RegistroControlListResponse> listar(
            @RequestParam(required = false) Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaDesde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaHasta,
            @RequestParam(required = false) String np,
            @RequestParam(required = false) String turno,
            @RequestParam(required = false) Long estadoId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit) {

        return ResponseEntity.ok(
                registrosControlService.listar(id, fechaDesde, fechaHasta, np, turno, estadoId, page, limit));
    }

    @PostMapping("/{id}/validar")
    public ResponseEntity<Void> validar(@PathVariable Long id) {
        moderacionService.validarRegistro(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/rechazar")
    public ResponseEntity<Void> rechazar(@PathVariable Long id) {
        moderacionService.rechazarRegistro(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/eliminar")
    public ResponseEntity<Void> eliminar(@PathVariable Long id) {
        moderacionService.eliminarRegistro(id);
        return ResponseEntity.noContent().build();
    }
}
