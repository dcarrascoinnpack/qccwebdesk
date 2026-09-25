package cl.faret.qccweb.bridge;

import cl.faret.qccweb.auth.AuditLogger;
import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.upstream.UpstreamNoAutorizadoException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * POST /api/v1/bridge — equivalente web de window.external.sendMessage → MessageRouter de Photino.
 * Recibe el mismo payload que PhotinoBridge.send ({ action, data } o campos en la raíz) y responde
 * el contrato normalizado { ok, success, data, error }.
 *
 * Requiere sesión (401 si no) y token CSRF (403 si no). Toda acción pasa por ActionPolicy.
 *
 * ESCRITURAS (reglas con recurso): antes del handler, límite por usuario (429 sin tocar la API);
 * después, auditoría evento=ESCRITURA con el usuario real de la sesión y el recurso afectado.
 */
@RestController
public class BridgeController {

    static final String MENSAJE_NO_DISPONIBLE = "Acción no disponible en la versión web.";
    static final String MENSAJE_SESION_EXPIRADA = "Sesión expirada. Inicia sesión nuevamente.";
    static final String MENSAJE_ERROR_INTERNO = "Error interno al procesar la acción.";
    static final String MENSAJE_LIMITE_ESCRITURAS = "Demasiadas operaciones seguidas. Espera un momento e inténtalo de nuevo.";
    private static final Pattern FORMATO_ACCION = Pattern.compile("[A-Za-z][A-Za-z0-9]*(\\.[A-Za-z0-9]+){1,4}");
    private static final Logger LOGGER = LoggerFactory.getLogger(BridgeController.class);

    private final ActionPolicy policy;
    private final AuditLogger audit;
    private final EscrituraRateLimiter limiteEscrituras;

    public BridgeController(ActionPolicy policy, AuditLogger audit, EscrituraRateLimiter limiteEscrituras) {
        this.policy = policy;
        this.audit = audit;
        this.limiteEscrituras = limiteEscrituras;
    }

    @PostMapping("/api/v1/bridge")
    public ResponseEntity<Map<String, Object>> ejecutar(
            @RequestBody(required = false) JsonNode cuerpo, HttpServletRequest request) {
        SessionUser usuario = usuarioActual();
        if (usuario == null) {
            return respuesta(HttpStatus.UNAUTHORIZED, BridgeResult.error(MENSAJE_SESION_EXPIRADA));
        }
        if (!(cuerpo instanceof ObjectNode payload)) {
            return respuesta(HttpStatus.BAD_REQUEST, BridgeResult.error("Solicitud inválida."));
        }
        JsonNode accionNodo = payload.get("action");
        String accion = accionNodo != null && accionNodo.isString() ? accionNodo.asString() : null;
        if (accion == null || accion.length() > 100 || !FORMATO_ACCION.matcher(accion).matches()) {
            return respuesta(HttpStatus.BAD_REQUEST, BridgeResult.error("Solicitud inválida."));
        }

        ActionPolicy.Decision decision = policy.evaluar(accion, usuario);
        if (decision instanceof ActionPolicy.Decision.Denegada denegada) {
            audit.accionDenegada(usuario.codigoUsuario(), usuario.empresa(), accion, denegada.motivo());
            return respuesta(HttpStatus.FORBIDDEN, BridgeResult.error(MENSAJE_NO_DISPONIBLE));
        }
        ActionPolicy.Regla regla = ((ActionPolicy.Decision.Permitida) decision).regla();
        if (regla.escritura() && !limiteEscrituras.permitir(usuario.userId())) {
            audit.accionDenegada(usuario.codigoUsuario(), usuario.empresa(), accion, "LIMITE_ESCRITURAS");
            return respuesta(HttpStatus.TOO_MANY_REQUESTS, BridgeResult.error(MENSAJE_LIMITE_ESCRITURAS));
        }

        long inicio = System.nanoTime();
        ObjectNode saneado = IdentityOverride.aplicar(payload.deepCopy(), regla.identidad(), usuario);
        try {
            BridgeResult resultado = regla.handler().ejecutar(saneado, usuario);
            auditar(usuario, accion, regla, saneado, resultado.ok(), resultado.ok() ? resultado.data() : null, inicio);
            return respuesta(HttpStatus.OK, resultado);
        } catch (UpstreamNoAutorizadoException e) {
            // La API rechazó el JWT de ESTE usuario: se invalida solo su sesión.
            HttpSession sesion = request.getSession(false);
            if (regla.escritura()) {
                auditar(usuario, accion, regla, saneado, false, null, inicio);
            }
            audit.sesionInvalidadaPorUpstream(usuario.codigoUsuario(), accion, sesion != null ? sesion.getId() : null);
            if (sesion != null) {
                sesion.invalidate();
            }
            SecurityContextHolder.clearContext();
            return respuesta(HttpStatus.UNAUTHORIZED, BridgeResult.error(MENSAJE_SESION_EXPIRADA));
        } catch (RuntimeException e) {
            // Sin payload ni mensaje de la excepción en el log (podrían contener datos del usuario).
            LOGGER.error("Error ejecutando acción {} para usuario {}: {}", accion, usuario.userId(), e.getClass().getName());
            auditar(usuario, accion, regla, saneado, false, null, inicio);
            return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, BridgeResult.error(MENSAJE_ERROR_INTERNO));
        }
    }

    /** Lectura → evento=ACCION; escritura → evento=ESCRITURA con el recurso afectado (nunca el payload). */
    private void auditar(SessionUser usuario, String accion, ActionPolicy.Regla regla, ObjectNode saneado, boolean ok,
            Object dataRespuesta, long inicio) {
        if (!regla.escritura()) {
            audit.accion(usuario.codigoUsuario(), usuario.empresa(), accion, ok, duracionMs(inicio));
            return;
        }
        String recurso;
        try {
            recurso = regla.recurso().de(saneado, dataRespuesta);
        } catch (RuntimeException e) {
            recurso = "?";
        }
        audit.escritura(usuario.codigoUsuario(), usuario.empresa(), accion, recurso, ok, duracionMs(inicio));
    }

    private static SessionUser usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SessionUser u ? u : null;
    }

    private static long duracionMs(long inicioNanos) {
        return (System.nanoTime() - inicioNanos) / 1_000_000;
    }

    private static ResponseEntity<Map<String, Object>> respuesta(HttpStatus status, BridgeResult resultado) {
        return ResponseEntity.status(status).body(resultado.comoRespuesta());
    }
}
