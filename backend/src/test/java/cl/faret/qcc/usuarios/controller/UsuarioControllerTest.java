package cl.faret.qcc.usuarios.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import cl.faret.qcc.usuarios.dto.CrearUsuarioRequest;
import cl.faret.qcc.usuarios.dto.ResetPasswordRequest;
import cl.faret.qcc.usuarios.dto.UsuarioResponse;
import cl.faret.qcc.usuarios.service.UsuarioService;

@ExtendWith(MockitoExtension.class)
class UsuarioControllerTest {

    @Mock
    private UsuarioService usuarioService;

    @InjectMocks
    private UsuarioController usuarioController;

    @Test
    void listarDevuelve200ConLaListaDeUsuarios() {
        UsuarioResponse usuario = new UsuarioResponse(
                1L, "jperez", "Juan Pérez", "operador", true, LocalDateTime.now());
        when(usuarioService.listar()).thenReturn(List.of(usuario));

        ResponseEntity<List<UsuarioResponse>> response = usuarioController.listar();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void crearDevuelve201ConElUsuarioCreado() {
        CrearUsuarioRequest request = new CrearUsuarioRequest();
        request.setCodigoUsuario("jperez");
        request.setNombreCompleto("Juan Pérez");
        request.setPassword("claveCorrecta123");
        request.setRol("operador");
        request.setActivo(true);

        UsuarioResponse creado = new UsuarioResponse(
                1L, "jperez", "Juan Pérez", "operador", true, LocalDateTime.now());
        when(usuarioService.crear(request)).thenReturn(creado);

        ResponseEntity<UsuarioResponse> response = usuarioController.crear(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getCodigoUsuario()).isEqualTo("jperez");
    }

    @Test
    void eliminarDevuelve204YDelegaEnElServicio() {
        ResponseEntity<Void> response = usuarioController.eliminar(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(usuarioService).eliminar(1L);
    }

    @Test
    void resetPasswordDevuelve204YDelegaEnElServicio() {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setNuevaPassword("nuevaClave123");

        ResponseEntity<Void> response = usuarioController.resetPassword(1L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(usuarioService).resetPassword(1L, "nuevaClave123");
    }
}
