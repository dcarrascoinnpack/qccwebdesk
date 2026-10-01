package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.bridge.LecturasDeSesion;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Control Documental" (compartido INNPACK/FARET). Port de Photino
 * src/Backend/Modules/ControlDocumental/ControlDocumentalHandler.cs +
 * InnpackControlDocumentalApiService. El payload de este módulo viaja PLANO (sin "data").
 *
 * "alcanceEmpresa" es un filtro de negocio (select "Todos / INNPACK / FARET / AMBAS" de la vista;
 * el dato es compartido entre ambas empresas), no identidad: se reenvía solo con esos valores.
 *
 * adjunto.abrir: Photino previsualiza imágenes/PDF (base64 al frontend) y para el resto escribe un
 * archivo temporal y lo abre con Process.Start. Aquí el gateway valida (contenido, tamaño, firma
 * real vs. MIME declarado, nombre) y devuelve el base64; el navegador previsualiza o descarga con
 * un Blob (web-bridge.js). Ningún archivo temporal ni ruta local.
 *
 * Diferencia DEFENSIVA (misma regla que Registros de Control): bool/objeto/array en filtros de texto
 * → "Parámetro de filtro inválido." (Photino reenviaría ToString()).
 *
 * Fase 4d — escrituras (create, update, version.crear, eliminar, adjunto.subir): la API NO tiene
 * columna de versión (a diferencia de Talleres Externos); `update` es un UPDATE directo sin
 * verificar existencia, igual que noConformidades.update — mismo patrón: huella de sesión (de
 * `get`) + relectura + comparación de huella antes del PUT. `eliminar`/`version.crear` solo relee
 * para convertir el "no existe" silencioso de la API en un error real, sin exigir huella (igual
 * criterio que noConformidades.eliminar: Photino tampoco protege esto).
 */
public class ControlDocumentalBridgeHandler {

    static final String MENSAJE_ID_DOCUMENTO = "Falta el id del documento";
    static final String MENSAJE_ID_VERSION = "Falta el id de la versión";
    static final String MENSAJE_FILTRO_INVALIDO = "Parámetro de filtro inválido.";
    static final String MENSAJE_ALCANCE_INVALIDO = "Filtro de alcance inválido.";
    static final String MENSAJE_SIN_CONTENIDO = "El adjunto no trae contenido";
    static final String MENSAJE_TAMANO = "El adjunto excede el tamaño máximo permitido.";
    static final String MENSAJE_ADJUNTO_INVALIDO = "El adjunto no es válido.";
    static final int MAX_ADJUNTO_BYTES = 25 * 1024 * 1024;
    static final int MAX_BASE64_CHARS = ((MAX_ADJUNTO_BYTES + 2) / 3) * 4;

    /** Opciones exactas del <select id="cd-filtro-alcance"> de la vista de Photino ("" = Todos). */
    private static final Set<String> ALCANCES = Set.of("INNPACK", "FARET", "AMBAS");
    private static final String[] FILTROS_TEXTO = {"texto", "tipoDocumento", "area", "estado", "alcanceEmpresa"};
    private static final String BASE = "/api/control-documental";

    /** Tipos que Photino previsualiza (image/* o application/pdf) y cuya firma se puede comprobar. */
    private static final Map<String, byte[][]> FIRMAS_PREVISUALIZABLES = Map.of(
            "application/pdf", new byte[][] {{0x25, 0x50, 0x44, 0x46, 0x2d}},
            "image/png", new byte[][] {{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}},
            "image/jpeg", new byte[][] {{(byte) 0xff, (byte) 0xd8, (byte) 0xff}},
            "image/jpg", new byte[][] {{(byte) 0xff, (byte) 0xd8, (byte) 0xff}},
            "image/gif", new byte[][] {{0x47, 0x49, 0x46, 0x38}},
            "image/bmp", new byte[][] {{0x42, 0x4d}},
            "image/webp", new byte[][] {{0x52, 0x49, 0x46, 0x46}});

