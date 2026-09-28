package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Control Documental" (compartido INNPACK/FARET) — SOLO LECTURA. Port de Photino
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
 * Escrituras (create, update, version.crear, eliminar, adjunto.subir) NO habilitadas.
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

    /** controlDocumental.get → GET api/control-documental/{id} */
    public BridgeResult get(ObjectNode payload, SessionUser usuario) {
        Integer id = entero(payload.get("id"));
        if (id == null) {
            return BridgeResult.error(MENSAJE_ID_DOCUMENTO);
        }
        return InnpackRespuestas.reenviar(api.get(usuario, BASE + "/" + id), mapper);
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
