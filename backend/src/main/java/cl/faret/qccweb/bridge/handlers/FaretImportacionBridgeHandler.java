package cl.faret.qccweb.bridge.handlers;

import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.Invalido;
import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.cerrada;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.bridge.EscrituraRateLimiter;
import cl.faret.qccweb.bridge.LecturasDeSesion;
import cl.faret.qccweb.upstream.FaretApiClient;
import cl.faret.qccweb.upstream.FaretRespuestas;
import cl.faret.qccweb.upstream.InnpackApiClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fase F6 - IMPORTACIÓN MASIVA FARET (Photino 1.8.15, 9e1b556: FaretHandler.HandleImportacionValidar / HandleImportacionConfirmar,
 * FaretImportacionApiService). QualityControlFaret.Api con el Bearer del usuario; módulo faret-importacion (solo admin y admin_ti).
 *
 * <ul>
 * <li>faret.importacion.validar: el navegador manda {fileName, base64}; el gateway valida y reenvía POST multipart api/importaciones/validar
 * (campo "archivo", Content-Type del xlsx fijado aquí, filename saneado). Defensas: solo .xlsx, nombre saneado (sin &lt; &gt; " ' &amp; ni
 * controles: la API lo guarda y la vista del historial lo pinta con innerHTML), base64 estricto, tope de 10 MB decodificados (el de
 * UploadOptions.MaxFileSizeMb de la API; Photino no tiene tope), firma ZIP (PK\3\4) y estructura mínima de xlsx ([Content_Types].xml y
 * xl/workbook.xml) con tope de entradas y de bytes descomprimidos (zip-bomb). No se interpreta el contenido más allá de eso. La respuesta
 * se reproyecta a {loteId, nombreArchivo, totalFilas, filasValidas, filasConError, errores:[{fila, errores:[texto]}]} con los textos de
 * error escapados (la vista los pinta con innerHTML y llevan texto de celdas). El loteId (GUID "N") se registra en la sesión.</li>
 * <li>faret.importacion.confirmar: {loteId} -> POST api/importaciones/confirmar {loteId}. Solo se acepta un loteId devuelto por un
 * validar de la MISMA sesión (LecturasDeSesion); uno desconocido se rechaza sin llamar a la API. Un lote en confirmación no se acepta
 * dos veces a la vez (la API quita el lote del caché recién después de insertar: dos confirmaciones simultáneas lo duplicarían).
 * La inserción de la API es una sola transacción (no hay lote parcial); si la llamada falla por timeout o red (resultado
 * DESCONOCIDO: la API puede seguir insertando) el lote se olvida y se pide revisar el historial antes de volver a importar.</li>
 * </ul>
 * Sin reintentos (F0). Límite propio por usuario (más estricto que el general de escrituras) para validar y confirmar.
 */
public class FaretImportacionBridgeHandler {

    static final long MAX_BYTES = 10L * 1024 * 1024;
    static final int MAX_ENTRADAS_ZIP = 500;
    static final long MAX_DESCOMPRIMIDO = 100L * 1024 * 1024;
    static final int MAX_NOMBRE = 150;
    static final String MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String MENSAJE_FALTA_NOMBRE = "Falta fileName";
    static final String MENSAJE_FALTA_BASE64 = "Falta el contenido del archivo (base64)";
    static final String MENSAJE_BASE64 = "Archivo inválido (base64 corrupto)";
    static final String MENSAJE_SOLO_XLSX = "Solo se admiten archivos .xlsx.";
    static final String MENSAJE_VACIO = "El archivo es requerido.";
    static final String MENSAJE_TAMANO = "El archivo excede el tamaño máximo de 10 MB.";
    static final String MENSAJE_NO_XLSX = "El archivo no es un libro .xlsx válido.";
    static final String MENSAJE_FALTA_LOTE = "Falta loteId";
    static final String MENSAJE_LOTE_DESCONOCIDO = "El lote no existe o expiró. Vuelva a validar el archivo.";
    static final String MENSAJE_EN_CURSO = "Este lote ya se está confirmando. Espera el resultado.";
    static final String MENSAJE_LIMITE = "Demasiadas importaciones seguidas. Espera un momento e inténtalo de nuevo.";
    static final String MENSAJE_RESULTADO_INCIERTO = "No se pudo confirmar la respuesta de la API Faret. Revisa el historial de importaciones "
            + "antes de volver a importar el archivo.";