    /** MIME que se conservan al descargar; cualquier otro baja como application/octet-stream. */
    private static final Set<String> MIME_DESCARGA = Set.of(
            "application/pdf", "image/png", "image/jpeg", "image/jpg", "image/gif", "image/bmp", "image/webp",
            "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain", "text/csv");

    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public ControlDocumentalBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /**
     * controlDocumental.list → GET api/control-documental?page=..&pageSize=..[&texto&tipoDocumento
     * &area&estado&alcanceEmpresa] — page/pageSize: entero o string numérica y > 0, si no 1/50;
     * filtros solo si no están en blanco, en ese orden, escapados como Uri.EscapeDataString.
     */
    public BridgeResult list(ObjectNode payload, SessionUser usuario) {
        Integer page = entero(payload.get("page"));
        Integer pageSize = entero(payload.get("pageSize"));
        StringBuilder path = new StringBuilder(BASE)
                .append("?page=").append(page != null && page > 0 ? page : 1)
                .append("&pageSize=").append(pageSize != null && pageSize > 0 ? pageSize : 50);
        for (String filtro : FILTROS_TEXTO) {
            JsonNode nodo = payload.get(filtro);
            if (nodo == null || nodo.isNull()) {
                continue;
            }
            if (!(nodo.isString() || nodo.isNumber())) {
                return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
            }
            String valor = nodo.asString();
            if (valor.isBlank()) {
                continue;
            }
            if (filtro.equals("alcanceEmpresa") && !ALCANCES.contains(valor)) {
                return BridgeResult.error(MENSAJE_ALCANCE_INVALIDO);
            }
            path.append('&').append(filtro).append('=').append(UriEscape.dataString(valor));
        }
        return InnpackRespuestas.reenviar(api.get(usuario, path.toString()), mapper);
    }

    /**
     * controlDocumental.get → GET api/control-documental/{id}. Registra la huella del documento en la
     * sesión (Fase 4d: exige esto antes de editarlo, detecta si cambió desde que se abrió).
     */
    public BridgeResult get(ObjectNode payload, SessionUser usuario) {
        Integer id = entero(payload.get("id"));
        if (id == null) {
            return BridgeResult.error(MENSAJE_ID_DOCUMENTO);
        }
        BridgeResult resultado = InnpackRespuestas.reenviar(api.get(usuario, BASE + "/" + id), mapper);
        if (resultado.ok()) {
            LecturasDeSesion.registrar(recursoLecturaDocumento(id), huellaDocumento(resultado.data()));
        }
        return resultado;
    }

    static String recursoLecturaDocumento(int id) {
        return "controlDocumental-detalle:" + id;
    }

    /** SHA-256 del detalle vigente del documento (claves ordenadas): cualquier cambio lo altera. */
    static String huellaDocumento(Object data) {
        JsonNode d = data instanceof JsonNode n && n.isObject() ? n : null;
        if (d == null) {
            return "SIN_DOCUMENTO";
        }
        StringBuilder sb = new StringBuilder();
        for (String campo : d.propertyNames().stream().sorted().toList()) {
            sb.append(campo).append('=').append(d.get(campo).toString()).append('\n');
        }
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** controlDocumental.adjunto.abrir → GET api/control-documental/adjunto/{documentoVersionId} */
    public BridgeResult adjuntoAbrir(ObjectNode payload, SessionUser usuario) {
        Integer versionId = entero(payload.get("documentoVersionId"));
        if (versionId == null) {
            return BridgeResult.error(MENSAJE_ID_VERSION);
        }
        BridgeResult upstream = InnpackRespuestas.reenviar(api.get(usuario, BASE + "/adjunto/" + versionId), mapper);
        return adjuntoParaNavegador(upstream, "adjunto_" + versionId, MAX_BASE64_CHARS, mapper);
    }

    // ------------------------------------------------------------------ Fase 4d: escrituras

    static final String MENSAJE_CAMPO_NO_PERMITIDO = "Campo no permitido: ";
    static final String MENSAJE_PARAMETRO_INVALIDO = "Parámetro inválido.";
    static final String MENSAJE_TEXTO_CARACTERES = "El texto contiene caracteres no permitidos.";
    static final String MENSAJE_TEXTO_HTML = "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").";
    static final String MENSAJE_OPCION_INVALIDA = "Valor no permitido en ";
    static final String MENSAJE_FECHA_INVALIDA = "La fecha no es válida (formato AAAA-MM-DD).";
    static final String MENSAJE_DOCUMENTO_SIN_LEER = "Abre el documento antes de editarlo.";
    static final String MENSAJE_DOCUMENTO_CONFLICTO = "El documento fue modificado por otra persona desde que lo abriste. "
            + "Vuelve a abrirlo para ver los cambios.";
    static final String MENSAJE_TIPO_ARCHIVO = "Tipo de archivo no permitido. Formatos válidos: .pdf, .doc, .docx, .jpg, .jpeg, .png, .webp";
    static final String MENSAJE_FALTA_NOMBRE_ARCHIVO = "Falta el nombre del archivo";
    static final String MENSAJE_FALTA_CONTENIDO_ARCHIVO = "Falta el contenido del archivo";
    static final String MENSAJE_ARCHIVO_INVALIDO = "El contenido del archivo no es válido";
    static final String MENSAJE_ARCHIVO_TAMANO = "El archivo supera el tamaño máximo permitido (10 MB)";
    static final String MENSAJE_ARCHIVO_FIRMA = "El archivo no corresponde al tipo declarado por su extensión.";
    /** 10 MB: límite real de la API (ControlDocumentalService.MaxTamanoBytesAdjunto), no los 25 MB de adjunto.abrir. */
    static final int MAX_SUBIDA_BYTES = 10 * 1024 * 1024;
    static final int MAX_SUBIDA_BASE64 = ((MAX_SUBIDA_BYTES + 2) / 3) * 4;

