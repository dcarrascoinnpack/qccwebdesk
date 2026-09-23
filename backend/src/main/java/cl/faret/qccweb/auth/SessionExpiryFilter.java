package cl.faret.qccweb.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Expiración absoluta de la sesión web: al llegar al vencimiento del JWT upstream (o al tope
 * configurado) la sesión se invalida y la petición sigue como anónima (→ 401 en rutas protegidas).
 * La expiración por inactividad la aplica el contenedor (maxInactiveInterval).
 */
public final class SessionExpiryFilter extends OncePerRequestFilter {

    private final Clock clock;
    private final AuditLogger audit;

    public SessionExpiryFilter(Clock clock, AuditLogger audit) {
        this.clock = clock;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SessionUser usuario = AuthController.usuarioDe(SecurityContextHolder.getContext().getAuthentication());
        if (usuario != null && usuario.expirada(clock.instant())) {
            HttpSession sesion = request.getSession(false);
            if (sesion != null) {
                audit.sesionExpirada(usuario.codigoUsuario(), sesion.getId());
                sesion.invalidate();
            }
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
