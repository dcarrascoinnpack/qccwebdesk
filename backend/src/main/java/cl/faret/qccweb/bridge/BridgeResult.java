package cl.faret.qccweb.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resultado de una acción del bridge, en el contrato normalizado de Photino { ok, success, data, error }. */
public record BridgeResult(boolean ok, Object data, String error) {

    public static BridgeResult ok(Object data) {
        return new BridgeResult(true, data, null);
    }

    public static BridgeResult error(String mensaje) {
        return new BridgeResult(false, null, mensaje);
    }

    /** Misma forma que MessageRouter.NormalizeResponse de Photino. */
    public Map<String, Object> comoRespuesta() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", ok);
        r.put("success", ok);
        r.put("data", data);
        r.put("error", error);
        return r;
    }
}
