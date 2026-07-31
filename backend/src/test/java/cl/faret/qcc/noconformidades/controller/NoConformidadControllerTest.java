package cl.faret.qcc.noconformidades.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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

@ExtendWith(MockitoExtension.class)
class NoConformidadControllerTest {

    @Mock
    private NoConformidadService noConformidadService;

    @InjectMocks
    private NoConformidadController controller;

    @Test
    void listarDevuelve200ConLaListaDelServicio() {
        NoConformidadListResponse esperado = new NoConformidadListResponse(List.of(), 0, 1, 50);
        when(noConformidadService.listar(
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(esperado);

        ResponseEntity<NoConformidadListResponse> respuesta =
                controller.listar(null, null, null, null, null, null, null, null, null);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void listarPropagaLosFiltrosAlServicio() {
        LocalDate desde = LocalDate.of(2026, 1, 1);
        LocalDate hasta = LocalDate.of(2026, 12, 31);
        when(noConformidadService.listar(
                eq("acme"), isNull(), eq("Mayor"), isNull(), isNull(), eq(desde), eq(hasta), eq(2), eq(20)))
                .thenReturn(new NoConformidadListResponse(List.of(), 0, 2, 20));

        controller.listar("acme", null, "Mayor", null, null, desde, hasta, 2, 20);

        verify(noConformidadService).listar(
                eq("acme"), isNull(), eq("Mayor"), isNull(), isNull(), eq(desde), eq(hasta), eq(2), eq(20));
    }

    @Test
    void resumenDevuelve200ConElResumenDelServicio() {
        NoConformidadResumenResponse esperado = new NoConformidadResumenResponse(10, 6, 4, 2);
        when(noConformidadService.resumen(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(esperado);

        ResponseEntity<NoConformidadResumenResponse> respuesta =
                controller.resumen(null, null, null, null, null, null, null);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody().getTotal()).isEqualTo(10);
    }

    @Test
    void filtrosOpcionesDevuelve200() {
        FiltrosOpcionesResponse esperado = new FiltrosOpcionesResponse(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(noConformidadService.filtrosOpciones()).thenReturn(esperado);

        ResponseEntity<FiltrosOpcionesResponse> respuesta = controller.filtrosOpciones();

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void obtenerPorIdDevuelve200YDelegaEnElServicio() {
        NoConformidadResponse esperado = new NoConformidadResponse(ncMinima());
        when(noConformidadService.obtenerPorId(1L)).thenReturn(esperado);

        ResponseEntity<NoConformidadResponse> respuesta = controller.obtenerPorId(1L);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void crearDevuelve201ConElResultadoDelServicio() {
        CrearNoConformidadRequest request = new CrearNoConformidadRequest();
        CrearNoConformidadResponse esperado = new CrearNoConformidadResponse(1L, "NC-2026-00001");
        when(noConformidadService.crear(request)).thenReturn(esperado);

        ResponseEntity<CrearNoConformidadResponse> respuesta = controller.crear(request);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void actualizarDevuelve200ConElResultadoDelServicio() {
        Map<String, Object> campos = Map.of("titulo", "Nuevo titulo");
        NoConformidadIdResponse esperado = new NoConformidadIdResponse(1L);
        when(noConformidadService.actualizar(1L, campos)).thenReturn(esperado);

        ResponseEntity<NoConformidadIdResponse> respuesta = controller.actualizar(1L, campos);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void actualizarGestionDevuelve200ConElResultadoDelServicio() {
        ActualizarGestionRequest request = new ActualizarGestionRequest();
        NoConformidadIdResponse esperado = new NoConformidadIdResponse(1L);
        when(noConformidadService.actualizarGestion(1L, request)).thenReturn(esperado);

        ResponseEntity<NoConformidadIdResponse> respuesta = controller.actualizarGestion(1L, request);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void cerrarDevuelve200ConElResultadoDelServicioCuandoNoSeEnviaCuerpo() {
        NoConformidadIdResponse esperado = new NoConformidadIdResponse(1L);
        when(noConformidadService.cerrar(eq(1L), any(CerrarNoConformidadRequest.class))).thenReturn(esperado);

        ResponseEntity<NoConformidadIdResponse> respuesta = controller.cerrar(1L, null);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void listarSeguimientoDevuelve200ConLaListaDelServicio() {
        List<SeguimientoResponse> esperado = List.of();
        when(noConformidadService.listarSeguimiento(1L)).thenReturn(esperado);

        ResponseEntity<List<SeguimientoResponse>> respuesta = controller.listarSeguimiento(1L);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void crearSeguimientoDevuelve201ConElResultadoDelServicio() {
        CrearSeguimientoRequest request = new CrearSeguimientoRequest();
        request.setComentario("Comentario");
        NoConformidadIdResponse esperado = new NoConformidadIdResponse(1L);
        when(noConformidadService.crearSeguimiento(1L, request)).thenReturn(esperado);

        ResponseEntity<NoConformidadIdResponse> respuesta = controller.crearSeguimiento(1L, request);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void obtenerAnalisisDevuelve200ConLoQueDevuelveElServicioAunqueSeaNulo() {
        when(noConformidadService.obtenerAnalisis(1L)).thenReturn(null);

        ResponseEntity<AnalisisResponse> respuesta = controller.obtenerAnalisis(1L);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isNull();
    }

    @Test
    void guardarAnalisisDevuelve200ConElResultadoDelServicio() {
        GuardarAnalisisRequest request = new GuardarAnalisisRequest();
        NoConformidadIdResponse esperado = new NoConformidadIdResponse(5L);
        when(noConformidadService.guardarAnalisis(1L, request)).thenReturn(esperado);

        ResponseEntity<NoConformidadIdResponse> respuesta = controller.guardarAnalisis(1L, request);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void listarAccionesDevuelve200ConLaListaDelServicio() {
        List<AccionCorrectivaResponse> esperado = List.of();
        when(noConformidadService.listarAcciones(1L)).thenReturn(esperado);

        ResponseEntity<List<AccionCorrectivaResponse>> respuesta = controller.listarAcciones(1L);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void crearAccionDevuelve201ConElResultadoDelServicio() {
        CrearAccionRequest request = new CrearAccionRequest();
        NoConformidadIdResponse esperado = new NoConformidadIdResponse(9L);
        when(noConformidadService.crearAccion(1L, request)).thenReturn(esperado);

        ResponseEntity<NoConformidadIdResponse> respuesta = controller.crearAccion(1L, request);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void actualizarAccionDevuelve200ConElResultadoDelServicio() {
        ActualizarAccionRequest request = new ActualizarAccionRequest();
        NoConformidadIdResponse esperado = new NoConformidadIdResponse(9L);
        when(noConformidadService.actualizarAccion(9L, request)).thenReturn(esperado);

        ResponseEntity<NoConformidadIdResponse> respuesta = controller.actualizarAccion(1L, 9L, request);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    private cl.faret.qcc.noconformidades.entity.NoConformidad ncMinima() {
        return new cl.faret.qcc.noconformidades.entity.NoConformidad(
                "NC-2026-00001",        // codigo
                "INTERNA",              // tipo
                "AUDITORIA_INTERNA",    // origen
                "Titulo",               // titulo
                "Descripcion",          // descripcion
                "MEDIA",                // severidad
                "Proceso",              // proceso
                null,                   // norma
                null,                   // reportadoPor
                LocalDate.now(),        // fechaDeteccion
                "ABIERTA",              // estado
                "PENDIENTE",            // estadoGestion
                null,                   // tipoPnc
                null,                   // fechaIngreso
                null,                   // fechaSalida
                "NP-1",                 // npNv
                "acme",                 // cliente
                null,                   // codigoProducto
                "Producto",             // producto
                null,                   // cantRequerida
                null,                   // cantRechazada
                null,                   // cantRecuperada
                null,                   // pncReal
                null,                   // pctRecuperacion
                null,                   // fechaFabricacion
                "Defecto",              // descripcionDefecto
                "Categoria",            // categoriaDefecto
                "Mayor",                // nivel
                null,                   // tipoFalla
                null,                   // area
                null,                   // maquina
                null,                   // operador
                null,                   // supervisor
                null,                   // revisadoPor
                null,                   // impacto
                null,                   // observacion
                null,                   // causaRaiz
                null,                   // accionesCorrectivas
                null,                   // verificacionSeguimiento
                "jperez");              // creadoPor
    }
}
