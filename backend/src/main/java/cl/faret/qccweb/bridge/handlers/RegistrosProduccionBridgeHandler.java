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
 * Módulo "Inspecciones Producción" (Registros Producción, INNPACK). Port de Photino
 * src/Backend/Modules/RegistrosProduccion/RegistrosProduccionHandler.cs +
 * InnpackRegistrosProduccionApiService. Es calco de {@link DashboardBridgeHandler}: en Photino
 * ambos handlers son idénticos salvo la ruta de la API (y que, a diferencia de Dashboard, las 5
 * sentencias de este módulo en la API SÍ filtran area='PRODUCCION'), y se mantienen como clases
 * separadas igual que allá.
 *
 * Fase 4b: validarRegistro/rechazarRegistro/eliminarRegistro (individuales). validarTodo/rechazarTodo
 * quedan deliberadamente fuera por ahora (mismo criterio que Dashboard).
 */
public class RegistrosProduccionBridgeHandler {

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

    public RegistrosProduccionBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /** registrosProduccion.obtenerFiltros → GET api/registros-produccion/filtros (sin parámetros). */
    public BridgeResult obtenerFiltros(ObjectNode payload, SessionUser usuario) {
        return InnpackRespuestas.reenviar(api.get(usuario, "/api/registros-produccion/filtros"), mapper);
    }

    /**
     * registrosProduccion.obtenerResumen → GET api/registros-produccion/resumen?fechaDesde=..
     * &fechaHasta=..&inspector=..&turno=..&proceso=.. (los 5 siempre presentes, en ese orden,
     * escapados como Uri.EscapeDataString). Igual que Photino: solo se leen desde "data"; ausente o
     * null → ""; un valor que no es string (número, bool, objeto) hace fallar a Photino (GetString)
     * → error.
     */
    public BridgeResult obtenerResumen(ObjectNode payload, SessionUser usuario) {
        JsonNode data = payload.get("data");
        StringBuilder query = new StringBuilder("/api/registros-produccion/resumen");
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

    /** Fase 4b: huella por fila (id → estadoValidacion) de "ultimosRegistros". Ver {@link DashboardBridgeHandler}. */
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
        return "registrosProduccion-registro:" + id;
    }

    /**
     * registrosProduccion.validarRegistro → PUT api/registros-produccion/{id}/validar {} (Fase 4b), igual
     * que Photino para el usuario: UPDATE de estado_validacion='VALIDADO' filtrado a area='PRODUCCION', sin
     * autor real (usuario_validacion queda hardcodeado 'SUPERVISOR' en la API).
     *
     * Seguridad transparente: lista blanca {id}; exige haber visto el registro en una lista cargada en
     * esta sesión (registrosProduccion.obtenerResumen); candado por id para doble clic. Sin detección de
     * conflicto (no hay endpoint de detalle por id para releer antes de escribir).
     */
    public BridgeResult validarRegistro(ObjectNode payload, SessionUser usuario) {
        return escritura(payload, usuario, "/api/registros-produccion/%d/validar");
    }

    /** registrosProduccion.rechazarRegistro → PUT api/registros-produccion/{id}/rechazar {} (Fase 4b). */
    public BridgeResult rechazarRegistro(ObjectNode payload, SessionUser usuario) {
        return escritura(payload, usuario, "/api/registros-produccion/%d/rechazar");
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
                LecturasDeSesion.olvidar(recursoLectura(id));
            }
            return resultado;
        }
    }

    /**
     * registrosProduccion.eliminarRegistro → DELETE api/registros-produccion/{id} (Fase 4b), igual que
     * Photino: borrado lógico (eliminado=1) filtrado a area='PRODUCCION', idempotente.
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
            BridgeResult resultado = InnpackRespuestas.reenviar(
                    api.delete(usuario, "/api/registros-produccion/" + id), mapper);
            if (resultado.ok()) {
                LecturasDeSesion.olvidar(recursoLectura(id));
            }
            return resultado;
        }
    }

    /** Recurso auditado: "registrosProduccion:<id>:<accion>". */
    public static String recursoRegistro(ObjectNode payload, String accion) {
        Integer id = entero(payload.get("id"));
        return "registrosProduccion:" + (id != null && id > 0 ? id : "?") + ":" + accion;
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
