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
 * Ruta de archivos (/api/v1/bridge/archivo, Fase 3k): tope propio más alto y, antes de aceptar un cuerpo grande,
 * exige que la petición traiga una cookie de sesión (sin ella → 401 sin leer el cuerpo; la validez de la sesión
 * la verifica después Spring Security).
 */
public final class RequestSizeLimitFilter extends OncePerRequestFilter {

    public static final String RUTA_ARCHIVO = "/api/v1/bridge/archivo";

    private final long maxBytes;
    private final long maxBytesArchivo;

    public RequestSizeLimitFilter(long maxBytes) {
        this(maxBytes, maxBytes);
    }

    public RequestSizeLimitFilter(long maxBytes, long maxBytesArchivo) {
        this.maxBytes = maxBytes;
        this.maxBytesArchivo = maxBytesArchivo;
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
        boolean archivo = request.getRequestURI().equals(request.getContextPath() + RUTA_ARCHIVO);
        if (archivo && largo > maxBytes && request.getRequestedSessionId() == null) {
            rechazar(response, HttpServletResponse.SC_UNAUTHORIZED, "Sesión expirada. Inicia sesión nuevamente.");
            return;
        }
        if (largo > (archivo ? maxBytesArchivo : maxBytes)) {
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
