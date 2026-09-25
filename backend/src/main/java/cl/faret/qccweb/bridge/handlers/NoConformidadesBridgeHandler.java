package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeAction;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "No Conformidades" (solo INNPACK) — SOLO LECTURA. Port de Photino
 * src/Backend/Modules/NoConformidades/NoConformidadesHandler.cs + InnpackNoConformidadesApiService +
 * InnpackNoConformidadesCatalogosApiService. El payload de este módulo viaja PLANO (sin "data").
 *
 * No hay "empresa" en ninguna acción (la tabla no tiene esa columna; el menú lo muestra solo con
 * data-empresa="INNPACK"): nada que pisar con la sesión. cliente/tipoPnc/nivel/estadoGestion/area/
 * fechaDesde/fechaHasta son filtros de negocio reales y se reenvían como en Photino.
 *
 * adjuntos.abrir: Photino devuelve el base64 y la vista lo muestra en la misma página (<img> o
 * <iframe> con data:), sin archivos temporales. Diferencias DEFENSIVAS: el gateway solo entrega PDF/
 * PNG/JPEG (lo único que la API acepta al subir) cuya firma real coincide con el MIME declarado, con
 * tamaño acotado; y sanea nombreArchivo (la vista lo inserta en innerHTML) en adjuntos.list/abrir.
 * bool/objeto/array en filtros de texto → "Parámetro de filtro inválido." (regla 2e).
 *
 * Escrituras (create, update, eliminar, gestion.actualizar, cerrar, seguimiento.crear,
 * analisis.guardar, acciones.crear/actualizar, adjuntos.subir/eliminar, catalogos.*.crear/desactivar)
 * NO habilitadas.
 */
public class NoConformidadesBridgeHandler {

