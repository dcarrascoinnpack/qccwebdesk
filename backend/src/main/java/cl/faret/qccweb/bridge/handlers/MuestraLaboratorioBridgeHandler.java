package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.bridge.LecturasDeSesion;
import cl.faret.qccweb.upstream.FpsApiClient;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
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
 * materialesFps (Fase 3w): fps-api materiales-por-proceso, solo para el idProcesoFps de una muestra cuyo detalle
 * abrió esta sesión (el botón solo existe en el detalle; evita recorrer procesos FPS arbitrarios).
 *
 * Fuera: consultarNp (Planificación FARET), consultarRegistroProduccion (FPS, solo en el alta de muestra, que depende
 * de consultarNp), resolverBobina (SAP) y las 25 escrituras.
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

    /** Mensajes de MuestraLaboratorioHandler de Photino (materialesFps). */
    static final String MENSAJE_FALTA_PROCESO = "Falta el idProceso (FPS) de la muestra";
    static final String MENSAJE_FPS_NO_CONFIGURADO = "FPS no está configurado en este equipo.";
    static final String MENSAJE_FPS_MATERIALES = "No fue posible consultar los materiales en FPS.";
    static final String MENSAJE_PROCESO_SIN_LEER = "Abre el detalle de la muestra antes de consultar los materiales FPS.";
    private static final java.util.regex.Pattern ID_PROCESO = java.util.regex.Pattern.compile("[1-9][0-9]{0,17}");

    private final InnpackApiClient api;
    private final FpsApiClient fps;
    private final ObjectMapper mapper;

    public MuestraLaboratorioBridgeHandler(InnpackApiClient api, FpsApiClient fps, ObjectMapper mapper) {
        this.api = api;
        this.fps = fps;
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
        BridgeResult r = get(usuario, BASE + "/" + (id != null ? id : 0));
        // Fase 3w: el proceso FPS de la muestra abierta habilita "Materiales FPS" para ESTA sesión.
        if (r.ok() && r.data() instanceof JsonNode d) {
            String proceso = idProceso(d.get("idProcesoFps"));
            if (proceso != null) {
                LecturasDeSesion.registrar(recursoProcesoFps(proceso), "1");
            }
        }
        return r;
    }

    /**
     * muestraLab.materialesFps {data:{idProceso}} → GET fps-api materiales-por-proceso?ids={idProceso} (Photino:
     * GetLong ?? 0, mayor que 0; FpsMaterialesApiService). Respuesta = MaterialInsumoDto en camelCase {idProceso, itemCode,
     * itemName} (texto; número → texto), como la serializa Photino. La API key de fps-api es del servidor.
     */
    public BridgeResult materialesFps(ObjectNode payload, SessionUser usuario) {
        JsonNode nodo = data(payload).get("idProceso");
        if (nodo != null && !nodo.isNull() && !(nodo.isString() || nodo.isNumber())) {
            return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
        }
        String proceso = idProceso(nodo);
        if (proceso == null) {
            return BridgeResult.error(MENSAJE_FALTA_PROCESO);
        }
        if (!fps.configurada()) {
            return BridgeResult.error(MENSAJE_FPS_NO_CONFIGURADO);
        }
        if (LecturasDeSesion.huella(recursoProcesoFps(proceso)) == null) {
            return BridgeResult.error(MENSAJE_PROCESO_SIN_LEER);
        }
        InnpackApiClient.Respuesta r = fps.get("materiales-por-proceso?ids=" + proceso);
        JsonNode cuerpo = null;
        if (r.body() != null) {
            try {
                cuerpo = mapper.readTree(r.body());
            } catch (RuntimeException e) {
                cuerpo = null;
            }
        }
        if (r.status() < 200 || r.status() > 299 || cuerpo == null || !cuerpo.path("ok").isBoolean()
                || !cuerpo.path("ok").asBoolean()) {
            return BridgeResult.error(MENSAJE_FPS_MATERIALES);
        }
        ArrayNode lista = mapper.createArrayNode();
        JsonNode filas = cuerpo.get("data");
        if (filas != null && filas.isArray()) {
            for (JsonNode fila : filas) {
                ObjectNode m = lista.addObject();
                m.put("idProceso", textoFila(fila, "Id_Proceso"));
                m.put("itemCode", textoFila(fila, "ItemCode"));
                m.put("itemName", textoFila(fila, "ItemName"));
            }
        }
        return BridgeResult.ok(lista);
    }

    /** GetString de FpsMaterialesApiService: string, número → texto; cualquier otro → "". */
    private static String textoFila(JsonNode fila, String campo) {
        JsonNode v = fila != null && fila.isObject() ? fila.get(campo) : null;
        return v != null && (v.isString() || v.isNumber()) ? v.asString() : "";
    }

    /** GetLong de Photino (mayor que 0) como dígitos: número entero o texto convertible; si no, null. */
    private static String idProceso(JsonNode nodo) {
        if (nodo == null || nodo.isNull()) {
            return null;
        }
        String v = nodo.isIntegralNumber() ? nodo.asString() : nodo.isString() ? nodo.asString().trim() : null;
        if (v == null || !ID_PROCESO.matcher(v).matches()) {
            return null;
        }
        try {
            return Long.toString(Long.parseLong(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String recursoProcesoFps(String proceso) {
        return "fps-proceso:" + proceso;
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
