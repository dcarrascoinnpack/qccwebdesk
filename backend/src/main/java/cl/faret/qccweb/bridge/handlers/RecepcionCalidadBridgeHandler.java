package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.bridge.LecturasDeSesion;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.SapApiClient;
import cl.faret.qccweb.upstream.UriEscape;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Control de Recepción - Calidad" — SOLO LECTURA. Port de Photino
 * src/Backend/Modules/RecepcionCalidad/RecepcionCalidadHandler.cs + InnpackRecepcionCalidadApiService.
 * El payload viaja dentro de "data".
 *
 * "empresa" es scope de sesión: el frontend INNPACK nunca la manda (default INNPACK) y el de Faret la
 * manda hardcodeada ("FARET"); la API filtra list/detalle por ella pero no la liga al JWT. Aquí sale
 * de la sesión (además de IdentityOverride en la regla), nunca del payload.
 *
 * foto.abrir — diferencias DEFENSIVAS: la API no filtra la foto por empresa ni por lote eliminado y
 * siempre guarda el MIME "image/jpeg". El gateway primero confirma el lote con el detalle de la
 * empresa de la sesión (y que su tipo coincida), solo acepta PVA/PliegoFaret, acota el tamaño y
 * entrega el MIME detectado por la firma real (JPEG/PNG/GIF/WEBP); si no es imagen, lo rechaza.
 * La vista lo muestra en la página (data:), sin archivos temporales.
 *
 * bool/objeto/array en filtros de texto → "Parámetro de filtro inválido." (regla 2e).
 *
 * Escrituras: bobinas.muestrear (Fase 3q), muestra.crear (Fase 3s), estado.actualizar (Fase 3t), nc.crear (Fase 3x) y
 * crear (Fase 3z). plan.generar NO habilitada (rota en la API: R10). sap.consultar/sap.lotes (Fase 3y): apisapfaret.
 */
public class RecepcionCalidadBridgeHandler {

    static final String MENSAJE_FILTRO_INVALIDO = "Parámetro de filtro inválido.";
    static final String MENSAJE_FALTA_FOTO = "Falta el lote o el tipo de materia prima";
    static final String MENSAJE_SIN_FOTO = "Este lote no tiene fotografía cargada";
    static final String MENSAJE_FOTO_INVALIDA = "La fotografía no es válida.";
    static final String MENSAJE_TAMANO = "La fotografía excede el tamaño máximo permitido.";
    static final int MAX_FOTO_BYTES = 10 * 1024 * 1024;
    static final int MAX_BASE64_CHARS = ((MAX_FOTO_BYTES + 2) / 3) * 4;

    /** Tipos con foto en la API (RecepcionCalidadRepository.ObtenerFoto). */
    private static final Set<String> TIPOS_CON_FOTO = Set.of("PVA", "PliegoFaret");
    private static final String BASE = "/api/recepcion-calidad";

    private final InnpackApiClient api;
    private final SapApiClient sap;
    private final ObjectMapper mapper;

