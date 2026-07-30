package cl.faret.qcc.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import cl.faret.qcc.auth.dto.LoginRequest;
import cl.faret.qcc.auth.dto.LoginResponse;
import cl.faret.qcc.auth.service.AuthService;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @InjectMocks
    private AuthController authController;

    @Test
    void devuelve200ConElTokenCuandoElLoginEsExitoso() {
        LoginRequest request = loginRequest("jperez", "claveCorrecta123");
        LoginResponse exito = LoginResponse.exito(usuarioDePrueba(), "token-de-prueba");
        when(authService.login(request)).thenReturn(exito);

        ResponseEntity<LoginResponse> response = authController.login(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getToken()).isEqualTo("token-de-prueba");
    }

    @Test
    void devuelve401SinTokenCuandoLasCredencialesSonInvalidas() {
        LoginRequest request = loginRequest("jperez", "claveIncorrecta");
        when(authService.login(request)).thenReturn(LoginResponse.fallo("Contraseña incorrecta"));

        ResponseEntity<LoginResponse> response = authController.login(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getToken()).isNull();
    }

    private cl.faret.qcc.auth.entity.Usuario usuarioDePrueba() {
        return new cl.faret.qcc.auth.entity.Usuario(
                1L, "jperez", "Juan Pérez", "hash", "operador", true, null);
    }

    private LoginRequest loginRequest(String codigoUsuario, String password) {
        LoginRequest request = new LoginRequest();
        request.setCodigoUsuario(codigoUsuario);
        request.setPassword(password);
        return request;
    }
}
