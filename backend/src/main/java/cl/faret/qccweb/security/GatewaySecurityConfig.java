package cl.faret.qccweb.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Seguridad del gateway — Fase 1a.
 *
 * Deny-by-default: solo el frontend estático, /version y el health son públicos (GET). Todo lo
 * demás (incluido /api/v1/bridge) responde 401 hasta que la Fase 1b agregue login y sesión.
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
    public SecurityFilterChain gatewaySecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .frameOptions(f -> f.deny()))
                .authorizeHttpRequests(auth -> auth
                        // Sin esto, un 403 (p. ej. CSRF) se reenvía a /error y termina como 401.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/version", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, FRONTEND_ESTATICO).permitAll()
                        .requestMatchers(HttpMethod.HEAD, FRONTEND_ESTATICO).permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}