    public RecepcionCalidadBridgeHandler(InnpackApiClient api, SapApiClient sap, ObjectMapper mapper) {
        this.api = api;
        this.sap = sap;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ Fase 3y: consultas SAP (apisapfaret)
    static final String MENSAJE_SAP_NO_CONFIGURADA = "La consulta a SAP no está configurada en este equipo.";
    static final String MENSAJE_SAP_RANGO = "Debes indicar desde y hasta (yyyyMMdd)";
    static final String MENSAJE_SAP_LOTES = "Debes indicar itemCode y fecha (yyyyMMdd)";
    static final String MENSAJE_SAP_FALLO = "No fue posible consultar SAP (apisapfaret).";
    private static final Pattern FECHA_SAP = Pattern.compile("[0-9]{8}");
    private static final int MAX_ITEM_CODE = 50;
    private static final int MAX_MENSAJE_SAP = 200;
    /** Caracteres que rompen el innerHTML / value="..." donde la vista de Photino pinta los datos de SAP. */
    private static final Pattern PELIGROSO_HTML = Pattern.compile("[<>\"]");

    /**
     * recepcion.sap.consultar {data:{desde, hasta}} → GET apisapfaret api/recepcion/bobinas?desde=&hasta=&empresa= (Photino:
     * RecepcionSapItemDto en camelCase). Seguridad transparente: empresa ← sesión (Photino la toma del payload con
     * default INNPACK); desde/hasta solo fechas yyyyMMdd válidas (Photino las concatena SIN escapar a la query: un payload
     * manipulado podría inyectar otra empresa); textos con marcado HTML o comillas se escapan (la vista los pinta con
     * innerHTML).
     */
    public BridgeResult sapConsultar(ObjectNode payload, SessionUser usuario) {
        if (!sap.configurada()) {
            return BridgeResult.error(MENSAJE_SAP_NO_CONFIGURADA);
        }
        JsonNode data = data(payload);
        String desde = texto(data.get("desde")).trim();
        String hasta = texto(data.get("hasta")).trim();
        if (!fechaSap(desde) || !fechaSap(hasta)) {
            return BridgeResult.error(MENSAJE_SAP_RANGO);
        }
        JsonNode filas = consultarSap("api/recepcion/bobinas?desde=" + desde + "&hasta=" + hasta + "&empresa="
                + UriEscape.dataString(usuario.empresa()));
        if (filas instanceof ObjectNode error) {
            return BridgeResult.error(error.get("mensaje").asString());
        }
        ArrayNode lista = mapper.createArrayNode();
        for (JsonNode fila : filas) {
            ObjectNode m = lista.addObject();
            m.put("docEntry", entero32(fila, "docEntry", 0));
            m.put("lineNum", entero32(fila, "lineNum", 0));
            m.put("fechaRecepcion", textoSap(fila, "fechaRecepcion"));
            m.put("proveedor", textoSap(fila, "proveedor"));
            m.put("guia", textoSap(fila, "guia"));
            m.put("itemCode", textoSap(fila, "itemCode"));
            m.put("descripcion", textoSap(fila, "descripcion"));
            java.math.BigDecimal cantidad = decimal(fila, "cantidadRecibida");
            m.put("cantidadRecibida", cantidad != null ? cantidad : java.math.BigDecimal.ZERO);
            ponerDecimal(m, "anchoDeclarado", decimal(fila, "anchoDeclarado"));
            ponerDecimal(m, "gramajeDeclarado", decimal(fila, "gramajeDeclarado"));
        }
        return BridgeResult.ok(lista);
    }

    /**
     * recepcion.sap.lotes {data:{itemCode, fecha}} → GET apisapfaret api/recepcion/bobinas/lotes?itemCode=&fecha=&empresa=
     * (Photino: RecepcionSapLoteDto en camelCase). Empresa ← sesión; fecha yyyyMMdd; itemCode escapado (≤ 50, sin
     * controles); textos peligrosos para innerHTML/atributos escapados.
     */
    public BridgeResult sapLotes(ObjectNode payload, SessionUser usuario) {
        if (!sap.configurada()) {
            return BridgeResult.error(MENSAJE_SAP_NO_CONFIGURADA);
        }
        JsonNode data = data(payload);
        String itemCode = texto(data.get("itemCode"));
        String fecha = texto(data.get("fecha")).trim();
        if (itemCode.isBlank() || fecha.isEmpty() || !fechaSap(fecha) || itemCode.length() > MAX_ITEM_CODE
                || CONTROL_UNA_LINEA.matcher(itemCode).find()) {
            return BridgeResult.error(MENSAJE_SAP_LOTES);
        }
        JsonNode filas = consultarSap("api/recepcion/bobinas/lotes?itemCode=" + UriEscape.dataString(itemCode) + "&fecha=" + fecha
                + "&empresa=" + UriEscape.dataString(usuario.empresa()));
        if (filas instanceof ObjectNode error) {
            return BridgeResult.error(error.get("mensaje").asString());
        }
        ArrayNode lista = mapper.createArrayNode();
        for (JsonNode fila : filas) {
            ObjectNode m = lista.addObject();
            m.put("itemCode", textoSap(fila, "itemCode"));
            m.put("numeroBobina", textoSap(fila, "numeroBobina"));
            m.put("absEntry", entero32(fila, "absEntry", 0));
            m.put("fechaCreacion", textoSap(fila, "fechaCreacion"));
        }
        return BridgeResult.ok(lista);
    }

    /** Arreglo data de apisapfaret, o un ObjectNode {mensaje} con el error a mostrar (como ExtraerMensajeSap de Photino). */
    private JsonNode consultarSap(String pathYQuery) {
        InnpackApiClient.Respuesta r = sap.get(pathYQuery);
        JsonNode cuerpo = null;
        if (r.body() != null) {
            try {
                cuerpo = mapper.readTree(r.body());
            } catch (RuntimeException e) {
                cuerpo = null;
            }
        }
        if (r.status() >= 200 && r.status() <= 299 && cuerpo != null) {
            JsonNode d = cuerpo.get("data");
            return d != null && d.isArray() ? d : mapper.createArrayNode();
        }
        String mensaje = MENSAJE_SAP_FALLO;
        // Validaciones de apisapfaret (400): su texto, como Photino. Fallos de SAP (502), red o timeout: genérico.
        if (r.status() == 400 && cuerpo != null) {
            JsonNode e = cuerpo.has("error") ? cuerpo.get("error") : cuerpo.get("message");
            if (e != null && e.isString() && !e.asString().isBlank() && e.asString().length() <= MAX_MENSAJE_SAP) {
                mensaje = escaparSiPeligroso(e.asString());
            }
        }
        ObjectNode error = mapper.createObjectNode();
        error.put("mensaje", mensaje);
        return error;
    }

    private static boolean fechaSap(String v) {
        if (!FECHA_SAP.matcher(v).matches()) {
            return false;
        }
        try {
            java.time.LocalDate.parse(v, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    /** GetString de Photino (string o número → texto; otro → ""), escapado si trae marcado HTML o comillas. */
    private static String textoSap(JsonNode fila, String campo) {
        JsonNode v = fila != null && fila.isObject() ? fila.get(campo) : null;
        return escaparSiPeligroso(v != null && (v.isString() || v.isNumber()) ? v.asString() : "");
    }

    private static String escaparSiPeligroso(String v) {
        if (!PELIGROSO_HTML.matcher(v).find()) {
            return v;
        }
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** GetInt de Photino: número int32 o texto convertible; si no, el valor por defecto del DTO. */
    private static int entero32(JsonNode fila, String campo, int porDefecto) {
        JsonNode v = fila != null && fila.isObject() ? fila.get(campo) : null;
        if (v == null) {
            return porDefecto;
        }
        if (v.isIntegralNumber() && v.canConvertToInt()) {
            return v.asInt();
        }
        if (v.isString()) {
            try {
                return Integer.parseInt(v.asString().trim());
            } catch (NumberFormatException e) {
                return porDefecto;
            }
        }
        return porDefecto;
    }

    /** GetDecimal de Photino: número o texto convertible; si no, null. */
    private static java.math.BigDecimal decimal(JsonNode fila, String campo) {
        JsonNode v = fila != null && fila.isObject() ? fila.get(campo) : null;
        if (v == null) {
            return null;
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        if (v.isString()) {
            try {
                return new java.math.BigDecimal(v.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static void ponerDecimal(ObjectNode m, String campo, java.math.BigDecimal v) {
        if (v == null) {
            m.putNull(campo);
        } else {
            m.put(campo, v);
        }
    }

    /** recepcion.list → GET api/recepcion-calidad?[estado=..&][tipoMateriaPrima=..&]empresa=.. */
    public BridgeResult list(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        StringBuilder path = new StringBuilder(BASE).append('?');
        for (String filtro : new String[] {"estado", "tipoMateriaPrima"}) {
            JsonNode nodo = data.get(filtro);
            if (nodo == null || nodo.isNull()) {
                continue;
            }
            if (!(nodo.isString() || nodo.isNumber())) {
                return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
            }
            String valor = nodo.asString();
            if (!valor.isBlank()) {
                path.append(filtro).append('=').append(UriEscape.dataString(valor)).append('&');
            }
        }
        path.append("empresa=").append(UriEscape.dataString(usuario.empresa()));
        return InnpackRespuestas.reenviar(api.get(usuario, path.toString()), mapper);
    }

    /**
     * recepcion.detalle → GET api/recepcion-calidad/{id}?empresa=.. (id ausente/inválido → 0, como Photino). Además
     * registra en la sesión la huella de la selección de bobinas muestreadas que el usuario queda viendo (detección de
     * cambios concurrentes en bobinas.muestrear).
     */
    public BridgeResult detalle(ObjectNode payload, SessionUser usuario) {
        Integer id = entero(data(payload).get("id"));
        BridgeResult upstream = detalleLote(id != null ? id : 0, usuario);
        if (upstream.ok() && id != null && id > 0) {
            LecturasDeSesion.registrar(recursoLecturaMuestreo(id), huellaMuestreadas(upstream.data()));
            LecturasDeSesion.registrar(recursoLecturaMuestra(id), huellaMuestra(upstream.data()));
            LecturasDeSesion.registrar(recursoLecturaEstado(id), huellaEstado(upstream.data()));
            LecturasDeSesion.registrar(recursoLecturaNc(id), huellaNc(upstream.data()));
        }
        return upstream;
    }

    static final String MENSAJE_FALTA_LOTE = "Falta indicar el lote";
    static final String MENSAJE_MUESTRA_SIN_LEER = "Abre el detalle del lote antes de crear la muestra de Laboratorio.";
    static final String MENSAJE_MUESTRA_CONFLICTO = "El lote fue modificado por otra persona desde que lo abriste (muestra de "
            + "Laboratorio o estado). Vuelve a abrirlo para ver los cambios.";
    /** Claves de crearMuestra de Photino INNPACK ({action, data:{loteId}}). */
    private static final Set<String> CLAVES_RAIZ_MUESTRA = Set.of("action", "data");
    private static final Set<String> CLAVES_DATA_MUESTRA = Set.of("loteId");
    /** Un candado por lote: serializa en este gateway las creaciones del mismo lote (doble clic, dos sesiones). */
    private final java.util.concurrent.ConcurrentHashMap<Integer, Object> candadosMuestra = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * recepcion.muestra.crear → POST api/recepcion-calidad/{loteId}/muestra-laboratorio {empresa, usuarioId, usuarioNombre}
     * (Fase 3s), igual que Photino para el usuario: la API lee el lote, INSERTA una muestra en muestra_laboratorio
     * (origen ControlRecepcion, analista = usuario) y pone el lote en EnAnalisis, en cualquier estado y SIN control de
     * duplicados (Photino deja crear otra aunque ya exista una: la vista solo lo informa). Responde {muestraLaboratorioId}.
     *
     * Seguridad transparente: empresa, usuarioId y usuarioNombre ← sesión (Photino los toma de su sesión C#); lista blanca
     * {loteId}; el lote se confirma con el detalle de la EMPRESA DE SESIÓN (la API no filtra empresa ni eliminado).
     * Duplicados accidentales: exige haber abierto el detalle en esta sesión, serializa por lote en el gateway y relee el
     * detalle; si la muestra vinculada o el estado cambiaron desde la apertura (otra sesión, doble clic, reintento tras un
     * timeout que sí creó) rechaza en vez de crear otra. Crear otra a sabiendas (tras reabrir el lote) sigue permitido.
     * Ventana residual: creaciones desde Photino u otra instancia entre la relectura y el POST, y una segunda muestra
     * paralela cuando ya existía una en EnAnalisis (el detalle solo muestra TOP 1). Fallo parcial (API sin transacción):
     * muestra creada con el lote sin pasar a EnAnalisis; el reintento lo detecta la huella.
     */
    public BridgeResult muestraCrear(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ_MUESTRA.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_DATA_MUESTRA.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer loteId = entero(data.get("loteId"));
        if (loteId == null || loteId <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_LOTE);
        }
        String leida = LecturasDeSesion.huella(recursoLecturaMuestra(loteId));
        if (leida == null) {
            return BridgeResult.error(MENSAJE_MUESTRA_SIN_LEER);
        }
        synchronized (candadosMuestra.computeIfAbsent(loteId, k -> new Object())) {
            // Una petición en espera (doble clic) ve aquí la huella ya olvidada por la anterior.
            if (!leida.equals(LecturasDeSesion.huella(recursoLecturaMuestra(loteId)))) {
                return BridgeResult.error(MENSAJE_MUESTRA_SIN_LEER);
            }
            BridgeResult vigente = detalleLote(loteId, usuario); // empresa de sesión: un lote ajeno no existe para esta sesión
            if (!vigente.ok()) {
                return vigente;
            }
            if (!huellaMuestra(vigente.data()).equals(leida)) {
                return BridgeResult.error(MENSAJE_MUESTRA_CONFLICTO);
            }
            ObjectNode cuerpo = mapper.createObjectNode();
            cuerpo.put("empresa", usuario.empresa());
            cuerpo.put("usuarioId", usuario.userId());
            cuerpo.put("usuarioNombre", autorDeSesion(usuario));
            BridgeResult resultado = InnpackRespuestas.reenviar(
                    api.postJson(usuario, BASE + "/" + loteId + "/muestra-laboratorio", cuerpo), mapper);
            if (resultado.ok()) {
                // La vista vuelve a abrir el detalle enseguida y registra la huella nueva.
                LecturasDeSesion.olvidar(recursoLecturaMuestra(loteId));
            }
            return resultado;
        }
    }

    /** Recurso auditado: "recepcion:<loteId>:muestra[:<muestraLaboratorioId>]". */
    public static String recursoMuestra(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer loteId = data == null ? null : entero(data.get("loteId"));
        JsonNode id = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("muestraLaboratorioId") : null;
        return "recepcion:" + (loteId != null && loteId > 0 ? loteId : "?") + ":muestra"
                + (id != null && id.canConvertToInt() ? ":" + id.asInt() : "");
    }

    static String recursoLecturaMuestra(int loteId) {
        return "recepcion-muestra:" + loteId;
    }

    /** Lo que cambia muestra.crear en el detalle: muestra vinculada (TOP 1) y estado del lote. */
    static String huellaMuestra(Object detalle) {
        JsonNode d = detalle instanceof JsonNode n && n.isObject() ? n : null;
        JsonNode m = d == null ? null : d.get("muestraLaboratorioId");
        return (m == null || m.isNull() ? "SIN_MUESTRA" : m.asString()) + "|" + (d == null ? "" : texto(d.get("estado")));
    }

    // ------------------------------------------------------------------ Fase 3z: recepcion.crear ("Nuevo lote")
    static final String MENSAJE_TIPO_INVALIDO = "Tipo de materia prima inválido.";
    static final String MENSAJE_BOBINAS_INVALIDAS = "La lista de bobinas no es válida.";
    static final String MENSAJE_OPCION_INVALIDA = "Valor no permitido en ";
    static final String MENSAJE_FECHA_INVALIDA = "La fecha de fabricación/vencimiento no es válida (formato AAAA-MM-DD).";
    static final String MENSAJE_NUMERO_INVALIDO = "Valor numérico fuera de rango en ";
    static final String MENSAJE_FOTO_NO_IMAGEN = "La fotografía no es una imagen válida.";
    /** Opciones del <select id="rcqNlTipo"> de Photino. */
    private static final Set<String> TIPOS_LOTE = Set.of("Bobina", "PVA", "PliegoFaret");
    /** Claves de crearLote de Photino (Bobina + manual + PVA + Pliego), todas dentro de "data". */
    private static final Set<String> CLAVES_DATA_CREAR = Set.of("tipoMateriaPrima", "proveedor", "guia", "itemCode", "descripcion",
            "loteProveedor", "anchoDeclarado", "gramajeDeclarado", "bobinas", "pvaNombreAdhesivo", "pvaCantidadBins",
            "pvaFechaFabricacionVencimiento", "pvaCertificadoCalidad", "pvaCondicionGeneral", "pvaObservacion", "pvaFotoBase64",
            "pfNp", "pfCliente", "pfProducto", "pfCantidadTotal", "pfCantidadVerde", "pfCantidadAzul", "pfCantidadRoja",
            "pfEstadoCarpeta", "pfCondicionVisual", "pfTipoHallazgo", "pfCantidadAfectada", "pfObservacion", "pfFotoBase64");
    private static final Set<String> CLAVES_RAIZ_CREAR = Set.of("action", "data");
    /** Largos de recepcion_lotes_control / recepcion_pva / recepcion_pliego_faret (una línea salvo observaciones). */
    private static final java.util.Map<String, Integer> LARGOS_CREAR = java.util.Map.ofEntries(
            java.util.Map.entry("proveedor", 150), java.util.Map.entry("guia", 50), java.util.Map.entry("itemCode", 100),
            java.util.Map.entry("descripcion", 255), java.util.Map.entry("loteProveedor", 100),
            java.util.Map.entry("pvaNombreAdhesivo", 150), java.util.Map.entry("pvaObservacion", 500),
            java.util.Map.entry("pfNp", 50), java.util.Map.entry("pfCliente", 150), java.util.Map.entry("pfProducto", 255),
            java.util.Map.entry("pfCondicionVisual", 255), java.util.Map.entry("pfObservacion", 500));
    private static final Set<String> TEXTOS_MULTILINEA = Set.of("pvaObservacion", "pfObservacion");
    /** Opciones exactas de los <select> de la vista (Photino). "" = sin elegir donde la vista lo ofrece. */
    private static final java.util.Map<String, Set<String>> OPCIONES_CREAR = java.util.Map.of(
            "pvaCertificadoCalidad", Set.of("Si", "No", "Pendiente"),
            "pvaCondicionGeneral", Set.of("Conforme", "ConObservacion", "NoConforme"),
            "pfEstadoCarpeta", Set.of("Recibida", "Incompleta", "NoRecibida"),
            "pfTipoHallazgo", Set.of("", "DiferenciaTono", "GotasBarniz", "PiojosSuciedad", "ReservaBarniz", "Rayas", "Repinte",
                    "DanoBordes", "Otro"));
    /** DECIMAL(p,2): máximo por columna (10,2 → 99.999.999,99; 12,2 → 9.999.999.999,99). */
    private static final java.util.Map<String, java.math.BigDecimal> MAXIMOS_CREAR = java.util.Map.of(
            "anchoDeclarado", new java.math.BigDecimal("99999999.99"), "gramajeDeclarado", new java.math.BigDecimal("99999999.99"),
            "pvaCantidadBins", new java.math.BigDecimal("99999999.99"),
            "pfCantidadTotal", new java.math.BigDecimal("9999999999.99"), "pfCantidadVerde", new java.math.BigDecimal("9999999999.99"),
            "pfCantidadAzul", new java.math.BigDecimal("9999999999.99"), "pfCantidadRoja", new java.math.BigDecimal("9999999999.99"),
            "pfCantidadAfectada", new java.math.BigDecimal("9999999999.99"));
    private static final int MAX_BOBINAS = 500;
    private static final int MAX_NUMERO_BOBINA = 100;
    private static final Pattern CONTROL_MULTILINEA = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern FECHA_ISO = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    /** Doble clic: el mismo alta de la misma sesión dentro de esta ventana devuelve el lote ya creado (no crea otro). */
    static final long VENTANA_REPETIDO_MS = 10_000;
    private final java.util.concurrent.ConcurrentHashMap<Integer, Object> candadosCrear = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * recepcion.crear → POST api/recepcion-calidad {tipoMateriaPrima, empresa, proveedor, ..., bobinas, pva*, pf*, usuarioNombre}
     * (Fase 3z), igual que Photino para el usuario: "Nuevo Lote de Inspección" (Bobina desde SAP, o PVA / Pliego manual con
     * foto opcional). Mismo cuerpo que RecepcionCalidadHandler de Photino (textos ausentes = "", números GetDecimal). La API
     * valida tipo, bobinas en Bobina y la suma de colores en Pliego, e inserta SIN transacción (R8a) y sin validar la foto
     * (R8b). Responde {id}; la vista cierra el modal y abre el detalle (hoy roto para PVA/Pliego en la API: R9).
     *
     * Seguridad transparente: usuarioNombre y empresa ← sesión; lista blanca de claves; tipo entre las 3 opciones del select;
     * textos con los largos del esquema (un exceso haría fallar un INSERT intermedio y dejaría un lote huérfano), sin
     * controles ni marcado HTML; selects con sus opciones exactas; fecha AAAA-MM-DD; números dentro del rango de la columna; bobinas
     * solo en Bobina (texto ≤ 100, sin repetir); foto base64 estricta, ≤ 10 MB y con firma real de imagen. Doble clic: mismo
     * alta de la misma sesión en 10 s → devuelve el lote ya creado. Viaja por /api/v1/bridge/archivo (la foto supera el tope
     * general). Residual: la procedencia SAP de una Bobina no se verifica (igual que Photino).
     */
    public BridgeResult crear(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ_CREAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_DATA_CREAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        for (String clave : data.propertyNames()) {
            JsonNode v = data.get(clave);
            if (!clave.equals("bobinas") && v != null && !v.isNull() && !v.isString() && !v.isNumber()) {
                return BridgeResult.error(MENSAJE_PARAMETRO_INVALIDO);
            }
        }
        String tipo = texto(data.get("tipoMateriaPrima"));
        if (!tipo.isEmpty() && !TIPOS_LOTE.contains(tipo)) {
            return BridgeResult.error(MENSAJE_TIPO_INVALIDO);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("tipoMateriaPrima", tipo);
        cuerpo.put("empresa", usuario.empresa());
        for (String campo : new String[] {"proveedor", "guia", "itemCode", "descripcion", "loteProveedor"}) {
            String error = ponerTexto(cuerpo, data, campo);
            if (error != null) {
                return BridgeResult.error(error);
            }
        }
        for (String campo : new String[] {"anchoDeclarado", "gramajeDeclarado"}) {
            String error = ponerNumero(cuerpo, data, campo);
            if (error != null) {
                return BridgeResult.error(error);
            }
        }
        // Bobinas: Photino toma los strings no vacíos del arreglo (un no-string lanzaría en GetString → error).
        ArrayNode bobinas = cuerpo.putArray("bobinas");
        JsonNode arr = data.get("bobinas");
        if (arr != null && !arr.isNull()) {
            if (!arr.isArray() || arr.size() > MAX_BOBINAS) {
                return BridgeResult.error(MENSAJE_BOBINAS_INVALIDAS);
            }
            Set<String> vistas = new HashSet<>();
            for (JsonNode b : arr) {
                if (!b.isString()) {
                    return BridgeResult.error(MENSAJE_BOBINAS_INVALIDAS);
                }
                String n = b.asString();
                if (n.isEmpty()) {
                    continue;
                }
                if (n.length() > MAX_NUMERO_BOBINA || CONTROL_UNA_LINEA.matcher(n).find() || MARCADO_HTML.matcher(n).find()
                        || !vistas.add(n)) {
                    return BridgeResult.error(MENSAJE_BOBINAS_INVALIDAS);
                }
                bobinas.add(n);
            }
        }
        if ((tipo.equals("PVA") || tipo.equals("PliegoFaret")) && !bobinas.isEmpty()) {
            return BridgeResult.error(MENSAJE_BOBINAS_INVALIDAS);
        }
        String[] textosPva = {"pvaNombreAdhesivo", "pvaCertificadoCalidad", "pvaCondicionGeneral", "pvaObservacion"};
        String[] textosPf = {"pfNp", "pfCliente", "pfProducto", "pfEstadoCarpeta", "pfCondicionVisual", "pfTipoHallazgo", "pfObservacion"};
        String error = ponerTexto(cuerpo, data, "pvaNombreAdhesivo");
        error = error != null ? error : ponerNumero(cuerpo, data, "pvaCantidadBins");
        if (error != null) {
            return BridgeResult.error(error);
        }
        String fecha = texto(data.get("pvaFechaFabricacionVencimiento")).trim();
        if (!fecha.isEmpty()) {
            if (!FECHA_ISO.matcher(fecha).matches()) {
                return BridgeResult.error(MENSAJE_FECHA_INVALIDA);
            }
            try {
                java.time.LocalDate.parse(fecha);
            } catch (java.time.format.DateTimeParseException e) {
                return BridgeResult.error(MENSAJE_FECHA_INVALIDA);
            }
        }
        cuerpo.put("pvaFechaFabricacionVencimiento", texto(data.get("pvaFechaFabricacionVencimiento")));
        for (String campo : new String[] {"pvaCertificadoCalidad", "pvaCondicionGeneral", "pvaObservacion"}) {
            error = ponerTexto(cuerpo, data, campo);
            if (error != null) {
                return BridgeResult.error(error);
            }
        }
        error = ponerFoto(cuerpo, data, "pvaFotoBase64");
        if (error != null) {
            return BridgeResult.error(error);
        }
        for (String campo : new String[] {"pfNp", "pfCliente", "pfProducto"}) {
            error = ponerTexto(cuerpo, data, campo);
            if (error != null) {
                return BridgeResult.error(error);
            }
        }
        for (String campo : new String[] {"pfCantidadTotal", "pfCantidadVerde", "pfCantidadAzul", "pfCantidadRoja"}) {
            error = ponerNumero(cuerpo, data, campo);
            if (error != null) {
                return BridgeResult.error(error);
            }
        }
        for (String campo : new String[] {"pfEstadoCarpeta", "pfCondicionVisual", "pfTipoHallazgo"}) {
            error = ponerTexto(cuerpo, data, campo);
            if (error != null) {
                return BridgeResult.error(error);
            }
        }
        error = ponerNumero(cuerpo, data, "pfCantidadAfectada");
        error = error != null ? error : ponerTexto(cuerpo, data, "pfObservacion");
        error = error != null ? error : ponerFoto(cuerpo, data, "pfFotoBase64");
        if (error != null) {
            return BridgeResult.error(error);
        }
        // Opciones de los selects: solo se validan en el tipo que las usa (los demás viajan como Photino y la API los ignora).
        for (String campo : tipo.equals("PVA") ? textosPva : tipo.equals("PliegoFaret") ? textosPf : new String[0]) {
            Set<String> opciones = OPCIONES_CREAR.get(campo);
            if (opciones != null && !opciones.contains(cuerpo.get(campo).asString())) {
                return BridgeResult.error(MENSAJE_OPCION_INVALIDA + campo + ".");
            }
        }
        cuerpo.put("usuarioNombre", autorDeSesion(usuario));

        String huella = huellaCrear(cuerpo);
        synchronized (candadosCrear.computeIfAbsent(usuario.userId(), k -> new Object())) {
            String previo = LecturasDeSesion.huella(RECURSO_ULTIMO_ALTA);
            if (previo != null) {
                String[] partes = previo.split("\\|");
                if (partes.length == 3 && partes[0].equals(huella)
                        && System.currentTimeMillis() - Long.parseLong(partes[1]) < VENTANA_REPETIDO_MS) {
                    ObjectNode mismo = mapper.createObjectNode();
                    mismo.put("id", Integer.parseInt(partes[2]));
                    return BridgeResult.ok(mismo);
                }
            }
            BridgeResult resultado = InnpackRespuestas.reenviar(api.postJson(usuario, BASE, cuerpo), mapper);
            if (resultado.ok() && resultado.data() instanceof JsonNode d && d.path("id").canConvertToInt()) {
                LecturasDeSesion.registrar(RECURSO_ULTIMO_ALTA, huella + "|" + System.currentTimeMillis() + "|" + d.path("id").asInt());
            }
            return resultado;
        }
    }

    private static final String RECURSO_ULTIMO_ALTA = "recepcion-crear:ultimo";

    /** Recurso auditado: "recepcion:<id>:crear:<tipo>" (sin datos del formulario). */
    public static String recursoCrear(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        String tipo = data == null ? "" : texto(data.get("tipoMateriaPrima"));
        JsonNode id = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("id") : null;
        return "recepcion:" + (id != null && id.canConvertToInt() ? id.asInt() : "?") + ":crear"
                + (TIPOS_LOTE.contains(tipo) ? ":" + tipo : "");
    }

    /** GetString de Photino con los controles de la web; null = ok. */
    private String ponerTexto(ObjectNode cuerpo, JsonNode data, String campo) {
        String v = texto(data.get(campo));
        Integer max = LARGOS_CREAR.get(campo);
        if (max != null && v.length() > max) {
            return "El campo " + campo + " supera el máximo de " + max + " caracteres.";
        }
        Pattern control = TEXTOS_MULTILINEA.contains(campo) ? CONTROL_MULTILINEA : CONTROL_UNA_LINEA;
        if (control.matcher(v).find()) {
            return MENSAJE_TEXTO_CARACTERES;
        }
        if (MARCADO_HTML.matcher(v).find()) {
            return MENSAJE_TEXTO_HTML;
        }
        cuerpo.put(campo, v);
        return null;
    }

    /** GetDecimal de Photino (número o texto convertible; si no, null) acotado al rango de la columna; null = ok. */
    private static String ponerNumero(ObjectNode cuerpo, JsonNode data, String campo) {
        java.math.BigDecimal v = decimal(data, campo);
        if (v == null) {
            cuerpo.putNull(campo);
            return null;
        }
        // Photino admite negativos y más de 2 decimales (SQL Server redondea): solo se acota al rango de la columna.
        if (v.abs().compareTo(MAXIMOS_CREAR.get(campo)) > 0) {
            return MENSAJE_NUMERO_INVALIDO + campo + ".";
        }
        cuerpo.put(campo, v);
        return null;
    }

    /** Foto opcional: base64 estricto, ≤ 10 MB, firma real de imagen (lo que el input accept="image/*" entrega). */
    private static String ponerFoto(ObjectNode cuerpo, JsonNode data, String campo) {
        String b64 = texto(data.get(campo)).trim();
        if (b64.isEmpty()) {
            cuerpo.put(campo, "");
            return null;
        }
        if (b64.length() > MAX_BASE64_CHARS) {
            return MENSAJE_TAMANO;
        }
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return MENSAJE_FOTO_NO_IMAGEN;
        }
        if (bytes.length == 0 || bytes.length > MAX_FOTO_BYTES || !esImagen(bytes)) {
            return bytes.length > MAX_FOTO_BYTES ? MENSAJE_TAMANO : MENSAJE_FOTO_NO_IMAGEN;
        }
        cuerpo.put(campo, b64);
        return null;
    }

    /** JPEG/PNG/GIF/WEBP (visibles) + BMP, TIFF, HEIC/HEIF/AVIF (una cámara/celular puede entregarlas; Photino las guarda). */
    static boolean esImagen(byte[] b) {
        if (mimeImagen(b) != null) {
            return true;
        }
        if (b.length >= 2 && b[0] == 'B' && b[1] == 'M') {
            return true;
        }
        if (b.length >= 4 && ((b[0] == 'I' && b[1] == 'I' && b[2] == 42 && b[3] == 0) || (b[0] == 'M' && b[1] == 'M' && b[2] == 0 && b[3] == 42))) {
            return true;
        }
        if (b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            String marca = new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            return Set.of("heic", "heix", "hevc", "hevx", "mif1", "msf1", "heif", "avif", "avis").contains(marca);
        }
        return false;
    }

    /** SHA-256 del cuerpo (incluida la foto): identifica "el mismo alta" para el doble clic. */
    private static String huellaCrear(ObjectNode cuerpo) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(cuerpo.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static final String MENSAJE_NC_SIN_LEER = "Abre el detalle del lote antes de crear la No Conformidad.";
    static final String MENSAJE_NC_CONFLICTO = "El lote fue modificado por otra persona desde que lo abriste (No Conformidad "
            + "vinculada o estado). Vuelve a abrirlo para ver los cambios.";
    /** Claves de crearNoConformidad de Photino INNPACK ({action, data:{loteId}}). */
    private static final Set<String> CLAVES_RAIZ_NC = Set.of("action", "data");
    private static final Set<String> CLAVES_DATA_NC = Set.of("loteId");
    /** Un candado por lote: serializa en este gateway las creaciones de NC del mismo lote (doble clic, dos sesiones). */
    private final java.util.concurrent.ConcurrentHashMap<Integer, Object> candadosNc = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * recepcion.nc.crear → POST api/recepcion-calidad/{loteId}/nc {usuarioNombre} (Fase 3x), igual que Photino para el
     * usuario: botón "Crear No Conformidad" del detalle de un lote "No conforme" sin NC; la API lee el lote (SIN filtro de
     * empresa), exige estado NoConforme y sin NC vinculada, CREA la NC (tipo INTERNA, origen AUDITORIA_INTERNA, severidad
     * MEDIA, creada por usuarioNombre) y DESPUÉS la vincula (sin transacción). Responde {ncId, codigo}.
     *
     * Seguridad transparente: usuarioNombre ← sesión (Photino: NombreCompleto de su sesión C#); lista blanca {loteId}; el
     * lote se confirma con el detalle de la EMPRESA DE SESIÓN. Duplicados: la API verifica sin bloqueo (dos creaciones
     * simultáneas pasan ambas); la web exige haber abierto el detalle, serializa por lote y relee: si la NC vinculada o el
     * estado cambiaron desde la apertura (otra sesión, doble clic, reintento tras un timeout que sí creó) rechaza en vez
     * de crear otra. Residual: creaciones desde Photino u otra instancia entre la relectura y el POST, y el fallo parcial
     * de la API (NC creada sin vincular: el lote sigue sin NC y un reintento crea otra) — documentados en la matriz.
     */
    public BridgeResult ncCrear(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ_NC.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_DATA_NC.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer loteId = entero(data.get("loteId"));
        if (loteId == null || loteId <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_LOTE);
        }
        String leida = LecturasDeSesion.huella(recursoLecturaNc(loteId));
        if (leida == null) {
            return BridgeResult.error(MENSAJE_NC_SIN_LEER);
        }
        synchronized (candadosNc.computeIfAbsent(loteId, k -> new Object())) {
            // Una petición en espera (doble clic) ve aquí la huella ya olvidada por la anterior.
            if (!leida.equals(LecturasDeSesion.huella(recursoLecturaNc(loteId)))) {
                return BridgeResult.error(MENSAJE_NC_SIN_LEER);
            }
            BridgeResult vigente = detalleLote(loteId, usuario); // empresa de sesión: un lote ajeno no existe para esta sesión
            if (!vigente.ok()) {
                return vigente;
            }
            if (!huellaNc(vigente.data()).equals(leida)) {
                return BridgeResult.error(MENSAJE_NC_CONFLICTO);
            }
            ObjectNode cuerpo = mapper.createObjectNode();
            cuerpo.put("usuarioNombre", autorDeSesion(usuario));
            BridgeResult resultado = InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/" + loteId + "/nc", cuerpo), mapper);
            if (resultado.ok()) {
                // La vista vuelve a abrir el detalle enseguida y registra la huella nueva.
                LecturasDeSesion.olvidar(recursoLecturaNc(loteId));
            }
            return resultado;
        }
    }

    /** Recurso auditado: "recepcion:<loteId>:nc[:<ncId>]". */
    public static String recursoNc(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer loteId = data == null ? null : entero(data.get("loteId"));
        JsonNode id = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("ncId") : null;
        return "recepcion:" + (loteId != null && loteId > 0 ? loteId : "?") + ":nc"
                + (id != null && id.canConvertToInt() ? ":" + id.asInt() : "");
    }

    static String recursoLecturaNc(int loteId) {
        return "recepcion-nc:" + loteId;
    }

    /** Lo que decide nc.crear en el detalle: NC vinculada y estado del lote. */
    static String huellaNc(Object detalle) {
        JsonNode d = detalle instanceof JsonNode n && n.isObject() ? n : null;
        JsonNode nc = d == null ? null : d.get("ncId");
        return (nc == null || nc.isNull() ? "SIN_NC" : nc.asString()) + "|" + (d == null ? "" : texto(d.get("estado")));
    }

    static final String MENSAJE_FALTA_LOTE_ESTADO = "Falta el lote o el estado";
    static final String MENSAJE_ESTADO_INVALIDO = "Estado inválido.";
    static final String MENSAJE_ESTADO_SIN_LEER = "Abre el detalle del lote antes de actualizar el estado.";
    static final String MENSAJE_ESTADO_CONFLICTO = "El estado del lote fue modificado por otra persona desde que lo abriste "
            + "(muestreo, muestra de Laboratorio o actualización de estado). Vuelve a abrirlo para ver los cambios.";
    /** Únicos valores que ofrece el <select id="rcqEstadoManual"> de Photino; la API no valida el valor. */
    private static final Set<String> ESTADOS_MANUALES = Set.of("RecibidaConforme", "RecibidaConObservacion", "NoConforme");
    /** Claves de actualizarEstado de Photino ({action, data:{loteId, estado}}). */
    private static final Set<String> CLAVES_RAIZ_ESTADO = Set.of("action", "data");
    private static final Set<String> CLAVES_DATA_ESTADO = Set.of("loteId", "estado");
    /** Un candado por lote: serializa en este gateway las actualizaciones del mismo lote (doble clic, dos sesiones). */
    private final java.util.concurrent.ConcurrentHashMap<Integer, Object> candadosEstado = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * recepcion.estado.actualizar → PATCH api/recepcion-calidad/{loteId}/estado {estado} (Fase 3t), igual que Photino
     * para el usuario: un UPDATE directo de `recepcion_lotes_control.estado`, sin autor ni control de duplicados ni
     * validación del valor en la API.
     *
     * Seguridad transparente: el lote se confirma con el detalle de la EMPRESA DE SESIÓN (la API no filtra empresa ni
     * eliminado — hallazgo R5); `estado` restringido a los 3 valores que ofrece el <select> de Photino (lo único
     * alcanzable por uso normal; un valor distinto solo vendría de un payload manipulado). Concurrencia: exige haber
     * abierto el detalle en esta sesión y lo relee antes del PATCH; si el estado cambió desde entonces (otra sesión,
     * `bobinas.muestrear` o `muestra.crear` también lo mutan) rechaza en vez de pisarlo. Candado por lote para doble
     * clic. Ventana residual: entre la relectura y el PATCH (milisegundos).
     */
    public BridgeResult estadoActualizar(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ_ESTADO.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_DATA_ESTADO.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer loteId = entero(data.get("loteId"));
        String estado = texto(data.get("estado")).trim();
        if (loteId == null || loteId <= 0 || estado.isEmpty()) {
            return BridgeResult.error(MENSAJE_FALTA_LOTE_ESTADO);
        }
        if (!ESTADOS_MANUALES.contains(estado)) {
            return BridgeResult.error(MENSAJE_ESTADO_INVALIDO);
        }
        String leida = LecturasDeSesion.huella(recursoLecturaEstado(loteId));
        if (leida == null) {
            return BridgeResult.error(MENSAJE_ESTADO_SIN_LEER);
        }
        synchronized (candadosEstado.computeIfAbsent(loteId, k -> new Object())) {
            // Una petición en espera (doble clic) ve aquí la huella ya olvidada por la anterior.
            if (!leida.equals(LecturasDeSesion.huella(recursoLecturaEstado(loteId)))) {
                return BridgeResult.error(MENSAJE_ESTADO_SIN_LEER);
            }
            BridgeResult vigente = detalleLote(loteId, usuario); // empresa de sesión: un lote ajeno no existe para esta sesión
            if (!vigente.ok()) {
                return vigente;
            }
            if (!huellaEstado(vigente.data()).equals(leida)) {
                return BridgeResult.error(MENSAJE_ESTADO_CONFLICTO);
            }
            ObjectNode cuerpo = mapper.createObjectNode();
            cuerpo.put("estado", estado);
            BridgeResult resultado = InnpackRespuestas.reenviar(
                    api.patchJson(usuario, BASE + "/" + loteId + "/estado", cuerpo), mapper);
            if (resultado.ok()) {
                // La vista vuelve a abrir el detalle enseguida y registra la huella nueva.
                LecturasDeSesion.olvidar(recursoLecturaEstado(loteId));
            }
            return resultado;
        }
    }

    /** Recurso auditado: "recepcion:<loteId>:estado:<valor>". */
    public static String recursoEstado(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer loteId = data == null ? null : entero(data.get("loteId"));
        String estado = data == null ? "" : texto(data.get("estado"));
        return "recepcion:" + (loteId != null && loteId > 0 ? loteId : "?") + ":estado" + (estado.isEmpty() ? "" : ":" + estado);
    }

    static String recursoLecturaEstado(int loteId) {
        return "recepcion-estado:" + loteId;
    }

    /** Lo único que cambia estado.actualizar en el detalle: el estado del lote. */
    static String huellaEstado(Object detalle) {
        JsonNode d = detalle instanceof JsonNode n && n.isObject() ? n : null;
        return d == null ? "" : texto(d.get("estado"));
    }

    static final String MENSAJE_FALTA_LOTE_BOBINAS = "Falta el lote o la lista de bobinas muestreadas";
    static final String MENSAJE_MUESTREO_SIN_LEER = "Abre el detalle del lote antes de guardar las bobinas muestreadas.";
    static final String MENSAJE_MUESTREO_CONFLICTO = "La selección de bobinas muestreadas fue modificada por otra persona desde que "
            + "abriste el lote. Vuelve a abrirlo para ver los cambios.";
    static final String MENSAJE_CAMPO_NO_PERMITIDO = "Campo no permitido: ";
    static final String MENSAJE_PARAMETRO_INVALIDO = "Parámetro inválido.";
    static final String MENSAJE_TEXTO_CARACTERES = "El texto contiene caracteres no permitidos.";
    static final String MENSAJE_TEXTO_HTML = "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").";
    static final String MARCA_BOBINAS = "__qccAuditoriaBobinas";
    /** Claves de _guardarMuestreadas de Photino ({action, data:{loteId, bobinas}}) + "usuario" de IdentityOverride. */
    private static final Set<String> CLAVES_RAIZ_MUESTREAR = Set.of("action", "data", "usuario");
    private static final Set<String> CLAVES_DATA_MUESTREAR = Set.of("loteId", "bobinas", "usuario");
    private static final Set<String> CLAVES_BOBINA = Set.of("numeroBobina", "seleccionTipo", "criterioManual");
    /** Valores que produce la vista (botón Aleatoria / selección manual); vacío → "Manual" como C# y la API. */
    private static final List<String> TIPOS_SELECCION = List.of("Manual", "Aleatoria");
    private static final Pattern CONTROL_UNA_LINEA = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final Pattern MARCADO_HTML = Pattern.compile("<[A-Za-z/!?]");

    /**
     * recepcion.bobinas.muestrear → POST api/recepcion-calidad/{loteId}/bobinas-muestreadas {bobinas[{numeroBobina,
     * seleccionTipo, criterioManual}], usuario}. PRIMERA ESCRITURA de Recepción (Fase 3q), igual que Photino para el
     * usuario: la API REEMPLAZA la selección del lote (DELETE + INSERT, sin transacción), valida que cada bobina
     * pertenezca al lote y pasa el estado PendienteMuestreo → PendienteLaboratorio (desde otros estados no lo cambia);
     * se puede volver a guardar en cualquier estado y re-seleccionar bobinas ya muestreadas (reemplazo), como en Photino.
     *
     * Seguridad transparente: `usuario` ← sesión (Photino lo toma de su sesión C#); lista blanca de claves y tipos;
     * número de bobina ≤ 100, tipo ≤ 20, criterio ≤ 255 una línea (columnas reales) sin controles ni HTML; bobinas
     * repetidas rechazadas (sin UNIQUE en la tabla: solo un payload manipulado las mandaría); el lote se confirma con el
     * detalle de la EMPRESA DE SESIÓN (ids ajenos → error) y la API valida la pertenencia de cada bobina. Concurrencia:
     * exige haber abierto el detalle en esta sesión y lo relee antes del POST; si la selección cambió desde entonces
     * (otra sesión la guardó) rechaza en vez de pisarla. Ventana residual: entre la relectura y el POST (milisegundos)
     * y el DELETE+INSERT no transaccional de la API (hardening futuro en la API).
     */
    public BridgeResult bobinasMuestrear(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ_MUESTREAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_DATA_MUESTREAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer loteId = entero(data.get("loteId"));
        JsonNode bobinas = data.get("bobinas");
        if (bobinas != null && !bobinas.isNull() && !bobinas.isArray()) {
            return BridgeResult.error(MENSAJE_PARAMETRO_INVALIDO);
        }
        if (loteId == null || loteId <= 0 || bobinas == null || bobinas.isNull() || bobinas.isEmpty()) {
            return BridgeResult.error(MENSAJE_FALTA_LOTE_BOBINAS);
        }
        ArrayNode lista = mapper.createArrayNode();
        Set<String> vistas = new HashSet<>();
        for (JsonNode b : bobinas) {
            if (!b.isObject()) {
                return BridgeResult.error(MENSAJE_PARAMETRO_INVALIDO);
            }
            for (String clave : b.propertyNames()) {
                if (!CLAVES_BOBINA.contains(clave)) {
                    return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
                }
            }
            for (String campo : new String[] {"numeroBobina", "seleccionTipo", "criterioManual"}) {
                JsonNode v = b.get(campo);
                if (v != null && !v.isNull() && !(v.isString() || v.isNumber() && campo.equals("numeroBobina"))) {
                    return BridgeResult.error(MENSAJE_PARAMETRO_INVALIDO);
                }
            }
            String numero = texto(b.get("numeroBobina"));
            String error = validarUnaLinea(numero, 100, "El número de bobina supera el máximo de 100 caracteres.");
            if (error != null) {
                return BridgeResult.error(error);
            }
            if (!numero.isEmpty() && !vistas.add(numero)) {
                return BridgeResult.error("La bobina " + numero + " está repetida en la selección.");
            }
            String tipo = texto(b.get("seleccionTipo")).trim();
            if (tipo.isEmpty()) {
                tipo = "Manual";
            }
            if (!TIPOS_SELECCION.contains(tipo)) {
                return BridgeResult.error("Tipo de selección inválido (Manual o Aleatoria).");
            }
            JsonNode c = b.get("criterioManual");
            String criterio = c == null || c.isNull() ? null : c.asString().strip();
            if (criterio != null) {
                error = validarUnaLinea(criterio, 255, "El motivo supera el máximo de 255 caracteres.");
                if (error != null) {
                    return BridgeResult.error(error);
                }
            }
            ObjectNode item = lista.addObject();
            item.put("numeroBobina", numero);
            item.put("seleccionTipo", tipo);
            if (criterio == null || criterio.isEmpty()) {
                item.putNull("criterioManual");
            } else {
                item.put("criterioManual", criterio);
            }
        }
        String leida = LecturasDeSesion.huella(recursoLecturaMuestreo(loteId));
        if (leida == null) {
            return BridgeResult.error(MENSAJE_MUESTREO_SIN_LEER);
        }
        BridgeResult vigente = detalleLote(loteId, usuario); // empresa de sesión: un lote ajeno no existe para esta sesión
        if (!vigente.ok()) {
            return vigente;
        }
        if (!huellaMuestreadas(vigente.data()).equals(leida)) {
            return BridgeResult.error(MENSAJE_MUESTREO_CONFLICTO);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.set("bobinas", lista);
        cuerpo.put("usuario", autorDeSesion(usuario));
        // Canal interno para la auditoría (cantidad de bobinas; el payload saneado no vuelve al navegador ni a la API).
        payload.put(MARCA_BOBINAS, lista.size());
        BridgeResult resultado = InnpackRespuestas.reenviar(
                api.postJson(usuario, BASE + "/" + loteId + "/bobinas-muestreadas", cuerpo), mapper);
        if (resultado.ok()) {
            // La vista vuelve a abrir el detalle enseguida y registra la huella de la nueva selección.
            LecturasDeSesion.olvidar(recursoLecturaMuestreo(loteId));
        }
        return resultado;
    }

    /** Recurso auditado: "recepcion:<loteId>:muestreadas:<n bobinas>" (sin números de bobina ni motivo). */
    public static String recursoMuestreo(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer loteId = data == null ? null : entero(data.get("loteId"));
        JsonNode n = payload.get(MARCA_BOBINAS);
        return "recepcion:" + (loteId != null && loteId > 0 ? loteId : "?") + ":muestreadas"
                + (n != null && n.canConvertToInt() ? ":" + n.asInt() : "");
    }

    static String recursoLecturaMuestreo(int loteId) {
        return "recepcion-muestreo:" + loteId;
    }

    /** SHA-256 de la selección vigente (muestreadas del detalle): cambia si otra sesión la reemplaza. */
    static String huellaMuestreadas(Object detalle) {
        JsonNode m = detalle instanceof JsonNode d && d.isObject() ? d.get("muestreadas") : null;
        String base = m == null || m.isNull() ? "SIN_SELECCION" : m.toString();
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(base.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** ≤ max caracteres, una sola línea, sin controles, sustitutos sueltos ni marcado HTML; null si es válido. */
    private static String validarUnaLinea(String valor, int max, String largo) {
        if (valor.length() > max) {
            return largo;
        }
        if (CONTROL_UNA_LINEA.matcher(valor).find() || tieneSustitutoSuelto(valor)) {
            return MENSAJE_TEXTO_CARACTERES;
        }
        if (MARCADO_HTML.matcher(valor).find()) {
            return MENSAJE_TEXTO_HTML;
        }
        return null;
    }

    private static boolean tieneSustitutoSuelto(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isHighSurrogate(ch) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }

    private static String nombreCampoSeguro(String clave) {
        return clave != null && clave.matches("[A-Za-z0-9_]{1,40}") ? clave : "?";
    }

    /** Mismo criterio que la sesión C# de Photino (nombre completo; si no, el código). */
    private static String autorDeSesion(SessionUser usuario) {
        String nombre = usuario.nombreCompleto();
        return nombre != null && !nombre.isBlank() ? nombre : usuario.codigoUsuario();
    }

    /** recepcion.foto.abrir → (detalle de la empresa de sesión) + GET api/recepcion-calidad/{loteId}/foto?tipoMateriaPrima=.. */
    public BridgeResult fotoAbrir(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        Integer loteId = entero(data.get("loteId"));
        String tipo = texto(data.get("tipoMateriaPrima")).trim();
        if (loteId == null || loteId <= 0 || tipo.isEmpty()) {
            return BridgeResult.error(MENSAJE_FALTA_FOTO);
        }
        if (!TIPOS_CON_FOTO.contains(tipo)) {
            return BridgeResult.error(MENSAJE_SIN_FOTO);
        }
        BridgeResult lote = detalleLote(loteId, usuario);
        if (!lote.ok()) {
            return lote;
        }
        JsonNode detalle = lote.data() instanceof JsonNode n ? n : null;
        if (detalle == null || !tipo.equals(texto(detalle.get("tipoMateriaPrima")))) {
            return BridgeResult.error(MENSAJE_SIN_FOTO);
        }
        BridgeResult upstream = InnpackRespuestas.reenviar(
                api.get(usuario, BASE + "/" + loteId + "/foto?tipoMateriaPrima=" + UriEscape.dataString(tipo)), mapper);
        if (!upstream.ok()) {
            return upstream;
        }
        JsonNode foto = upstream.data() instanceof JsonNode n && n.isObject() ? n : null;
        String base64 = texto(foto == null ? null : foto.get("base64")).trim();
        if (base64.isEmpty()) {
            return BridgeResult.error(MENSAJE_SIN_FOTO);
        }
        if (base64.length() > MAX_BASE64_CHARS) {
            return BridgeResult.error(MENSAJE_TAMANO);
        }
        String mime = mimeImagen(ControlDocumentalBridgeHandler.inicioDecodificado(base64));
        if (mime == null) {
            return BridgeResult.error(MENSAJE_FOTO_INVALIDA);
        }
        ObjectNode salida = mapper.createObjectNode();
        salida.put("base64", base64);
        salida.put("mime", mime);
        return BridgeResult.ok(salida);
    }

    /** MIME de imagen según la firma real (JPEG/PNG/GIF/WEBP); null si no es una imagen reconocida. */
    public static String mimeImagen(byte[] b) {
        if (b == null) {
            return null;
        }
        if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0d && b[5] == 0x0a && b[6] == 0x1a && b[7] == 0x0a) {
            return "image/png";
        }
        if (b.length >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "image/gif";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private BridgeResult detalleLote(int id, SessionUser usuario) {
        return InnpackRespuestas.reenviar(
                api.get(usuario, BASE + "/" + id + "?empresa=" + UriEscape.dataString(usuario.empresa())), mapper);
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
