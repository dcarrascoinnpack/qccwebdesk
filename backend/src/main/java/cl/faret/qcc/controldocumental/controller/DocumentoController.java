package cl.faret.qcc.controldocumental.controller;

import java.util.Map;

import jakarta.validation.Valid;

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

import cl.faret.qcc.controldocumental.dto.CrearDocumentoRequest;
import cl.faret.qcc.controldocumental.dto.CrearVersionRequest;
import cl.faret.qcc.controldocumental.dto.DocumentoIdResponse;
import cl.faret.qcc.controldocumental.dto.DocumentoListResponse;
import cl.faret.qcc.controldocumental.dto.DocumentoResponse;
import cl.faret.qcc.controldocumental.service.DocumentoService;

@RestController
@RequestMapping("/api/v1/documentos")
public class DocumentoController {

    private final DocumentoService documentoService;

    public DocumentoController(DocumentoService documentoService) {
        this.documentoService = documentoService;
    }

    @GetMapping
    public ResponseEntity<DocumentoListResponse> listar(
            @RequestParam(required = false) String texto,
            @RequestParam(required = false) String tipoDocumento,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String alcanceEmpresa,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {

        return ResponseEntity.ok(
                documentoService.listar(texto, tipoDocumento, area, estado, alcanceEmpresa, page, pageSize));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentoResponse> obtenerPorId(@PathVariable Long id) {
        return ResponseEntity.ok(documentoService.obtenerPorId(id));
    }

    @PostMapping
    public ResponseEntity<DocumentoIdResponse> crear(@Valid @RequestBody CrearDocumentoRequest request) {
        DocumentoIdResponse response = documentoService.crear(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<DocumentoIdResponse> actualizar(
            @PathVariable Long id, @RequestBody Map<String, Object> campos) {
        return ResponseEntity.ok(documentoService.actualizar(id, campos));
    }

    @PostMapping("/{id}/versiones")
    public ResponseEntity<DocumentoIdResponse> crearVersion(
            @PathVariable Long id, @Valid @RequestBody CrearVersionRequest request) {
        DocumentoIdResponse response = documentoService.crearVersion(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
