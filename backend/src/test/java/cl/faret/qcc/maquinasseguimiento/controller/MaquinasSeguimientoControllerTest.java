package cl.faret.qcc.maquinasseguimiento.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import cl.faret.qcc.maquinasseguimiento.dto.MaquinasSeguimientoResumenResponse;
import cl.faret.qcc.maquinasseguimiento.service.MaquinasSeguimientoService;

@ExtendWith(MockitoExtension.class)
class MaquinasSeguimientoControllerTest {

    @Mock
    private MaquinasSeguimientoService maquinasSeguimientoService;

    @InjectMocks
    private MaquinasSeguimientoController controller;

    @Test
    void resumenDevuelve200ConElResultadoDelServicio() {
        MaquinasSeguimientoResumenResponse esperado =
                new MaquinasSeguimientoResumenResponse(0, 0, List.of(), null, null, List.of());
        when(maquinasSeguimientoService.resumen(null, false)).thenReturn(esperado);

        ResponseEntity<MaquinasSeguimientoResumenResponse> respuesta = controller.resumen(null, false);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }
}
