package cl.faret.qccweb.version;

import tools.jackson.databind.JsonNode;

/** Acceso de test a la verificación (package-private) de ContractGuard. */
public final class ContractGuardTestAccess {

    private ContractGuardTestAccess() {}

    public static void verificar(JsonNode contrato, boolean bloquear) {
        ContractGuard.verificar(contrato, bloquear);
    }
}
