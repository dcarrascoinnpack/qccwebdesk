package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.FaretApiClient;
import cl.faret.qccweb.upstream.FaretRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lecturas de la pantalla de inicio FARET (Fase 6b) — SOLO LECTURA. Port de Photino 1.8.15 (9e1b556)
 * src/Backend/Modules/Faret/FaretHandler.cs (+ servicios FaretApi): cada acción llama a UNA de las tres APIs FARET.
 *
 * Contrato igual que Photino: las acciones `faret.*` llegan con el payload PLANO (filtros en la raíz, no en `data`); cada
 * filtro viaja solo si no está en blanco, en el orden fijo de la query de Photino y escapado como Uri.EscapeDataString;
 * la respuesta de QualityControlFaret.Api (ApiResponse {success,data,message}) se desenvuelve (TryUnwrapApiResponse).
 * Seguridad transparente de la web (mismo criterio que Formularios): lista cerrada de claves (el resto del payload se
 * ignora, nada del navegador se concatena a la URL salvo los valores validados); bool/objeto/array en un filtro →
 * "Parámetro de filtro inválido." (regla 2e); fechas AAAA-MM-DD; textos con trim, sin caracteres de control y ≤ 200;
 * page/pageSize enteros positivos (como Photino, un valor no entero o ≤ 0 simplemente no viaja) y pageSize acotado a 200,
 * el tope real de la API (ImportacionesService.GetPncListAsync).
 *
 * La sesión FARET es obligatoria (ActionPolicy por empresa): faret.data.list, faret.indicadoresCalidad.resumen y
 * faret.talleresExternos.resumen van a QualityControlFaret.Api con el Bearer del usuario de la sesión.
 */
public class FaretBridgeHandler {

    public static final String MENSAJE_FILTRO_INVALIDO = FormulariosBridgeHandler.MENSAJE_FILTRO_INVALIDO;
    public static final String MENSAJE_FILTRO_LARGO = FormulariosBridgeHandler.MENSAJE_FILTRO_LARGO;
    public static final String MENSAJE_PAGINACION = "Parámetro de paginación inválido.";
    public static final int MAX_FILTRO = FormulariosBridgeHandler.MAX_FILTRO;
    /** Tope de pageSize de api/importaciones/pnc (ImportacionesService: Math.Min(pageSize, 200)). */
    public static final int MAX_PAGE_SIZE_PNC = 200;
    /** Tope de page: una página mayor no tiene sentido y evita desbordes del offset de la API. */
    public static final int MAX_PAGE = 1_000_000;

    /** Orden de la query de FaretHandler.BuildDataFiltros (page y pageSize se agregan al final, solo en list). */
    static final List<String> FILTROS_DATA = List.of("cliente", "tipoPnc", "nivel", "fechaDesde", "fechaHasta");
    /** FaretHandler.HandleIndicadoresCalidad: solo el período. */
    static final List<String> FILTROS_INDICADORES = List.of("fechaDesde", "fechaHasta");
    /** FaretHandler.BuildTalleresExternosFiltros. */
    static final List<String> FILTROS_TALLERES = List.of("nv", "producto", "cliente", "tallerExterno", "proceso", "responsable",
            "prioridad", "estado", "fechaAsignacionDesde", "fechaAsignacionHasta", "fechaCompromisoDesde", "fechaCompromisoHasta");
    /** Claves de fecha (AAAA-MM-DD) entre todos los filtros anteriores. */
    static final Set<String> FECHAS = Set.of("fechaDesde", "fechaHasta", "fechaAsignacionDesde", "fechaAsignacionHasta",
            "fechaCompromisoDesde", "fechaCompromisoHasta");

    private static final String RUTA_PNC = "/api/importaciones/pnc";
    private static final String RUTA_INDICADORES = "/api/importaciones/pnc/indicadores-calidad";
    private static final String RUTA_TALLERES_RESUMEN = "/api/talleres-externos/resumen";
    private static final Pattern FECHA_ISO = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");

    private final FaretApiClient qualityControl;
    private final ObjectMapper mapper;

    public FaretBridgeHandler(FaretApiClient qualityControl, ObjectMapper mapper) {
        this.qualityControl = qualityControl;
        this.mapper = mapper;
    }