    private static final String RUTA_VALIDAR = "/api/importaciones/validar";
    private static final String RUTA_CONFIRMAR = "/api/importaciones/confirmar";
    private static final String PREFIJO_LOTE = "faret:importacion:lote:";
    private static final Set<String> CLAVES_VALIDAR = Set.of("action", "fileName", "base64");
    private static final Set<String> CLAVES_CONFIRMAR = Set.of("action", "loteId");
    /** GUID en formato "N" en minúsculas (Guid.NewGuid().ToString("N") de la API). */
    private static final Pattern LOTE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final int MAX_TEXTO_ERROR = 1000;

    private final FaretApiClient qualityControl;
    private final ObjectMapper mapper;
    private final EscrituraRateLimiter limiteValidar;
    private final EscrituraRateLimiter limiteConfirmar;
    private final Set<String> confirmando = ConcurrentHashMap.newKeySet();

    public FaretImportacionBridgeHandler(FaretApiClient qualityControl, ObjectMapper mapper, EscrituraRateLimiter limiteValidar,
            EscrituraRateLimiter limiteConfirmar) {
        this.qualityControl = qualityControl;
        this.mapper = mapper;
        this.limiteValidar = limiteValidar;
        this.limiteConfirmar = limiteConfirmar;
    }

    // ------------------------------------------------------------------------------------------- validar

