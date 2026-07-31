package cl.faret.qcc.noconformidades.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.faret.qcc.noconformidades.dto.AccionCorrectivaResponse;
import cl.faret.qcc.noconformidades.dto.ActualizarAccionRequest;
import cl.faret.qcc.noconformidades.dto.ActualizarGestionRequest;
import cl.faret.qcc.noconformidades.dto.AnalisisResponse;
import cl.faret.qcc.noconformidades.dto.CerrarNoConformidadRequest;
import cl.faret.qcc.noconformidades.dto.CrearAccionRequest;
import cl.faret.qcc.noconformidades.dto.CrearNoConformidadRequest;
import cl.faret.qcc.noconformidades.dto.CrearNoConformidadResponse;
import cl.faret.qcc.noconformidades.dto.CrearSeguimientoRequest;
import cl.faret.qcc.noconformidades.dto.FiltrosOpcionesResponse;
import cl.faret.qcc.noconformidades.dto.GuardarAnalisisRequest;
import cl.faret.qcc.noconformidades.dto.NoConformidadIdResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadListResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadResumenResponse;
import cl.faret.qcc.noconformidades.dto.SeguimientoResponse;
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

    @PatchMapping("/{id}")
    public ResponseEntity<NoConformidadIdResponse> actualizar(
            @PathVariable Long id, @RequestBody Map<String, Object> campos) {
        return ResponseEntity.ok(noConformidadService.actualizar(id, campos));
    }

    @PatchMapping("/{id}/gestion")
    public ResponseEntity<NoConformidadIdResponse> actualizarGestion(
            @PathVariable Long id, @RequestBody ActualizarGestionRequest request) {
        return ResponseEntity.ok(noConformidadService.actualizarGestion(id, request));
    }

    @PostMapping("/{id}/cerrar")
    public ResponseEntity<NoConformidadIdResponse> cerrar(
            @PathVariable Long id, @RequestBody(required = false) CerrarNoConformidadRequest request) {
        CerrarNoConformidadRequest cuerpo = request != null ? request : new CerrarNoConformidadRequest();
        return ResponseEntity.ok(noConformidadService.cerrar(id, cuerpo));
    }

    @GetMapping("/{id}/seguimiento")
    public ResponseEntity<List<SeguimientoResponse>> listarSeguimiento(@PathVariable Long id) {
        return ResponseEntity.ok(noConformidadService.listarSeguimiento(id));
    }

    @PostMapping("/{id}/seguimiento")
    public ResponseEntity<NoConformidadIdResponse> crearSeguimiento(
            @PathVariable Long id, @Valid @RequestBody CrearSeguimientoRequest request) {
        NoConformidadIdResponse response = noConformidadService.crearSeguimiento(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}/analisis")
    public ResponseEntity<AnalisisResponse> obtenerAnalisis(@PathVariable Long id) {
        return ResponseEntity.ok(noConformidadService.obtenerAnalisis(id));
    }

    @PostMapping("/{id}/analisis")
    public ResponseEntity<NoConformidadIdResponse> guardarAnalisis(
            @PathVariable Long id, @Valid @RequestBody GuardarAnalisisRequest request) {
        return ResponseEntity.ok(noConformidadService.guardarAnalisis(id, request));
    }

    @GetMapping("/{id}/acciones")
    public ResponseEntity<List<AccionCorrectivaResponse>> listarAcciones(@PathVariable Long id) {
        return ResponseEntity.ok(noConformidadService.listarAcciones(id));
    }

    @PostMapping("/{id}/acciones")
    public ResponseEntity<NoConformidadIdResponse> crearAccion(
            @PathVariable Long id, @Valid @RequestBody CrearAccionRequest request) {
        NoConformidadIdResponse response = noConformidadService.crearAccion(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{id}/acciones/{accionId}")
    public ResponseEntity<NoConformidadIdResponse> actualizarAccion(
            @PathVariable Long id, @PathVariable Long accionId,
            @Valid @RequestBody ActualizarAccionRequest request) {
        return ResponseEntity.ok(noConformidadService.actualizarAccion(accionId, request));
    }
}
