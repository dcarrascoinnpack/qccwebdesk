package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.bridge.LecturasDeSesion;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Inspecciones Calidad" (Dashboard Calidad, INNPACK). Port de Photino
 * src/Backend/Modules/Dashboard/DashboardHandler.cs + InnpackDashboardApiService.
 *
 * Fase 4b: validarRegistro/rechazarRegistro/eliminarRegistro (individuales). Fase 4b': validarTodo/
 * rechazarTodo — igual que Photino (sin WHERE en la API: tocan TODA la tabla registros_control, no
 * solo lo filtrado en pantalla; decisión del usuario, paridad exacta con el riesgo aceptado).
 */
public class DashboardBridgeHandler {

    static final String MENSAJE_FILTRO_INVALIDO = "Parámetro de filtro inválido.";
    private static final String[] FILTROS = {"fechaDesde", "fechaHasta", "inspector", "turno", "proceso"};

    static final String MENSAJE_FALTA_ID = "Falta el id del registro.";
    static final String MENSAJE_SIN_LEER = "Actualiza la lista antes de validar, rechazar o eliminar este registro.";
    static final String MENSAJE_CAMPO_NO_PERMITIDO = "Campo no permitido: ";
    private static final Set<String> CLAVES_RAIZ_ESCRITURA = Set.of("action", "id");
    /** Un candado por id: serializa en este gateway los dobles clics sobre el mismo registro. */
    private final ConcurrentHashMap<Integer, Object> candados = new ConcurrentHashMap<>();

    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public DashboardBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /** dashboard.obtenerFiltros → GET api/dashboard/filtros (sin parámetros). */
    public BridgeResult obtenerFiltros(ObjectNode payload, SessionUser usuario) {
        return InnpackRespuestas.reenviar(api.get(usuario, "/api/dashboard/filtros"), mapper);
    }

    /**
     * dashboard.obtenerResumen → GET api/dashboard/resumen?fechaDesde=..&fechaHasta=..&inspector=..
     * &turno=..&proceso=.. (los 5 siempre presentes, en ese orden, escapados como
     * Uri.EscapeDataString). Igual que Photino: solo se leen desde "data"; ausente o null → "";
     * un valor que no es string (número, bool, objeto) hace fallar a Photino (GetString) → error.
     */
    public BridgeResult obtenerResumen(ObjectNode payload, SessionUser usuario) {
        JsonNode data = payload.get("data");
        StringBuilder query = new StringBuilder("/api/dashboard/resumen");
        char separador = '?';
        for (String filtro : FILTROS) {
            String valor = "";
            JsonNode nodo = data != null && data.isObject() ? data.get(filtro) : null;
            if (nodo != null && !nodo.isNull()) {
                if (!nodo.isString()) {
                    return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
                }
                valor = nodo.asString();
            }
            query.append(separador).append(filtro).append('=').append(UriEscape.dataString(valor));
            separador = '&';
        }
        BridgeResult resultado = InnpackRespuestas.reenviar(api.get(usuario, query.toString()), mapper);
        if (resultado.ok()) {
            registrarHuellas(resultado.data());
        }
        return resultado;
    }

    /**
     * Fase 4b: huella por fila (id → estadoValidacion) de "ultimosRegistros" (los 15 más recientes que
     * trae el resumen), para exigir que la sesión haya visto el registro en una lista cargada antes de
     * validar/rechazar/eliminarlo. No hay endpoint de detalle por id en este módulo: a diferencia de
     * Recepción, no se relee el registro antes de escribir (sin detección de conflicto), igual que
     * Photino (la API tampoco la tiene).
     */
    private static void registrarHuellas(Object data) {
        JsonNode d = data instanceof JsonNode n && n.isObject() ? n : null;
        JsonNode filas = d == null ? null : d.get("ultimosRegistros");
        if (filas == null || !filas.isArray()) {
            return;
        }
        for (JsonNode fila : filas) {
            Integer id = entero(fila.get("id"));
            if (id != null && id > 0) {
                LecturasDeSesion.registrar(recursoLectura(id), texto(fila.get("estadoValidacion")));
            }
        }
    }

    static String recursoLectura(int id) {
        return "dashboard-registro:" + id;
    }

    /**
     * dashboard.validarRegistro → PUT api/dashboard/{id}/validar {} (Fase 4b), igual que Photino para el
     * usuario: UPDATE directo de estado_validacion='VALIDADO', sin autor real (usuario_validacion queda
     * hardcodeado 'SUPERVISOR' en la API) ni control de duplicados ni validación de que el id exista.
     *
     * Seguridad transparente: lista blanca {id}; exige haber visto el registro en una lista cargada en
     * esta sesión (dashboard.obtenerResumen); candado por id para doble clic. Sin detección de conflicto
     * (no hay endpoint de detalle por id para releer antes de escribir).
     */
    public BridgeResult validarRegistro(ObjectNode payload, SessionUser usuario) {
        return escritura(payload, usuario, "/api/dashboard/%d/validar");
    }

