package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.FpsApiClient;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.PlanificacionApiClient;
import cl.faret.qccweb.upstream.UriEscape;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Trazabilidad" (INNPACK) — SOLO LECTURA. Port de Photino src/Backend/Modules/Trazabilidad
 * (TrazabilidadHandler / TrazabilidadService / InnpackTrazabilidadApiService).
 *
 * trazabilidad.consultarNp {data:{np}} junta tres fuentes: procesos del NP en Planificación FARET
 * (GET api/plan/vista-planificacion, sin auth, se filtra en memoria por np o nvInn), materiales reales por proceso en
 * fps-api (falla suave: sin materiales) y paletizado del NP en QualityControlInnpack.Api (falla suave: lista vacía).
 * Igual que Photino: Planificación sin configurar NO es error (procesos vacíos, y avisoPlanificacion siempre null).
 *
 * Seguridad transparente de la web: la vista pinta con innerHTML datos de APIs externas, así que todo texto se escapa si
 * trae marcado; solo campos de la lista exacta (nada extra llega al navegador); np acotado y sin controles; los mensajes
 * de Planificación (Photino muestra el cuerpo crudo) se acotan; los ids a FPS van en tandas.
 */
public class TrazabilidadBridgeHandler {

    static final String MENSAJE_NP = "Debes indicar el NP a consultar";
    static final String MENSAJE_NP_INVALIDO = "El NP no es válido.";
    static final String MENSAJE_PLANIFICACION = "No se pudo consultar Planificación FARET";
    static final String MENSAJE_INESPERADA = "Respuesta inesperada de Planificación FARET.";
    static final String MENSAJE_INVALIDA = "Respuesta inválida de Planificación FARET.";
    static final int MAX_NP = 50;
    static final int MAX_MENSAJE = 200;
    static final int TANDA_IDS_FPS = 100;
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final Pattern PELIGROSO_HTML = Pattern.compile("[<>\"&']");

    private final PlanificacionApiClient planificacion;
    private final FpsApiClient fps;
    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public TrazabilidadBridgeHandler(
            PlanificacionApiClient planificacion, FpsApiClient fps, InnpackApiClient api, ObjectMapper mapper) {
        this.planificacion = planificacion;
        this.fps = fps;
        this.api = api;
        this.mapper = mapper;
    }

    public BridgeResult consultarNp(ObjectNode payload, SessionUser usuario) {
        JsonNode data = payload.get("data");
        String np = data != null && data.isObject() ? texto(data.get("np")).trim() : "";
        if (np.isEmpty()) {
            return BridgeResult.error(MENSAJE_NP);
        }
        if (np.length() > MAX_NP || CONTROL.matcher(np).find()) {
            return BridgeResult.error(MENSAJE_NP_INVALIDO);
        }

        ArrayNode procesos = mapper.createArrayNode();
        if (planificacion.configurada()) {
            InnpackApiClient.Respuesta r = planificacion.get("api/plan/vista-planificacion");
            if (r.status() < 200 || r.status() > 299) {
                return BridgeResult.error(mensajePlanificacion(r));
            }
            JsonNode raiz = leer(r.body());
            if (raiz == null) {
                return BridgeResult.error(MENSAJE_INVALIDA);
            }
            if (!raiz.isArray()) {
                return BridgeResult.error(MENSAJE_INESPERADA);
            }
            for (JsonNode fila : raiz) {
                if (!np.equals(texto(fila.get("np"))) && !np.equals(texto(fila.get("nvInn")))) {
                    continue;
                }
                procesos.add(proceso(fila));
            }
            adjuntarMateriales(procesos);
        }

        ObjectNode salida = mapper.createObjectNode();
        salida.put("np", seguro(np));
        salida.set("procesos", procesos);
        salida.set("paletizado", paletizado(usuario, np));
        salida.putNull("avisoPlanificacion");
        return BridgeResult.ok(salida);
    }