    static final String MENSAJE_ID_NC = "Falta el id de la no conformidad";
    static final String MENSAJE_ID_ADJUNTO = "Falta el id del adjunto";
    static final String MENSAJE_FILTRO_INVALIDO = "Parámetro de filtro inválido.";
    static final String MENSAJE_SIN_CONTENIDO = "El adjunto no trae contenido";
    static final String MENSAJE_TAMANO = "El adjunto excede el tamaño máximo permitido.";
    static final String MENSAJE_ADJUNTO_INVALIDO = "El adjunto no es válido.";
    static final String MENSAJE_PARAMETRO_INVALIDO = "Parámetro inválido.";
    static final String MENSAJE_FALTA_COMENTARIO = "Falta el comentario de seguimiento";
    static final String MENSAJE_COMENTARIO_LARGO = "El comentario supera el máximo de 2000 caracteres.";
    static final String MENSAJE_COMENTARIO_CARACTERES = "El comentario contiene caracteres no permitidos.";
    static final String MENSAJE_COMENTARIO_HTML = "El comentario no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").";
    static final int MAX_COMENTARIO = 2000;
    /**
     * Apertura de etiqueta/comentario/declaración según el parser HTML: "<" seguido INMEDIATAMENTE de
     * letra ASCII, "/", "!" o "?". "a < b", "5<6", "->" o "<3" no abren etiqueta y se aceptan.
     */
    private static final Pattern MARCADO_HTML = Pattern.compile("<[A-Za-z/!?]");
    static final String MENSAJE_CAMPO_NO_PERMITIDO = "Campo no permitido: ";
    static final String MENSAJE_FECHA_INVALIDA = "La fecha límite no es válida (formato AAAA-MM-DD).";
    static final String MENSAJE_TEXTO_CARACTERES = "El texto contiene caracteres no permitidos.";
    static final String MENSAJE_TEXTO_HTML = "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").";
    static final int MAX_DESCRIPCION_ACCION = 500;
    static final int MAX_RESPONSABLE = 150;
    /** Claves que manda Photino en _agregarAccion (+ "action"); cualquier otra se rechaza. */
    private static final Set<String> CAMPOS_ACCION_CREAR = Set.of(
            "action", "id", "analisisId", "creadoPor", "descripcion", "responsable", "fechaLimite", "prioridad");
    private static final Set<String> PRIORIDADES = Set.of("ALTA", "MEDIA", "BAJA");
    private static final Pattern FECHA_ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** Campos de una línea (input text): ningún carácter de control, tampoco saltos de línea. */
    private static final Pattern CONTROL_UNA_LINEA = Pattern.compile("[\\x00-\\x08\\x0A-\\x1F\\x7F]");
    /** Caracteres de control salvo tab, salto de línea y retorno de carro. */
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /** Máximo de la API al subir (PDF 10 MB; fotos 5 MB). */
    static final int MAX_ADJUNTO_BYTES = 10 * 1024 * 1024;
    static final int MAX_BASE64_CHARS = ((MAX_ADJUNTO_BYTES + 2) / 3) * 4;

    /** Los 9 catálogos administrables (segmento de ruta de api/nc-catalogos). */
    public static final String[] CATALOGOS = {
        "clientes", "categoriasDefecto", "tiposFalla", "supervisores", "revisores",
        "areas", "familiasProducto", "niveles", "impactos"};

    /** MIME que NoConformidadesService acepta al subir (CAUSA_RAIZ_PDF / EVIDENCIA_FOTO). */
    private static final Set<String> MIME_ADJUNTO = Set.of("application/pdf", "image/png", "image/jpeg");
    private static final String[] FILTROS_TEXTO = {"cliente", "tipoPnc", "nivel", "estadoGestion", "area", "fechaDesde", "fechaHasta"};
    private static final String BASE = "/api/no-conformidades";
    private static final String BASE_CATALOGOS = "/api/nc-catalogos";

    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public NoConformidadesBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /**
     * noConformidades.list → GET api/no-conformidades?page=..&pageSize=..[&filtros] — page/pageSize:
     * entero o string numérica y > 0, si no 1/50 (Exportar/indicadores mandan 999999).
     */
    public BridgeResult list(ObjectNode payload, SessionUser usuario) {
        Integer page = entero(payload.get("page"));
        Integer pageSize = entero(payload.get("pageSize"));
        StringBuilder filtros = new StringBuilder();
        if (!filtrosQuery(payload, filtros)) {
            return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
        }
        String path = BASE + "?page=" + (page != null && page > 0 ? page : 1)
                + "&pageSize=" + (pageSize != null && pageSize > 0 ? pageSize : 50) + filtros;
        return InnpackRespuestas.reenviar(api.get(usuario, path), mapper);
    }

    /** noConformidades.resumen → GET api/no-conformidades/resumen[?filtros] */
    public BridgeResult resumen(ObjectNode payload, SessionUser usuario) {
        StringBuilder filtros = new StringBuilder();
        if (!filtrosQuery(payload, filtros)) {
            return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
        }
        String path = BASE + "/resumen" + (filtros.isEmpty() ? "" : "?" + filtros.substring(1));
        return InnpackRespuestas.reenviar(api.get(usuario, path), mapper);
    }

    /** noConformidades.filtrosOpciones → GET api/no-conformidades/filtros-opciones (no lee el payload). */
    public BridgeResult filtrosOpciones(ObjectNode payload, SessionUser usuario) {
        return InnpackRespuestas.reenviar(api.get(usuario, BASE + "/filtros-opciones"), mapper);
    }

    /** noConformidades.get → GET api/no-conformidades/{id} */
    public BridgeResult get(ObjectNode payload, SessionUser usuario) {
        return porId(payload, usuario, "");
    }

    /**
     * noConformidades.seguimiento.list → GET api/no-conformidades/{id}/seguimiento, con `comentario` y
     * `autor` ESCAPADOS como HTML (diferencia defensiva, SEC-27): la vista de Photino los pinta con
     * innerHTML sin escapar, así que un comentario con marcado guardado desde Photino se ejecutaría en el
     * navegador web. innerHTML decodifica las entidades → el texto se ve idéntico y nunca se ejecuta.
     */
    public BridgeResult seguimientoList(ObjectNode payload, SessionUser usuario) {
        return escaparCampos(porId(payload, usuario, "/seguimiento"), "comentario", "autor");
    }

    /** noConformidades.analisis.get → GET api/no-conformidades/{id}/analisis */
    public BridgeResult analisisGet(ObjectNode payload, SessionUser usuario) {
        return porId(payload, usuario, "/analisis");
    }

    /**
     * noConformidades.acciones.list → GET api/no-conformidades/{id}/acciones, con `descripcion`,
     * `responsable` y `prioridad` escapados como HTML (misma razón que seguimiento.list, SEC-27). `estado`
     * no se toca (la vista lo compara con valores fijos). OJO al habilitar acciones.actualizar: la vista
     * reenvía descripcion/responsable tomados de esta lista → el gateway deberá des-escaparlos
     * (HtmlUtils.htmlUnescape) antes de validarlos y enviarlos a la API.
     */
    public BridgeResult accionesList(ObjectNode payload, SessionUser usuario) {
        return escaparCampos(porId(payload, usuario, "/acciones"), "descripcion", "responsable", "prioridad");
    }

    /** Escapa como HTML (UTF-8: solo & < > " ') los campos string indicados de cada ítem de una lista. */
    private static BridgeResult escaparCampos(BridgeResult upstream, String... campos) {
        if (upstream.ok() && upstream.data() instanceof ArrayNode items) {
            for (JsonNode item : items) {
                if (item instanceof ObjectNode c) {
                    for (String campo : campos) {
                        JsonNode v = c.get(campo);
                        if (v != null && v.isString()) {
                            c.put(campo, HtmlUtils.htmlEscape(v.asString(), "UTF-8"));
                        }
                    }
                }
            }
        }
        return upstream;
    }

    /** noConformidades.adjuntos.list → GET api/no-conformidades/{id}/adjuntos, con nombreArchivo saneado. */
    public BridgeResult adjuntosList(ObjectNode payload, SessionUser usuario) {
        BridgeResult upstream = porId(payload, usuario, "/adjuntos");
        if (upstream.ok() && upstream.data() instanceof ArrayNode items) {
            for (JsonNode item : items) {
                if (item instanceof ObjectNode adjunto && adjunto.has("nombreArchivo")) {
                    adjunto.put("nombreArchivo", ControlDocumentalBridgeHandler.nombreArchivoSeguro(
                            textoDe(adjunto, "nombreArchivo"), "adjunto_" + textoDe(adjunto, "id")));
                }
            }
        }
        return upstream;
    }

    /** noConformidades.adjuntos.abrir → GET api/no-conformidades/{id}/adjuntos/{adjuntoId}, validado. */
    public BridgeResult adjuntosAbrir(ObjectNode payload, SessionUser usuario) {
        Integer id = entero(payload.get("id"));
        if (id == null) {
            return BridgeResult.error(MENSAJE_ID_NC);
        }
        Integer adjuntoId = entero(payload.get("adjuntoId"));
        if (adjuntoId == null) {
            return BridgeResult.error(MENSAJE_ID_ADJUNTO);
        }
        BridgeResult upstream = InnpackRespuestas.reenviar(api.get(usuario, BASE + "/" + id + "/adjuntos/" + adjuntoId), mapper);
        if (!upstream.ok()) {
            return upstream;
        }
        JsonNode adjunto = upstream.data() instanceof JsonNode n && n.isObject() ? n : null;
        String base64 = textoDe(adjunto, "contenidoBase64").trim();
        if (base64.isEmpty()) {
            return BridgeResult.error(MENSAJE_SIN_CONTENIDO);
        }
        if (base64.length() > MAX_BASE64_CHARS) {
            return BridgeResult.error(MENSAJE_TAMANO);
        }
        byte[] inicio = ControlDocumentalBridgeHandler.inicioDecodificado(base64);
        String tipoMime = textoDe(adjunto, "tipoMime").trim().toLowerCase();
        if (inicio == null || !MIME_ADJUNTO.contains(tipoMime) || !ControlDocumentalBridgeHandler.firmaCoincide(tipoMime, inicio)) {
            return BridgeResult.error(MENSAJE_ADJUNTO_INVALIDO);
        }
        ObjectNode salida = mapper.createObjectNode();
        salida.put("id", adjuntoId);
        salida.put("nombreArchivo", ControlDocumentalBridgeHandler.nombreArchivoSeguro(textoDe(adjunto, "nombreArchivo"), "adjunto_" + adjuntoId));
        salida.put("tipoMime", tipoMime);
        salida.put("contenidoBase64", base64);
        return BridgeResult.ok(salida);
    }

    /**
     * noConformidades.seguimiento.crear → POST api/no-conformidades/{id}/seguimiento {comentario, autor}.
     * PRIMERA ESCRITURA de la web (vertical slice). Diferencias con Photino, todas defensivas:
     *  - autor = SIEMPRE el nombre del usuario de la sesión (Photino lo toma de sessionStorage del
     *    navegador y la API lo acepta del body, SEC-12). Nada más del payload viaja a la API: solo el
     *    comentario validado; autor/usuario/usuarioId/creadoPor/rol/empresa del navegador se ignoran.
     *  - comentario: string, sin espacios en los extremos, no vacío, ≤ 2000 caracteres (la columna es
     *    TEXT; 2000 deja margen en bytes UTF-8), sin caracteres de control y SIN marcado HTML
     *    ("<" seguido de letra ASCII, "/", "!" o "?"): la vista de Photino pinta el comentario con innerHTML
     *    sin escapar (SEC-27). Texto normal con acentos, emojis, "a < b", "5<6" o "->" pasa intacto.
     *    Se RECHAZA (no se neutraliza con entidades) para no guardar texto alterado que Photino u otros
     *    consumidores mostrarían distinto.
     *  - la NC debe existir (GET previo): evita el 500 de la API por la FK y comentar una NC eliminada.
     */
    public BridgeResult seguimientoCrear(ObjectNode payload, SessionUser usuario) {
        Integer id = entero(payload.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_ID_NC);
        }
        JsonNode nodo = payload.get("comentario");
        if (nodo != null && !nodo.isNull() && !nodo.isString()) {
            return BridgeResult.error(MENSAJE_PARAMETRO_INVALIDO);
        }
        String comentario = nodo == null || nodo.isNull() ? "" : nodo.asString().strip();
        if (comentario.isEmpty()) {
            return BridgeResult.error(MENSAJE_FALTA_COMENTARIO);
        }
        if (comentario.codePointCount(0, comentario.length()) > MAX_COMENTARIO) {
            return BridgeResult.error(MENSAJE_COMENTARIO_LARGO);
        }
        if (CONTROL.matcher(comentario).find()) {
            return BridgeResult.error(MENSAJE_COMENTARIO_CARACTERES);
        }
        if (MARCADO_HTML.matcher(comentario).find()) {
            return BridgeResult.error(MENSAJE_COMENTARIO_HTML);
        }
        BridgeResult nc = InnpackRespuestas.reenviar(api.get(usuario, BASE + "/" + id), mapper);
        if (!nc.ok()) {
            return nc;
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("comentario", comentario);
        cuerpo.put("autor", autorDeSesion(usuario));
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/" + id + "/seguimiento", cuerpo), mapper);
    }

    /**
     * noConformidades.acciones.crear → POST api/no-conformidades/{id}/acciones
     * {analisisId, descripcion, responsable, fechaLimite, prioridad, creadoPor}. SEGUNDA ESCRITURA.
     *
     * IDENTIDAD (seguridad) — `creadoPor`: SIEMPRE el usuario de la sesión; lo que mande el navegador se
     * descarta. DATOS DE NEGOCIO — `responsable` (persona a cargo, texto libre elegido por el usuario),
     * `descripcion`, `fechaLimite`, `prioridad`, `analisisId`: se validan y se conservan tal cual.
     *
     * Lista blanca ESTRICTA de claves (las que manda Photino): cualquier otra clave (usuario, autor,
     * usuarioId, empresa, rol, data, estado...) → error sin tocar la API. Límites del contrato real
     * (tabla nc_acciones_correctivas): descripción ≤ 500, responsable ≤ 150 (una línea), fecha DATE
     * AAAA-MM-DD, prioridad ENUM ALTA/MEDIA/BAJA o vacía; obligatorios y mensajes = los de la API. Sin marcado
     * HTML en textos (la vista los pinta con innerHTML sin escapar, SEC-27). `analisisId` (opcional) debe ser el
     * análisis de ESA NC (el único que la vista puede enviar). La NC debe existir (GET previo). Photino no
     * impide agregar acciones a una NC cerrada: la web tampoco.
     */
    public BridgeResult accionesCrear(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CAMPOS_ACCION_CREAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer id = entero(payload.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_ID_NC);
        }
        JsonNode analisisNodo = payload.get("analisisId");
        Integer analisisId = null;
        if (analisisNodo != null && !analisisNodo.isNull()) {
            if (!analisisNodo.isIntegralNumber() || !analisisNodo.canConvertToInt() || analisisNodo.asInt() <= 0) {
                return BridgeResult.error(MENSAJE_PARAMETRO_INVALIDO);
            }
            analisisId = analisisNodo.asInt();
        }
        for (String campo : new String[] {"descripcion", "responsable", "fechaLimite", "prioridad"}) {
            JsonNode n = payload.get(campo);
            if (n != null && !n.isNull() && !n.isString()) {
                return BridgeResult.error(MENSAJE_PARAMETRO_INVALIDO);
            }
        }
        String descripcion = textoPlano(payload.get("descripcion"));
        String error = validarTextoUnaLinea(descripcion, "Falta la descripción de la acción", MAX_DESCRIPCION_ACCION,
                "La descripción supera el máximo de 500 caracteres.");
        if (error != null) {
            return BridgeResult.error(error);
        }
        String responsable = textoPlano(payload.get("responsable"));
        error = validarTextoUnaLinea(responsable, "Falta el responsable", MAX_RESPONSABLE, "El responsable supera el máximo de 150 caracteres.");
        if (error != null) {
            return BridgeResult.error(error);
        }
        String fechaLimite = textoPlano(payload.get("fechaLimite"));
        if (fechaLimite.isEmpty()) {
            return BridgeResult.error("Falta la fecha límite");
        }
        if (!FECHA_ISO.matcher(fechaLimite).matches()) {
            return BridgeResult.error(MENSAJE_FECHA_INVALIDA);
        }
        try {
            java.time.LocalDate.parse(fechaLimite);
        } catch (java.time.format.DateTimeParseException e) {
            return BridgeResult.error(MENSAJE_FECHA_INVALIDA);
        }
        String prioridad = textoPlano(payload.get("prioridad"));
        if (!prioridad.isEmpty() && !PRIORIDADES.contains(prioridad)) {
            return BridgeResult.error("Prioridad inválida (ALTA, MEDIA o BAJA).");
        }
        BridgeResult nc = InnpackRespuestas.reenviar(api.get(usuario, BASE + "/" + id), mapper);
        if (!nc.ok()) {
            return nc;
        }
        if (analisisId != null) {
            BridgeResult analisis = InnpackRespuestas.reenviar(api.get(usuario, BASE + "/" + id + "/analisis"), mapper);
            if (!analisis.ok()) {
                return analisis;
            }
            JsonNode a = analisis.data() instanceof JsonNode n ? n : null;
            JsonNode aId = a == null ? null : a.get("id");
            if (aId == null || !aId.canConvertToInt() || aId.asInt() != analisisId) {
                return BridgeResult.error("El análisis indicado no corresponde a la no conformidad.");
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        if (analisisId != null) {
            cuerpo.put("analisisId", analisisId);
        } else {
            cuerpo.putNull("analisisId");
        }
        cuerpo.put("descripcion", descripcion);
        cuerpo.put("responsable", responsable);
        cuerpo.put("fechaLimite", fechaLimite);
        if (prioridad.isEmpty()) {
            cuerpo.putNull("prioridad");
        } else {
            cuerpo.put("prioridad", prioridad);
        }
        cuerpo.put("creadoPor", autorDeSesion(usuario));
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/" + id + "/acciones", cuerpo), mapper);
    }

    /** Recurso auditado de acciones.crear: "nc:<id>" y, si la API devuelve el id creado, ":accion:<id>" (el log solo admite [letras números . _ @ : -]). */
    public static String recursoAccion(ObjectNode payload, Object dataRespuesta) {
        String recurso = recursoNc(payload);
        if (dataRespuesta instanceof JsonNode d && d.isObject() && d.get("id") != null && d.get("id").canConvertToLong()) {
            recurso += ":accion:" + d.get("id").asLong();
        }
        return recurso;
    }

    /** String sin espacios en los extremos ("" si falta o es null). */
    private static String textoPlano(JsonNode nodo) {
        return nodo == null || nodo.isNull() ? "" : nodo.asString().strip();
    }

    /** Obligatorio, ≤ max caracteres, una sola línea, sin controles ni marcado HTML; null si es válido. */
    private static String validarTextoUnaLinea(String valor, String faltante, int max, String largo) {
        if (valor.isEmpty()) {
            return faltante;
        }
        if (valor.codePointCount(0, valor.length()) > max) {
            return largo;
        }
        if (CONTROL_UNA_LINEA.matcher(valor).find()) {
            return MENSAJE_TEXTO_CARACTERES;
        }
        if (MARCADO_HTML.matcher(valor).find()) {
            return MENSAJE_TEXTO_HTML;
        }
        return null;
    }

    /** Nombre de campo para el mensaje de error: solo [A-Za-z0-9_], ≤ 40; si no, "?". */
    private static String nombreCampoSeguro(String clave) {
        return clave != null && clave.matches("[A-Za-z0-9_]{1,40}") ? clave : "?";
    }

    /** Recurso auditado de seguimiento.crear: "nc:<id>" (solo dígitos; cualquier otra cosa → "nc:?"). */
    public static String recursoNc(ObjectNode payload) {
        Integer id = entero(payload.get("id"));
        return "nc:" + (id != null && id > 0 ? id : "?");
    }

    /** Mismo criterio que _usuarioActual() de Photino (nombre, si no el código), pero desde la SESIÓN. */
    static String autorDeSesion(SessionUser usuario) {
        String nombre = usuario.nombreCompleto();
        return nombre != null && !nombre.isBlank() ? nombre : usuario.codigoUsuario();
    }

    /** noConformidades.catalogos.{catalogo}.list → GET api/nc-catalogos/{catalogo} (no lee el payload). */
    public BridgeAction catalogoList(String catalogo) {
        return (payload, usuario) -> InnpackRespuestas.reenviar(api.get(usuario, BASE_CATALOGOS + "/" + catalogo), mapper);
    }

    private BridgeResult porId(ObjectNode payload, SessionUser usuario, String sufijo) {
        Integer id = entero(payload.get("id"));
        if (id == null) {
            return BridgeResult.error(MENSAJE_ID_NC);
        }
        return InnpackRespuestas.reenviar(api.get(usuario, BASE + "/" + id + sufijo), mapper);
    }

    /**
     * FiltrosQuery de Photino: "&clave=valor" por cada filtro no en blanco, en ese orden, escapado como
     * Uri.EscapeDataString. false si algún filtro no es string/número.
     */
    private static boolean filtrosQuery(ObjectNode payload, StringBuilder query) {
        for (String filtro : FILTROS_TEXTO) {
            JsonNode nodo = payload.get(filtro);
            if (nodo == null || nodo.isNull()) {
                continue;
            }
            if (!(nodo.isString() || nodo.isNumber())) {
                return false;
            }
            String valor = nodo.asString();
            if (!valor.isBlank()) {
                query.append('&').append(filtro).append('=').append(UriEscape.dataString(valor));
            }
        }
        return true;
    }

    private static String textoDe(JsonNode objeto, String campo) {
        JsonNode nodo = objeto == null ? null : objeto.get(campo);
        return nodo != null && (nodo.isString() || nodo.isNumber()) ? nodo.asString() : "";
    }

    /** TryGetInt de Photino: número entero int32, o string convertible (int.TryParse); si no → null. */
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
