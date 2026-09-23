package cl.faret.qccweb.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tamaño máximo de cuerpo para /api/** (bridge y autenticación).
 *
 * Spring/Tomcat no ofrecen una propiedad que limite cuerpos JSON de @RequestBody
 * (server.tomcat.max-http-form-post-size solo aplica a formularios), por eso este filtro mínimo:
 * - Content-Length mayor al límite → 413, sin leer el cuerpo.
 * - Cuerpo sin Content-Length (Transfer-Encoding: chunked) → 411: el navegador (fetch con cuerpo
 *   string) siempre envía Content-Length, así el límite no se puede esquivar con chunked.
 * Se ejecuta antes de Spring Security: un cuerpo excesivo se descarta sin procesar sesión ni CSRF.
 */
public final class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestSizeLimitFilter(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String metodo = request.getMethod();
        return !(("POST".equals(metodo) || "PUT".equals(metodo) || "PATCH".equals(metodo))
                && request.getRequestURI().startsWith(request.getContextPath() + "/api/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long largo = request.getContentLengthLong();
        String transferEncoding = request.getHeader("Transfer-Encoding");
        // Sin Content-Length ni Transfer-Encoding no hay cuerpo (p. ej. un POST de logout vacío).
        if (largo < 0 && transferEncoding != null) {
            rechazar(response, HttpServletResponse.SC_LENGTH_REQUIRED, "Solicitud inválida.");
            return;
        }
        if (largo > maxBytes) {
            rechazar(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "La solicitud excede el tamaño máximo permitido.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void rechazar(HttpServletResponse response, int status, String mensaje) throws IOException {
        response.setStatus(status);
        response.setHeader("Connection", "close");
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"ok\":false,\"success\":false,\"data\":null,\"error\":\"" + mensaje + "\"}");
    }
}
