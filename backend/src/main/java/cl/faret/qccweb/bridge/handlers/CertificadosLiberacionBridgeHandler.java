package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Certificados de Liberación" (INNPACK) — SOLO LECTURA. Port de Photino
 * src/Backend/Modules/CertificadosLiberacion/CertificadosLiberacionHandler.cs +
 * InnpackCertificadosLiberacionApiService.
 *
 * "empresa" aquí ES un filtro de negocio (select "Todas / FARET SPA / INNPACK SPA" de la vista;
 * nombres del sistema legado, no el scope de sesión INNPACK/FARET): se reenvía, pero solo con esos
 * valores exactos. No se usa IdentityOverride.
 *
 * calidadPdf.descargar: Photino escribe el PDF en Descargas y lo abre con Process.Start. Aquí el
 * gateway solo valida (contenido, tamaño, firma %PDF-, nombre) y devuelve {fileName, base64}; la
 * descarga la hace el navegador (web-bridge.js, Blob). Ningún archivo temporal ni ruta local.
 * Diferencia de contrato: Photino responde data.path, la web data.fileName (el controller ignora data).
 *
 * certificadosLiberacion.pdf.descargar (certificado de terminaciones) existe en el handler de Photino
 * pero ningún controller lo usa: no se habilita.
 */
public class CertificadosLiberacionBridgeHandler {

    static final String MENSAJE_FOLIO = "Falta indicar el folio";
    static final String MENSAJE_EMPRESA_INVALIDA = "Filtro de empresa inválido.";
    static final String MENSAJE_SIN_CONTENIDO = "El certificado no trae contenido";
    static final String MENSAJE_TAMANO = "El certificado excede el tamaño máximo permitido.";
    static final String MENSAJE_NO_PDF = "El certificado no es un PDF válido.";
    static final int MAX_PDF_BYTES = 15 * 1024 * 1024;
    static final int MAX_BASE64_CHARS = ((MAX_PDF_BYTES + 2) / 3) * 4;

    /** Opciones exactas del <select id="clFiltroEmpresa"> de la vista de Photino ("" = Todas). */
    private static final Set<String> EMPRESAS_FILTRO = Set.of("FARET SPA", "INNPACK SPA");
    private static final String[] FILTROS = {"folio", "np", "cliente", "empresa", "operador", "inspector", "fechaDesde", "fechaHasta"};
    private static final String BASE = "/api/certificados-liberacion";

    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public CertificadosLiberacionBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /**
     * certificadosLiberacion.buscar → GET api/certificados-liberacion[?folio=..&np=..&cliente=..
     * &empresa=..&operador=..&inspector=..&fechaDesde=..&fechaHasta=..] — solo los no vacíos, en ese
     * orden, escapados como Uri.EscapeDataString; sin "?" si no hay ninguno. GetString de Photino:
     * string o número → texto; cualquier otro tipo → "" (se omite).
     */
    public BridgeResult buscar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        Map<String, String> valores = new LinkedHashMap<>();
        for (String filtro : FILTROS) {
            String valor = texto(data.get(filtro));
            if (!valor.isBlank()) {
                valores.put(filtro, valor);
            }
        }
        String empresa = valores.get("empresa");
        if (empresa != null && !EMPRESAS_FILTRO.contains(empresa)) {
            return BridgeResult.error(MENSAJE_EMPRESA_INVALIDA);
        }
        StringBuilder path = new StringBuilder(BASE);
        char separador = '?';
        for (Map.Entry<String, String> e : valores.entrySet()) {
            path.append(separador).append(e.getKey()).append('=').append(UriEscape.dataString(e.getValue()));
            separador = '&';
        }
        return InnpackRespuestas.reenviar(api.get(usuario, path.toString()), mapper);
    }

    /** certificadosLiberacion.calidadPdf.descargar → GET api/certificados-liberacion/{folio}/calidad-pdf */
    public BridgeResult calidadPdfDescargar(ObjectNode payload, SessionUser usuario) {
        Long folio = entero(data(payload).get("folio"));
        if (folio == null || folio <= 0) {
            return BridgeResult.error(MENSAJE_FOLIO);
        }
        BridgeResult upstream = InnpackRespuestas.reenviar(api.get(usuario, BASE + "/" + folio + "/calidad-pdf"), mapper);
        if (!upstream.ok()) {
            return upstream;
        }
        JsonNode pdf = upstream.data() instanceof JsonNode n && n.isObject() ? n : null;
        String base64 = pdf != null && pdf.get("base64") != null && pdf.get("base64").isString() ? pdf.get("base64").asString().trim() : "";
        if (base64.isEmpty()) {
            return BridgeResult.error(MENSAJE_SIN_CONTENIDO);
        }
        if (base64.length() > MAX_BASE64_CHARS) {
            return BridgeResult.error(MENSAJE_TAMANO);
        }
        if (!esPdf(base64)) {
            return BridgeResult.error(MENSAJE_NO_PDF);
        }
        String nombre = pdf.get("fileName") != null && pdf.get("fileName").isString() ? pdf.get("fileName").asString() : "";
        ObjectNode salida = mapper.createObjectNode();
        salida.put("fileName", nombreArchivoSeguro(nombre, "CertificadoCalidad_" + folio + ".pdf"));
        salida.put("base64", base64);
        return BridgeResult.ok(salida);
    }

    /** Firma "%PDF-" en los primeros bytes (se decodifican solo los 8 primeros caracteres base64). */
    public static boolean esPdf(String base64) {
        if (base64.length() < 8) {
            return false;
        }
        try {
            byte[] inicio = Base64.getDecoder().decode(base64.substring(0, 8));
            return new String(inicio, StandardCharsets.ISO_8859_1).startsWith("%PDF-");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Solo el nombre base (sin rutas), sin caracteres de control ni inválidos en Windows, sin marcas
     * Unicode bidi, largo acotado, siempre terminado en .pdf. Vacío → nombre por defecto.
     */
    public static String nombreArchivoSeguro(String nombre, String porDefecto) {
        String n = nombre == null ? "" : nombre;
        int corte = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        n = corte >= 0 ? n.substring(corte + 1) : n;
        n = n.replaceAll("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]", "");
        n = n.replaceAll("[\\x00-\\x1f\\x7f<>:\"|?*]", "_");
        n = n.replaceAll("^[\\s.]+|[\\s.]+$", "");
        if (n.isEmpty() || n.equalsIgnoreCase("pdf") || n.equalsIgnoreCase(".pdf")) {
            n = porDefecto;
        }
        if (!n.toLowerCase().endsWith(".pdf")) {
            n += ".pdf";
        }
        if (n.length() > 150) {
            n = n.substring(0, 146) + ".pdf";
        }
        if (n.matches("(?i)^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*|$)")) {
            n = "_" + n;
        }
        return n;
    }

    private JsonNode data(ObjectNode payload) {
        JsonNode data = payload.get("data");
        return data != null && data.isObject() ? data : mapper.createObjectNode();
    }

    /** GetString de Photino: string o número → texto; cualquier otro tipo (o ausente) → "". */
    private static String texto(JsonNode nodo) {
        return nodo != null && (nodo.isString() || nodo.isNumber()) ? nodo.asString() : "";
    }

    /** GetLong de Photino: número entero int64, o string convertible (long.TryParse); si no → null. */
    private static Long entero(JsonNode nodo) {
        if (nodo == null) {
            return null;
        }
        if (nodo.isNumber()) {
            return nodo.isIntegralNumber() && nodo.canConvertToLong() ? nodo.asLong() : null;
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
}