    private ObjectNode proceso(JsonNode fila) {
        ObjectNode p = mapper.createObjectNode();
        p.put("id", entero64(fila.get("id")));
        p.put("np", seguro(texto(fila.get("np"))));
        p.put("nvInn", seguro(texto(fila.get("nvInn"))));
        p.put("cant", decimal(fila.get("cant")));
        JsonNode ent = fila.get("ent");
        if (ent != null && ent.isString()) {
            p.put("ent", seguro(ent.asString()));
        } else {
            p.putNull("ent");
        }
        p.put("sec", seguro(texto(fila.get("sec"))));
        p.put("rec", seguro(texto(fila.get("rec"))));
        p.put("est", seguro(texto(fila.get("est"))));
        p.put("item", seguro(texto(fila.get("item"))));
        p.put("itemName", seguro(texto(fila.get("itemName"))));
        p.put("cli", seguro(texto(fila.get("cli"))));
        p.put("proc", seguro(texto(fila.get("proc"))));
        p.put("cantProd", decimal(fila.get("cantProd")));
        JsonNode vel = fila.get("vel");
        if (vel != null && vel.isNumber()) {
            p.put("vel", vel.decimalValue());
        } else {
            p.putNull("vel");
        }
        JsonNode lead = fila.get("leadDias");
        if (lead != null && lead.isIntegralNumber() && lead.canConvertToInt()) {
            p.put("leadDias", lead.asInt());
        } else {
            p.putNull("leadDias");
        }
        p.putArray("materiales");
        return p;
    }

    /** Materiales reales (Tipo='INSUMO') por proceso: falla suave, igual que Photino (los procesos se muestran sin ellos). */
    private void adjuntarMateriales(ArrayNode procesos) {
        if (procesos.isEmpty() || !fps.configurada()) {
            return;
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode p : procesos) {
            ids.add(p.get("id").asLong());
        }
        Map<String, List<ObjectNode>> porProceso = new LinkedHashMap<>();
        List<Long> lista = new ArrayList<>(ids);
        for (int i = 0; i < lista.size(); i += TANDA_IDS_FPS) {
            List<String> tanda = lista.subList(i, Math.min(lista.size(), i + TANDA_IDS_FPS)).stream().map(String::valueOf).toList();
            InnpackApiClient.Respuesta r = fps.get("materiales-por-proceso?ids=" + String.join(",", tanda));
            JsonNode raiz = r.status() >= 200 && r.status() <= 299 ? leer(r.body()) : null;
            JsonNode okProp = raiz == null ? null : raiz.get("ok");
            if (okProp == null || !okProp.isBoolean() || !okProp.asBoolean()) {
                return;
            }
            JsonNode filas = raiz.get("data");
            if (filas != null && filas.isArray()) {
                for (JsonNode f : filas) {
                    ObjectNode m = mapper.createObjectNode();
                    String idProceso = texto(f.get("Id_Proceso"));
                    m.put("idProceso", seguro(idProceso));
                    m.put("itemCode", seguro(texto(f.get("ItemCode"))));
                    m.put("itemName", seguro(texto(f.get("ItemName"))));
                    porProceso.computeIfAbsent(idProceso, k -> new ArrayList<>()).add(m);
                }
            }
        }
        for (JsonNode p : procesos) {
            List<ObjectNode> materiales = porProceso.get(String.valueOf(p.get("id").asLong()));
            if (materiales != null) {
                ((ObjectNode) p).withArray("materiales").addAll(materiales);
            }
        }
    }

