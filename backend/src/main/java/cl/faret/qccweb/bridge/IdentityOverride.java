package cl.faret.qccweb.bridge;

import cl.faret.qccweb.auth.SessionUser;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sobrescribe los campos de identidad del actor en el payload con los datos de la sesión.
 *
 * Es por acción y explícito (ActionPolicy.Regla.identidad), no global: nombres como "rol",
 * "revisadoPor" o "responsable" son datos de negocio en algunas acciones de Photino (p. ej. el rol
 * del usuario que se crea, o un revisor elegido de un catálogo) y pisarlos siempre corrompería datos.
 *
 * Se aplica al nivel raíz y dentro de "data" (los dos formatos de payload de Photino: INNPACK usa
 * { action, data: {...} }, Faret manda los campos en la raíz). Si el campo viene, se reemplaza; si
 * la acción lo declara y no viene, se agrega — así el valor del navegador nunca llega a la API.
 */
public final class IdentityOverride {

    /** Dato de la sesión con que se reemplaza un campo. */
    public enum Fuente {
        USUARIO_ID,
        CODIGO_USUARIO,
        NOMBRE_COMPLETO,
        ROL,
        EMPRESA
    }

    private IdentityOverride() {}

    public static ObjectNode aplicar(ObjectNode payload, Map<String, Fuente> campos, SessionUser usuario) {
        if (campos.isEmpty()) {
            return payload;
        }
        reemplazarEn(payload, campos, usuario);
        JsonNode data = payload.get("data");
        if (data instanceof ObjectNode dataObj) {
            reemplazarEn(dataObj, campos, usuario);
        }
        return payload;
    }

    private static void reemplazarEn(ObjectNode nodo, Map<String, Fuente> campos, SessionUser u) {
        campos.forEach((campo, fuente) -> {
            switch (fuente) {
                case USUARIO_ID -> nodo.put(campo, u.userId());
                case CODIGO_USUARIO -> nodo.put(campo, u.codigoUsuario());
                case NOMBRE_COMPLETO -> nodo.put(campo, u.nombreCompleto());
                case ROL -> nodo.put(campo, u.rol());
                case EMPRESA -> nodo.put(campo, u.empresa());
            }
        });
    }
}
