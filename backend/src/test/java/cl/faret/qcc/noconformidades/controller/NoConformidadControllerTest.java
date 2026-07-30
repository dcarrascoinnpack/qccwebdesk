package cl.faret.qcc.noconformidades.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import cl.faret.qcc.noconformidades.dto.CrearNoConformidadRequest;
import cl.faret.qcc.noconformidades.dto.CrearNoConformidadResponse;
import cl.faret.qcc.noconformidades.dto.FiltrosOpcionesResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadListResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadResumenResponse;
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