    private static final Pattern CONTROL_UNA_LINEA = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final Pattern CONTROL_MULTILINEA = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern MARCADO_HTML = Pattern.compile("<[A-Za-z/!?]");
    private static final Pattern FECHA_ISO = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Set<String> ESTADOS = Set.of("VIGENTE", "EN_REVISION", "OBSOLETO");
    /** Mismas 9 claves de LeerCamposDocumento/ControlDocumentalService.cs; el formulario de Photino siempre las manda todas. */
    private static final Set<String> CAMPOS_DOCUMENTO = Set.of("codigoBase", "nombre", "tipoDocumento", "area", "alcanceEmpresa",
            "estado", "responsable", "ubicacion", "observaciones");
    private static final Set<String> CLAVES_CREAR = concat(CAMPOS_DOCUMENTO,
            "action", "creadoPor", "version", "fechaActualizacion", "proximaRevision", "adjuntoNombreArchivo", "adjuntoContenidoBase64");
    private static final Set<String> CLAVES_ACTUALIZAR = concat(CAMPOS_DOCUMENTO, "action", "id", "actualizadoPor");
    private static final Set<String> CLAVES_VERSION_CREAR = Set.of("action", "documentoId", "version", "fechaActualizacion",
            "proximaRevision", "creadoPor", "adjuntoNombreArchivo", "adjuntoContenidoBase64");
    private static final Set<String> CLAVES_ELIMINAR = Set.of("action", "id", "actualizadoPor");
    private static final Set<String> CLAVES_ADJUNTO_SUBIR = Set.of("action", "documentoVersionId", "nombreArchivo",
            "contenidoBase64", "subidoPor");

    /** Mismo diccionario que ControlDocumentalService.MimePorExtension (orden = el de la vista/API). */
    private static final Map<String, String> MIME_POR_EXTENSION_SUBIDA = Map.ofEntries(
            Map.entry(".pdf", "application/pdf"), Map.entry(".doc", "application/msword"),
            Map.entry(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry(".jpg", "image/jpeg"), Map.entry(".jpeg", "image/jpeg"), Map.entry(".png", "image/png"),
            Map.entry(".webp", "image/webp"));
    private static final byte[] FIRMA_OLE2 = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};
    private static final byte[] FIRMA_ZIP = {0x50, 0x4b, 0x03, 0x04};

    private static Set<String> concat(Set<String> base, String... extra) {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>(base);
        s.addAll(List.of(extra));
        return java.util.Collections.unmodifiableSet(s);
    }