    /** api/trazabilidad/paletizado?np= — cualquier fallo o campo con otro tipo → lista vacía (como el catch de Photino). */
    private ArrayNode paletizado(SessionUser usuario, String np) {
        ArrayNode vacia = mapper.createArrayNode();
        InnpackApiClient.Respuesta r = api.get(usuario, "/api/trazabilidad/paletizado?np=" + UriEscape.dataString(np));
        if (r.status() < 200 || r.status() > 299) {
            return vacia;
        }
        JsonNode raiz = leer(r.body());
        JsonNode success = raiz == null ? null : raiz.get("success");
        if (success == null || !success.isBoolean() || !success.asBoolean()) {
            return vacia;
        }
        JsonNode data = raiz.get("data");
        if (data == null || data.isNull() || !data.isArray()) {
            return vacia;
        }
        ArrayNode salida = mapper.createArrayNode();
        try {
            for (JsonNode f : data) {
                if (!f.isObject()) {
                    throw new IllegalArgumentException();
                }
                ObjectNode p = salida.addObject();
                p.put("idPalet", cadena(f, "idPalet", ""));
                p.put("fecha", fecha(f, "fecha"));
                p.put("planta", cadena(f, "planta", ""));
                p.put("npCliente", cadena(f, "npCliente", ""));
                p.put("notaVentaInnpack", cadena(f, "notaVentaInnpack", ""));
                p.put("nombreCliente", cadena(f, "nombreCliente", ""));
                p.put("taller", cadena(f, "taller", ""));
                p.put("tipo", cadena(f, "tipo", ""));
                JsonNode cantidad = campo(f, "cantidad");
                if (cantidad != null && !cantidad.isNumber()) {
                    throw new IllegalArgumentException();
                }
                p.put("cantidad", cantidad == null ? BigDecimal.ZERO : cantidad.decimalValue());
                p.put("descripcion", cadena(f, "descripcion", ""));
                p.put("fechaImpresion", cadena(f, "fechaImpresion", null));
            }
        } catch (IllegalArgumentException e) {
            return vacia;
        }
        return salida;
    }

    private String mensajePlanificacion(InnpackApiClient.Respuesta r) {
        JsonNode raiz = leer(r.body());
        JsonNode m = raiz == null ? null : raiz.get("message");
        if (m != null && m.isString() && !m.asString().isBlank() && m.asString().length() <= MAX_MENSAJE) {
            return seguro(m.asString());
        }
        return MENSAJE_PLANIFICACION;
    }

    private JsonNode leer(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers (GetString/GetLong/GetDecimal de Photino)

    private static String texto(JsonNode nodo) {
        return nodo != null && (nodo.isString() || nodo.isNumber()) ? nodo.asString() : "";
    }

    private static long entero64(JsonNode nodo) {
        return nodo != null && nodo.isIntegralNumber() && nodo.canConvertToLong() ? nodo.asLong() : 0L;
    }

    private static BigDecimal decimal(JsonNode nodo) {
        return nodo != null && nodo.isNumber() ? nodo.decimalValue() : BigDecimal.ZERO;
    }

    /** Propiedad por nombre sin distinguir mayúsculas (PropertyNameCaseInsensitive de Photino en el DTO de paletizado). */
    private static JsonNode campo(JsonNode objeto, String nombre) {
        JsonNode exacto = objeto.get(nombre);
        if (exacto != null) {
            return exacto;
        }
        for (Map.Entry<String, JsonNode> e : objeto.properties()) {
            if (e.getKey().equalsIgnoreCase(nombre)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Campo string del DTO: ausente → porDefecto; null → null; string → valor escapado; otro tipo → inválido. */
    private static String cadena(JsonNode objeto, String nombre, String porDefecto) {
        JsonNode nodo = campo(objeto, nombre);
        if (nodo == null) {
            return porDefecto;
        }
        if (nodo.isNull()) {
            return null;
        }
        if (!nodo.isString()) {
            throw new IllegalArgumentException();
        }
        return seguro(nodo.asString());
    }

    /** DateTime? de Photino: ausente o null → null; texto ISO válido → el mismo texto; otro → inválido. */
    private static String fecha(JsonNode objeto, String nombre) {
        JsonNode nodo = campo(objeto, nombre);
        if (nodo == null || nodo.isNull()) {
            return null;
        }
        if (!nodo.isString()) {
            throw new IllegalArgumentException();
        }
        String v = nodo.asString();
        try {
            OffsetDateTime.parse(v);
        } catch (DateTimeParseException e) {
            try {
                LocalDateTime.parse(v);
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException();
            }
        }
        return v;
    }

    /** La vista usa innerHTML: texto con marcado o comillas se escapa. */
    private static String seguro(String v) {
        if (v == null || !PELIGROSO_HTML.matcher(v).find()) {
            return v;
        }
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