    /** faret.importacion.validar: ver la clase. Éxito: {loteId, nombreArchivo, totalFilas, filasValidas, filasConError, errores}. */
    public BridgeResult validar(ObjectNode payload, SessionUser usuario) {
        String nombre;
        byte[] contenido;
        try {
            cerrada(payload, CLAVES_VALIDAR);
            String nombreCrudo = textoObligatorio(payload.get("fileName"), MENSAJE_FALTA_NOMBRE);
            String base64 = textoObligatorio(payload.get("base64"), MENSAJE_FALTA_BASE64);
            nombre = nombreXlsx(nombreCrudo);
            if (base64.length() > ((MAX_BYTES + 2) / 3) * 4) {
                return BridgeResult.error(MENSAJE_TAMANO);
            }
            try {
                contenido = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                return BridgeResult.error(MENSAJE_BASE64);
            }
            if (contenido.length == 0) {
                return BridgeResult.error(MENSAJE_VACIO);
            }
            if (contenido.length > MAX_BYTES) {
                return BridgeResult.error(MENSAJE_TAMANO);
            }
            validarXlsx(contenido);
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        if (!limiteValidar.permitir(usuario.userId())) {
            return BridgeResult.error(MENSAJE_LIMITE);
        }
        BridgeResult r = FaretRespuestas.desenvolver(qualityControl.postMultipart(usuario, RUTA_VALIDAR, "archivo", nombre, MIME_XLSX, contenido), mapper);
        if (!r.ok()) {
            return r;
        }
        JsonNode data = (JsonNode) r.data();
        JsonNode loteNodo = data == null ? null : data.get("loteId");
        if (data == null || !data.isObject() || loteNodo == null || !loteNodo.isString() || !LOTE_ID.matcher(loteNodo.asString()).matches()) {
            return BridgeResult.error(FaretRespuestas.ERROR_FARET);
        }
        String loteId = loteNodo.asString();
        int validas = entero(data.get("filasValidas"));
        ObjectNode salida = mapper.createObjectNode();
        salida.put("loteId", loteId);
        salida.put("nombreArchivo", nombre);
        salida.put("totalFilas", entero(data.get("totalFilas")));
        salida.put("filasValidas", validas);
        salida.put("filasConError", entero(data.get("filasConError")));
        salida.set("errores", erroresEscapados(data.get("errores")));
        LecturasDeSesion.registrar(PREFIJO_LOTE + loteId, "validado:" + validas);
        return BridgeResult.ok(salida);
    }

    /** "faret:importacion:validar" y ":lote:<loteId>" si la API devolvió el lote (nunca el nombre ni el contenido). */
    public static String recursoValidar(ObjectNode payload, Object dataRespuesta) {
        String loteId = loteDe(dataRespuesta);
        return "faret:importacion:validar" + (loteId != null ? ":lote:" + loteId : "");
    }

    // ------------------------------------------------------------------------------------------- confirmar

    /** faret.importacion.confirmar: ver la clase. Éxito: {importacionId, filasInsertadas}. */
    public BridgeResult confirmar(ObjectNode payload, SessionUser usuario) {
        String loteId;
        try {
            cerrada(payload, CLAVES_CONFIRMAR);
            loteId = textoObligatorio(payload.get("loteId"), MENSAJE_FALTA_LOTE);
            if (!LOTE_ID.matcher(loteId).matches()) {
                return BridgeResult.error(MENSAJE_LOTE_DESCONOCIDO);
            }
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        String clave = PREFIJO_LOTE + loteId;
        if (LecturasDeSesion.huella(clave) == null) {
            return BridgeResult.error(MENSAJE_LOTE_DESCONOCIDO);
        }
        if (!limiteConfirmar.permitir(usuario.userId())) {
            return BridgeResult.error(MENSAJE_LIMITE);
        }
        if (!confirmando.add(loteId)) {
            return BridgeResult.error(MENSAJE_EN_CURSO);
        }
        try {
            ObjectNode cuerpo = mapper.createObjectNode();
            cuerpo.put("loteId", loteId);
            InnpackApiClient.Respuesta respuesta = qualityControl.postJson(usuario, RUTA_CONFIRMAR, cuerpo);
            if (respuesta.status() == 0) {
                // Timeout o red: la API puede haber insertado (o seguir insertando). Se olvida el lote para no permitir un reintento ciego.
                LecturasDeSesion.olvidar(clave);
                return BridgeResult.error(MENSAJE_RESULTADO_INCIERTO);
            }
            BridgeResult r = FaretRespuestas.desenvolver(respuesta, mapper);
            if (!r.ok()) {
                if (r.error() != null && r.error().toLowerCase(Locale.ROOT).contains("no existe o expir")) {
                    LecturasDeSesion.olvidar(clave);
                }
                return r;
            }
            JsonNode data = (JsonNode) r.data();
            if (data == null || !data.isObject() || data.get("importacionId") == null || !data.get("importacionId").canConvertToLong()) {
                // La API respondió éxito pero sin la forma esperada: el lote pudo insertarse, no se reintenta.
                LecturasDeSesion.olvidar(clave);
                return BridgeResult.error(MENSAJE_RESULTADO_INCIERTO);
            }
            LecturasDeSesion.olvidar(clave);
            ObjectNode salida = mapper.createObjectNode();
            salida.put("importacionId", data.get("importacionId").asLong());
            salida.put("filasInsertadas", entero(data.get("filasInsertadas")));
            return BridgeResult.ok(salida);
        } finally {
            confirmando.remove(loteId);
        }
    }

    /** "faret:importacion:[<importacionId>:]lote:<loteId>" (el id solo si la API confirmó; nunca contenido). */
    public static String recursoConfirmar(ObjectNode payload, Object dataRespuesta) {
        JsonNode n = payload.get("loteId");
        String loteId = n != null && n.isString() && LOTE_ID.matcher(n.asString().strip()).matches() ? n.asString().strip() : "?";
        // El auditor acota el recurso a 64 caracteres: el id de la importación va antes del lote (que ocupa 32) para no perderse.
        String importacion = "";
        if (dataRespuesta instanceof JsonNode d && d.isObject() && d.get("importacionId") != null && d.get("importacionId").canConvertToLong()) {
            importacion = d.get("importacionId").asLong() + ":";
        }
        return "faret:importacion:" + importacion + "lote:" + loteId;
    }

    // ------------------------------------------------------------------------------------------- validación del archivo

    /** Texto obligatorio: string (cualquier otro tipo es inválido), sin espacios en los extremos. */
    private static String textoObligatorio(JsonNode n, String faltante) {
        if (n == null || n.isNull()) {
            throw new Invalido(faltante);
        }
        if (!n.isString()) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
        }
        String t = n.asString().strip();
        if (t.isEmpty()) {
            throw new Invalido(faltante);
        }
        return t;
    }

    /** Solo .xlsx, nombre saneado (sin rutas, controles, marcado ni comillas) y que conserve la extensión. */
    static String nombreXlsx(String crudo) {
        String minusculas = crudo.toLowerCase(Locale.ROOT);
        if (crudo.length() > 255 || !minusculas.endsWith(".xlsx")) {
            throw new Invalido(MENSAJE_SOLO_XLSX);
        }
        String nombre = NoConformidadesBridgeHandler.nombreAdjuntoSeguro(crudo, "importacion.xlsx");
        if (!nombre.toLowerCase(Locale.ROOT).endsWith(".xlsx") || nombre.length() <= ".xlsx".length()) {
            throw new Invalido("El nombre del archivo no es válido (máximo " + MAX_NOMBRE + " caracteres, termina en .xlsx).");
        }
        return nombre;
    }

    /**
     * Firma ZIP + estructura mínima de un libro (Open XML): [Content_Types].xml y xl/workbook.xml. Recorre los encabezados locales con
     * ZipInputStream (solo JDK), cuenta los bytes descomprimidos sin conservarlos y aborta al pasar los topes (zip-bomb). No interpreta
     * el contenido de las partes.
     */
    static void validarXlsx(byte[] contenido) {
        if (contenido.length < 4 || contenido[0] != 'P' || contenido[1] != 'K' || contenido[2] != 3 || contenido[3] != 4) {
            throw new Invalido(MENSAJE_NO_XLSX);
        }
        Set<String> vistas = new HashSet<>();
        long total = 0;
        int entradas = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(contenido))) {
            ZipEntry entrada;
            while ((entrada = zip.getNextEntry()) != null) {
                if (++entradas > MAX_ENTRADAS_ZIP) {
                    throw new Invalido(MENSAJE_NO_XLSX);
                }
                String nombre = entrada.getName();
                if (nombre.startsWith("/") || nombre.contains("..") || nombre.contains("\\")) {
                    throw new Invalido(MENSAJE_NO_XLSX);
                }
                vistas.add(nombre);
                int leidos;
                while ((leidos = zip.read(buffer)) > 0) {
                    total += leidos;
                    if (total > MAX_DESCOMPRIMIDO) {
                        throw new Invalido(MENSAJE_NO_XLSX);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof Invalido invalido) {
                throw invalido;
            }
            throw new Invalido(MENSAJE_NO_XLSX);
        }
        if (!vistas.contains("[Content_Types].xml") || !vistas.contains("xl/workbook.xml")) {
            throw new Invalido(MENSAJE_NO_XLSX);
        }
    }

    // ------------------------------------------------------------------------------------------- respuesta

    private static int entero(JsonNode n) {
        return n != null && n.isIntegralNumber() && n.canConvertToInt() && n.asInt() >= 0 ? n.asInt() : 0;
    }

    /** errores [{fila, errores:[texto]}] con cada texto escapado (la vista usa innerHTML y los textos traen valores de celdas). */
    private ArrayNode erroresEscapados(JsonNode errores) {
        ArrayNode salida = mapper.createArrayNode();
        if (errores == null || !errores.isArray()) {
            return salida;
        }
        for (JsonNode fila : errores) {
            if (!fila.isObject()) {
                continue;
            }
            ObjectNode item = mapper.createObjectNode();
            item.put("fila", entero(fila.get("fila")));
            ArrayNode textos = item.putArray("errores");
            JsonNode lista = fila.get("errores");
            if (lista != null && lista.isArray()) {
                for (JsonNode t : lista) {
                    if (t.isString()) {
                        String s = t.asString();
                        textos.add(HtmlUtils.htmlEscape(s.length() > MAX_TEXTO_ERROR ? s.substring(0, MAX_TEXTO_ERROR) : s, "UTF-8"));
                    }
                }
            }
            salida.add(item);
        }
        return salida;
    }

    private static String loteDe(Object dataRespuesta) {
        if (dataRespuesta instanceof JsonNode d && d.isObject() && d.get("loteId") != null && d.get("loteId").isString()
                && LOTE_ID.matcher(d.get("loteId").asString()).matches()) {
            return d.get("loteId").asString();
        }
        return null;
    }
}
