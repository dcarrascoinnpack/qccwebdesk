package cl.faret.qcc.noconformidades.controller;

import java.time.LocalDate;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.faret.qcc.noconformidades.dto.CrearNoConformidadRequest;
import cl.faret.qcc.noconformidades.dto.CrearNoConformidadResponse;
import cl.faret.qcc.noconformidades.dto.FiltrosOpcionesResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadListResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadResumenResponse;
import cl.faret.qcc.noconformidades.service.NoConformidadService;

@RestController
@RequestMapping("/api/v1/no-conformidades")
public class NoConformidadController {

    private final NoConformidadService noConformidadService;

    public NoConformidadController(NoConformidadService noConformidadService) {
        this.noConformidadService = noConformidadService;
    }

    @GetMapping
    public ResponseEntity<NoConformidadListResponse> listar(
            @RequestParam(required = false) String cliente,
            @RequestParam(required = false) String tipoPnc,
            @RequestParam(required = false) String nivel,
            @RequestParam(required = false) String estadoGestion,
            @RequestParam(required = false) String responsable,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaDesde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaHasta,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {

        return ResponseEntity.ok(noConformidadService.listar(
                cliente, tipoPnc, nivel, estadoGestion, responsable, fechaDesde, fechaHasta, page, pageSize));
    }

    @GetMapping("/resumen")
    public ResponseEntity<NoConformidadResumenResponse> resumen(
            @RequestParam(required = false) String cliente,
            @RequestParam(required = false) String tipoPnc,
            @RequestParam(required = false) String nivel,
            @RequestParam(required = false) String estadoGestion,
            @RequestParam(required = false) String responsable,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaDesde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaHasta) {

        return ResponseEntity.ok(noConformidadService.resumen(
                cliente, tipoPnc, nivel, estadoGestion, responsable, fechaDesde, fechaHasta));
    }

    @GetMapping("/filtros-opciones")
    public ResponseEntity<FiltrosOpcionesResponse> filtrosOpciones() {
        return ResponseEntity.ok(noConformidadService.filtrosOpciones());
    }

    @GetMapping("/{id}")
    public ResponseEntity<NoConformidadResponse> obtenerPorId(@PathVariable Long id) {
        return ResponseEntity.ok(noConformidadService.obtenerPorId(id));
    }

    @PostMapping
    public ResponseEntity<CrearNoConformidadResponse> crear(
            @Valid @RequestBody CrearNoConformidadRequest request) {
        CrearNoConformidadResponse response = noConformidadService.crear(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
