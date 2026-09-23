package cl.faret.qccweb.upstream;

import cl.faret.qccweb.bridge.BridgeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Traduce una respuesta ApiResponse&lt;T&gt; { success, message, data, errors } de la API INNPACK al
 * resultado del bridge, con la misma semántica que Photino (HomeHandler.Forward /
 * TryUnwrapApiResponse): data pasa tal cual; si success=false se muestra el message de la API; si
 * la respuesta no es un ApiResponse válido, un mensaje genérico.
 */
public final class InnpackRespuestas {

    static final String ERROR_GENERICO = "Error al comunicarse con la API Innpack";

    private InnpackRespuestas() {}

    public static BridgeResult reenviar(InnpackApiClient.Respuesta respuesta, ObjectMapper mapper) {
        JsonNode raiz = leer(respuesta.body(), mapper);
        JsonNode success = raiz == null ? null : raiz.get("success");

        if (success == null || !success.isBoolean()) {
            return BridgeResult.error(ERROR_GENERICO);
        }
        if (!success.asBoolean()) {
            JsonNode mensaje = raiz.get("message");
            return BridgeResult.error(mensaje != null && mensaje.isString() ? mensaje.asString() : ERROR_GENERICO);
        }
        boolean httpOk = respuesta.status() >= 200 && respuesta.status() < 300;
        if (!httpOk) {
            return BridgeResult.error(ERROR_GENERICO);
        }
        JsonNode data = raiz.get("data");
        return BridgeResult.ok(data == null || data.isMissingNode() ? null : data);
    }

    private static JsonNode leer(String body, ObjectMapper mapper) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode nodo = mapper.readTree(body);
            return nodo != null && nodo.isObject() ? nodo : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
