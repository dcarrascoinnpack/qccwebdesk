package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.FpsApiClient;
import cl.faret.qccweb.upstream.InnpackApiClient;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Columna "Liberación Calidad" de No Conformidades (Fase 3v) — port de LiberacionCalidadHandler de Photino 1.8.14
 * (fd7f076): GET {fps-api}/liberaciones/inspectores?nps=... en tandas de 300 (límite de fps-api), solo NP numéricas y
 * sin duplicados. SOLO LECTURA. El cruce NP + código lo hace el frontend (window.LiberacionCalidad).
 *
 * Seguridad transparente de la web: fps-api arma una consulta SAP (OPENQUERY) interpolando las NP, así que el gateway
 * solo reenvía dígitos ASCII (≤ 15); tope total de NP por llamada; solo filas de las NP pedidas y de la empresa de la
 * sesión (el mismo filtro que aplica el frontend, `Empresa.toUpperCase().includes(empresa)`); cada fila reproyectada a
 * los 7 campos de fps-api con sus tipos. La API key de fps-api es del servidor y nunca llega al navegador.
 */
public class LiberacionCalidadBridgeHandler {

    /** Mismos textos que LiberacionCalidadHandler de Photino. */
    public static final String MENSAJE_NO_CONFIGURADA = "fps-api no está configurada en este equipo.";
    static final String MENSAJE_FPS_FALLO = "No fue posible consultar las liberaciones en fps-api.";
    static final String MENSAJE_PARAMETRO = "Parámetro de filtro inválido.";
    static final int MAX_NPS_POR_LLAMADA = 300;
    /** Web: tope de NP distintas por acción (10 llamadas a fps-api); por sobre esto, el mismo error que un fallo. */
    static final int MAX_NPS_TOTAL = 3000;
    private static final Pattern NP = Pattern.compile("[0-9]{1,15}");
    private static final String[] CAMPOS_TEXTO = {"Empresa", "CodigoArticulo", "Inspector", "UltimaLiberacion"};
    private static final String[] CAMPOS_ENTEROS = {"UltimoFolio", "Liberaciones"};

    private final FpsApiClient fps;
    private final ObjectMapper mapper;

    public LiberacionCalidadBridgeHandler(FpsApiClient fps, ObjectMapper mapper) {
        this.fps = fps;
        this.mapper = mapper;
    }

    /** liberacionCalidad.inspectores {data:{nps:[...]}} → arreglo de filas de fps-api (data). */
    public BridgeResult inspectores(ObjectNode payload, SessionUser usuario) {
        if (!fps.configurada()) {
            return BridgeResult.error(MENSAJE_NO_CONFIGURADA);
        }
        Set<String> nps = new LinkedHashSet<>();
        JsonNode data = payload.get("data");
        JsonNode arr = data != null && data.isObject() ? data.get("nps") : null;
        if (arr != null && arr.isArray()) {
            for (JsonNode el : arr) {
                String np;
                if (el.isIntegralNumber()) {
                    np = el.asString();
                } else if (el.isString()) {
                    np = el.asString().trim();
                } else if (el.isNumber() || el.isNull()) {
                    continue; // número no entero o null: Photino lo descarta (no son solo dígitos)
                } else {
                    return BridgeResult.error(MENSAJE_PARAMETRO);
                }
                if (NP.matcher(np).matches()) {
                    nps.add(np);
                }
            }
        }
        if (nps.size() > MAX_NPS_TOTAL) {
            return BridgeResult.error(MENSAJE_FPS_FALLO);
        }

        String empresa = usuario.empresa() == null ? "" : usuario.empresa().toUpperCase(Locale.ROOT);
        ArrayNode filas = mapper.createArrayNode();
        List<String> lista = new ArrayList<>(nps);
        for (int i = 0; i < lista.size(); i += MAX_NPS_POR_LLAMADA) {
            List<String> lote = lista.subList(i, Math.min(lista.size(), i + MAX_NPS_POR_LLAMADA));
            InnpackApiClient.Respuesta r = fps.get("liberaciones/inspectores?nps=" + String.join("%2C", lote));
            if (r.status() < 200 || r.status() > 299 || r.body() == null) {
                return BridgeResult.error(MENSAJE_FPS_FALLO);
            }
            JsonNode cuerpo;
            try {
                cuerpo = mapper.readTree(r.body());
            } catch (RuntimeException e) {
                return BridgeResult.error(MENSAJE_FPS_FALLO);
            }
            JsonNode d = cuerpo == null ? null : cuerpo.get("data");
            if (d == null || !d.isArray()) {
                continue;
            }
            for (JsonNode fila : d) {
                ObjectNode limpia = reproyectar(fila, nps, empresa);
                if (limpia != null) {
                    filas.add(limpia);
                }
            }
        }
        return BridgeResult.ok(filas);
    }

    /** Fila con los 7 campos de fps-api y sus tipos, solo si es de una NP pedida y de la empresa de la sesión. */
    private ObjectNode reproyectar(JsonNode fila, Set<String> nps, String empresa) {
        if (fila == null || !fila.isObject()) {
            return null;
        }
        JsonNode np = fila.get("Np");
        String npTexto = np == null ? null : (np.isIntegralNumber() ? np.asString() : np.isString() ? np.asString().trim() : null);
        JsonNode emp = fila.get("Empresa");
        if (npTexto == null || !nps.contains(npTexto) || emp == null || !emp.isString()
                || !emp.asString().toUpperCase(Locale.ROOT).contains(empresa)) {
            return null;
        }
        ObjectNode limpia = mapper.createObjectNode();
        limpia.set("Np", np);
        for (String campo : CAMPOS_TEXTO) {
            JsonNode v = fila.get(campo);
            if (v != null && v.isString()) {
                limpia.put(campo, v.asString());
            } else {
                limpia.putNull(campo);
            }
        }
        for (String campo : CAMPOS_ENTEROS) {
            JsonNode v = fila.get(campo);
            if (v != null && v.isIntegralNumber()) {
                limpia.set(campo, v);
            } else {
                limpia.putNull(campo);
            }
        }
        return limpia;
    }
}
