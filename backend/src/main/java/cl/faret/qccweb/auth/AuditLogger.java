package cl.faret.qccweb.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auditoría de autenticación (logger "qcc.audit"). Nunca registra contraseñas, tokens ni payloads;
 * el id de sesión (que es una credencial) solo como prefijo de su SHA-256. Los valores que vienen
 * del cliente se sanean para evitar inyección de líneas en el log.
 */
public final class AuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("qcc.audit");
    private static final int MAX_LARGO = 64;

    public void loginOk(String usuario, String ip, String empresa, String sessionId) {
        AUDIT.info("evento=LOGIN_OK usuario={} empresa={} ip={} sesion={}",
                sanear(usuario), empresa, sanear(ip), hashSesion(sessionId));
    }

    public void loginFallido(String usuario, String ip, String motivo) {
        AUDIT.warn("evento=LOGIN_FALLIDO usuario={} ip={} motivo={}", sanear(usuario), sanear(ip), sanear(motivo));
    }

    public void loginBloqueado(String usuario, String ip, String motivo) {
        AUDIT.warn("evento=LOGIN_BLOQUEADO usuario={} ip={} motivo={}", sanear(usuario), sanear(ip), motivo);
    }

    public void loginErrorUpstream(String usuario, String ip, String detalle) {
        AUDIT.error("evento=LOGIN_ERROR_UPSTREAM usuario={} ip={} detalle={}", sanear(usuario), sanear(ip), sanear(detalle));
    }

    public void logout(String usuario, String ip, String sessionId) {
        AUDIT.info("evento=LOGOUT usuario={} ip={} sesion={}", sanear(usuario), sanear(ip), hashSesion(sessionId));
    }

    public void sesionExpirada(String usuario, String sessionId) {
        AUDIT.info("evento=SESION_EXPIRADA usuario={} sesion={}", sanear(usuario), hashSesion(sessionId));
    }

    public void accion(String usuario, String empresa, String accion, boolean ok, long duracionMs) {
        AUDIT.info("evento=ACCION usuario={} empresa={} accion={} resultado={} ms={}",
                sanear(usuario), empresa, sanear(accion), ok ? "OK" : "ERROR", duracionMs);
    }

    /**
     * Escritura ejecutada vía bridge: usuario REAL de la sesión, recurso afectado (p. ej. nc:501),
     * resultado y duración. Nunca el contenido enviado (comentarios, archivos) ni el payload.
     */
    public void escritura(String usuario, String empresa, String accion, String recurso, boolean ok, long duracionMs) {
        AUDIT.info("evento=ESCRITURA usuario={} empresa={} accion={} recurso={} resultado={} ms={}",
                sanear(usuario), empresa, sanear(accion), sanear(recurso), ok ? "OK" : "ERROR", duracionMs);
    }

    public void accionDenegada(String usuario, String empresa, String accion, String motivo) {
        AUDIT.warn("evento=ACCION_DENEGADA usuario={} empresa={} accion={} motivo={}",
                sanear(usuario), empresa, sanear(accion), motivo);
    }

    public void sesionInvalidadaPorUpstream(String usuario, String accion, String sessionId) {
        AUDIT.warn("evento=SESION_INVALIDADA_UPSTREAM_401 usuario={} accion={} sesion={}",
                sanear(usuario), sanear(accion), hashSesion(sessionId));
    }

    static String sanear(String valor) {
        if (valor == null) {
            return "-";
        }
        String limpio = valor.replaceAll("[^\\p{L}\\p{N}._@:\\-]", "_");
        return limpio.length() > MAX_LARGO ? limpio.substring(0, MAX_LARGO) + "…" : limpio;
    }

    static String hashSesion(String sessionId) {
        if (sessionId == null) {
            return "-";
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return "-";
        }
    }
}
