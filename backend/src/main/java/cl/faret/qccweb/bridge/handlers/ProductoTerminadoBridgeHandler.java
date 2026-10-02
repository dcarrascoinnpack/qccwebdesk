package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import java.util.regex.Pattern;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Producto Terminado" (INNPACK). Port de Photino
 * src/Backend/Modules/ProductoTerminado/ProductoTerminadoHandler.cs + InnpackProductoTerminadoApiService.
 *
 * "empresa" NO se lee del payload: en Photino cada módulo frontend la manda hardcodeada
 * ("INNPACK" / "FARET") y los modelos la documentan como "scope obligatorio, no un filtro que el
 * usuario pueda elegir"; la API la valida pero no la liga al JWT. Aquí sale exclusivamente de la
 * sesión (SessionUser.empresa(), además de IdentityOverride en la regla) y se valida como Photino.
 *
 * Fase 4f: `eliminar` y `actualizarFecha` habilitadas. `usuarioNombre` (actualizarFecha) también sale
 * SIEMPRE de sesión (Photino lo toma de `sessionStorage`, no es un valor del servidor, pero el
 * gateway igual lo fija — ni siquiera es clave aceptada del payload, mismo criterio que el resto del
 * sistema). `id` se rechaza ANTES de llamar a la API si falta, igual que el handler C# de Photino.
 */
public class ProductoTerminadoBridgeHandler {

    static final String MENSAJE_EMPRESA = "Falta indicar la empresa (INNPACK o FARET)";
    static final String MENSAJE_ID = "Falta el id de la inspección";
    static final String MENSAJE_CAMPO_NO_PERMITIDO = "Campo no permitido: ";
    static final String MENSAJE_FECHA_INVALIDA = "La fecha no es válida (formato AAAA-MM-DD).";
    static final String MENSAJE_HORA_INVALIDA = "La hora no es válida (formato HH:mm).";
    private static final Set<String> EMPRESAS = Set.of("INNPACK", "FARET");
    private static final String BASE = "/api/producto-terminado";
    private static final Set<String> CLAVES_ELIMINAR = Set.of("id");
    private static final Set<String> CLAVES_ACTUALIZAR_FECHA = Set.of("id", "fechaRegistro", "horaRegistro");
    /** {@code <input type="date">}/{@code <input type="time">} de Photino. */
    private static final Pattern FECHA_ISO = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern HORA_ISO = Pattern.compile("[0-9]{2}:[0-9]{2}(:[0-9]{2})?");
    private static final String[] FILTROS_TEXTO_1 = {"fechaDesde", "fechaHasta", "np", "codigoProducto", "proceso", "maquina", "turno"};

    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public ProductoTerminadoBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /** productoTerminado.filtros → GET api/producto-terminado/filtros?empresa=.. */
    public BridgeResult filtros(ObjectNode payload, SessionUser usuario) {
        String empresa = empresa(usuario);
        if (empresa == null) {
            return BridgeResult.error(MENSAJE_EMPRESA);
        }
        return InnpackRespuestas.reenviar(api.get(usuario, BASE + "/filtros?empresa=" + UriEscape.dataString(empresa)), mapper);
    }

    /** productoTerminado.resumen → GET api/producto-terminado/resumen?empresa=..[&filtros] */
    public BridgeResult resumen(ObjectNode payload, SessionUser usuario) {
        String empresa = empresa(usuario);
        if (empresa == null) {
            return BridgeResult.error(MENSAJE_EMPRESA);
        }
        return InnpackRespuestas.reenviar(api.get(usuario, BASE + "/resumen?" + query(empresa, data(payload))), mapper);
    }

    /** productoTerminado.list → GET api/producto-terminado?empresa=..[&filtros]&page=..&limit=.. (defaults 1/50). */
    public BridgeResult list(ObjectNode payload, SessionUser usuario) {
        String empresa = empresa(usuario);
        if (empresa == null) {
            return BridgeResult.error(MENSAJE_EMPRESA);
        }
        JsonNode data = data(payload);
        Integer page = entero(data.get("page"));
        Integer limit = entero(data.get("limit"));
        String path = BASE + "?" + query(empresa, data) + "&page=" + (page != null ? page : 1) + "&limit=" + (limit != null ? limit : 50);
        return InnpackRespuestas.reenviar(api.get(usuario, path), mapper);
    }

