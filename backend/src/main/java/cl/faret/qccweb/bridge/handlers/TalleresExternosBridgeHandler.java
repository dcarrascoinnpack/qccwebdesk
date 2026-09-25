package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Talleres Externos" (INNPACK) — SOLO LECTURA. Port de Photino
 * src/Backend/Modules/TalleresExternos/TalleresExternosHandler.cs + InnpackTalleresExternosApiService.
 * Payload dentro de "data" (el controller JS usa el wrapper this._send(action, data)).
 *
 * Sin empresa (menú solo INNPACK; Faret tiene su propio módulo faret.*) ni rol (Photino y la API
 * solo exigen sesión). Respuestas sin transformación (Forward de Photino).
 *
 * Escrituras (create, update, eliminar, catalogos.eliminarTaller/eliminarProceso, sincronizarFps)
 * NO habilitadas: ver docs/matriz-escrituras-propuesta.md.
 */
public class TalleresExternosBridgeHandler {

    static final String MENSAJE_ID_HISTORIAL = "Falta 'id' para consultar el historial.";
    private static final String BASE = "/api/talleres-externos";

    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public TalleresExternosBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /**
     * talleresExternos.list → GET api/talleres-externos?page=..&pageSize=.. — GetInt de Photino con
     * defaults 1/50 (sin validar > 0: la API normaliza pageSize a 1..500).
     */
    public BridgeResult list(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        long page = entero(data.get("page"), 1);
        long pageSize = entero(data.get("pageSize"), 50);
        return get(usuario, BASE + "?page=" + page + "&pageSize=" + pageSize);
    }

    /** talleresExternos.catalogos → GET api/talleres-externos/catalogos (no lee el payload). */
    public BridgeResult catalogos(ObjectNode payload, SessionUser usuario) {
        return get(usuario, BASE + "/catalogos");
    }

    /** talleresExternos.historialLiberaciones → GET api/talleres-externos/{id}/historial-liberaciones (id long > 0). */
    public BridgeResult historialLiberaciones(ObjectNode payload, SessionUser usuario) {
        long id = largo(data(payload).get("id"), 0);
        if (id <= 0) {
            return BridgeResult.error(MENSAJE_ID_HISTORIAL);
        }
        return get(usuario, BASE + "/" + id + "/historial-liberaciones");
    }

    private BridgeResult get(SessionUser usuario, String path) {
        return InnpackRespuestas.reenviar(api.get(usuario, path), mapper);
    }

    private JsonNode data(ObjectNode payload) {
        JsonNode data = payload.get("data");
        return data != null && data.isObject() ? data : mapper.createObjectNode();
    }

    /** GetInt de Photino: número int32, o texto convertible (int.TryParse); si no → porDefecto. */
    private static long entero(JsonNode nodo, int porDefecto) {
        Long v = numero(nodo);
        return v != null && v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE ? v : porDefecto;
    }

    /** GetLong de Photino: número int64, o texto convertible (long.TryParse); si no → porDefecto. */
    private static long largo(JsonNode nodo, long porDefecto) {
        Long v = numero(nodo);
        return v != null ? v : porDefecto;
    }

    private static Long numero(JsonNode nodo) {
        if (nodo == null || nodo.isNull()) {
            return null;
        }
        if (nodo.isNumber()) {
            return nodo.isIntegralNumber() && nodo.canConvertToLong() ? nodo.asLong() : null;
        }
        if (nodo.isString()) {
            try {
                return Long.valueOf(nodo.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
