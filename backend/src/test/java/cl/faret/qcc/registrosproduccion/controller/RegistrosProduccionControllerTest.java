package cl.faret.qcc.registrosproduccion.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import cl.faret.qcc.dashboard.dto.DashboardResumenResponse;
import cl.faret.qcc.registrosproduccion.service.RegistrosProduccionService;

@ExtendWith(MockitoExtension.class)
class RegistrosProduccionControllerTest {

    @Mock
    private RegistrosProduccionService registrosProduccionService;

    @InjectMocks
    private RegistrosProduccionController controller;

    @Test
    void resumenDevuelve200ConElResultadoDelServicio() {
        DashboardResumenResponse esperado = new DashboardResumenResponse(
                0, 0, 0, BigDecimal.ZERO, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(registrosProduccionService.resumen(null, null, null, null, null)).thenReturn(esperado);

        ResponseEntity<DashboardResumenResponse> respuesta = controller.resumen(null, null, null, null, null);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void validarDevuelve204YDelegaEnElServicio() {
        ResponseEntity<Void> respuesta = controller.validar(1L);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(registrosProduccionService).validarRegistro(1L);
    }
}
