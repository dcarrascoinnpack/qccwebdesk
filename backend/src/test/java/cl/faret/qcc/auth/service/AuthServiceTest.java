package cl.faret.qcc.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import cl.faret.qcc.auth.dto.LoginRequest;
import cl.faret.qcc.auth.dto.LoginResponse;
import cl.faret.qcc.auth.entity.Usuario;
import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.auth.security.JwtService;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String SECRETO_DE_PRUEBA =
            "secreto-de-prueba-de-al-menos-32-caracteres-para-hs256";

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtService jwtService = new JwtService(SECRETO_DE_PRUEBA, 60);

    @Mock
    private UsuarioRepository usuarioRepository;

    @Test
    void loginFallaSiElUsuarioNoExisteOEstaDesactivado() {
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez"))
                .thenReturn(Optional.empty());

        AuthService authService = new AuthService(usuarioRepository, jwtService);
        LoginResponse response = authService.login(loginRequest("jperez", "cualquiera"));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Usuario no existe");
        assertThat(response.getToken()).isNull();
    }

    @Test
    void loginFallaSiLaContrasenaEsIncorrecta() {
        Usuario usuario = usuarioConPassword("jperez", "claveCorrecta123");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez"))
                .thenReturn(Optional.of(usuario));

        AuthService authService = new AuthService(usuarioRepository, jwtService);
        LoginResponse response = authService.login(loginRequest("jperez", "claveIncorrecta"));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Contraseña incorrecta");
        assertThat(response.getToken()).isNull();
    }

    @Test
    void loginFallaSinExcepcionSiElUsuarioTienePasswordHashNulo() {
        Usuario usuario = new Usuario(2L, "jperez", "Juan Pérez", null, "operador", true, null);
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez"))
                .thenReturn(Optional.of(usuario));

        AuthService authService = new AuthService(usuarioRepository, jwtService);
        LoginResponse response = authService.login(loginRequest("jperez", "cualquiera"));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Contraseña incorrecta");
        assertThat(response.getToken()).isNull();
    }

    @Test
    void loginExitosoDevuelveDatosDelUsuarioYUnTokenValido() {
        Usuario usuario = usuarioConPassword("jperez", "claveCorrecta123");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez"))
                .thenReturn(Optional.of(usuario));

        AuthService authService = new AuthService(usuarioRepository, jwtService);
        LoginResponse response = authService.login(loginRequest("jperez", "claveCorrecta123"));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo("Login correcto");
        assertThat(response.getUserId()).isEqualTo(usuario.getId());
        assertThat(response.getCodigoUsuario()).isEqualTo("jperez");
        assertThat(response.getNombreCompleto()).isEqualTo(usuario.getNombreCompleto());
        assertThat(response.getRol()).isEqualTo(usuario.getRol());

        assertThat(response.getToken()).isNotBlank();
        assertThat(jwtService.validarYObtenerClaims(response.getToken()).getSubject())
                .isEqualTo("jperez");
    }

    private Usuario usuarioConPassword(String codigoUsuario, String passwordEnClaro) {
        return new Usuario(
                1L,
                codigoUsuario,
                "Juan Pérez",
                passwordEncoder.encode(passwordEnClaro),
                "operador",
                true,
                null);
    }

    private LoginRequest loginRequest(String codigoUsuario, String password) {
        LoginRequest request = new LoginRequest();
        request.setCodigoUsuario(codigoUsuario);
        request.setPassword(password);
        return request;
    }
}
