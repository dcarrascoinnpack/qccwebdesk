package cl.faret.qccweb.auth;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import tools.jackson.databind.ObjectMapper;

/** Beans de autenticación web. Los filtros NO son @Component para no registrarse dos veces. */
@Configuration
public class AuthConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public AuditLogger auditLogger() {
        return new AuditLogger();
    }

    @Bean
    public ClientIpResolver clientIpResolver(AuthProperties properties) {
        return new ClientIpResolver(properties.trustedProxies());
    }

    @Bean
    public LoginRateLimiter loginRateLimiter(AuthProperties properties, Clock clock) {
        return new LoginRateLimiter(properties.rateLimit(), clock);
    }

    @Bean
    public InnpackAuthClient innpackAuthClient(AuthProperties properties, ObjectMapper mapper) {
        return new InnpackAuthClient(properties, mapper);
    }

    @Bean
    public FaretAuthClient faretAuthClient(AuthProperties properties, ObjectMapper mapper) {
        return new FaretAuthClient(properties, mapper);
    }

    /** Contexto de seguridad guardado solo en la sesión HTTP del servidor. */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * Token CSRF en cookie legible por JS (necesario para que web-bridge.js lo reenvíe en
     * X-XSRF-TOKEN). No es un secreto de sesión: solo prueba que la petición viene de esta página.
     */
    @Bean
    public CsrfTokenRepository csrfTokenRepository(AuthProperties properties) {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookieCustomizer(c -> c.secure(properties.cookieSecure()).sameSite("Strict").path("/"));
        return repo;
    }
}
