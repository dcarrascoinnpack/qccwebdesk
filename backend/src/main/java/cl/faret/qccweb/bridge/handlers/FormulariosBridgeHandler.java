package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Formularios" (INNPACK; en Photino también Faret) — SOLO LECTURA. Port de Photino 1.8.15 (9e1b556)
 * src/Backend/Modules/Formularios/FormulariosHandler.cs + InnpackFormulariosApiService: formularios de
 * LogisticControlCenter (control_bins) leídos vía api/formularios. Dato compartido INNPACK/FARET: la API no filtra por
 * empresa y Photino no manda ninguna, así que aquí tampoco hay IdentityOverride.
 *
 * Contrato igual que Photino: `tipo` es una lista cerrada de 4 valores del select de la vista → segmento de ruta (nada del
 * navegador se concatena a la URL sin pasar por el mapa); los 6 filtros viajan solo si no están en blanco, con trim y
 * escapados como Uri.EscapeDataString; `detalle` no existe para revisionCamionJornada ("Este formulario no tiene detalle")
 * y exige id entero positivo. Seguridad transparente de la web (mismo criterio que los demás módulos): bool/objeto/array en
 * un filtro → "Parámetro de filtro inválido." (regla 2e); fechas con el formato real del flatpickr (AAAA-MM-DD); `estado`
 * restringido a las opciones del select; textos sin caracteres de control y con largo acotado.
 *
 * formularios.abrirPdf: en Photino abre la URL con el visor del sistema (Process.Start) y solo acepta https hacia
 * solicitudes.faret.cl. En la web el gateway valida lo mismo (y además: sin credenciales en la URL, puerto por defecto) y
 * responde {abierto:true, url}; la pestaña nueva la abre el navegador (web-bridge.js), sin pasar por la API ni por el
 * servidor. Diferencia de contrato: Photino responde solo {abierto:true}; el controller ignora data.
 */
public class FormulariosBridgeHandler {

    public static final String MENSAJE_TIPO = "Formulario no soportado";
    public static final String MENSAJE_SIN_DETALLE = "Este formulario no tiene detalle";
    public static final String MENSAJE_ID = "ID de registro inválido";
    public static final String MENSAJE_URL = "URL de PDF inválida";
    public static final String MENSAJE_FILTRO_INVALIDO = "Parámetro de filtro inválido.";
    public static final String MENSAJE_ESTADO_INVALIDO = "Filtro de estado inválido.";
    public static final String MENSAJE_FILTRO_LARGO = "El filtro supera el máximo de 200 caracteres.";
    public static final int MAX_FILTRO = 200;

