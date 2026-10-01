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
 * Módulo "Registros de Control" (INNPACK). Port de Photino
 * src/Backend/Modules/RegistrosControl/RegistrosControlHandler.cs +
 * InnpackRegistrosControlApiService.ObtenerRegistrosAsync.
 *
 * Fase 4b: validarRegistro/rechazarRegistro/eliminarRegistro (individuales; este módulo no tiene
 * validarTodo/rechazarTodo ni en Photino ni en la API).
 */
public class RegistrosControlBridgeHandler {

    static final String MENSAJE_FILTRO_INVALIDO = "Parámetro de filtro inválido.";
    private static final String[] FILTROS_TEXTO = {"fechaDesde", "fechaHasta", "np", "turno", "estado"};

    static final String MENSAJE_FALTA_ID = "Falta el id del registro.";
    static final String MENSAJE_SIN_LEER = "Actualiza la lista antes de validar, rechazar o eliminar este registro.";
    static final String MENSAJE_CONFLICTO = "El registro fue modificado por otra persona desde que cargaste la lista. "
            + "Actualízala para ver los cambios.";
    static final String MENSAJE_CAMPO_NO_PERMITIDO = "Campo no permitido: ";
    private static final Set<String> CLAVES_RAIZ_ESCRITURA = Set.of("action", "id");
    /** Un candado por id: serializa en este gateway los dobles clics sobre el mismo registro. */
    private final ConcurrentHashMap<Integer, Object> candados = new ConcurrentHashMap<>();

    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public RegistrosControlBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /**
     * registrosControl.obtenerRegistros → GET api/registros-control?page=..&limit=..[&fechaDesde=..]
     * [&fechaHasta=..][&np=..][&turno=..][&estado=..][&id=..][&procesoId=..][&parametroId=..]
     *
     * Mismas reglas que Photino (todo se lee desde "data"; sin "data" → solo defaults):
     * - page (default 1) y limit (default 20): entero, o string convertible a entero; cualquier
     *   otra cosa → default. Siempre van en la query (limit=999999 es el "traer todo" de
     *   Exportar/Imprimir).
     * - fechaDesde/fechaHasta/np/turno/estado: string o número → se envía si no está en blanco,
     *   escapado como Uri.EscapeDataString; ausente/null → se omite.
     * - id: string o número convertible a entero → se envía; si no convierte → se omite.
     * - procesoId/parametroId: entero o string numérica → se envía; cualquier otra cosa → se omite.
     *
     * Diferencia DEFENSIVA respecto de Photino (acordada con el usuario, Fase 2e): para los
     * filtros de texto e id, Photino convierte booleanos/objetos/arrays con JsonElement.ToString()
     * y reenvía "True" o el JSON crudo a la API. Aquí se responde "Parámetro de filtro inválido."
     * sin llamar a la API. La UI real siempre envía strings, así que solo afecta a payloads
     * manipulados.
     */
    public BridgeResult obtenerRegistros(ObjectNode payload, SessionUser usuario) {
        JsonNode data = payload.get("data");
        if (data == null || !data.isObject()) {
            data = mapper.createObjectNode();
        }

        StringBuilder query = new StringBuilder("/api/registros-control")
                .append("?page=").append(entero(data.get("page"), 1))
                .append("&limit=").append(entero(data.get("limit"), 20));

        for (String filtro : FILTROS_TEXTO) {
            JsonNode nodo = data.get(filtro);
            if (nodo == null || nodo.isNull()) {
                continue;
            }
            if (!esTexto(nodo)) {
                return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
            }
            String valor = nodo.asString();
            if (!valor.isBlank()) {
                query.append('&').append(filtro).append('=').append(UriEscape.dataString(valor));
            }
        }

        JsonNode id = data.get("id");
        if (id != null && !id.isNull()) {
            if (!esTexto(id)) {
                return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
            }
            Integer valor = parsearEntero(id.asString());
            if (valor != null) {
                query.append("&id=").append(valor);
            }
        }

        for (String filtro : new String[] {"procesoId", "parametroId"}) {
            Integer valor = enteroOpcional(data.get(filtro));
            if (valor != null) {
                query.append('&').append(filtro).append('=').append(valor);
            }
        }

        BridgeResult resultado = InnpackRespuestas.reenviar(api.get(usuario, query.toString()), mapper);
        if (resultado.ok()) {
            registrarHuellas(resultado.data());
        }
        return resultado;
    }

    /**
     * Fase 4b: huella por fila (id → estadoValidacion) de "items", para exigir que la sesión haya visto
     * el registro en una lista cargada antes de validar/rechazar/eliminarlo, y para detectar si otra
     * persona lo cambió entre esa lista y el clic (releyendo por id antes de escribir).
     */
    private static void registrarHuellas(Object data) {
        JsonNode d = data instanceof JsonNode n && n.isObject() ? n : null;
        JsonNode items = d == null ? null : d.get("items");
        if (items == null || !items.isArray()) {
            return;
        }
        for (JsonNode fila : items) {
            Integer id = enteroOpcional(fila.get("id"));
            if (id != null && id > 0) {
                LecturasDeSesion.registrar(recursoLectura(id), texto(fila.get("estadoValidacion")));
            }
        }
    }

    static String recursoLectura(int id) {
        return "registrosControl-registro:" + id;
    }

