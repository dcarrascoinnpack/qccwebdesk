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
 * Módulo "Inspecciones Calidad" (Dashboard Calidad, INNPACK) — SOLO LECTURA. Port de Photino
 * src/Backend/Modules/Dashboard/DashboardHandler.cs + InnpackDashboardApiService.
 *
 * Las escrituras del módulo (validarRegistro, rechazarRegistro, eliminarRegistro, validarTodo,
 * rechazarTodo) NO están habilitadas: ActionPolicy las rechaza.
 */
public class DashboardBridgeHandler {

    static final String MENSAJE_FILTRO_INVALIDO = "Parámetro de filtro inválido.";
    private static final String[] FILTROS = {"fechaDesde", "fechaHasta", "inspector", "turno", "proceso"};

    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public DashboardBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /** dashboard.obtenerFiltros → GET api/dashboard/filtros (sin parámetros). */
    public BridgeResult obtenerFiltros(ObjectNode payload, SessionUser usuario) {
        return InnpackRespuestas.reenviar(api.get(usuario, "/api/dashboard/filtros"), mapper);
    }

    /**
     * dashboard.obtenerResumen → GET api/dashboard/resumen?fechaDesde=..&fechaHasta=..&inspector=..
     * &turno=..&proceso=.. (los 5 siempre presentes, en ese orden, escapados como
     * Uri.EscapeDataString). Igual que Photino: solo se leen desde "data"; ausente o null → "";
     * un valor que no es string (número, bool, objeto) hace fallar a Photino (GetString) → error.
     */
    public BridgeResult obtenerResumen(ObjectNode payload, SessionUser usuario) {
        JsonNode data = payload.get("data");
        StringBuilder query = new StringBuilder("/api/dashboard/resumen");
        char separador = '?';
        for (String filtro : FILTROS) {
            String valor = "";
            JsonNode nodo = data != null && data.isObject() ? data.get(filtro) : null;
            if (nodo != null && !nodo.isNull()) {
                if (!nodo.isString()) {
                    return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
                }
                valor = nodo.asString();
            }
            query.append(separador).append(filtro).append('=').append(UriEscape.dataString(valor));
            separador = '&';
        }
        return InnpackRespuestas.reenviar(api.get(usuario, query.toString()), mapper);
    }
}