    /** Valor del <select id="fm-tipo"> de Photino → segmento de ruta de la API (lista cerrada, igual que el handler C#). */
    static final Map<String, String> TIPOS = tipos();
    /** Único tipo sin endpoint de detalle (revision-camion-jornada no tiene ítems). */
    static final String TIPO_SIN_DETALLE = "revision-camion-jornada";
    /** Orden en que InnpackFormulariosApiService arma la query (CamposFiltro del handler C#). */
    static final List<String> FILTROS = List.of("fechaDesde", "fechaHasta", "patente", "conductor", "responsable", "estado");
    /** Opciones no vacías del <select id="fm-estado"> ("" = Todos los estados, se omite). */
    static final Set<String> ESTADOS = Set.of("guardado");
    /** Los PDF viven en la app web de formularios: solo https hacia ese host exacto. */
    static final String HOST_PDF = "solicitudes.faret.cl";
    private static final String BASE = "/api/formularios";
    private static final Pattern FECHA_ISO = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");

    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public FormulariosBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    private static Map<String, String> tipos() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("inspeccionesVehiculares", "inspecciones-vehiculares");
        m.put("revisionCamionJornada", "revision-camion-jornada");
        m.put("checklistBodegaCajas", "checklist-bodega-cajas");
        m.put("revisionBodegaOficinas", "revision-bodega-oficinas");
        return java.util.Collections.unmodifiableMap(m);
    }

    /** formularios.list → GET api/formularios/{tipo}[?fechaDesde=..&fechaHasta=..&patente=..&conductor=..&responsable=..&estado=..] */
    public BridgeResult list(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        String tipo = TIPOS.get(texto(data.get("tipo")));
        if (tipo == null) {
            return BridgeResult.error(MENSAJE_TIPO);
        }
        StringBuilder path = new StringBuilder(BASE).append('/').append(tipo);
        char separador = '?';
        for (String filtro : FILTROS) {
            JsonNode nodo = data.get(filtro);
            if (nodo != null && !nodo.isNull() && !nodo.isString() && !nodo.isNumber()) {
                return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
            }
            String valor = texto(nodo).trim();
            if (valor.isEmpty()) {
                continue;
            }
            String error = validarFiltro(filtro, valor);
            if (error != null) {
                return BridgeResult.error(error);
            }
            path.append(separador).append(filtro).append('=').append(UriEscape.dataString(valor));
            separador = '&';
        }
        return InnpackRespuestas.reenviar(api.get(usuario, path.toString()), mapper);
    }

    /** formularios.detalle → GET api/formularios/{tipo}/{id} (no existe para revisionCamionJornada). */
    public BridgeResult detalle(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        String tipo = TIPOS.get(texto(data.get("tipo")));
        if (tipo == null) {
            return BridgeResult.error(MENSAJE_TIPO);
        }
        if (tipo.equals(TIPO_SIN_DETALLE)) {
            return BridgeResult.error(MENSAJE_SIN_DETALLE);
        }
        Integer id = entero(data.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_ID);
        }
        return InnpackRespuestas.reenviar(api.get(usuario, BASE + "/" + tipo + "/" + id), mapper);
    }

    /** formularios.abrirPdf: valida la URL como Photino (https + host exacto) y la devuelve para que el navegador la abra. */
    public BridgeResult abrirPdf(ObjectNode payload, SessionUser usuario) {
        String url = urlPdfValida(texto(data(payload).get("url")));
        if (url == null) {
            return BridgeResult.error(MENSAJE_URL);
        }
        ObjectNode salida = mapper.createObjectNode();
        salida.put("abierto", true);
        salida.put("url", url);
        return BridgeResult.ok(salida);
    }

    /**
     * Uri.TryCreate(Absolute) + Scheme == https + Host == solicitudes.faret.cl (sin distinguir mayúsculas) del handler C#,
     * más (web): sin credenciales en la URL, puerto por defecto y sin caracteres de control. Devuelve la URL normalizada
     * (ASCII) o null si no es válida.
     */
    public static String urlPdfValida(String url) {
        if (url == null || url.isBlank() || url.length() > 2048 || CONTROL.matcher(url).find()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return null;
        }
        if (!uri.isAbsolute() || uri.getScheme() == null || !uri.getScheme().toLowerCase(Locale.ROOT).equals("https")
                || uri.getHost() == null || !uri.getHost().equalsIgnoreCase(HOST_PDF)
                || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return null;
        }
        return uri.toASCIIString();
    }

    private static String validarFiltro(String filtro, String valor) {
        if (filtro.equals("fechaDesde") || filtro.equals("fechaHasta")) {
            if (!FECHA_ISO.matcher(valor).matches()) {
                return "La fecha " + filtro + " no es válida (formato AAAA-MM-DD).";
            }
            try {
                LocalDate.parse(valor);
            } catch (DateTimeParseException e) {
                return "La fecha " + filtro + " no es válida (formato AAAA-MM-DD).";
            }
            return null;
        }
        if (filtro.equals("estado")) {
            return ESTADOS.contains(valor) ? null : MENSAJE_ESTADO_INVALIDO;
        }
        if (valor.length() > MAX_FILTRO) {
            return MENSAJE_FILTRO_LARGO;
        }
        if (CONTROL.matcher(valor).find()) {
            return MENSAJE_FILTRO_INVALIDO;
        }
        return null;
    }

    private JsonNode data(ObjectNode payload) {
        JsonNode data = payload.get("data");
        return data != null && data.isObject() ? data : mapper.createObjectNode();
    }

    /** GetString de Photino: string o número → texto; cualquier otro tipo (o ausente) → "". */
    private static String texto(JsonNode nodo) {
        return nodo != null && (nodo.isString() || nodo.isNumber()) ? nodo.asString() : "";
    }

    /** int.TryParse(GetString(..)) de Photino: entero int32 o string convertible; si no → null. */
    private static Integer entero(JsonNode nodo) {
        String s = texto(nodo).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
