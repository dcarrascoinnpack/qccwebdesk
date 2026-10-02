package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo Inicio (INNPACK). Port de Photino src/Backend/Modules/Home/HomeHandler.cs.
 *
 * Fase 4f: `frecuencias.actualizar` habilitada. Sin autor: ni Photino ni la API lo registran;
 * auditoría obligatoria del gateway. `id`/`frecuenciaMinutos` se rechazan ANTES de llamar a la API
 * si faltan o no son &gt; 0, igual que el handler C# de Photino.
 */
public class InicioBridgeHandler {

    static final String MENSAJE_PARAMETROS_INVALIDOS = "Parámetros inválidos para actualizar la frecuencia.";
    static final String MENSAJE_CAMPO_NO_PERMITIDO = "Campo no permitido: ";
    private static final Set<String> CLAVES_FRECUENCIA = Set.of("id", "frecuenciaMinutos");

    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public InicioBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /** HomeHandler.ObtenerDashboard → InnpackHomeApiService.DashboardAsync: GET api/home/dashboard. */
    public BridgeResult getDashboard(ObjectNode payload, SessionUser usuario) {
        return InnpackRespuestas.reenviar(api.get(usuario, "/api/home/dashboard"), mapper);
    }

    /**
     * inicio.frecuencias.actualizar → PUT api/home/frecuencias/{id} {frecuenciaMinutos} (Fase 4f), igual que
     * Photino: configuración global de frecuencia objetivo de control por proceso.
     */
    public BridgeResult frecuenciasActualizar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_FRECUENCIA.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer id = entero(data.get("id"));
        Integer minutos = entero(data.get("frecuenciaMinutos"));
        if (id == null || id <= 0 || minutos == null || minutos <= 0) {
            return BridgeResult.error(MENSAJE_PARAMETROS_INVALIDOS);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("frecuenciaMinutos", minutos);
        return InnpackRespuestas.reenviar(api.putJson(usuario, "/api/home/frecuencias/" + id, cuerpo), mapper);
    }

    /** Recurso auditado: "inicio:frecuencia:&lt;id&gt;". */
    public static String recursoFrecuenciasActualizar(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer id = data == null ? null : entero(data.get("id"));
        return "inicio:frecuencia:" + (id != null ? id : "?");
    }

    private JsonNode data(ObjectNode payload) {
        JsonNode data = payload.get("data");
        return data != null && data.isObject() ? data : mapper.createObjectNode();
    }

    private static String nombreCampoSeguro(String clave) {
        return clave != null && clave.matches("[A-Za-z0-9_]{1,40}") ? clave : "?";
    }

    /** GetInt de Photino: número entero int32, o string convertible; si no → null. */
    private static Integer entero(JsonNode nodo) {
        if (nodo == null) {
            return null;
        }
        if (nodo.isNumber()) {
            return nodo.isIntegralNumber() && nodo.canConvertToInt() ? nodo.asInt() : null;
        }
        if (nodo.isString()) {
            try {
                return Integer.valueOf(nodo.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
