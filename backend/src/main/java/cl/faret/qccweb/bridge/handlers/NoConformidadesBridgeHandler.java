package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeAction;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.bridge.LecturasDeSesion;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import java.util.List;
import java.util.Map;
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
    /** Claves que manda Photino en _guardarAnalisis (+ "action"); cualquier otra se rechaza. */
    private static final Set<String> CAMPOS_ANALISIS_GUARDAR = Set.of("action", "id", "usuario", "metodologia", "problemaDetectado",
            "porque1", "porque2", "porque3", "porque4", "porque5", "causaRaiz", "conclusion");
    private static final List<String> CAMPOS_TEXTO_ANALISIS = List.of("metodologia", "problemaDetectado",
            "porque1", "porque2", "porque3", "porque4", "porque5", "causaRaiz", "conclusion");
    /** textareas de la vista (TEXT / NVARCHAR(MAX)); el resto son inputs de una línea. */
    private static final Set<String> CAMPOS_MULTILINEA = Set.of("problemaDetectado", "causaRaiz", "conclusion");
    private static final Set<String> METODOLOGIAS = Set.of("CINCO_PORQUES", "ISHIKAWA", "MIXTA");
    private static final List<String> CAMPOS_HUELLA_ANALISIS = List.of("id", "metodologia", "problemaDetectado",
            "porque1", "porque2", "porque3", "porque4", "porque5", "causaRaiz", "conclusion",
            "creadoPor", "creadoEn", "actualizadoPor", "actualizadoEn");
    static final int MAX_TEXT_BYTES = 65_535;
    static final int MAX_PORQUE = 500;
    static final String SIN_ANALISIS = "SIN_ANALISIS";
    static final String MARCA_OPERACION = "__qccAuditoriaOperacion";
    static final String MENSAJE_ANALISIS_SIN_LEER = "Abre el análisis de la no conformidad antes de guardarlo.";
    static final String MENSAJE_ANALISIS_CONFLICTO = "El análisis fue modificado por otra persona desde que lo abriste. "
            + "Cierra y vuelve a abrir el análisis para ver la versión actual antes de guardar (no se guardó nada).";
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
    /** Largo máximo de `nombre` por catálogo (NoConformidadesCatalogosService.Catalogos de la API = columnas cat_nc_*). */
    static final Map<String, Integer> MAX_NOMBRE_CATALOGO = Map.of(
            "clientes", 150, "categoriasDefecto", 150, "tiposFalla", 150, "supervisores", 150, "revisores", 150,
            "areas", 150, "familiasProducto", 50, "niveles", 20, "impactos", 50);
    /** Claves que manda Photino en _catalogoCrear (+ "action"); cualquier otra se rechaza. */
    private static final Set<String> CAMPOS_CATALOGO_CREAR = Set.of("action", "nombre", "creadoPor");
    /** Mismo colapso que la API (Regex.Replace(nombre.Trim(), @"\s+", " ")). */
    private static final Pattern ESPACIOS = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

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

    /**
     * noConformidades.get → GET api/no-conformidades/{id}. Además registra en la sesión la huella de la NC que el
     * usuario queda viendo (detección de lost update en noConformidades.update, Fase 3l).
     */
    public BridgeResult get(ObjectNode payload, SessionUser usuario) {
        BridgeResult upstream = porId(payload, usuario, "");
        Integer id = entero(payload.get("id"));
        if (upstream.ok() && id != null) {
            LecturasDeSesion.registrar(recursoLecturaNc(id), huellaNc(upstream.data()));
        }
        return upstream;
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

    /**
     * noConformidades.analisis.get → GET api/no-conformidades/{id}/analisis. Además registra en la sesión la
     * huella del análisis que el usuario queda viendo (detección de lost update en analisis.guardar). La vista
     * pone estos textos con `.value` en inputs/textareas: no se interpreta HTML, por eso aquí NO se escapan.
     */
    public BridgeResult analisisGet(ObjectNode payload, SessionUser usuario) {
        BridgeResult upstream = porId(payload, usuario, "/analisis");
        Integer id = entero(payload.get("id"));
        if (upstream.ok() && id != null) {
            LecturasDeSesion.registrar(recursoLecturaAnalisis(id), huellaAnalisis(upstream.data()));
        }
        return upstream;
    }

    /**
     * noConformidades.analisis.guardar → PUT api/no-conformidades/{id}/analisis. TERCERA ESCRITURA; a
     * diferencia de las anteriores NO es aditiva: la API hace upsert del ÚLTIMO análisis de la NC (UPDATE en
     * sitio si existe, INSERT si no) y NO guarda historial ni ofrece versión/ETag/rowversion (en el port SQL
     * Server `actualizado_en` ni se actualiza). El valor anterior no es recuperable.
     *
     * Identidad — `usuario` (la API lo guarda como creado_por / actualizado_por): SIEMPRE la sesión.
     * Negocio — lista blanca exacta de Photino, límites del esquema real (el más estricto entre MySQL
     * `calidad` y el port SQL Server): metodologia ENUM obligatoria; problemaDetectado obligatorio, TEXT
     * (≤ 65.535 bytes UTF-8), multilínea; porque1..5 opcionales, VARCHAR/NVARCHAR(500) (≤ 500 unidades
     * UTF-16), una línea; causaRaiz/conclusion opcionales, TEXT, multilínea. "" se conserva como "" (Photino
     * manda los vacíos así); null/ausente → null. Sin marcado HTML ni controles (salvo \t\n\r en multilínea).
     *
     * Lost update (diferencia web, sin cambiar el contrato): se exige que ESTA sesión haya leído el análisis
     * (analisis.get) y que siga igual al releerlo antes del PUT; si otra persona lo cambió → conflicto sin
     * escribir. Límites: ventana mínima entre relectura y PUT; pestañas de la misma sesión; Photino no protege.
     */
    public BridgeResult analisisGuardar(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CAMPOS_ANALISIS_GUARDAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer id = entero(payload.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_ID_NC);
        }
        for (String campo : CAMPOS_TEXTO_ANALISIS) {
            JsonNode n = payload.get(campo);
            if (n != null && !n.isNull() && !n.isString()) {
                return BridgeResult.error(MENSAJE_PARAMETRO_INVALIDO);
            }
        }
        String metodologia = textoPlano(payload.get("metodologia"));
        if (metodologia.isEmpty()) {
            return BridgeResult.error("Falta la metodología");
        }
        if (!METODOLOGIAS.contains(metodologia)) {
            return BridgeResult.error("Metodología inválida. Valores permitidos: CINCO_PORQUES, ISHIKAWA, MIXTA");
        }
        String problema = textoPlano(payload.get("problemaDetectado"));
        if (problema.isEmpty()) {
            return BridgeResult.error("Falta el problema detectado");
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("metodologia", metodologia);
        for (String campo : CAMPOS_TEXTO_ANALISIS) {
            if (campo.equals("metodologia")) {
                continue;
            }
            JsonNode n = payload.get(campo);
            if (n == null || n.isNull()) {
                cuerpo.putNull(campo);
                continue;
            }
            String valor = n.asString().strip();
            boolean multilinea = CAMPOS_MULTILINEA.contains(campo);
            String error = validarTextoAnalisis(campo, valor, multilinea);
            if (error != null) {
                return BridgeResult.error(error);
            }
            cuerpo.put(campo, valor);
        }
        BridgeResult nc = InnpackRespuestas.reenviar(api.get(usuario, BASE + "/" + id), mapper);
        if (!nc.ok()) {
            return nc;
        }
        String leida = LecturasDeSesion.huella(recursoLecturaAnalisis(id));
        if (leida == null) {
            return BridgeResult.error(MENSAJE_ANALISIS_SIN_LEER);
        }
        BridgeResult vigente = InnpackRespuestas.reenviar(api.get(usuario, BASE + "/" + id + "/analisis"), mapper);
        if (!vigente.ok()) {
            return vigente;
        }
        String actual = huellaAnalisis(vigente.data());
        if (!actual.equals(leida)) {
            return BridgeResult.error(MENSAJE_ANALISIS_CONFLICTO);
        }
        cuerpo.put("usuario", autorDeSesion(usuario));
        // Canal interno para la auditoría (el payload saneado no vuelve al navegador ni a la API).
        payload.put(MARCA_OPERACION, SIN_ANALISIS.equals(actual) ? "NUEVO" : "REEMPLAZO");
        BridgeResult resultado = InnpackRespuestas.reenviar(api.putJson(usuario, BASE + "/" + id + "/analisis", cuerpo), mapper);
        if (resultado.ok()) {
            // La vista relee el análisis enseguida (y vuelve a registrar la huella); sin relectura no se puede
            // volver a guardar encima de lo que se acaba de escribir.
            LecturasDeSesion.olvidar(recursoLecturaAnalisis(id));
        }
        return resultado;
    }

    /** Recurso auditado: "nc:<id>:analisis[:<analisisId>]:NUEVO|REEMPLAZO" (sin contenido del análisis). */
    public static String recursoAnalisis(ObjectNode payload, Object dataRespuesta) {
        StringBuilder r = new StringBuilder(recursoNc(payload)).append(":analisis");
        if (dataRespuesta instanceof JsonNode d && d.isObject() && d.get("id") != null && d.get("id").canConvertToLong()) {
            r.append(':').append(d.get("id").asLong());
        }
        JsonNode marca = payload.get(MARCA_OPERACION);
        if (marca != null && marca.isString()) {
            r.append(':').append(marca.asString());
        }
        return r.toString();
    }

    private static String validarTextoAnalisis(String campo, String valor, boolean multilinea) {
        if (multilinea) {
            if (valor.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
                return "El campo " + campo + " supera el máximo permitido (65.535 bytes).";
            }
            if (CONTROL.matcher(valor).find()) {
                return MENSAJE_TEXTO_CARACTERES;
            }
        } else {
            if (valor.length() > MAX_PORQUE) {
                return "El campo " + campo + " supera el máximo de 500 caracteres.";
            }
            if (CONTROL_UNA_LINEA.matcher(valor).find()) {
                return MENSAJE_TEXTO_CARACTERES;
            }
        }
        if (MARCADO_HTML.matcher(valor).find()) {
            return MENSAJE_TEXTO_HTML;
        }
        return null;
    }

    static String recursoLecturaAnalisis(int ncId) {
        return "nc-analisis:" + ncId;
    }

    /** SHA-256 de los campos del análisis vigente (o de "sin análisis"): detecta cualquier cambio de contenido. */
    static String huellaAnalisis(Object data) {
        if (!(data instanceof JsonNode d) || !d.isObject()) {
            return SIN_ANALISIS;
        }
        StringBuilder sb = new StringBuilder();
        for (String campo : CAMPOS_HUELLA_ANALISIS) {
            JsonNode v = d.get(campo);
            sb.append(campo).append('=').append(v == null || v.isNull() ? "\u0000" : v.toString()).append('\n');
        }
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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

    /** Campos del formulario "Nueva NC" de Photino (_camposMap) con el largo de su columna en no_conformidades. */
    private static final Map<String, Integer> CAMPOS_NC_TEXTO = Map.ofEntries(
            Map.entry("npNv", 100), Map.entry("cliente", 150), Map.entry("codigoProducto", 100), Map.entry("producto", 255),
            Map.entry("familiaProducto", 50), Map.entry("tipoPnc", 50), Map.entry("nivel", 20), Map.entry("categoriaDefecto", 150),
            Map.entry("tipoFalla", 150), Map.entry("impacto", 50), Map.entry("disposicion", 50), Map.entry("area", 150),
            Map.entry("maquina", 150), Map.entry("operador", 150), Map.entry("supervisor", 150), Map.entry("revisadoPor", 150));
    /** Textareas del formulario (columnas NVARCHAR(MAX)/TEXT): multilínea, tope en bytes como el análisis (3c). */
    private static final List<String> CAMPOS_NC_MULTILINEA = List.of("descripcionDefecto", "observacion", "causaRaiz",
            "accionesCorrectivas", "verificacionSeguimiento");
    private static final List<String> CAMPOS_NC_NUMERO = List.of("cantRequerida", "cantRechazada", "cantRecuperada", "pncReal",
            "cantDestruida", "cantRepuesta");
    private static final List<String> CAMPOS_NC_FECHA = List.of("fechaIngreso", "fechaSalida", "fechaFabricacion");
    /** Cabecera que Photino arma en el navegador: la web la RECALCULA (lo recibido se descarta). */
    private static final Set<String> CAMPOS_NC_CABECERA = Set.of("tipo", "origen", "titulo", "descripcion", "severidad", "proceso",
            "fechaDeteccion");
    /** Opciones exactas de los <select> de la vista (ncq-f-tipo-pnc / ncq-f-disposicion). */
    private static final Set<String> TIPOS_PNC = Set.of("", "Cuarentena", "Rechazo", "Rechazo Cliente", "Reclamo", "Interna");
    private static final Set<String> DISPOSICIONES = Set.of("", "No aplica", "Reposición", "Destrucción", "Reposición y destrucción");
    /** DECIMAL(12,2). */
    private static final java.math.BigDecimal MAX_CANTIDAD = new java.math.BigDecimal("9999999999.99");
    public static final String MENSAJE_NC_OBLIGATORIOS = "NP/NV, Cliente, Código, Producto, Categoría defecto, Nivel, Descripción defecto, "
            + "Cant. requerida y Cant. rechazada son obligatorios";
    private static final java.time.ZoneId ZONA_PLANTA = java.time.ZoneId.of("America/Santiago");

    /**
     * noConformidades.create → POST api/no-conformidades. Formulario "Nueva NC" de Photino (_guardarForm):
     * {action, creadoPor, 34 campos de _camposMap, cabecera tipo/origen/titulo/descripcion/severidad/proceso/fechaDeteccion}.
     *
     * IDENTIDAD — `creadoPor`: SIEMPRE la sesión. ALCANCE — la API acepta `empresa`/`ambito` (y otras columnas que
     * Photino INNPACK no manda: reportadoPor, norma, areasSecundarias, tiempoPerdidoHoras): lista blanca ESTRICTA de
     * las claves de Photino, cualquier otra → error sin tocar la API. Sin `empresa` la NC queda NULL/PRODUCTO, igual
     * que Photino (el listado INNPACK incluye empresa NULL). CABECERA — se recalcula aquí con la misma lógica de
     * Photino (severidad desde el nivel, título, descripción, proceso, fechaDeteccion = fechaIngreso); lo que mande el
     * navegador se descarta. Validación (la API no valida largos: el exceso sería un 500 de SQL): obligatorios de
     * Photino, largo de cada columna, sin controles ni HTML, selects con sus opciones exactas, fechas AAAA-MM-DD,
     * cantidades ≥ 0 dentro de DECIMAL(12,2). Los adjuntos elegidos se suben después con adjuntos.subir (otra acción).
     */
    public BridgeResult ncCrear(ObjectNode payload, SessionUser usuario) {
        CuerpoNc c = cuerpoNc(payload, "creadoPor", false, usuario);
        return c.error() != null ? c.error() : InnpackRespuestas.reenviar(api.postJson(usuario, BASE, c.cuerpo()), mapper);
    }

    static final String MENSAJE_NC_SIN_LEER = "Abre la no conformidad antes de editarla.";
    static final String MENSAJE_NC_CONFLICTO = "La no conformidad fue modificada por otra persona desde que la abriste. "
            + "Ciérrala y vuelve a abrirla para ver los cambios antes de editar.";
    static final String MENSAJE_NC_CERRADA = "La no conformidad está cerrada; no se puede editar.";

    /**
     * noConformidades.update → PUT api/no-conformidades/{id}. Mismo formulario y payload que create (Photino
     * `_guardarForm` con `_editingId`: {action, id, actualizadoPor, ...campos, ...cabecera}); misma lista blanca,
     * validaciones y cabecera recalculada (cuerpoNc). `actualizadoPor` ← sesión.
     *
     * La API actualiza sin verificar existencia, borrado ni cierre (UPDATE ... WHERE id) y sobrescribe sin historial.
     * La web (Fase 3l): exige haber abierto la NC en esta sesión (huella registrada por noConformidades.get), la
     * relee antes del PUT (404/eliminada → error sin escribir), NO permite editar una NC CERRADA (decisión 3l-a, más
     * estricta que Photino) y rechaza si la NC cambió desde que se abrió (lost update).
     */
    public BridgeResult ncActualizar(ObjectNode payload, SessionUser usuario) {
        Integer id = entero(payload.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_ID_NC);
        }
        CuerpoNc c = cuerpoNc(payload, "actualizadoPor", true, usuario);
        if (c.error() != null) {
            return c.error();
        }
        String leida = LecturasDeSesion.huella(recursoLecturaNc(id));
        if (leida == null) {
            return BridgeResult.error(MENSAJE_NC_SIN_LEER);
        }
        BridgeResult vigente = InnpackRespuestas.reenviar(api.get(usuario, BASE + "/" + id), mapper);
        if (!vigente.ok()) {
            return vigente;
        }
        JsonNode nc = vigente.data() instanceof JsonNode n ? n : null;
        if ("CERRADA".equalsIgnoreCase(textoDe(nc, "estadoGestion")) || "CERRADA".equalsIgnoreCase(textoDe(nc, "estado"))) {
            return BridgeResult.error(MENSAJE_NC_CERRADA);
        }
        if (!huellaNc(vigente.data()).equals(leida)) {
            return BridgeResult.error(MENSAJE_NC_CONFLICTO);
        }
        BridgeResult resultado = InnpackRespuestas.reenviar(api.putJson(usuario, BASE + "/" + id, c.cuerpo()), mapper);
        if (resultado.ok()) {
            // Photino cierra el formulario y, para volver a editar, relee la NC (y vuelve a registrar la huella).
            LecturasDeSesion.olvidar(recursoLecturaNc(id));
        }
        return resultado;
    }

    private record CuerpoNc(ObjectNode cuerpo, BridgeResult error) {
        static CuerpoNc error(String mensaje) {
            return new CuerpoNc(null, BridgeResult.error(mensaje));
        }
    }

    static String recursoLecturaNc(int ncId) {
        return "nc-detalle:" + ncId;
    }

    /** SHA-256 del detalle vigente de la NC (claves ordenadas): cualquier cambio de contenido o estado la altera. */
    static String huellaNc(Object data) {
        if (!(data instanceof JsonNode d) || !d.isObject()) {
            return "SIN_NC";
        }
        StringBuilder sb = new StringBuilder();
        for (String campo : d.propertyNames().stream().sorted().toList()) {
            sb.append(campo).append('=').append(d.get(campo).toString()).append('\n');
        }
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Validación y armado común de create/update (lista blanca de Photino, cabecera recalculada, largos del esquema). */
    private CuerpoNc cuerpoNc(ObjectNode payload, String claveAutor, boolean conId, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!clave.equals("action") && !clave.equals(claveAutor) && !(conId && clave.equals("id")) && !CAMPOS_NC_TEXTO.containsKey(clave)
                    && !CAMPOS_NC_MULTILINEA.contains(clave) && !CAMPOS_NC_NUMERO.contains(clave)
                    && !CAMPOS_NC_FECHA.contains(clave) && !CAMPOS_NC_CABECERA.contains(clave)) {
                return CuerpoNc.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        Map<String, String> textos = new java.util.HashMap<>();
        for (String campo : CAMPOS_NC_TEXTO.keySet().stream().sorted().toList()) {
            JsonNode n = payload.get(campo);
            if (n != null && !n.isNull() && !n.isString()) {
                return CuerpoNc.error(MENSAJE_PARAMETRO_INVALIDO);
            }
            String valor = textoPlano(n);
            int max = CAMPOS_NC_TEXTO.get(campo);
            if (valor.length() > max) {
                return CuerpoNc.error("El campo " + campo + " supera el máximo de " + max + " caracteres.");
            }
            if (CONTROL_UNA_LINEA.matcher(valor).find() || tieneSustitutoSuelto(valor)) {
                return CuerpoNc.error(MENSAJE_TEXTO_CARACTERES);
            }
            if (MARCADO_HTML.matcher(valor).find()) {
                return CuerpoNc.error(MENSAJE_TEXTO_HTML);
            }
            textos.put(campo, valor);
        }
        for (String campo : CAMPOS_NC_MULTILINEA) {
            JsonNode n = payload.get(campo);
            if (n != null && !n.isNull() && !n.isString()) {
                return CuerpoNc.error(MENSAJE_PARAMETRO_INVALIDO);
            }
            String valor = textoPlano(n);
            String error = validarTextoAnalisis(campo, valor, true);
            if (error == null && tieneSustitutoSuelto(valor)) {
                error = MENSAJE_TEXTO_CARACTERES;
            }
            if (error != null) {
                return CuerpoNc.error(error);
            }
            textos.put(campo, valor);
        }
        if (!TIPOS_PNC.contains(textos.get("tipoPnc"))) {
            return CuerpoNc.error("Tipo PNC inválido.");
        }
        if (!DISPOSICIONES.contains(textos.get("disposicion"))) {
            return CuerpoNc.error("Disposición inválida.");
        }
        Map<String, JsonNode> numeros = new java.util.HashMap<>();
        for (String campo : CAMPOS_NC_NUMERO) {
            JsonNode n = payload.get(campo);
            if (n == null || n.isNull()) {
                continue;
            }
            if (!n.isNumber()) {
                return CuerpoNc.error(MENSAJE_PARAMETRO_INVALIDO);
            }
            java.math.BigDecimal v = n.decimalValue();
            if (v.signum() < 0 || v.compareTo(MAX_CANTIDAD) > 0) {
                return CuerpoNc.error("La cantidad " + campo + " no es válida (0 a 9.999.999.999,99).");
            }
            numeros.put(campo, n); // se reenvía el número tal como lo manda Photino
        }
        Map<String, String> fechas = new java.util.HashMap<>();
        for (String campo : CAMPOS_NC_FECHA) {
            JsonNode n = payload.get(campo);
            if (n != null && !n.isNull() && !n.isString()) {
                return CuerpoNc.error(MENSAJE_PARAMETRO_INVALIDO);
            }
            String valor = textoPlano(n);
            if (valor.isEmpty()) {
                continue;
            }
            if (!FECHA_ISO.matcher(valor).matches()) {
                return CuerpoNc.error("La fecha " + campo + " no es válida (formato AAAA-MM-DD).");
            }
            try {
                java.time.LocalDate.parse(valor);
            } catch (java.time.format.DateTimeParseException e) {
                return CuerpoNc.error("La fecha " + campo + " no es válida (formato AAAA-MM-DD).");
            }
            fechas.put(campo, valor);
        }
        for (String obligatorio : List.of("npNv", "cliente", "codigoProducto", "producto", "categoriaDefecto", "nivel",
                "descripcionDefecto")) {
            if (textos.get(obligatorio).isEmpty()) {
                return CuerpoNc.error(MENSAJE_NC_OBLIGATORIOS);
            }
        }
        if (!numeros.containsKey("cantRequerida") || !numeros.containsKey("cantRechazada")) {
            return CuerpoNc.error(MENSAJE_NC_OBLIGATORIOS);
        }
        // Cabecera: misma lógica que _guardarForm de Photino, calculada con los valores ya validados.
        String fechaIngreso = fechas.getOrDefault("fechaIngreso", java.time.LocalDate.now(ZONA_PLANTA).toString());
        String producto = textos.get("producto");
        String titulo = ("PNC " + textos.get("npNv") + " - " + (producto.isEmpty() ? textos.get("cliente") : producto)).strip();
        if (titulo.length() > 255) {
            return CuerpoNc.error("El título de la no conformidad (PNC + NP/NV + producto) supera el máximo de 255 caracteres.");
        }
        String descripcion = java.util.stream.Stream.of(textos.get("categoriaDefecto"), textos.get("descripcionDefecto"))
                .filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.joining(" - "));
        String proceso = !textos.get("tipoPnc").isEmpty() ? textos.get("tipoPnc")
                : !textos.get("area").isEmpty() ? textos.get("area") : "PNC Nueva";
        // Mismo orden de claves que Photino ({creadoPor|actualizadoPor, ...campos, fechaIngreso, ...cabecera}).
        cuerpo.put(claveAutor, autorDeSesion(usuario));
        for (String campo : List.of("fechaIngreso", "npNv", "cliente", "codigoProducto", "producto", "familiaProducto", "tipoPnc",
                "nivel", "categoriaDefecto", "tipoFalla", "impacto", "cantRequerida", "cantRechazada", "cantRecuperada", "pncReal",
                "disposicion", "cantDestruida", "cantRepuesta", "area", "maquina", "operador", "supervisor", "revisadoPor",
                "fechaSalida", "fechaFabricacion", "descripcionDefecto", "observacion", "causaRaiz", "accionesCorrectivas",
                "verificacionSeguimiento")) {
            if (campo.equals("fechaIngreso")) {
                cuerpo.put(campo, fechaIngreso);
            } else if (CAMPOS_NC_NUMERO.contains(campo)) {
                if (numeros.containsKey(campo)) {
                    cuerpo.set(campo, numeros.get(campo));
                } else {
                    cuerpo.putNull(campo);
                }
            } else if (CAMPOS_NC_FECHA.contains(campo)) {
                if (fechas.containsKey(campo)) {
                    cuerpo.put(campo, fechas.get(campo));
                } else {
                    cuerpo.putNull(campo);
                }
            } else {
                cuerpo.put(campo, textos.get(campo));
            }
        }
        cuerpo.put("tipo", "INTERNA");
        cuerpo.put("origen", "AUDITORIA_INTERNA");
        cuerpo.put("titulo", titulo);
        cuerpo.put("descripcion", descripcion);
        cuerpo.put("severidad", severidadDeNivel(textos.get("nivel")));
        cuerpo.put("proceso", proceso);
        cuerpo.put("fechaDeteccion", fechaIngreso);
        return new CuerpoNc(cuerpo, null);
    }

    private static final Set<String> CAMPOS_ADJUNTO_SUBIR = Set.of("action", "id", "tipo", "nombreArchivo", "tipoMime",
            "contenidoBase64", "subidoPor");
    static final int MAX_FOTO_BYTES = 5 * 1024 * 1024;

    /**
     * noConformidades.adjuntos.subir → POST api/no-conformidades/{id}/adjuntos {tipo, nombreArchivo, tipoMime,
     * contenidoBase64, subidoPor}. Photino sube desde el alta de NC y desde el modal de análisis (Adjuntar/Reemplazar
     * PDF, Fotos). Llega SOLO por /api/v1/bridge/archivo (tope de cuerpo propio; ver BridgeController).
     *
     * IDENTIDAD — `subidoPor`: SIEMPRE la sesión. Lista blanca de claves. La API valida MIME DECLARADO, tamaño, NC
     * cerrada y máx. 10 fotos; la web además: tipo CAUSA_RAIZ_PDF (application/pdf) o EVIDENCIA_FOTO (image/jpeg,
     * image/png), base64 estricto, tamaño DECODIFICADO (PDF 10 MB, foto 5 MB), FIRMA REAL coherente con el MIME
     * (%PDF-, PNG, JPEG). NOMBRE: Photino lo pinta con innerHTML y Photino escritorio lee directo de la API, así que se
     * SANEA al subir (decisión 3k-a): nombre base, sin controles/bidi/reservados, `< > " ' ` & : | ? *` → "_", ≤ 150.
     * Reemplazar el PDF lo hace la API (marca eliminado el anterior).
     */
    public BridgeResult adjuntosSubir(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CAMPOS_ADJUNTO_SUBIR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer id = entero(payload.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_ID_NC);
        }
        for (String campo : new String[] {"tipo", "nombreArchivo", "tipoMime", "contenidoBase64"}) {
            JsonNode n = payload.get(campo);
            if (n != null && !n.isNull() && !n.isString()) {
                return BridgeResult.error(MENSAJE_PARAMETRO_INVALIDO);
            }
        }
        String tipo = textoPlano(payload.get("tipo"));
        boolean esPdf = tipo.equals("CAUSA_RAIZ_PDF");
        if (!esPdf && !tipo.equals("EVIDENCIA_FOTO")) {
            return BridgeResult.error("Tipo de adjunto inválido (CAUSA_RAIZ_PDF o EVIDENCIA_FOTO).");
        }
        String tipoMime = textoPlano(payload.get("tipoMime"));
        if (esPdf ? !tipoMime.equals("application/pdf") : !(tipoMime.equals("image/jpeg") || tipoMime.equals("image/png"))) {
            return BridgeResult.error(esPdf ? "Solo se permite un archivo PDF" : "Solo se permiten fotografías JPG o PNG");
        }
        String nombreCrudo = textoPlano(payload.get("nombreArchivo"));
        if (nombreCrudo.isEmpty()) {
            return BridgeResult.error("Falta el nombre del archivo");
        }
        String base64 = payload.get("contenidoBase64") == null || payload.get("contenidoBase64").isNull()
                ? "" : payload.get("contenidoBase64").asString();
        if (base64.isEmpty()) {
            return BridgeResult.error("Falta el contenido del archivo");
        }
        int maxBytes = esPdf ? MAX_ADJUNTO_BYTES : MAX_FOTO_BYTES;
        if (base64.length() > ((maxBytes + 2) / 3) * 4) {
            return BridgeResult.error(esPdf ? "El PDF excede el tamaño máximo de 10 MB" : "La fotografía excede el tamaño máximo de 5 MB");
        }
        byte[] contenido;
        try {
            contenido = java.util.Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return BridgeResult.error(MENSAJE_ADJUNTO_INVALIDO);
        }
        if (contenido.length == 0) {
            return BridgeResult.error("El archivo está vacío");
        }
        if (contenido.length > maxBytes) {
            return BridgeResult.error(esPdf ? "El PDF excede el tamaño máximo de 10 MB" : "La fotografía excede el tamaño máximo de 5 MB");
        }
        if (!ControlDocumentalBridgeHandler.firmaCoincide(tipoMime, contenido)) {
            return BridgeResult.error("El contenido del archivo no corresponde a un " + (esPdf ? "PDF" : "JPG/PNG") + " válido.");
        }
        String nombre = nombreAdjuntoSeguro(nombreCrudo, esPdf ? "adjunto.pdf" : ("image/png".equals(tipoMime) ? "foto.png" : "foto.jpg"));
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("tipo", tipo);
        cuerpo.put("nombreArchivo", nombre);
        cuerpo.put("tipoMime", tipoMime);
        cuerpo.put("contenidoBase64", base64);
        cuerpo.put("subidoPor", autorDeSesion(usuario));
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/" + id + "/adjuntos", cuerpo), mapper);
    }

    /** nombreArchivoSeguro de Control Documental + comillas simples, backtick, & y sustitutos sueltos → "_" (innerHTML/atributos). */
    static String nombreAdjuntoSeguro(String nombre, String porDefecto) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nombre.length(); i++) {
            char c = nombre.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < nombre.length() && Character.isLowSurrogate(nombre.charAt(i + 1))) {
                sb.append(c).append(nombre.charAt(++i));
            } else {
                sb.append(Character.isSurrogate(c) ? '_' : c);
            }
        }
        String n = ControlDocumentalBridgeHandler.nombreArchivoSeguro(sb.toString().replaceAll("['`&]", "_"), porDefecto);
        // El corte a 150 puede partir un par sustituto al final.
        return n.isEmpty() || !Character.isHighSurrogate(n.charAt(n.length() - 1)) ? n : n.substring(0, n.length() - 1);
    }

    /** Recurso auditado de adjuntos.subir: "nc:<id>" y ":adjunto:<id>" si la API devuelve el id creado. */
    public static String recursoAdjunto(ObjectNode payload, Object dataRespuesta) {
        String recurso = recursoNc(payload);
        if (dataRespuesta instanceof JsonNode d && d.isObject() && d.get("id") != null && d.get("id").canConvertToLong()) {
            recurso += ":adjunto:" + d.get("id").asLong();
        }
        return recurso;
    }

    /** _mapNivelASeveridad de Photino: CRIT → ALTA, MAYOR → MEDIA, MENOR → BAJA, otro → MEDIA. */
    public static String severidadDeNivel(String nivel) {
        String n = nivel == null ? "" : nivel.toUpperCase(java.util.Locale.ROOT);
        if (n.contains("CRIT")) {
            return "ALTA";
        }
        if (n.contains("MAYOR")) {
            return "MEDIA";
        }
        if (n.contains("MENOR")) {
            return "BAJA";
        }
        return "MEDIA";
    }

    /** Recurso auditado de create: "nc:<id>" con el id que devuelve la API ("nc:nueva" si no lo trae, p. ej. error). */
    public static String recursoNcCreada(ObjectNode payload, Object dataRespuesta) {
        if (dataRespuesta instanceof JsonNode d && d.isObject() && d.get("id") != null && d.get("id").canConvertToLong()) {
            return "nc:" + d.get("id").asLong();
        }
        return "nc:nueva";
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

    /**
     * noConformidades.catalogos.{catalogo}.crear → POST api/nc-catalogos/{catalogo} con {nombre, creadoPor}.
     * Photino (_catalogoCrear, desde el combo inline) manda {action, nombre, creadoPor}.
     *
     * IDENTIDAD — `creadoPor`: SIEMPRE el usuario de la sesión; lo que mande el navegador se descarta. Cualquier
     * otra clave (usuario, autor, usuarioId, empresa, id, activo...) → error sin tocar la API.
     * `nombre` con el contrato real (NoConformidadesCatalogosService.CrearAsync + UNIQUE(nombre) de cat_nc_*):
     * obligatorio ("Falta el nombre"), trim + espacios internos colapsados a uno (igual que la API, que lo
     * repite), ≤ largo de la columna en unidades UTF-16 (como `string.Length` de la API; clientes 150), una
     * línea sin caracteres de control ni sustitutos UTF-16 sueltos, sin marcado HTML (el valor termina en
     * no_conformidades y la vista lo pinta con innerHTML, SEC-27). Duplicados: los resuelve la API (devuelve
     * el valor existente —y lo reactiva si estaba inactivo— con su id); el gateway no inventa otra política.
     */
    public BridgeAction catalogoCrear(String catalogo) {
        int max = MAX_NOMBRE_CATALOGO.get(catalogo);
        return (payload, usuario) -> {
            for (String clave : payload.propertyNames()) {
                if (!CAMPOS_CATALOGO_CREAR.contains(clave)) {
                    return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
                }
            }
            JsonNode n = payload.get("nombre");
            if (n != null && !n.isNull() && !n.isString()) {
                return BridgeResult.error(MENSAJE_PARAMETRO_INVALIDO);
            }
            String crudo = n == null || n.isNull() ? "" : n.asString();
            if (CONTROL_UNA_LINEA.matcher(crudo).find() || tieneSustitutoSuelto(crudo)) {
                return BridgeResult.error(MENSAJE_TEXTO_CARACTERES);
            }
            String nombre = ESPACIOS.matcher(crudo).replaceAll(" ").strip();
            if (nombre.isEmpty()) {
                return BridgeResult.error("Falta el nombre");
            }
            if (nombre.length() > max) {
                return BridgeResult.error("El valor no puede superar los " + max + " caracteres.");
            }
            if (MARCADO_HTML.matcher(nombre).find()) {
                return BridgeResult.error(MENSAJE_TEXTO_HTML);
            }
            ObjectNode cuerpo = mapper.createObjectNode();
            cuerpo.put("nombre", nombre);
            cuerpo.put("creadoPor", autorDeSesion(usuario));
            return InnpackRespuestas.reenviar(api.postJson(usuario, BASE_CATALOGOS + "/" + catalogo, cuerpo), mapper);
        };
    }

    /** Recurso auditado de catalogos.{catalogo}.crear: "catalogo:<catalogo>" y ":<id>" si la API devuelve el id. */
    public static String recursoCatalogo(String catalogo, Object dataRespuesta) {
        String recurso = "catalogo:" + catalogo;
        if (dataRespuesta instanceof JsonNode d && d.isObject() && d.get("id") != null && d.get("id").canConvertToLong()) {
            recurso += ":" + d.get("id").asLong();
        }
        return recurso;
    }

    private static boolean tieneSustitutoSuelto(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return true;
            }
        }
        return false;
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
