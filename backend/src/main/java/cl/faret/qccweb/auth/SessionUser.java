package cl.faret.qccweb.auth;

import java.time.Instant;

/**
 * Usuario autenticado de una sesión web. Vive SOLO en la sesión del servidor (nunca viaja al
 * navegador completo): identidad, rol y empresa salen de aquí, no de lo que envíe el cliente.
 *
 * @param upstreamToken JWT emitido por la API INNPACK para este usuario. Nunca se expone ni se loguea.
 * @param expiresAt     vencimiento absoluto de la sesión web (mínimo entre exp del JWT y el tope configurado)
 */
public record SessionUser(
        int userId,
        String codigoUsuario,
        String nombreCompleto,
        String rol,
        String empresa,
        String upstreamToken,
        Instant createdAt,
        Instant expiresAt) {

    public boolean expirada(Instant ahora) {
        return !ahora.isBefore(expiresAt);
    }

    /** Nunca incluir el token: evita filtrarlo por logs, excepciones o depuración. */
    @Override
    public String toString() {
        return "SessionUser[userId=" + userId + ", codigoUsuario=" + codigoUsuario + ", rol=" + rol
                + ", empresa=" + empresa + ", expiresAt=" + expiresAt + "]";
    }
}
