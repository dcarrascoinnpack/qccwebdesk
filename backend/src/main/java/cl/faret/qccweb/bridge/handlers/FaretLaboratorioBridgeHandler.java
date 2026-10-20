package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.CuentaServicioInnpack;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.UpstreamNoAutorizadoException;
import cl.faret.qccweb.upstream.UriEscape;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Laboratorio FARET (faretLab.*), parte de lecturas. Port de Photino src/Backend/Modules/FaretLaboratorio/
 * FaretLaboratorioHandler.cs: la API INNPACK api/faret-laboratorio solo exige JWT ([Authorize] sin roles) y una sesión FARET no
 * tiene token INNPACK, así que se llama con la cuenta de servicio de bajo privilegio ({@link CuentaServicioInnpack}), igual que
 * Photino con su InnpackApiServiceAccount. La identidad, el rol y la auditoría siguen siendo los del usuario FARET de la petición:
 * si la API rechaza el token de servicio solo se descarta ese token (la sesión web del usuario no se cierra).
 *
 * Payload dentro de "data". Filtros de list: estado, tipoMuestra, np (como Photino); bool/objeto/array → "Parámetro de filtro inválido.".
 */
public class FaretLaboratorioBridgeHandler {

    static final String MENSAJE_FILTRO_INVALIDO = "Parámetro de filtro inválido.";
    static final String MENSAJE_NO_DISPONIBLE = "Laboratorio FARET no está disponible en este equipo.";
    private static final String BASE = "/api/faret-laboratorio";
    private static final String[] FILTROS = {"estado", "tipoMuestra", "np"};

    private final InnpackApiClient api;
    private final CuentaServicioInnpack cuentaServicio;
    private final ObjectMapper mapper;

    public FaretLaboratorioBridgeHandler(InnpackApiClient api, CuentaServicioInnpack cuentaServicio, ObjectMapper mapper) {
        this.api = api;
        this.cuentaServicio = cuentaServicio;
        this.mapper = mapper;
    }

    /** faretLab.list → GET api/faret-laboratorio[?estado=..&tipoMuestra=..&np=..] (solo filtros no vacíos). */
    public BridgeResult list(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        StringBuilder query = new StringBuilder();
        for (String filtro : FILTROS) {
            JsonNode nodo = data.get(filtro);
            if (nodo == null || nodo.isNull()) {
                continue;
            }
            if (!(nodo.isString() || nodo.isNumber())) {
                return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
            }
            String valor = nodo.asString();
            if (!valor.isBlank()) {
                query.append(filtro).append('=').append(UriEscape.dataString(valor)).append('&');
            }
        }
        return get(query.isEmpty() ? BASE : BASE + "?" + query.substring(0, query.length() - 1));
    }

    /** faretLab.detalle → GET api/faret-laboratorio/{id} (id ausente/inválido → 0, como Photino). */
    public BridgeResult detalle(ObjectNode payload, SessionUser usuario) {
        Integer id = entero(data(payload).get("id"));
        return get(BASE + "/" + (id != null ? id : 0));
    }

    /** faretLab.especificacion.list → GET api/faret-laboratorio/especificaciones */
    public BridgeResult especificacionList(ObjectNode payload, SessionUser usuario) {
        return get(BASE + "/especificaciones");
    }

    private BridgeResult get(String path) {
        var servicio = cuentaServicio.sesion();
        if (servicio.isEmpty()) {
            return BridgeResult.error(MENSAJE_NO_DISPONIBLE);
        }
        try {
            return InnpackRespuestas.reenviar(api.get(servicio.get(), path), mapper);
        } catch (UpstreamNoAutorizadoException e) {
            // El token de la cuenta de servicio venció o fue revocado: no es la sesión del usuario FARET.
            cuentaServicio.invalidar();
            return BridgeResult.error(MENSAJE_NO_DISPONIBLE);
        }
    }

    private JsonNode data(ObjectNode payload) {
        JsonNode data = payload.get("data");
        return data != null && data.isObject() ? data : mapper.createObjectNode();
    }

    /** GetInt de Photino: número entero int32, o string convertible (int.TryParse); si no → null. */
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
