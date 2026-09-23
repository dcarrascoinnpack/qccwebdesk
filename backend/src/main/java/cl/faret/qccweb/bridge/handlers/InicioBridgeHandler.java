package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo Inicio (INNPACK). Port de Photino src/Backend/Modules/Home/HomeHandler.cs.
 *
 * Solo inicio.getDashboard (lectura). inicio.frecuencias.actualizar (escritura) NO está habilitada.
 */
public class InicioBridgeHandler {

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
}
