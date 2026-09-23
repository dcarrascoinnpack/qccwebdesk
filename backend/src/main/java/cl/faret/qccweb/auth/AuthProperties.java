package cl.faret.qccweb.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de autenticación web (qcc.web.auth.*). Ninguno de estos valores es secreto: el
 * gateway no guarda credenciales propias para el login, solo reenvía las del usuario a la API.
 *
 * @param innpackApiBaseUrl  URL base de la API INNPACK (fuente maestra de usuarios INNPACK)
 * @param connectTimeout     timeout de conexión hacia la API
 * @param readTimeout        timeout de lectura hacia la API
 * @param sessionIdleTimeout expiración de la sesión web por inactividad
 * @param sessionMaxDuration tope absoluto de la sesión (además se respeta el exp del JWT upstream)
 * @param loginMinDuration   duración mínima de cada respuesta de login (reduce el canal de tiempo
 *                           que distingue "usuario no existe" de "contraseña incorrecta")
 * @param trustedProxies     IPs del reverse proxy (IIS) cuyo X-Forwarded-For se acepta
 * @param cookieSecure       atributo Secure de la cookie CSRF (la de sesión va en server.servlet.*)
 * @param rateLimit          límites de intentos de login
 */
@ConfigurationProperties(prefix = "qcc.web.auth")
public record AuthProperties(
        String innpackApiBaseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration sessionIdleTimeout,
        Duration sessionMaxDuration,
        Duration loginMinDuration,
        List<String> trustedProxies,
        boolean cookieSecure,
        RateLimit rateLimit) {

    public AuthProperties {
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
    }

    /**
     * @param maxIntentosPorIp     intentos de login (de cualquier usuario) permitidos por IP en la ventana
     * @param ventanaIp            ventana del límite por IP
     * @param fallosAntesDeBloqueo fallos consecutivos de un usuario+IP antes de empezar el backoff
     * @param bloqueoBase          primer bloqueo; se duplica con cada fallo adicional
     * @param bloqueoMaximo        tope del bloqueo por usuario+IP
     */
    public record RateLimit(
            int maxIntentosPorIp,
            Duration ventanaIp,
            int fallosAntesDeBloqueo,
            Duration bloqueoBase,
            Duration bloqueoMaximo) {}
}
