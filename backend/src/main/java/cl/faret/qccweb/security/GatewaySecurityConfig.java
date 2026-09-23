package cl.faret.qccweb.security;

import cl.faret.qccweb.auth.AuditLogger;
import cl.faret.qccweb.auth.SessionExpiryFilter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Seguridad del gateway (Fases 1a + 1b).
 *
 * - Deny-by-default: solo el frontend estático, /version, el health y los endpoints de
 *   autenticación son públicos. Todo lo demás exige una sesión autenticada (401 si no).
 * - Sesión server-side (cookie QCC_SESSION, ver gateway.yaml). La sesión solo se crea en un login
 *   correcto: request cache deshabilitado y guardado explícito del contexto de seguridad.
 * - CSRF en cookie XSRF-TOKEN + cabecera X-XSRF-TOKEN (patrón SPA), también para login/logout.
 *
 * CSP: el frontend compartido de Photino inyecta sus controllers como &lt;script&gt; inline
 * (core/app.js, App.loadModule) y carga flatpickr/xlsx desde jsdelivr, por eso script-src necesita
 * 'unsafe-inline' + cdn.jsdelivr.net. La vista previa de adjuntos usa &lt;iframe src="data:..."&gt;
 * (frame-src data:) y las fotos de inspecciones vienen de api.faret.cl (img-src).
 */
@Configuration
public class GatewaySecurityConfig {

    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net",
            "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net",
            "img-src 'self' data: blob: https://api.faret.cl",
            "font-src 'self' data: https://cdn.jsdelivr.net",
            "connect-src 'self'",
            "frame-src 'self' data: blob:",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    /** Lista blanca del frontend compartido (estructura de src/UI/www de Photino + web/ del shim). */
    private static final String[] FRONTEND_ESTATICO = {
        "/", "/index.html", "/*.png", "/*.jpg",
        "/core/**", "/shared/**", "/modules/**", "/libs/**", "/web/**"
    };

    @Bean
    public SecurityFilterChain gatewaySecurityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            Clock clock,
            AuditLogger auditLogger) throws Exception {
        http
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(c -> c.securityContextRepository(securityContextRepository))
                // La fijación de sesión se resuelve en AuthController (invalida y crea sesión nueva).
                .sessionManagement(s -> s
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(f -> f.none()))
                .csrf(c -> c.spa().csrfTokenRepository(csrfTokenRepository))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .addFilterAfter(new SessionExpiryFilter(clock, auditLogger), CsrfCookieFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .frameOptions(f -> f.deny()))
                .authorizeHttpRequests(auth -> auth
                        // Sin esto, un 403 (p. ej. CSRF) se reenvía a /error y termina como 401.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/version", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/session").permitAll()
                        .requestMatchers(HttpMethod.GET, FRONTEND_ESTATICO).permitAll()
                        .requestMatchers(HttpMethod.HEAD, FRONTEND_ESTATICO).permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}
