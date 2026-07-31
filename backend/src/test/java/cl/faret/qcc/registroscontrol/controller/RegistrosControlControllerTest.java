package cl.faret.qcc.registroscontrol.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import cl.faret.qcc.registroscontrol.dto.RegistroControlListResponse;
import cl.faret.qcc.registroscontrol.service.RegistroControlModeracionService;
import cl.faret.qcc.registroscontrol.service.RegistrosControlService;

@ExtendWith(MockitoExtension.class)
class RegistrosControlControllerTest {

    @Mock
    private RegistrosControlService registrosControlService;

    @Mock
    private RegistroControlModeracionService moderacionService;

    @InjectMocks
    private RegistrosControlController controller;

    @Test
    void listarDevuelve200ConLaListaDelServicio() {
        RegistroControlListResponse esperado = new RegistroControlListResponse(List.of(), 0, 1, 20);
        when(registrosControlService.listar(null, null, null, null, null, null, null, null))
                .thenReturn(esperado);

        ResponseEntity<RegistroControlListResponse> respuesta =
                controller.listar(null, null, null, null, null, null, null, null);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void validarDevuelve204YDelegaEnElServicioDeModeracion() {
        ResponseEntity<Void> respuesta = controller.validar(1L);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(moderacionService).validarRegistro(1L);
    }

    @Test
    void rechazarDevuelve204YDelegaEnElServicioDeModeracion() {
        ResponseEntity<Void> respuesta = controller.rechazar(1L);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(moderacionService).rechazarRegistro(1L);
    }

    @Test
    void eliminarDevuelve204YDelegaEnElServicioDeModeracion() {
        ResponseEntity<Void> respuesta = controller.eliminar(1L);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(moderacionService).eliminarRegistro(1L);
    }
}