    /** Relee el registro por id (GET ?id=) y devuelve su estadoValidacion actual, o null si ya no aparece. */
    private String estadoActual(int id, SessionUser usuario) {
        BridgeResult r = InnpackRespuestas.reenviar(
                api.get(usuario, "/api/registros-control?page=1&limit=1&id=" + id), mapper);
        if (!r.ok() || !(r.data() instanceof JsonNode d) || !d.isObject()) {
            return null;
        }
        JsonNode items = d.get("items");
        JsonNode primero = items != null && items.isArray() && !items.isEmpty() ? items.get(0) : null;
        return primero == null ? null : texto(primero.get("estadoValidacion"));
    }

    /**
     * registrosControl.validarRegistro → PUT api/registros-control/{id}/validar {} (Fase 4b), igual que
     * Photino para el usuario: UPDATE directo de estado_validacion='VALIDADO', sin autor real
     * (usuario_validacion queda hardcodeado 'SUPERVISOR' en la API) ni filtro de área.
     *
     * Seguridad transparente: lista blanca {id}; exige haber visto el registro en una lista cargada en
     * esta sesión; candado por id; relee el registro por id antes de escribir y rechaza si su
     * estadoValidacion cambió desde que la sesión lo listó (otra persona ya validó/rechazó).
     */
    public BridgeResult validarRegistro(ObjectNode payload, SessionUser usuario) {
        return escritura(payload, usuario, "/api/registros-control/%d/validar");
    }

    /** registrosControl.rechazarRegistro → PUT api/registros-control/{id}/rechazar {} (Fase 4b). */
    public BridgeResult rechazarRegistro(ObjectNode payload, SessionUser usuario) {
        return escritura(payload, usuario, "/api/registros-control/%d/rechazar");
    }

    private BridgeResult escritura(ObjectNode payload, SessionUser usuario, String rutaFormato) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ_ESCRITURA.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer id = enteroOpcional(payload.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_ID);
        }
        String leida = LecturasDeSesion.huella(recursoLectura(id));
        if (leida == null) {
            return BridgeResult.error(MENSAJE_SIN_LEER);
        }
        synchronized (candados.computeIfAbsent(id, k -> new Object())) {
            if (!leida.equals(LecturasDeSesion.huella(recursoLectura(id)))) {
                return BridgeResult.error(MENSAJE_SIN_LEER);
            }
            String vigente = estadoActual(id, usuario);
            if (!leida.equals(vigente)) {
                return BridgeResult.error(MENSAJE_CONFLICTO);
            }
            BridgeResult resultado = InnpackRespuestas.reenviar(
                    api.putJson(usuario, String.format(rutaFormato, id), mapper.createObjectNode()), mapper);
            if (resultado.ok()) {
                LecturasDeSesion.olvidar(recursoLectura(id));
            }
            return resultado;
        }
    }

    /**
     * registrosControl.eliminarRegistro → DELETE api/registros-control/{id} (Fase 4b), igual que Photino:
     * borrado lógico (eliminado=1), idempotente, sin filtro de área.
     */
    public BridgeResult eliminarRegistro(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ_ESCRITURA.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer id = enteroOpcional(payload.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_ID);
        }
        if (LecturasDeSesion.huella(recursoLectura(id)) == null) {
            return BridgeResult.error(MENSAJE_SIN_LEER);
        }
        synchronized (candados.computeIfAbsent(id, k -> new Object())) {
            BridgeResult resultado = InnpackRespuestas.reenviar(
                    api.delete(usuario, "/api/registros-control/" + id), mapper);
            if (resultado.ok()) {
                LecturasDeSesion.olvidar(recursoLectura(id));
            }
            return resultado;
        }
    }

    /** Recurso auditado: "registrosControl:<id>:<accion>". */
    public static String recursoRegistro(ObjectNode payload, String accion) {
        Integer id = enteroOpcional(payload.get("id"));
        return "registrosControl:" + (id != null && id > 0 ? id : "?") + ":" + accion;
    }

    private static String nombreCampoSeguro(String clave) {
        return clave != null && clave.matches("[A-Za-z0-9_]{1,40}") ? clave : "?";
    }

    /** Lo que Photino lee con GetString y reenvía tal cual: strings y números. */
    private static boolean esTexto(JsonNode nodo) {
        return nodo.isString() || nodo.isNumber();
    }

    /** GetString de Photino: string o número → texto; otro/ausente → "". */
    private static String texto(JsonNode nodo) {
        return nodo != null && (nodo.isString() || nodo.isNumber()) ? nodo.asString() : "";
    }

    /** GetInt de Photino: número entero int32, o string convertible; cualquier otra cosa → default. */
    private static int entero(JsonNode nodo, int porDefecto) {
        Integer valor = enteroOpcional(nodo);
        return valor != null ? valor : porDefecto;
    }

    /** GetIntOrNull de Photino: número entero int32, o string convertible; cualquier otra cosa → null. */
    private static Integer enteroOpcional(JsonNode nodo) {
        if (nodo == null) {
            return null;
        }
        if (nodo.isNumber()) {
            return nodo.isIntegralNumber() && nodo.canConvertToInt() ? nodo.asInt() : null;
        }
        if (nodo.isString()) {
            return parsearEntero(nodo.asString());
        }
        return null;
    }

    /** Equivalente a int.TryParse (admite espacios alrededor y signo); null si no es un entero válido. */
    private static Integer parsearEntero(String valor) {
        try {
            return Integer.valueOf(valor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