    /** dashboard.rechazarRegistro → PUT api/dashboard/{id}/rechazar {} (Fase 4b). Mismo criterio que validarRegistro. */
    public BridgeResult rechazarRegistro(ObjectNode payload, SessionUser usuario) {
        return escritura(payload, usuario, "/api/dashboard/%d/rechazar");
    }

    private BridgeResult escritura(ObjectNode payload, SessionUser usuario, String rutaFormato) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ_ESCRITURA.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer id = entero(payload.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_ID);
        }
        if (LecturasDeSesion.huella(recursoLectura(id)) == null) {
            return BridgeResult.error(MENSAJE_SIN_LEER);
        }
        synchronized (candados.computeIfAbsent(id, k -> new Object())) {
            BridgeResult resultado = InnpackRespuestas.reenviar(
                    api.putJson(usuario, String.format(rutaFormato, id), mapper.createObjectNode()), mapper);
            if (resultado.ok()) {
                // La vista recarga la lista enseguida (cargarDatos) y registra la huella nueva.
                LecturasDeSesion.olvidar(recursoLectura(id));
            }
            return resultado;
        }
    }

    /**
     * dashboard.eliminarRegistro → DELETE api/dashboard/{id} (Fase 4b), igual que Photino: borrado
     * lógico (eliminado=1), idempotente. La vista pide confirm() antes de enviar.
     */
    public BridgeResult eliminarRegistro(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ_ESCRITURA.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer id = entero(payload.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_ID);
        }
        if (LecturasDeSesion.huella(recursoLectura(id)) == null) {
            return BridgeResult.error(MENSAJE_SIN_LEER);
        }
        synchronized (candados.computeIfAbsent(id, k -> new Object())) {
            BridgeResult resultado = InnpackRespuestas.reenviar(api.delete(usuario, "/api/dashboard/" + id), mapper);
            if (resultado.ok()) {
                LecturasDeSesion.olvidar(recursoLectura(id));
            }
            return resultado;
        }
    }

    /** Recurso auditado: "dashboard:<id>:<accion>". */
    public static String recursoRegistro(ObjectNode payload, String accion) {
        Integer id = entero(payload.get("id"));
        return "dashboard:" + (id != null && id > 0 ? id : "?") + ":" + accion;
    }

    static final String MENSAJE_CAMPO_NO_PERMITIDO_TODO = "Campo no permitido: ";
    private static final Set<String> CLAVES_RAIZ_TODO = Set.of("action");

    /**
     * dashboard.validarTodo → PUT api/dashboard/validar-todo {} (Fase 4b'), igual que Photino para el
     * usuario: botón "Validar todo" del dashboard, sin confirmación en la vista. La API hace
     * `UPDATE registros_control SET estado_validacion='VALIDADO', ...` SIN WHERE: toca TODA la tabla,
     * no solo lo que el filtro actual muestra en pantalla (hallazgo documentado en la matriz; replicado
     * tal cual por decisión del usuario, paridad exacta con el riesgo aceptado).
     *
     * Seguridad transparente: lista blanca {action} (sin id ni parámetros, como Photino); no hay huella
     * ni candado por id posible (la acción no referencia ningún id). El límite de escrituras por minuto
     * del gateway (EscrituraRateLimiter) es la única fricción adicional frente a Photino.
     */
    public BridgeResult validarTodo(ObjectNode payload, SessionUser usuario) {
        return todo(payload, usuario, "/api/dashboard/validar-todo");
    }

    /** dashboard.rechazarTodo → PUT api/dashboard/rechazar-todo {} (Fase 4b'). Mismo criterio que validarTodo. */
    public BridgeResult rechazarTodo(ObjectNode payload, SessionUser usuario) {
        return todo(payload, usuario, "/api/dashboard/rechazar-todo");
    }

    private BridgeResult todo(ObjectNode payload, SessionUser usuario, String ruta) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ_TODO.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO_TODO + nombreCampoSeguro(clave));
            }
        }
        return InnpackRespuestas.reenviar(api.putJson(usuario, ruta, mapper.createObjectNode()), mapper);
    }

    /** Recurso auditado: "dashboard:todos:<accion>" (sin id: la acción no referencia ninguno en particular). */
    public static String recursoTodo(String accion) {
        return "dashboard:todos:" + accion;
    }

    private static String nombreCampoSeguro(String clave) {
        return clave != null && clave.matches("[A-Za-z0-9_]{1,40}") ? clave : "?";
    }

    /** GetString de Photino: string o número → texto; otro/ausente → "". */
    private static String texto(JsonNode nodo) {
        return nodo != null && (nodo.isString() || nodo.isNumber()) ? nodo.asString() : "";
    }

    /** GetInt de Photino: número entero int32, o string convertible; si no, null. */
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