    /** faret.data.list → GET api/importaciones/pnc?cliente&tipoPnc&nivel&fechaDesde&fechaHasta&page&pageSize */
    public BridgeResult dataList(ObjectNode payload, SessionUser usuario) {
        Query filtros = construirQuery(payload, FILTROS_DATA);
        if (filtros.error() != null) {
            return BridgeResult.error(filtros.error());
        }
        StringBuilder query = new StringBuilder(filtros.texto());
        for (String clave : List.of("page", "pageSize")) {
            JsonNode nodo = payload.get(clave);
            if (nodo != null && !nodo.isNull() && !nodo.isString() && !nodo.isNumber()) {
                return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
            }
            Integer valor = enteroPositivo(nodo);
            if (valor == null) {
                continue;
            }
            if (clave.equals("page") && valor > MAX_PAGE) {
                return BridgeResult.error(MENSAJE_PAGINACION);
            }
            if (clave.equals("pageSize")) {
                valor = Math.min(valor, MAX_PAGE_SIZE_PNC);
            }
            query.append(query.isEmpty() ? "" : "&").append(clave).append('=').append(valor);
        }
        return qc(usuario, RUTA_PNC, query);
    }

    /** faret.indicadoresCalidad.resumen → GET api/importaciones/pnc/indicadores-calidad?fechaDesde&fechaHasta */
    public BridgeResult indicadoresCalidad(ObjectNode payload, SessionUser usuario) {
        Query filtros = construirQuery(payload, FILTROS_INDICADORES);
        return filtros.error() != null ? BridgeResult.error(filtros.error()) : qc(usuario, RUTA_INDICADORES, new StringBuilder(filtros.texto()));
    }

    /** faret.talleresExternos.resumen → GET api/talleres-externos/resumen con los 12 filtros. */
    public BridgeResult talleresExternosResumen(ObjectNode payload, SessionUser usuario) {
        Query filtros = construirQuery(payload, FILTROS_TALLERES);
        return filtros.error() != null ? BridgeResult.error(filtros.error()) : qc(usuario, RUTA_TALLERES_RESUMEN, new StringBuilder(filtros.texto()));
    }

    private BridgeResult qc(SessionUser usuario, String ruta, StringBuilder query) {
        String path = query.isEmpty() ? ruta : ruta + "?" + query;
        return FaretRespuestas.desenvolver(qualityControl.get(usuario, path), mapper);
    }

    // ----------------------------------------------------------------------------- filtros

    private record Query(String texto, String error) {}

    /** Arma "k=v&k=v" con los filtros no vacíos, en el orden dado; error = primer filtro inválido. */
    private Query construirQuery(ObjectNode payload, List<String> orden) {
        StringBuilder query = new StringBuilder();
        for (String clave : orden) {
            JsonNode nodo = payload.get(clave);
            if (nodo != null && !nodo.isNull() && !nodo.isString() && !nodo.isNumber()) {
                return new Query(null, MENSAJE_FILTRO_INVALIDO);
            }
            String valor = texto(nodo).trim();
            if (valor.isEmpty()) {
                continue;
            }
            String error = validar(clave, valor);
            if (error != null) {
                return new Query(null, error);
            }
            query.append(query.isEmpty() ? "" : "&").append(clave).append('=').append(UriEscape.dataString(valor));
        }
        return new Query(query.toString(), null);
    }

    private static String validar(String clave, String valor) {
        if (FECHAS.contains(clave)) {
            if (!FECHA_ISO.matcher(valor).matches()) {
                return "La fecha " + clave + " no es válida (formato AAAA-MM-DD).";
            }
            try {
                LocalDate.parse(valor);
            } catch (DateTimeParseException e) {
                return "La fecha " + clave + " no es válida (formato AAAA-MM-DD).";
            }
            return null;
        }
        if (valor.length() > MAX_FILTRO) {
            return MENSAJE_FILTRO_LARGO;
        }
        return CONTROL.matcher(valor).find() ? MENSAJE_FILTRO_INVALIDO : null;
    }

    /** GetString de Photino: string o número → texto; cualquier otro tipo (o ausente) → "". */
    private static String texto(JsonNode nodo) {
        return nodo != null && (nodo.isString() || nodo.isNumber()) ? nodo.asString() : "";
    }

    /** TryGetInt + {@code > 0} de Photino: entero int32 (número entero o string) positivo; si no, null (no viaja). */
    private static Integer enteroPositivo(JsonNode nodo) {
        if (nodo == null || nodo.isNull()) {
            return null;
        }
        Integer valor = null;
        if (nodo.isIntegralNumber() && nodo.canConvertToInt()) {
            valor = nodo.asInt();
        } else if (nodo.isString()) {
            try {
                valor = Integer.valueOf(nodo.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return valor != null && valor > 0 ? valor : null;
    }
}
