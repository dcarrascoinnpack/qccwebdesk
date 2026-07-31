package cl.faret.qcc.laboratorio.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.faret.qcc.laboratorio.dto.LaboratorioCatalogosResponse;
import cl.faret.qcc.laboratorio.dto.LaboratorioResumenResponse;
import cl.faret.qcc.laboratorio.service.LaboratorioService;

@RestController
@RequestMapping("/api/v1/laboratorio")
public class LaboratorioController {

    private final LaboratorioService laboratorioService;

    public LaboratorioController(LaboratorioService laboratorioService) {
        this.laboratorioService = laboratorioService;
    }

    @GetMapping("/resumen")
    public ResponseEntity<LaboratorioResumenResponse> resumen(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaDesde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaHasta,
            @RequestParam(required = false) Long ensayoId,
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false, defaultValue = "false") boolean sinLimite) {

        return ResponseEntity.ok(
                laboratorioService.resumen(fechaDesde, fechaHasta, ensayoId, materialId, sinLimite));
    }

    @GetMapping("/catalogos")
    public ResponseEntity<LaboratorioCatalogosResponse> catalogos() {
        return ResponseEntity.ok(laboratorioService.catalogos());
    }
}
