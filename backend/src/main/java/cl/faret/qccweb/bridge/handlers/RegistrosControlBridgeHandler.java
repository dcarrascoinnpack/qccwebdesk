package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Registros de Control" (INNPACK) — SOLO LECTURA. Port de Photino
 * src/Backend/Modules/RegistrosControl/RegistrosControlHandler.cs +
 * InnpackRegistrosControlApiService.ObtenerRegistrosAsync.
 *
 * Las escrituras del módulo (validarRegistro, rechazarRegistro, eliminarRegistro) NO están
 * habilitadas: ActionPolicy las rechaza.
 */
public class RegistrosControlBridgeHandler {

    static final String MENSAJE_FILTRO_INVALIDO = "Parámetro de filtro inválido.";
    private static final String[] FILTROS_TEXTO = {"fechaDesde", "fechaHasta", "np", "turno", "estado"};

    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public RegistrosControlBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /**
     * registrosControl.obtenerRegistros → GET api/registros-control?page=..&limit=..[&fechaDesde=..]
     * [&fechaHasta=..][&np=..][&turno=..][&estado=..][&id=..][&procesoId=..][&parametroId=..]
     *
     * Mismas reglas que Photino (todo se lee desde "data"; sin "data" → solo defaults):
     * - page (default 1) y limit (default 20): entero, o string convertible a entero; cualquier
     *   otra cosa → default. Siempre van en la query (limit=999999 es el "traer todo" de
     *   Exportar/Imprimir).
     * - fechaDesde/fechaHasta/np/turno/estado: string o número → se envía si no está en blanco,
     *   escapado como Uri.EscapeDataString; ausente/null → se omite.
     * - id: string o número convertible a entero → se envía; si no convierte → se omite.
     * - procesoId/parametroId: entero o string numérica → se envía; cualquier otra cosa → se omite.
     *
     * Diferencia DEFENSIVA respecto de Photino (acordada con el usuario, Fase 2e): para los
     * filtros de texto e id, Photino convierte booleanos/objetos/arrays con JsonElement.ToString()
     * y reenvía "True" o el JSON crudo a la API. Aquí se responde "Parámetro de filtro inválido."
     * sin llamar a la API. La UI real siempre envía strings, así que solo afecta a payloads
     * manipulados.
     */
    public BridgeResult obtenerRegistros(ObjectNode payload, SessionUser usuario) {
        JsonNode data = payload.get("data");
        if (data == null || !data.isObject()) {
            data = mapper.createObjectNode();
        }

        StringBuilder query = new StringBuilder("/api/registros-control")
                .append("?page=").append(entero(data.get("page"), 1))
                .append("&limit=").append(entero(data.get("limit"), 20));

        for (String filtro : FILTROS_TEXTO) {
            JsonNode nodo = data.get(filtro);
            if (nodo == null || nodo.isNull()) {
                continue;
            }
            if (!esTexto(nodo)) {
                return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
            }
            String valor = nodo.asString();
            if (!valor.isBlank()) {
                query.append('&').append(filtro).append('=').append(UriEscape.dataString(valor));
            }
        }

        JsonNode id = data.get("id");
        if (id != null && !id.isNull()) {
            if (!esTexto(id)) {
                return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
            }
            Integer valor = parsearEntero(id.asString());
            if (valor != null) {
                query.append("&id=").append(valor);
            }
        }

        for (String filtro : new String[] {"procesoId", "parametroId"}) {
            Integer valor = enteroOpcional(data.get(filtro));
            if (valor != null) {
                query.append('&').append(filtro).append('=').append(valor);
            }
        }

        return InnpackRespuestas.reenviar(api.get(usuario, query.toString()), mapper);
    }

    /** Lo que Photino lee con GetString y reenvía tal cual: strings y números. */
    private static boolean esTexto(JsonNode nodo) {
        return nodo.isString() || nodo.isNumber();
    }

    /** GetInt de Photino: número entero int32, o string convertible; cualquier otra cosa → default. */
    private static int entero(JsonNode nodo, int porDefecto) {
        Integer valor = enteroOpcional(nodo);
        return valor != null ? valor : porDefecto;
    }

    /** GetIntOrNull de Photino: número entero int32, o string convertible; cualquier otra cosa → null. */
    private static Integer enteroOpcional(JsonNode nodo) {
        if (nodo == null) {
            return null;
        }
        if (nodo.isNumber()) {
            return nodo.isIntegralNumber() && nodo.canConvertToInt() ? nodo.asInt() : null;
        }
        if (nodo.isString()) {
            return parsearEntero(nodo.asString());
        }
        return null;
    }

    /** Equivalente a int.TryParse (admite espacios alrededor y signo); null si no es un entero válido. */
    private static Integer parsearEntero(String valor) {
        try {
            return Integer.valueOf(valor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
