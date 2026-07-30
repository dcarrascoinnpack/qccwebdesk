package cl.faret.qcc.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import cl.faret.qcc.auth.entity.Usuario;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

class JwtServiceTest {

    private static final String SECRETO_DE_PRUEBA =
            "secreto-de-prueba-de-al-menos-32-caracteres-para-hs256";

    private final JwtService jwtService = new JwtService(SECRETO_DE_PRUEBA, 60);

    @Test
    void generaUnTokenValidoConLosClaimsDelUsuario() {
        Usuario usuario = new Usuario(1L, "jperez", "Juan Pérez", "hash", "operador", true, null);

        String token = jwtService.generarToken(usuario);
        Claims claims = jwtService.validarYObtenerClaims(token);

        assertThat(claims.getSubject()).isEqualTo("jperez");
        assertThat(claims.get("userId", Integer.class)).isEqualTo(1);
        assertThat(claims.get("nombreCompleto", String.class)).isEqualTo("Juan Pérez");
        assertThat(claims.get("rol", String.class)).isEqualTo("operador");
    }

    @Test
    void rechazaUnTokenFirmadoConOtroSecreto() {
        JwtService otroServicio = new JwtService("otro-secreto-distinto-de-al-menos-32-caracteres", 60);
        Usuario usuario = new Usuario(1L, "jperez", "Juan Pérez", "hash", "operador", true, null);

        String token = otroServicio.generarToken(usuario);

        assertThatThrownBy(() -> jwtService.validarYObtenerClaims(token))
                .isInstanceOf(JwtException.class);
    }
}
