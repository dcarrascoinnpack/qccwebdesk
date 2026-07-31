package cl.faret.qcc.controldocumental.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import cl.faret.qcc.controldocumental.dto.CrearDocumentoRequest;
import cl.faret.qcc.controldocumental.dto.CrearVersionRequest;
import cl.faret.qcc.controldocumental.dto.DocumentoIdResponse;
import cl.faret.qcc.controldocumental.dto.DocumentoListResponse;
import cl.faret.qcc.controldocumental.dto.DocumentoResponse;
import cl.faret.qcc.controldocumental.entity.Documento;
import cl.faret.qcc.controldocumental.service.DocumentoService;

@ExtendWith(MockitoExtension.class)
class DocumentoControllerTest {

    @Mock
    private DocumentoService documentoService;

    @InjectMocks
    private DocumentoController controller;

    @Test
    void listarDevuelve200ConLaListaDelServicio() {
        DocumentoListResponse esperado = new DocumentoListResponse(List.of(), 0, 1, 50);
        when(documentoService.listar(
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(esperado);

        ResponseEntity<DocumentoListResponse> respuesta =
                controller.listar(null, null, null, null, null, null, null);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void listarPropagaLosFiltrosAlServicio() {
        when(documentoService.listar(eq("acme"), isNull(), isNull(), eq("VIGENTE"), isNull(), eq(2), eq(20)))
                .thenReturn(new DocumentoListResponse(List.of(), 0, 2, 20));

        controller.listar("acme", null, null, "VIGENTE", null, 2, 20);

        org.mockito.Mockito.verify(documentoService)
                .listar(eq("acme"), isNull(), isNull(), eq("VIGENTE"), isNull(), eq(2), eq(20));
    }

    @Test
    void obtenerPorIdDevuelve200YDelegaEnElServicio() {
        DocumentoResponse esperado = new DocumentoResponse(documentoMinimo(), List.of());
        when(documentoService.obtenerPorId(1L)).thenReturn(esperado);

        ResponseEntity<DocumentoResponse> respuesta = controller.obtenerPorId(1L);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void crearDevuelve201ConElResultadoDelServicio() {
        CrearDocumentoRequest request = new CrearDocumentoRequest();
        DocumentoIdResponse esperado = new DocumentoIdResponse(1L);
        when(documentoService.crear(request)).thenReturn(esperado);

        ResponseEntity<DocumentoIdResponse> respuesta = controller.crear(request);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void actualizarDevuelve200ConElResultadoDelServicio() {
        Map<String, Object> campos = Map.of("nombre", "Nuevo nombre");
        DocumentoIdResponse esperado = new DocumentoIdResponse(1L);
        when(documentoService.actualizar(1L, campos)).thenReturn(esperado);

        ResponseEntity<DocumentoIdResponse> respuesta = controller.actualizar(1L, campos);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void crearVersionDevuelve201ConElResultadoDelServicio() {
        CrearVersionRequest request = new CrearVersionRequest();
        DocumentoIdResponse esperado = new DocumentoIdResponse(5L);
        when(documentoService.crearVersion(1L, request)).thenReturn(esperado);

        ResponseEntity<DocumentoIdResponse> respuesta = controller.crearVersion(1L, request);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    private Documento documentoMinimo() {
        return new Documento(
                "PRO-001", "Procedimiento", "Calidad", "Nombre del documento",
                "INNPACK", "VIGENTE", "Responsable", "ruta/doc.pdf", null, "jperez");
    }
}
