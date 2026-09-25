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
 * Módulo "Laboratorio - Muestras" (INNPACK) — SOLO LECTURA, parte INNPACK API (Fase 2l). Port de
 * Photino src/Backend/Modules/MuestraLaboratorio/MuestraLaboratorioHandler.cs +
 * InnpackMuestraLaboratorioApiService. Payload dentro de "data". Referencia de la API:
 * qualitycontrolinnpack_sqlserver_port (la copia qualitycontrolinnpack no tiene estos endpoints).
 *
 * Sin empresa (menú solo INNPACK) ni rol (Photino y la API solo exigen sesión).
 *
 * adjunto.abrir: Photino previsualiza imagen/PDF y para el resto escribe %TEMP%\QCC_MuestraLaboratorio
 * + Process.Start. Aquí se valida igual que Control Documental (ControlDocumentalBridgeHandler.
 * adjuntoParaNavegador, ≤ 10 MB = máximo de la API) y web-bridge.js previsualiza o descarga.
 *
 * bool/objeto/array en filtros → "Parámetro de filtro inválido." (regla 2e; también maquinaId).
 *
 * Fuera: lecturas contra otras APIs externas (consultarNp → Planificación FARET,
 * consultarRegistroProduccion/materialesFps → FPS, resolverBobina → SAP) y las 25 escrituras.
 */
public class MuestraLaboratorioBridgeHandler {

    static final String MENSAJE_FILTRO_INVALIDO = "Parámetro de filtro inválido.";
    static final String MENSAJE_FALTA_NP = "Falta indicar la NP";
    static final String MENSAJE_FALTA_BOBINA = "Falta el número de bobina";
    static final String MENSAJE_FALTA_ADJUNTO = "Falta indicar el adjunto";
    static final int MAX_ADJUNTO_BYTES = 10 * 1024 * 1024;
    static final int MAX_BASE64_CHARS = ((MAX_ADJUNTO_BYTES + 2) / 3) * 4;

    /** Orden exacto de InnpackMuestraLaboratorioApiService.ListAsync (maquinaId va al final). */
    private static final String[] FILTROS_TEXTO = {
        "estado", "tipoMuestra", "np", "fechaDesde", "fechaHasta", "cliente", "codigoProducto",
        "descripcion", "origen", "analistaNombre", "bobina"};
    private static final String BASE = "/api/muestra-laboratorio";

    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public MuestraLaboratorioBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /** muestraLab.list → GET api/muestra-laboratorio[?filtros no vacíos...&maquinaId=n] */
    public BridgeResult list(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        StringBuilder query = new StringBuilder();
        for (String filtro : FILTROS_TEXTO) {
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
        JsonNode maquina = data.get("maquinaId");
        if (maquina != null && !maquina.isNull()) {
            if (!(maquina.isString() || maquina.isNumber())) {
                return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
            }
            Integer maquinaId = entero(maquina);
            if (maquinaId != null) {
                query.append("maquinaId=").append(maquinaId).append('&');
            }
        }
        String path = query.isEmpty() ? BASE : BASE + "?" + query.substring(0, query.length() - 1);
        return get(usuario, path);
    }

    /** muestraLab.detalle → GET api/muestra-laboratorio/{id} (id ausente/inválido → 0, como Photino). */
    public BridgeResult detalle(ObjectNode payload, SessionUser usuario) {
        Integer id = entero(data(payload).get("id"));
        return get(usuario, BASE + "/" + (id != null ? id : 0));
    }

    /** muestraLab.catalogos → GET api/muestra-laboratorio/catalogos */
    public BridgeResult catalogos(ObjectNode payload, SessionUser usuario) {
        return get(usuario, BASE + "/catalogos");
    }

    /** muestraLab.indicadores → GET api/muestra-laboratorio/indicadores */
    public BridgeResult indicadores(ObjectNode payload, SessionUser usuario) {
        return get(usuario, BASE + "/indicadores");
    }

    /** muestraLab.metodo.list → GET api/muestra-laboratorio/metodos */
    public BridgeResult metodoList(ObjectNode payload, SessionUser usuario) {
        return get(usuario, BASE + "/metodos");
    }

    /** muestraLab.especificacion.list → GET api/muestra-laboratorio/especificaciones */
    public BridgeResult especificacionList(ObjectNode payload, SessionUser usuario) {
        return get(usuario, BASE + "/especificaciones");
    }

    /** muestraLab.bobinaHistorial → GET api/muestra-laboratorio/bobina-historial?numeroBobina=..&excluirMuestraId=n (default 0). */
    public BridgeResult bobinaHistorial(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        String numeroBobina = texto(data.get("numeroBobina"));
        if (numeroBobina.isBlank()) {
            return BridgeResult.error(MENSAJE_FALTA_BOBINA);
        }
        Integer excluir = entero(data.get("excluirMuestraId"));
        return get(usuario, BASE + "/bobina-historial?numeroBobina=" + UriEscape.dataString(numeroBobina)
                + "&excluirMuestraId=" + (excluir != null ? excluir : 0));
    }

    /** muestraLab.registroProduccion.list → GET api/muestra-laboratorio/registro-produccion?np=..[&lote=..] */
    public BridgeResult registroProduccionList(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        String np = texto(data.get("np"));
        if (np.isBlank()) {
            return BridgeResult.error(MENSAJE_FALTA_NP);
        }
        String lote = texto(data.get("lote"));
        return get(usuario, BASE + "/registro-produccion?np=" + UriEscape.dataString(np)
                + (lote.isBlank() ? "" : "&lote=" + UriEscape.dataString(lote)));
    }

    /** muestraLab.adjunto.abrir → GET api/muestra-laboratorio/adjunto/{adjuntoId}, validado para el navegador. */
    public BridgeResult adjuntoAbrir(ObjectNode payload, SessionUser usuario) {
        Integer adjuntoId = entero(data(payload).get("adjuntoId"));
        if (adjuntoId == null || adjuntoId <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_ADJUNTO);
        }
        return ControlDocumentalBridgeHandler.adjuntoParaNavegador(
                get(usuario, BASE + "/adjunto/" + adjuntoId), "adjunto_" + adjuntoId, MAX_BASE64_CHARS, mapper);
    }

    private BridgeResult get(SessionUser usuario, String path) {
        return InnpackRespuestas.reenviar(api.get(usuario, path), mapper);
    }

    private JsonNode data(ObjectNode payload) {
        JsonNode data = payload.get("data");
        return data != null && data.isObject() ? data : mapper.createObjectNode();
    }

    /** GetString de Photino: string o número (→ texto); cualquier otro tipo → "". */
    private static String texto(JsonNode nodo) {
        return nodo != null && (nodo.isString() || nodo.isNumber()) ? nodo.asString() : "";
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