    /** productoTerminado.detalle → GET api/producto-terminado/{id}?empresa=.. (id ≤ 0 → error, como Photino). */
    public BridgeResult detalle(ObjectNode payload, SessionUser usuario) {
        String empresa = empresa(usuario);
        if (empresa == null) {
            return BridgeResult.error(MENSAJE_EMPRESA);
        }
        Integer id = entero(data(payload).get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_ID);
        }
        return InnpackRespuestas.reenviar(api.get(usuario, BASE + "/" + id + "?empresa=" + UriEscape.dataString(empresa)), mapper);
    }

    /** productoTerminado.exportarDetalle → GET api/producto-terminado/exportar-detalle?empresa=..[&filtros] */
    public BridgeResult exportarDetalle(ObjectNode payload, SessionUser usuario) {
        String empresa = empresa(usuario);
        if (empresa == null) {
            return BridgeResult.error(MENSAJE_EMPRESA);
        }
        return InnpackRespuestas.reenviar(api.get(usuario, BASE + "/exportar-detalle?" + query(empresa, data(payload))), mapper);
    }

    /**
     * productoTerminado.eliminar → DELETE api/producto-terminado/{id}?empresa=.. (Fase 4f), igual que Photino:
     * elimina una inspección (borrado lógico). `id` se rechaza ANTES de llamar a la API si falta. La API SÍ
     * verifica existencia por sí misma (devuelve 404 real con "No se encontró la inspección solicitada").
     */
    public BridgeResult eliminar(ObjectNode payload, SessionUser usuario) {
        String empresa = empresa(usuario);
        if (empresa == null) {
            return BridgeResult.error(MENSAJE_EMPRESA);
        }
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_ELIMINAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer id = entero(data.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_ID);
        }
        return InnpackRespuestas.reenviar(api.delete(usuario, BASE + "/" + id + "?empresa=" + UriEscape.dataString(empresa)), mapper);
    }

    /** Recurso auditado: "productoTerminado:&lt;id&gt;:eliminar". */
    public static String recursoEliminar(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer id = data == null ? null : entero(data.get("id"));
        return "productoTerminado:" + (id != null ? id : "?") + ":eliminar";
    }

    /**
     * productoTerminado.actualizarFecha → PUT api/producto-terminado/{id}/fecha {empresa, fechaRegistro,
     * horaRegistro, usuarioNombre} (Fase 4f), igual que Photino: corrige fecha/hora de registro con auditoría (el
     * resto de la inspección sigue siendo de solo lectura, exclusivo de la app móvil). `id` se rechaza ANTES de
     * llamar a la API si falta. `fechaRegistro`/`horaRegistro` restringidos al formato real de los
     * {@code <input type="date">}/{@code <input type="time">} de Photino (`horaRegistro` opcional, en blanco si no
     * se indica).
     */
    public BridgeResult actualizarFecha(ObjectNode payload, SessionUser usuario) {
        String empresa = empresa(usuario);
        if (empresa == null) {
            return BridgeResult.error(MENSAJE_EMPRESA);
        }
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_ACTUALIZAR_FECHA.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer id = entero(data.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_ID);
        }
        String fechaRegistro = texto(data.get("fechaRegistro"));
        if (!fechaRegistro.isEmpty() && !FECHA_ISO.matcher(fechaRegistro).matches()) {
            return BridgeResult.error(MENSAJE_FECHA_INVALIDA);
        }
        String horaRegistro = texto(data.get("horaRegistro"));
        if (!horaRegistro.isEmpty() && !HORA_ISO.matcher(horaRegistro).matches()) {
            return BridgeResult.error(MENSAJE_HORA_INVALIDA);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("empresa", empresa);
        cuerpo.put("fechaRegistro", fechaRegistro);
        cuerpo.put("horaRegistro", horaRegistro);
        cuerpo.put("usuarioNombre", autorDeSesion(usuario));
        return InnpackRespuestas.reenviar(api.putJson(usuario, BASE + "/" + id + "/fecha", cuerpo), mapper);
    }

    /** Recurso auditado: "productoTerminado:&lt;id&gt;:fecha". */
    public static String recursoActualizarFecha(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer id = data == null ? null : entero(data.get("id"));
        return "productoTerminado:" + (id != null ? id : "?") + ":fecha";
    }

    /** GetString de Photino: string o número → texto; null/ausente → "". */
    private static String texto(JsonNode nodo) {
        return nodo != null && (nodo.isString() || nodo.isNumber()) ? nodo.asString() : "";
    }

    private static String nombreCampoSeguro(String clave) {
        return clave != null && clave.matches("[A-Za-z0-9_]{1,40}") ? clave : "?";
    }

    /** Mismo criterio que la sesión C# de Photino (nombre completo; si no, el código). */
    private static String autorDeSesion(SessionUser usuario) {
        String nombre = usuario.nombreCompleto();
        return nombre != null && !nombre.isBlank() ? nombre : usuario.codigoUsuario();
    }

    /** Empresa de la SESIÓN (nunca del payload), validada igual que Photino/la API. */
    private static String empresa(SessionUser usuario) {
        String e = usuario.empresa();
        return e != null && EMPRESAS.contains(e) ? e : null;
    }

    private JsonNode data(ObjectNode payload) {
        JsonNode data = payload.get("data");
        return data != null && data.isObject() ? data : mapper.createObjectNode();
    }

    /**
     * BuildQuery de InnpackProductoTerminadoApiService: empresa + opcionales no vacíos en orden fijo
     * fechaDesde, fechaHasta, np, codigoProducto, proceso, maquina, turno, inspectorId, resultado,
     * origenId. Texto: string o número (→ texto); cualquier otro tipo → "" (se omite), como el
     * GetString de Photino. Enteros: entero o string numérica; si no → se omiten.
     */
    private static String query(String empresa, JsonNode data) {
        StringBuilder sb = new StringBuilder("empresa=").append(UriEscape.dataString(empresa));
        for (String filtro : FILTROS_TEXTO_1) {
            agregarTexto(sb, filtro, data.get(filtro));
        }
        agregarEntero(sb, "inspectorId", data.get("inspectorId"));
        agregarTexto(sb, "resultado", data.get("resultado"));
        agregarEntero(sb, "origenId", data.get("origenId"));
        return sb.toString();
    }

    private static void agregarTexto(StringBuilder sb, String nombre, JsonNode nodo) {
        if (nodo == null || !(nodo.isString() || nodo.isNumber())) {
            return;
        }
        String valor = nodo.asString();
        if (!valor.isBlank()) {
            sb.append('&').append(nombre).append('=').append(UriEscape.dataString(valor));
        }
    }

    private static void agregarEntero(StringBuilder sb, String nombre, JsonNode nodo) {
        Integer valor = entero(nodo);
        if (valor != null) {
            sb.append('&').append(nombre).append('=').append(valor);
        }
    }

    /** GetInt de Photino: número entero int32, o string convertible (int.TryParse); si no → null. */
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
