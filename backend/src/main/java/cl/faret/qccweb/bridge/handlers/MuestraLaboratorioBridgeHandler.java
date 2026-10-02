package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.bridge.LecturasDeSesion;
import cl.faret.qccweb.upstream.FpsApiClient;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Laboratorio - Muestras" (INNPACK), parte INNPACK API. Port de Photino
 * src/Backend/Modules/MuestraLaboratorio/MuestraLaboratorioHandler.cs +
 * InnpackMuestraLaboratorioApiService. Payload dentro de "data". Referencia de la API:
 * qualitycontrolinnpack_sqlserver_port (la copia qualitycontrolinnpack no tiene estos endpoints).
 *
 * Sin empresa (menú solo INNPACK). Lecturas sin rol (Photino y la API solo exigen sesión); las
 * escrituras usan ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO (operador/admin/admin_ti),
 * igual que el resto de la matriz.
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
 * Fase 4e-1 (piloto de las 25 escrituras): `crear` y `ph.guardar`. usuarioId/usuarioNombre (o
 * analistaUsuarioId/analistaNombre) SIEMPRE de sesión — ni siquiera son claves aceptadas del payload,
 * igual criterio que Control Documental. `ph.guardar` fija el patrón de "corrección" común a los 13
 * tipos de ensayo (ensayoOriginalId/motivoReemplazo, lo valida la API: motivo obligatorio si hay id,
 * original debe existir y estar Finalizado). Sin huella ni candado: son altas puras, la API no
 * verifica duplicados (mismo criterio que noConformidades.create/controlDocumental.create).
 *
 * Fuera: consultarNp (Planificación FARET), consultarRegistroProduccion (FPS, solo en el alta de muestra, que depende
 * de consultarNp), resolverBobina (SAP) y el resto de las escrituras (quedan para 4e-2/3/4).
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

    // ------------------------------------------------------------------ Fase 4e-1: escrituras (piloto)

    static final String MENSAJE_CAMPO_NO_PERMITIDO = "Campo no permitido: ";
    static final String MENSAJE_OPCION_INVALIDA = "Valor no permitido en ";
    static final String MENSAJE_PARAMETRO_INVALIDO = "Parámetro inválido.";
    static final String MENSAJE_TEXTO_CARACTERES = "El texto contiene caracteres no permitidos.";
    static final String MENSAJE_TEXTO_HTML = "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").";

    private static final Pattern CONTROL_UNA_LINEA = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final Pattern CONTROL_MULTILINEA = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern MARCADO_HTML = Pattern.compile("<[A-Za-z/!?]");

    /** Opciones exactas del {@code <select id="mlbNmOrigen">} de Photino. */
    private static final Set<String> ORIGENES = Set.of("ControlRecepcion", "Corrugado", "Monotapa", "Emplacado",
            "Troquelado", "Pegado", "ProductoTerminado", "MuestraExterna", "Pruebas", "Otro");
    /** Opciones exactas del {@code <select id="mlbNmTipoMuestra">} de Photino. */
    private static final Set<String> TIPOS_MUESTRA = Set.of("Papel", "Monotapa", "CartonCorrugadoEmplacado",
            "PliegoImpreso", "CajaPegada", "CajaMaster", "AdhesivoPVA", "AdhesivoCorrugado", "Otro");
    /** Opciones del {@code <select id="mlbNmEtapaOrigen">} ("" = -- Seleccionar --, solo si Origen=Pruebas). */
    private static final Set<String> ETAPAS_ORIGEN = Set.of("ControlRecepcion", "Corrugado", "Monotapa", "Emplacado",
            "Troquelado", "Pegado", "ProductoTerminado", "Otro");

    /** Textos de una línea (sin \n) de muestraLab.crear; el resto de los 29 campos se arma aparte. */
    private static final List<String> TEXTOS_UNA_LINEA_CREAR = List.of("np", "cliente", "codigoProducto",
            "descripcion", "maquina", "turno", "lote", "proveedor", "fechaEnsayo", "procesoTextoFps", "operadorTexto",
            "fechaProduccionFps", "fechaConsultaFps", "solicitante", "motivoSolicitud", "ensayosRequeridos",
            "pliegoRelacionado");

    /** Claves de muestraLab.crear de Photino ({@code {action, data:{...}}}); usuarioId/usuarioNombre SIEMPRE de sesión. */
    private static final Set<String> CLAVES_CREAR = concat(TEXTOS_UNA_LINEA_CREAR, "origen", "tipoMuestra",
            "observacion", "maquinaId", "tipoOndaId", "registroProduccionRecordKey", "idProcesoFps", "etapaOrigen",
            "pesoOndaExtendida", "pesoRecorte10x10", "longitudOndaExtendida", "alturaOnda", "monotapaRelacionadaId");

    /** Claves de muestraLab.ph.guardar; analistaUsuarioId/analistaNombre SIEMPRE de sesión. */
    private static final Set<String> CLAVES_PH_GUARDAR = Set.of(
            "muestraId", "metodo", "observacion", "valorTexto", "colorObservado", "ensayoOriginalId", "motivoReemplazo");

    private static Set<String> concat(List<String> base, String... extra) {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>(base);
        s.addAll(List.of(extra));
        return java.util.Collections.unmodifiableSet(s);
    }

    /**
     * muestraLab.crear → POST api/muestra-laboratorio {origen, tipoMuestra, ..., usuarioId, usuarioNombre}
     * (Fase 4e-1), igual que Photino: alta de una muestra de Laboratorio. Origen/Tipo de muestra obligatorios y la
     * FK de monotapaRelacionadaId (debe ser una muestra existente de origen Monotapa) las valida la API con sus
     * propios mensajes; el gateway no las repite. Sin huella ni candado: alta pura, sin control de duplicados.
     *
     * Seguridad transparente: usuarioId/usuarioNombre ← sesión (ni siquiera son claves aceptadas del payload);
     * origen/tipoMuestra/etapaOrigen restringidos a los {@code <select>} de Photino; el resto de los campos (incluido
     * el snapshot FPS: registroProduccionRecordKey/idProcesoFps/procesoTextoFps/operadorTexto/fechaProduccionFps/
     * fechaConsultaFps) viaja tal cual lo manda el cliente — Photino tampoco los valida contra una consulta real, son
     * estado local del formulario de "Nueva muestra" (igual nivel de confianza, no una relajación nueva); textos
     * sin caracteres de control ni marcado HTML.
     */
    public BridgeResult crear(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_CREAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = null;
        for (String campo : TEXTOS_UNA_LINEA_CREAR) {
            error = error != null ? error : ponerTexto(cuerpo, data, campo, CONTROL_UNA_LINEA);
        }
        error = error != null ? error : ponerTexto(cuerpo, data, "observacion", CONTROL_MULTILINEA);
        if (error == null) {
            String origen = texto(data.get("origen"));
            if (!origen.isEmpty() && !ORIGENES.contains(origen)) {
                error = MENSAJE_OPCION_INVALIDA + "origen.";
            }
            cuerpo.put("origen", origen);
        }
        if (error == null) {
            String tipoMuestra = texto(data.get("tipoMuestra"));
            if (!tipoMuestra.isEmpty() && !TIPOS_MUESTRA.contains(tipoMuestra)) {
                error = MENSAJE_OPCION_INVALIDA + "tipoMuestra.";
            }
            cuerpo.put("tipoMuestra", tipoMuestra);
        }
        if (error == null) {
            String etapaOrigen = texto(data.get("etapaOrigen"));
            if (!etapaOrigen.isEmpty() && !ETAPAS_ORIGEN.contains(etapaOrigen)) {
                error = MENSAJE_OPCION_INVALIDA + "etapaOrigen.";
            }
            cuerpo.put("etapaOrigen", etapaOrigen);
        }
        if (error != null) {
            return BridgeResult.error(error);
        }
        ponerEntero(cuerpo, data, "maquinaId");
        ponerEntero(cuerpo, data, "tipoOndaId");
        ponerEntero(cuerpo, data, "monotapaRelacionadaId");
        ponerLargo(cuerpo, data, "registroProduccionRecordKey");
        ponerLargo(cuerpo, data, "idProcesoFps");
        ponerDecimal(cuerpo, data, "pesoOndaExtendida");
        ponerDecimal(cuerpo, data, "pesoRecorte10x10");
        ponerDecimal(cuerpo, data, "longitudOndaExtendida");
        ponerDecimal(cuerpo, data, "alturaOnda");
        cuerpo.put("usuarioId", usuario.userId());
        cuerpo.put("usuarioNombre", autorDeSesion(usuario));
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE, cuerpo), mapper);
    }

    /** Recurso auditado: "muestraLab:&lt;id&gt;:crear". */
    public static String recursoCrear(ObjectNode payload, Object dataRespuesta) {
        JsonNode id = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("id") : null;
        return "muestraLab:" + (id != null && id.canConvertToInt() ? id.asInt() : "?") + ":crear";
    }

    /**
     * muestraLab.ph.guardar → POST api/muestra-laboratorio/ph {muestraId, metodo, observacion, valorTexto,
     * colorObservado, ensayoOriginalId, motivoReemplazo, analistaUsuarioId, analistaNombre} (Fase 4e-1), igual que
     * Photino: registra un ensayo de pH sobre una muestra. `ensayoOriginalId`/`motivoReemplazo` es el patrón común
     * de "corrección" de los 13 tipos de ensayo — la API exige motivo si viene un id y que el ensayo original exista
     * y esté Finalizado; mensajes propios de la API, el gateway no los repite.
     *
     * Seguridad transparente: analistaUsuarioId/analistaNombre ← sesión; muestraId/valorTexto obligatorios los
     * valida la API (mismo mensaje que Photino). Sin huella: cada ensayo es una fila nueva, no sobrescribe nada.
     */
    public BridgeResult phGuardar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_PH_GUARDAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = ponerTexto(cuerpo, data, "metodo", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "observacion", CONTROL_MULTILINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "valorTexto", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "colorObservado", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "motivoReemplazo", CONTROL_UNA_LINEA);
        if (error != null) {
            return BridgeResult.error(error);
        }
        Integer muestraId = entero(data.get("muestraId"));
        cuerpo.put("muestraId", muestraId != null ? muestraId : 0);
        ponerEntero(cuerpo, data, "ensayoOriginalId");
        cuerpo.put("analistaUsuarioId", usuario.userId());
        cuerpo.put("analistaNombre", autorDeSesion(usuario));
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/ph", cuerpo), mapper);
    }

    /** Recurso auditado: "muestraLab:&lt;muestraId&gt;:ph[:&lt;ensayoId&gt;]". */
    public static String recursoPhGuardar(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer muestraId = data == null ? null : entero(data.get("muestraId"));
        JsonNode ensayoId = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("ensayoId") : null;
        return "muestraLab:" + (muestraId != null ? muestraId : "?") + ":ph"
                + (ensayoId != null && ensayoId.canConvertToInt() ? ":" + ensayoId.asInt() : "");
    }

    /** GetString de Photino + control de seguridad: string/número → texto; sin \\x00-\\x1F/\\x7F ni "&lt;tag". */
    private static String ponerTexto(ObjectNode cuerpo, JsonNode data, String campo, Pattern control) {
        JsonNode v = data.get(campo);
        if (v != null && !v.isNull() && !v.isString() && !v.isNumber()) {
            return MENSAJE_PARAMETRO_INVALIDO;
        }
        String valor = texto(v);
        if (control.matcher(valor).find()) {
            return MENSAJE_TEXTO_CARACTERES;
        }
        if (MARCADO_HTML.matcher(valor).find()) {
            return MENSAJE_TEXTO_HTML;
        }
        cuerpo.put(campo, valor);
        return null;
    }

    private static void ponerEntero(ObjectNode cuerpo, JsonNode data, String campo) {
        Integer v = entero(data.get(campo));
        if (v != null) {
            cuerpo.put(campo, v);
        } else {
            cuerpo.putNull(campo);
        }
    }

    private static void ponerLargo(ObjectNode cuerpo, JsonNode data, String campo) {
        Long v = largo(data.get(campo));
        if (v != null) {
            cuerpo.put(campo, v);
        } else {
            cuerpo.putNull(campo);
        }
    }

    private static void ponerDecimal(ObjectNode cuerpo, JsonNode data, String campo) {
        BigDecimal v = decimal(data.get(campo));
        if (v != null) {
            cuerpo.put(campo, v);
        } else {
            cuerpo.putNull(campo);
        }
    }

    /** GetLong de Photino: número entero int64, o string convertible; si no → null. */
    private static Long largo(JsonNode nodo) {
        if (nodo == null || nodo.isNull()) {
            return null;
        }
        if (nodo.isNumber()) {
            return nodo.isIntegralNumber() ? nodo.asLong() : null;
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

    /** GetDecimal de Photino: número, o string convertible; si no → null. */
    private static BigDecimal decimal(JsonNode nodo) {
        if (nodo == null || nodo.isNull()) {
            return null;
        }
        if (nodo.isNumber()) {
            try {
                return new BigDecimal(nodo.asString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (nodo.isString()) {
            try {
                return new BigDecimal(nodo.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String nombreCampoSeguro(String clave) {
        return clave != null && clave.matches("[A-Za-z0-9_]{1,40}") ? clave : "?";
    }

    /** Mismo criterio que la sesión C# de Photino (nombre completo; si no, el código). */
    private static String autorDeSesion(SessionUser usuario) {
        String nombre = usuario.nombreCompleto();
        return nombre != null && !nombre.isBlank() ? nombre : usuario.codigoUsuario();
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
