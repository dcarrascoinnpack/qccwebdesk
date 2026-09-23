package cl.faret.qccweb.bridge;

import cl.faret.qccweb.auth.SessionUser;
import tools.jackson.databind.node.ObjectNode;

/**
 * Implementación web de una acción de Photino.
 *
 * @see ActionPolicy
 */
@FunctionalInterface
public interface BridgeAction {

    /**
     * @param payload payload de Photino ya saneado: los campos de identidad declarados en la regla
     *                fueron sobrescritos con los datos de la sesión
     * @param usuario usuario de la sesión server-side (única fuente de identidad, rol, empresa y JWT)
     */
    BridgeResult ejecutar(ObjectNode payload, SessionUser usuario);
}