    /**
     * controlDocumental.create → POST api/control-documental {codigoBase, nombre, ..., version,
     * fechaActualizacion, proximaRevision, adjuntoNombreArchivo, adjuntoContenidoBase64, creadoPor}
     * (Fase 4d), igual que Photino: un solo modal crea el documento y su primera versión (adjunto
     * inicial opcional) en una transacción de la API. Campos obligatorios (NV/tipo/nombre/versión/
     * fecha) y enums los valida la API con mensajes propios; el gateway valida seguridad (lista
     * blanca, caracteres, firma real del adjunto) y deja pasar el resto tal cual.
     *
     * Seguridad transparente: creadoPor ← sesión; textos sin controles ni marcado HTML (sin largo
     * propio: no se pudo confirmar el de la columna real — residual documentado); fechas AAAA-MM-DD
     * si vienen, null si no; estado/alcanceEmpresa restringidos a los `<select>` de Photino; adjunto
     * opcional con firma real de archivo (no solo extensión, a diferencia de Photino/la API).
     */
    public BridgeResult crear(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_CREAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = armarCamposDocumento(cuerpo, payload);
        error = error != null ? error : ponerTextoUnaLinea(cuerpo, payload, "version");
        error = error != null ? error : ponerFecha(cuerpo, payload, "fechaActualizacion");
        error = error != null ? error : ponerFechaOpcional(cuerpo, payload, "proximaRevision");
        error = error != null ? error : ponerAdjuntoOpcional(cuerpo, payload, "adjuntoNombreArchivo", "adjuntoContenidoBase64");
        if (error != null) {
            return BridgeResult.error(error);
        }
        cuerpo.put("creadoPor", autorDeSesion(usuario));
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE, cuerpo), mapper);
    }

    /** Recurso auditado: "controlDocumental:<id>:crear". */
    public static String recursoCrear(ObjectNode payload, Object dataRespuesta) {
        JsonNode id = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("id") : null;
        return "controlDocumental:" + (id != null && id.canConvertToInt() ? id.asInt() : "?") + ":crear";
    }

    /**
     * controlDocumental.update → PUT api/control-documental/{id} {codigoBase, ..., actualizadoPor}
     * (Fase 4d), igual que Photino: mismo formulario que create, sin versión ni adjunto. La API hace
     * un UPDATE directo sin verificar existencia ni comparar nada (sobrescribe sin historial).
     *
     * Seguridad transparente: igual que create + {id}; exige haber abierto el documento en esta
     * sesión (huella de `get`), lo relee antes del PUT (404/eliminado → error en vez de un OK que
     * no cambió nada) y rechaza si cambió desde que se abrió (lost update) — mismo patrón que
     * noConformidades.update, la API tampoco tiene versión acá.
     */
    public BridgeResult actualizar(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_ACTUALIZAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer id = entero(payload.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_ID_DOCUMENTO);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = armarCamposDocumento(cuerpo, payload);
        if (error != null) {
            return BridgeResult.error(error);
        }
        String leida = LecturasDeSesion.huella(recursoLecturaDocumento(id));
        if (leida == null) {
            return BridgeResult.error(MENSAJE_DOCUMENTO_SIN_LEER);
        }
        BridgeResult vigente = InnpackRespuestas.reenviar(api.get(usuario, BASE + "/" + id), mapper);
        if (!vigente.ok()) {
            return vigente;
        }
        if (!huellaDocumento(vigente.data()).equals(leida)) {
            return BridgeResult.error(MENSAJE_DOCUMENTO_CONFLICTO);
        }
        cuerpo.put("actualizadoPor", autorDeSesion(usuario));
        BridgeResult resultado = InnpackRespuestas.reenviar(api.putJson(usuario, BASE + "/" + id, cuerpo), mapper);
        if (resultado.ok()) {
            // La vista cierra el formulario; para editar de nuevo relee el documento (y la huella).
            LecturasDeSesion.olvidar(recursoLecturaDocumento(id));
        }
        return resultado;
    }

    /** Recurso auditado: "controlDocumental:<id>:actualizar". */
    public static String recursoActualizar(ObjectNode payload, Object dataRespuesta) {
        Integer id = entero(payload.get("id"));
        return "controlDocumental:" + (id != null ? id : "?") + ":actualizar";
    }

    /** Arma los 9 campos de LeerCamposDocumento; devuelve el mensaje de error, o null si quedó ok. */
    private String armarCamposDocumento(ObjectNode cuerpo, ObjectNode payload) {
        for (String campo : List.of("codigoBase", "nombre", "tipoDocumento", "area", "responsable", "ubicacion")) {
            String error = ponerTextoUnaLinea(cuerpo, payload, campo);
            if (error != null) {
                return error;
            }
        }
        String error = ponerTextoMultilinea(cuerpo, payload, "observaciones");
        if (error != null) {
            return error;
        }
        String alcance = texto(payload.get("alcanceEmpresa"));
        if (!alcance.isEmpty() && !ALCANCES.contains(alcance)) {
            return MENSAJE_OPCION_INVALIDA + "alcanceEmpresa.";
        }
        cuerpo.put("alcanceEmpresa", alcance);
        String estado = texto(payload.get("estado"));
        if (!estado.isEmpty() && !ESTADOS.contains(estado)) {
            return MENSAJE_OPCION_INVALIDA + "estado.";
        }
        cuerpo.put("estado", estado);
        return null;
    }

    private String ponerTextoUnaLinea(ObjectNode cuerpo, ObjectNode payload, String campo) {
        return ponerTexto(cuerpo, payload, campo, CONTROL_UNA_LINEA);
    }

    private String ponerTextoMultilinea(ObjectNode cuerpo, ObjectNode payload, String campo) {
        return ponerTexto(cuerpo, payload, campo, CONTROL_MULTILINEA);
    }

    /** GetString de Photino: string o número → texto; null/ausente → ""; presente no texto/número → inválido. */
    private String ponerTexto(ObjectNode cuerpo, ObjectNode payload, String campo, Pattern control) {
        JsonNode v = payload.get(campo);
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

    /** Fecha del &lt;input type="date"&gt; de Photino, obligatoria aquí: ausente/vacía igual se manda "" (la API la rechaza). */
    private String ponerFecha(ObjectNode cuerpo, ObjectNode payload, String campo) {
        String valor = texto(payload.get(campo));
        if (valor.isEmpty()) {
            cuerpo.put(campo, "");
            return null;
        }
        if (!FECHA_ISO.matcher(valor).matches()) {
            return MENSAJE_FECHA_INVALIDA;
        }
        try {
            java.time.LocalDate.parse(valor);
        } catch (java.time.format.DateTimeParseException e) {
            return MENSAJE_FECHA_INVALIDA;
        }
        cuerpo.put(campo, valor);
        return null;
    }

    /** proximaRevision: Photino manda null si está vacía (la API la autocalcula a +365 días). */
    private String ponerFechaOpcional(ObjectNode cuerpo, ObjectNode payload, String campo) {
        JsonNode v = payload.get(campo);
        if (v == null || v.isNull() || texto(v).isEmpty()) {
            cuerpo.putNull(campo);
            return null;
        }
        return ponerFecha(cuerpo, payload, campo);
    }

    /**
     * controlDocumental.version.crear → POST api/control-documental/{documentoId}/version {version,
     * fechaActualizacion, proximaRevision, adjuntoNombreArchivo, adjuntoContenidoBase64, creadoPor}
     * (Fase 4d), igual que Photino: agrega una versión y la deja vigente (demueve a las anteriores),
     * con adjunto inicial opcional; actualiza `documentos.actualizado_por`.
     *
     * Seguridad transparente: creadoPor ← sesión; version sin controles/HTML; fechas AAAA-MM-DD;
     * adjunto con firma real; confirma que el documento existe releyéndolo antes de escribir (la API
     * no lo valida — crearía una versión huérfana). Sin huella: Photino tampoco protege el doble
     * envío de una nueva versión más allá del confirm() del navegador.
     */
    public BridgeResult versionCrear(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_VERSION_CREAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer documentoId = entero(payload.get("documentoId"));
        if (documentoId == null || documentoId <= 0) {
            return BridgeResult.error(MENSAJE_ID_DOCUMENTO);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = ponerTextoUnaLinea(cuerpo, payload, "version");
        error = error != null ? error : ponerFecha(cuerpo, payload, "fechaActualizacion");
        error = error != null ? error : ponerFechaOpcional(cuerpo, payload, "proximaRevision");
        error = error != null ? error : ponerAdjuntoOpcional(cuerpo, payload, "adjuntoNombreArchivo", "adjuntoContenidoBase64");
        if (error != null) {
            return BridgeResult.error(error);
        }
        BridgeResult vigente = InnpackRespuestas.reenviar(api.get(usuario, BASE + "/" + documentoId), mapper);
        if (!vigente.ok()) {
            return vigente;
        }
        cuerpo.put("creadoPor", autorDeSesion(usuario));
        BridgeResult resultado = InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/" + documentoId + "/version", cuerpo), mapper);
        if (resultado.ok()) {
            // Cambió la versión vigente del documento: fuerza a releer antes de un siguiente guardado.
            LecturasDeSesion.olvidar(recursoLecturaDocumento(documentoId));
        }
        return resultado;
    }

    /** Recurso auditado: "controlDocumental:<documentoId>:version[:<versionId>]". */
    public static String recursoVersionCrear(ObjectNode payload, Object dataRespuesta) {
        Integer documentoId = entero(payload.get("documentoId"));
        JsonNode id = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("id") : null;
        return "controlDocumental:" + (documentoId != null ? documentoId : "?") + ":version"
                + (id != null && id.canConvertToInt() ? ":" + id.asInt() : "");
    }

    /**
     * controlDocumental.eliminar → DELETE api/control-documental/{id}?actualizadoPor=.. (Fase 4d),
     * igual que Photino: borrado lógico, la API no verifica existencia previa (UPDATE sin WHERE de
     * existencia real, responde 200 igual si el id no existe).
     *
     * Seguridad transparente: lista blanca {id, actualizadoPor}; actualizadoPor ← sesión; relee el
     * documento antes de borrar (404/ya eliminado → error real en vez del OK vacío de la API).
     */
    public BridgeResult eliminar(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_ELIMINAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer id = entero(payload.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_ID_DOCUMENTO);
        }
        BridgeResult vigente = InnpackRespuestas.reenviar(api.get(usuario, BASE + "/" + id), mapper);
        if (!vigente.ok()) {
            return vigente;
        }
        BridgeResult resultado = InnpackRespuestas.reenviar(
                api.delete(usuario, BASE + "/" + id + "?actualizadoPor=" + UriEscape.dataString(autorDeSesion(usuario))), mapper);
        if (resultado.ok()) {
            LecturasDeSesion.olvidar(recursoLecturaDocumento(id));
        }
        return resultado;
    }

    /** Recurso auditado: "controlDocumental:<id>:eliminar". */
    public static String recursoEliminar(ObjectNode payload, Object dataRespuesta) {
        Integer id = entero(payload.get("id"));
        return "controlDocumental:" + (id != null ? id : "?") + ":eliminar";
    }

    /**
     * controlDocumental.adjunto.subir → POST api/control-documental/adjunto/{documentoVersionId}
     * {nombreArchivo, contenidoBase64, subidoPor} (Fase 4d), igual que Photino: reemplaza el adjunto
     * de esa versión (upsert con candado de fila en la API, evita duplicados por subidas paralelas).
     * La API no valida que `documentoVersionId` exista antes de escribir.
     *
     * Seguridad transparente: subidoPor ← sesión; nombre saneado; extensión restringida a las 7 del
     * `accept` de Photino (.pdf/.doc/.docx/.jpg/.jpeg/.png/.webp); además de la extensión (único
     * control de Photino/la API), el gateway valida la FIRMA real de los primeros bytes contra el
     * tipo declarado — diferencia defensiva, un archivo renombrado no pasa. Tope 10 MB (el real de
     * la API, no los 25 MB de adjunto.abrir). Viaja por /api/v1/bridge/archivo (supera el tope general).
     */
    public BridgeResult adjuntoSubir(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_ADJUNTO_SUBIR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer versionId = entero(payload.get("documentoVersionId"));
        if (versionId == null) {
            return BridgeResult.error(MENSAJE_ID_VERSION);
        }
        String nombre = texto(payload.get("nombreArchivo"));
        String contenidoBase64 = texto(payload.get("contenidoBase64"));
        if (nombre.isBlank()) {
            return BridgeResult.error(MENSAJE_FALTA_NOMBRE_ARCHIVO);
        }
        if (contenidoBase64.isBlank()) {
            return BridgeResult.error(MENSAJE_FALTA_CONTENIDO_ARCHIVO);
        }
        String error = validarArchivo(nombre, contenidoBase64);
        if (error != null) {
            return BridgeResult.error(error);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("nombreArchivo", nombreArchivoSeguro(nombre, "adjunto"));
        cuerpo.put("contenidoBase64", contenidoBase64);
        cuerpo.put("subidoPor", autorDeSesion(usuario));
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/adjunto/" + versionId, cuerpo), mapper);
    }

    /** Recurso auditado: "controlDocumental:adjunto:<documentoVersionId>:subido". */
    public static String recursoAdjuntoSubir(ObjectNode payload, Object dataRespuesta) {
        Integer versionId = entero(payload.get("documentoVersionId"));
        return "controlDocumental:adjunto:" + (versionId != null ? versionId : "?") + ":subido";
    }

    /** {adjuntoNombreArchivo, adjuntoContenidoBase64} opcionales de create/version.crear: ambos vacíos = sin adjunto. */
    private String ponerAdjuntoOpcional(ObjectNode cuerpo, ObjectNode payload, String claveNombre, String claveContenido) {
        String nombre = texto(payload.get(claveNombre));
        String contenido = texto(payload.get(claveContenido));
        if (nombre.isBlank() || contenido.isBlank()) {
            cuerpo.putNull(claveNombre);
            cuerpo.putNull(claveContenido);
            return null;
        }
        String error = validarArchivo(nombre, contenido);
        if (error != null) {
            return error;
        }
        cuerpo.put(claveNombre, nombreArchivoSeguro(nombre, "adjunto"));
        cuerpo.put(claveContenido, contenido);
        return null;
    }

    /** Extensión conocida + base64 decodificable ≤ 10 MB + firma real de los primeros bytes coincidente. */
    private static String validarArchivo(String nombreArchivo, String base64) {
        String extension = extension(nombreArchivo);
        String mimeEsperado = MIME_POR_EXTENSION_SUBIDA.get(extension);
        if (mimeEsperado == null) {
            return MENSAJE_TIPO_ARCHIVO;
        }
        if (base64.length() > MAX_SUBIDA_BASE64) {
            return MENSAJE_ARCHIVO_TAMANO;
        }
        byte[] contenido;
        try {
            contenido = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return MENSAJE_ARCHIVO_INVALIDO;
        }
        if (contenido.length > MAX_SUBIDA_BYTES) {
            return MENSAJE_ARCHIVO_TAMANO;
        }
        if (!firmaArchivoCoincide(mimeEsperado, contenido)) {
            return MENSAJE_ARCHIVO_FIRMA;
        }
        return null;
    }

    private static String extension(String nombreArchivo) {
        int punto = nombreArchivo.lastIndexOf('.');
        return punto < 0 ? "" : nombreArchivo.substring(punto).toLowerCase(java.util.Locale.ROOT);
    }

    /** PDF/JPEG/PNG/WEBP reutilizan firmaCoincide (adjunto.abrir); DOC/DOCX no están en ese mapa (no son previsualizables). */
    private static boolean firmaArchivoCoincide(String mime, byte[] contenido) {
        return switch (mime) {
            case "application/msword" -> coincidePrefijo(contenido, FIRMA_OLE2);
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> coincidePrefijo(contenido, FIRMA_ZIP);
            default -> firmaCoincide(mime, contenido);
        };
    }

    private static boolean coincidePrefijo(byte[] contenido, byte[] firma) {
        if (contenido.length < firma.length) {
            return false;
        }
        for (int i = 0; i < firma.length; i++) {
            if (contenido[i] != firma[i]) {
                return false;
            }
        }
        return true;
    }

    private static String nombreCampoSeguro(String clave) {
        return clave != null && clave.matches("[A-Za-z0-9_]{1,40}") ? clave : "?";
    }

    /** Mismo criterio que la sesión C# de Photino (nombre completo; si no, el código). */
    private static String autorDeSesion(SessionUser usuario) {
        String nombre = usuario.nombreCompleto();
        return nombre != null && !nombre.isBlank() ? nombre : usuario.codigoUsuario();
    }

    /** GetString de Photino: string o número → texto; null/ausente → "". */
    private static String texto(JsonNode nodo) {
        return nodo != null && (nodo.isString() || nodo.isNumber()) ? nodo.asString() : "";
    }

    /**
     * Valida un adjunto {nombreArchivo, tipoMime, contenidoBase64} de la API (contenido, tamaño, base64, nombre) y
     * arma el contrato {previsualizable, nombreArchivo, tipoMime, contenidoBase64} que web-bridge.js previsualiza o
     * descarga. Compartido con Laboratorio (2l). Paridad (Fase 3o): se previsualiza en los MISMOS casos que Photino
     * (MIME declarado image/* o application/pdf, que la API asigna por extensión), pero se sirve con el tipo REAL
     * detectado por la firma (PDF/PNG/JPEG/GIF/BMP/WEBP); si la firma no es de un tipo seguro se descarga.
     */
    public static BridgeResult adjuntoParaNavegador(BridgeResult upstream, String nombrePorDefecto, int maxBase64Chars, ObjectMapper mapper) {
        if (!upstream.ok()) {
            return upstream;
        }
        JsonNode adjunto = upstream.data() instanceof JsonNode n && n.isObject() ? n : null;
        String base64 = textoDe(adjunto, "contenidoBase64").trim();
        if (base64.isEmpty()) {
            return BridgeResult.error(MENSAJE_SIN_CONTENIDO);
        }
        if (base64.length() > maxBase64Chars) {
            return BridgeResult.error(MENSAJE_TAMANO);
        }
        byte[] inicio = inicioDecodificado(base64);
        if (inicio == null) {
            return BridgeResult.error(MENSAJE_ADJUNTO_INVALIDO);
        }
        String tipoMime = textoDe(adjunto, "tipoMime").trim().toLowerCase();
        String nombre = nombreArchivoSeguro(textoDe(adjunto, "nombreArchivo"), nombrePorDefecto);
        String real = mimePorFirma(inicio);
        boolean previsualizable = real != null && (tipoMime.startsWith("image/") || tipoMime.equals("application/pdf"));

        ObjectNode salida = mapper.createObjectNode();
        salida.put("previsualizable", previsualizable);
        salida.put("nombreArchivo", nombre);
        salida.put("tipoMime", previsualizable ? real : MIME_DESCARGA.contains(tipoMime) ? tipoMime : "application/octet-stream");
        salida.put("contenidoBase64", base64);
        return BridgeResult.ok(salida);
    }

    /** Tipos previsualizables en orden de detección (sin el alias image/jpg). */
    private static final List<String> MIME_DETECTABLES = List.of("application/pdf", "image/png", "image/jpeg", "image/gif",
            "image/webp", "image/bmp");

    /** Tipo REAL según la firma de los primeros bytes (PDF o imagen segura); null si no es ninguno. */
    public static String mimePorFirma(byte[] inicio) {
        for (String mime : MIME_DETECTABLES) {
            if (inicio != null && firmaCoincide(mime, inicio)) {
                return mime;
            }
        }
        return null;
    }

    /** Primeros 12 bytes del contenido (16 chars base64); null si el base64 es inválido. */
    public static byte[] inicioDecodificado(String base64) {
        try {
            String cabeza = base64.length() >= 16 ? base64.substring(0, 16) : base64;
            int resto = cabeza.length() % 4;
            if (resto != 0) {
                cabeza = cabeza + "=".repeat(4 - resto);
            }
            return Base64.getDecoder().decode(cabeza);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** MIME previsualizable (imagen/PDF) cuya firma real coincide con lo declarado. */
    public static boolean firmaCoincide(String tipoMime, byte[] inicio) {
        byte[][] firmas = FIRMAS_PREVISUALIZABLES.get(tipoMime);
        if (firmas == null) {
            return false;
        }
        for (byte[] firma : firmas) {
            if (inicio.length >= firma.length) {
                boolean igual = true;
                for (int i = 0; i < firma.length && igual; i++) {
                    igual = inicio[i] == firma[i];
                }
                if (igual) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Solo el nombre base (sin rutas), sin caracteres de control ni inválidos en Windows, sin marcas
     * Unicode bidi, sin nombres reservados, largo acotado; conserva la extensión original.
     */
    public static String nombreArchivoSeguro(String nombre, String porDefecto) {
        String n = nombre == null ? "" : nombre;
        int corte = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        n = corte >= 0 ? n.substring(corte + 1) : n;
        n = n.replaceAll("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]", "");
        n = n.replaceAll("[\\x00-\\x1f\\x7f<>:\"|?*]", "_");
        n = n.replaceAll("^[\\s.]+|[\\s.]+$", "");
        if (n.isEmpty()) {
            n = porDefecto;
        }
        if (n.length() > 150) {
            n = n.substring(0, 150);
        }
        if (n.matches("(?i)^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*|$)")) {
            n = "_" + n;
        }
        return n;
    }

    private static String textoDe(JsonNode objeto, String campo) {
        JsonNode nodo = objeto == null ? null : objeto.get(campo);
        return nodo != null && nodo.isString() ? nodo.asString() : "";
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
