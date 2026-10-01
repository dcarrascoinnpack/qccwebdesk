package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.bridge.LecturasDeSesion;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import java.math.BigDecimal;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Talleres Externos" (INNPACK). Port de Photino
 * src/Backend/Modules/TalleresExternos/TalleresExternosHandler.cs + InnpackTalleresExternosApiService.
 * Payload dentro de "data" (el controller JS usa el wrapper this._send(action, data)).
 *
 * Sin empresa (menú solo INNPACK; Faret tiene su propio módulo faret.*) ni rol (Photino y la API
 * solo exigen sesión). Respuestas sin transformación (Forward de Photino).
 *
 * Fase 4c — escrituras (create, update, eliminar, catalogos.eliminarTaller/eliminarProceso,
 * sincronizarFps): a diferencia de recepcion.estado.actualizar, la API SÍ tiene concurrencia
 * optimista real (columna `version`, 409 con mensaje humano si no coincide, 404 si no existe) — el
 * gateway no reimplementa esa detección, solo reenvía el `version` que trae el payload (el que la
 * sesión vio en la última lista) y confía en que la API arbitre el conflicto. La huella de sesión
 * (igual que en el resto del sistema) exige haber cargado la lista/catálogo antes de escribir, como
 * piso mínimo independiente de lo que la API ya resuelva.
 */
public class TalleresExternosBridgeHandler {

    static final String MENSAJE_ID_HISTORIAL = "Falta 'id' para consultar el historial.";
    private static final String BASE = "/api/talleres-externos";

    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public TalleresExternosBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /**
     * talleresExternos.list → GET api/talleres-externos?page=..&pageSize=.. — GetInt de Photino con
     * defaults 1/50 (sin validar > 0: la API normaliza pageSize a 1..500). Registra la huella de
     * "visto en esta sesión" por cada trabajo listado (Fase 4c: exige esto antes de editar/eliminar).
     */
    public BridgeResult list(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        long page = entero(data.get("page"), 1);
        long pageSize = entero(data.get("pageSize"), 50);
        BridgeResult resultado = get(usuario, BASE + "?page=" + page + "&pageSize=" + pageSize);
        if (resultado.ok()) {
            registrarHuellasTrabajos(resultado.data());
        }
        return resultado;
    }

    private static void registrarHuellasTrabajos(Object data) {
        JsonNode d = data instanceof JsonNode n && n.isObject() ? n : null;
        JsonNode items = d == null ? null : d.get("items");
        if (items == null || !items.isArray()) {
            return;
        }
        for (JsonNode fila : items) {
            Long id = numero(fila.get("id"));
            if (id != null && id > 0) {
                LecturasDeSesion.registrar(recursoLecturaTrabajo(id), "visto");
            }
        }
    }

    static String recursoLecturaTrabajo(long id) {
        return "talleres-trabajo:" + id;
    }

    /**
     * talleresExternos.catalogos → GET api/talleres-externos/catalogos (no lee el payload). Registra
     * la huella de "visto en esta sesión" por cada taller/proceso del catálogo (Fase 4c: exige esto
     * antes de desactivar uno).
     */
    public BridgeResult catalogos(ObjectNode payload, SessionUser usuario) {
        BridgeResult resultado = get(usuario, BASE + "/catalogos");
        if (resultado.ok()) {
            registrarHuellasCatalogos(resultado.data());
        }
        return resultado;
    }

    private static void registrarHuellasCatalogos(Object data) {
        JsonNode d = data instanceof JsonNode n && n.isObject() ? n : null;
        if (d == null) {
            return;
        }
        registrarHuellasCatalogo(d.get("talleres"), "taller");
        registrarHuellasCatalogo(d.get("procesos"), "proceso");
    }

    private static void registrarHuellasCatalogo(JsonNode lista, String tipo) {
        if (lista == null || !lista.isArray()) {
            return;
        }
        for (JsonNode fila : lista) {
            Long id = numero(fila.get("id"));
            if (id != null && id > 0) {
                LecturasDeSesion.registrar(recursoLecturaCatalogo(tipo, id), "visto");
            }
        }
    }

    static String recursoLecturaCatalogo(String tipo, long id) {
        return "talleres-catalogo-" + tipo + ":" + id;
    }

    /** talleresExternos.historialLiberaciones → GET api/talleres-externos/{id}/historial-liberaciones (id long > 0). */
    public BridgeResult historialLiberaciones(ObjectNode payload, SessionUser usuario) {
        long id = largo(data(payload).get("id"), 0);
        if (id <= 0) {
            return BridgeResult.error(MENSAJE_ID_HISTORIAL);
        }
        return get(usuario, BASE + "/" + id + "/historial-liberaciones");
    }

    // ------------------------------------------------------------------ Fase 4c: escrituras

    static final String MENSAJE_CAMPO_NO_PERMITIDO = "Campo no permitido: ";
    static final String MENSAJE_PARAMETRO_INVALIDO = "Parámetro inválido.";
    static final String MENSAJE_TEXTO_CARACTERES = "El texto contiene caracteres no permitidos.";
    static final String MENSAJE_TEXTO_HTML = "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").";
    static final String MENSAJE_FALTA_ID_ACTUALIZAR = "Falta 'id' para actualizar.";
    static final String MENSAJE_FALTA_ID_ELIMINAR = "Falta 'id' para eliminar.";
    static final String MENSAJE_OPCION_INVALIDA = "Valor no permitido en ";
    static final String MENSAJE_FECHA_INVALIDA = "La fecha no es válida (formato AAAA-MM-DD).";
    static final String MENSAJE_SIN_LEER_TRABAJO = "Actualiza la lista antes de editar o eliminar este trabajo.";
    static final String MENSAJE_SIN_LEER_CATALOGO = "Actualiza el catálogo antes de eliminar este valor.";

    private static final Pattern CONTROL_UNA_LINEA = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final Pattern CONTROL_MULTILINEA = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern MARCADO_HTML = Pattern.compile("<[A-Za-z/!?]");
    private static final Pattern FECHA_ISO = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Set<String> PRIORIDADES = Set.of("BAJA", "MEDIA", "ALTA");
    private static final Set<String> ESTADOS =
            Set.of("PENDIENTE_ASIGNACION", "ASIGNADO", "EN_PROCESO", "ENTREGADO", "ANULADO");

    /** Claves de BuildRequestPayload de Photino (sin id/version/usuarioId: esos van aparte). */
    private static final Set<String> CLAVES_DATA_TRABAJO = Set.of("nv", "producto", "codigoProducto", "item", "cliente",
            "fechaAsignacion", "tallerExternoNombre", "procesoNombre", "responsableInternoNombre", "prioridad",
            "fechaCompromiso", "estado", "cantidadARevisar", "cantidadRevisadaEntregada", "cantidadFaltanteAjusteManual",
            "cantidadFaltanteManual", "cantidadFaltanteJustificacion", "precioCotizacion", "precioTaller", "observaciones");
    private static final Set<String> CLAVES_DATA_CREAR = CLAVES_DATA_TRABAJO;
    private static final Set<String> CLAVES_DATA_ACTUALIZAR = concat(CLAVES_DATA_TRABAJO, "id", "version");
    private static final Set<String> CLAVES_DATA_ELIMINAR = Set.of("id", "version");
    private static final Set<String> CLAVES_DATA_CATALOGO = Set.of("id");
    private static final Set<String> CLAVES_RAIZ = Set.of("action", "data");

    private static Set<String> concat(Set<String> base, String... extra) {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>(base);
        s.addAll(java.util.List.of(extra));
        return java.util.Collections.unmodifiableSet(s);
    }

    /**
     * talleresExternos.create → POST api/talleres-externos {nv, producto, ..., usuarioId} (Fase 4c),
     * igual que Photino para el usuario: inserta un trabajo (taller/proceso/responsable se resuelven
     * o crean por nombre en la API, nunca por id); sin control de duplicados (el formulario no lo
     * tiene). La API valida negocio (NV/Ítem/Producto obligatorios, prioridad/estado de los selects,
     * cantidades ≥0, largos de justificación/precio/observaciones) y responde el error tal cual si
     * falla (transacción simple, sin riesgo de huérfanos).
     *
     * Seguridad transparente: lista blanca de las 18 claves del formulario; usuarioId ← sesión (nunca
     * del payload); textos sin controles ni marcado HTML (sin largo propio: no se pudo confirmar el
     * de la columna real, la API valida los que sí se conocen — precios ≤200, justificación ≤500,
     * observaciones ≤2000); fechas AAAA-MM-DD si vienen, null si no (como el date picker de Photino);
     * prioridad/estado restringidos a las opciones de los selects de Photino.
     */
    public BridgeResult crear(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_DATA_CREAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = armarCuerpoTrabajo(cuerpo, data);
        if (error != null) {
            return BridgeResult.error(error);
        }
        cuerpo.put("usuarioId", usuario.userId());
        BridgeResult resultado = InnpackRespuestas.reenviar(api.postJson(usuario, BASE, cuerpo), mapper);
        if (resultado.ok() && resultado.data() instanceof JsonNode d && d.path("id").canConvertToLong()) {
            LecturasDeSesion.registrar(recursoLecturaTrabajo(d.path("id").asLong()), "visto");
        }
        return resultado;
    }

    /** Recurso auditado: "talleresExternos:<id>:crear". */
    public static String recursoCrear(ObjectNode payload, Object dataRespuesta) {
        JsonNode id = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("id") : null;
        return "talleresExternos:" + (id != null && id.canConvertToLong() ? id.asLong() : "?") + ":crear";
    }

    /**
     * talleresExternos.update → PUT api/talleres-externos/{id} {..., version, usuarioId} (Fase 4c),
     * igual que Photino para el usuario: la API relee `version` dentro de su propia transacción
     * (UPDLOCK/ROWLOCK) y responde 404 si no existe/ya está eliminado o 409 con un mensaje humano si
     * `version` no coincide (el gateway no reimplementa esa comparación: reenvía la que trae el
     * payload, que el navegador sacó de la última lista cargada, y confía en la API como autoridad).
     *
     * Seguridad transparente: igual que crear + {id, version}; exige haber visto el trabajo en una
     * lista cargada en esta sesión (huella); usuarioId ← sesión.
     */
    public BridgeResult actualizar(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_DATA_ACTUALIZAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Long id = numero(data.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_ID_ACTUALIZAR);
        }
        if (LecturasDeSesion.huella(recursoLecturaTrabajo(id)) == null) {
            return BridgeResult.error(MENSAJE_SIN_LEER_TRABAJO);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = armarCuerpoTrabajo(cuerpo, data);
        if (error != null) {
            return BridgeResult.error(error);
        }
        cuerpo.put("version", entero(data.get("version"), 0));
        cuerpo.put("usuarioId", usuario.userId());
        BridgeResult resultado = InnpackRespuestas.reenviar(api.putJson(usuario, BASE + "/" + id, cuerpo), mapper);
        if (resultado.ok()) {
            // La vista recarga la lista enseguida (_cargarTodo) y registra la huella/versión nueva.
            LecturasDeSesion.olvidar(recursoLecturaTrabajo(id));
        }
        return resultado;
    }

    /** Recurso auditado: "talleresExternos:<id>:actualizar". */
    public static String recursoActualizar(ObjectNode payload, Object dataRespuesta) {
        Long id = idDeData(payload);
        return "talleresExternos:" + (id != null && id > 0 ? id : "?") + ":actualizar";
    }

    /** Arma {nv..observaciones} del cuerpo; devuelve el mensaje de error, o null si quedó ok. */
    private String armarCuerpoTrabajo(ObjectNode cuerpo, JsonNode data) {
        String error = ponerTextoRequerido(cuerpo, data, "nv");
        error = error != null ? error : ponerTextoRequerido(cuerpo, data, "producto");
        error = error != null ? error : ponerTextoOpcional(cuerpo, data, "codigoProducto", false, null);
        error = error != null ? error : ponerTextoRequerido(cuerpo, data, "item");
        error = error != null ? error : ponerTextoOpcional(cuerpo, data, "cliente", false, null);
        if (error != null) {
            return error;
        }
        error = ponerFecha(cuerpo, data, "fechaAsignacion");
        error = error != null ? error : ponerTextoOpcional(cuerpo, data, "tallerExternoNombre", false, null);
        error = error != null ? error : ponerTextoOpcional(cuerpo, data, "procesoNombre", false, null);
        error = error != null ? error : ponerTextoOpcional(cuerpo, data, "responsableInternoNombre", false, null);
        if (error != null) {
            return error;
        }
        String prioridad = textoOpcional(data.get("prioridad"));
        if (prioridad != null && !prioridad.isEmpty() && !PRIORIDADES.contains(prioridad)) {
            return MENSAJE_OPCION_INVALIDA + "prioridad.";
        }
        cuerpo.put("prioridad", prioridad == null || prioridad.isEmpty() ? "MEDIA" : prioridad);
        error = ponerFecha(cuerpo, data, "fechaCompromiso");
        if (error != null) {
            return error;
        }
        String estado = textoOpcional(data.get("estado"));
        if (estado != null && !estado.isEmpty() && !ESTADOS.contains(estado)) {
            return MENSAJE_OPCION_INVALIDA + "estado.";
        }
        cuerpo.put("estado", estado == null || estado.isEmpty() ? "PENDIENTE_ASIGNACION" : estado);
        ponerDecimalConDefaultCero(cuerpo, data, "cantidadARevisar");
        ponerDecimalConDefaultCero(cuerpo, data, "cantidadRevisadaEntregada");
        cuerpo.put("cantidadFaltanteAjusteManual", leerBool(data.get("cantidadFaltanteAjusteManual")));
        ponerDecimalOpcional(cuerpo, data, "cantidadFaltanteManual");
        error = ponerTextoOpcional(cuerpo, data, "cantidadFaltanteJustificacion", true, 500);
        error = error != null ? error : ponerTextoOpcional(cuerpo, data, "precioCotizacion", false, 200);
        error = error != null ? error : ponerTextoOpcional(cuerpo, data, "precioTaller", false, 200);
        error = error != null ? error : ponerTextoOpcional(cuerpo, data, "observaciones", true, 2000);
        return error;
    }

    /** GetString(...) ?? "" de Photino: null/ausente → ""; presente no texto/número → inválido. Sin controles ni HTML. */
    private String ponerTextoRequerido(ObjectNode cuerpo, JsonNode data, String campo) {
        JsonNode v = data.get(campo);
        if (v != null && !v.isNull() && !v.isString() && !v.isNumber()) {
            return MENSAJE_PARAMETRO_INVALIDO;
        }
        String texto = textoOpcional(v);
        texto = texto == null ? "" : texto;
        if (CONTROL_UNA_LINEA.matcher(texto).find()) {
            return MENSAJE_TEXTO_CARACTERES;
        }
        if (MARCADO_HTML.matcher(texto).find()) {
            return MENSAJE_TEXTO_HTML;
        }
        cuerpo.put(campo, texto);
        return null;
    }

    /**
     * GetString(...) de Photino: ausente/null → null (en precioCotizacion/precioTaller esto CONSERVA
     * el valor existente al actualizar; "" lo limpia); presente no texto/número → inválido.
     */
    private String ponerTextoOpcional(ObjectNode cuerpo, JsonNode data, String campo, boolean multilinea, Integer maxLargo) {
        JsonNode v = data.get(campo);
        if (v != null && !v.isNull() && !v.isString() && !v.isNumber()) {
            return MENSAJE_PARAMETRO_INVALIDO;
        }
        String texto = textoOpcional(v);
        if (texto == null) {
            cuerpo.putNull(campo);
            return null;
        }
        if (maxLargo != null && texto.length() > maxLargo) {
            return "El campo " + campo + " supera el máximo de " + maxLargo + " caracteres.";
        }
        Pattern control = multilinea ? CONTROL_MULTILINEA : CONTROL_UNA_LINEA;
        if (control.matcher(texto).find()) {
            return MENSAJE_TEXTO_CARACTERES;
        }
        if (MARCADO_HTML.matcher(texto).find()) {
            return MENSAJE_TEXTO_HTML;
        }
        cuerpo.put(campo, texto);
        return null;
    }

    /** Fecha del &lt;input type="date"&gt; de Photino: ausente/vacía → null; si viene, AAAA-MM-DD válida. */
    private String ponerFecha(ObjectNode cuerpo, JsonNode data, String campo) {
        String texto = textoOpcional(data.get(campo));
        if (texto == null || texto.isEmpty()) {
            cuerpo.putNull(campo);
            return null;
        }
        if (!FECHA_ISO.matcher(texto).matches()) {
            return MENSAJE_FECHA_INVALIDA;
        }
        try {
            java.time.LocalDate.parse(texto);
        } catch (java.time.format.DateTimeParseException e) {
            return MENSAJE_FECHA_INVALIDA;
        }
        cuerpo.put(campo, texto);
        return null;
    }

    /** GetDecimal(...) ?? 0 de Photino: ausente/no numérico → 0 (igual que teNumeroOCero en la vista). */
    private static void ponerDecimalConDefaultCero(ObjectNode cuerpo, JsonNode data, String campo) {
        BigDecimal v = decimal(data.get(campo));
        cuerpo.put(campo, v != null ? v : BigDecimal.ZERO);
    }

    /** GetDecimal(...) de Photino: ausente/no numérico → null (sin forzar 0; es el ajuste manual). */
    private static void ponerDecimalOpcional(ObjectNode cuerpo, JsonNode data, String campo) {
        BigDecimal v = decimal(data.get(campo));
        if (v == null) {
            cuerpo.putNull(campo);
        } else {
            cuerpo.put(campo, v);
        }
    }

    /**
     * talleresExternos.eliminar → DELETE api/talleres-externos/{id}?version=&usuarioId= (Fase 4c),
     * igual que Photino: anula el trabajo (soft delete) con el mismo control de `version` que update
     * (404 si no existe, 409 si la versión no coincide). Sin cuerpo: va por querystring.
     *
     * Seguridad transparente: lista blanca {id, version}; usuarioId ← sesión (nunca del payload);
     * exige haber visto el trabajo en una lista cargada en esta sesión.
     */
    public BridgeResult eliminar(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_DATA_ELIMINAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Long id = numero(data.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_ID_ELIMINAR);
        }
        if (LecturasDeSesion.huella(recursoLecturaTrabajo(id)) == null) {
            return BridgeResult.error(MENSAJE_SIN_LEER_TRABAJO);
        }
        long version = entero(data.get("version"), 0);
        BridgeResult resultado = InnpackRespuestas.reenviar(
                api.delete(usuario, BASE + "/" + id + "?version=" + version + "&usuarioId=" + usuario.userId()), mapper);
        if (resultado.ok()) {
            LecturasDeSesion.olvidar(recursoLecturaTrabajo(id));
        }
        return resultado;
    }

    /** Recurso auditado: "talleresExternos:<id>:eliminar". */
    public static String recursoEliminar(ObjectNode payload, Object dataRespuesta) {
        Long id = idDeData(payload);
        return "talleresExternos:" + (id != null && id > 0 ? id : "?") + ":eliminar";
    }

    /**
     * talleresExternos.catalogos.eliminarTaller/eliminarProceso → DELETE .../catalogos/talleres|procesos/{id}
     * (Fase 4c), igual que Photino: desactiva el valor del catálogo (activo=0); 404 si no existe o ya
     * estaba inactivo. Los trabajos ya guardados conservan su copia de texto (no se ven afectados).
     *
     * Seguridad transparente: lista blanca {id}; exige haber visto el valor en el catálogo cargado en
     * esta sesión.
     */
    public BridgeResult catalogoEliminarTaller(ObjectNode payload, SessionUser usuario) {
        return catalogoEliminar(payload, usuario, "taller", BASE + "/catalogos/talleres/");
    }

    public BridgeResult catalogoEliminarProceso(ObjectNode payload, SessionUser usuario) {
        return catalogoEliminar(payload, usuario, "proceso", BASE + "/catalogos/procesos/");
    }

    private BridgeResult catalogoEliminar(ObjectNode payload, SessionUser usuario, String tipo, String ruta) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_DATA_CATALOGO.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Long id = numero(data.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_ID_ELIMINAR);
        }
        if (LecturasDeSesion.huella(recursoLecturaCatalogo(tipo, id)) == null) {
            return BridgeResult.error(MENSAJE_SIN_LEER_CATALOGO);
        }
        BridgeResult resultado = InnpackRespuestas.reenviar(api.delete(usuario, ruta + id), mapper);
        if (resultado.ok()) {
            LecturasDeSesion.olvidar(recursoLecturaCatalogo(tipo, id));
        }
        return resultado;
    }

    /** Recurso auditado: "talleresExternos:catalogo:<tipo>:<id>:eliminar". */
    public static String recursoCatalogoEliminar(String tipo, ObjectNode payload, Object dataRespuesta) {
        Long id = idDeData(payload);
        return "talleresExternos:catalogo:" + tipo + ":" + (id != null && id > 0 ? id : "?") + ":eliminar";
    }

    /**
     * talleresExternos.sincronizarFps → POST api/talleres-externos/sincronizar-fps {usuarioId} (Fase
     * 4c), igual que Photino: recorre los trabajos activos con código de producto, consulta fps-api
     * por cada uno e inserta sus liberaciones nuevas (idempotente: una liberación ya registrada se
     * ignora por su folio único). El botón envía `{action, data:{}}` (wrapper `_send` de la vista,
     * igual que las lecturas de este módulo): sin id ni parámetros propios. Un error de un trabajo no
     * aborta el resto (queda en `data.errores`).
     *
     * Seguridad transparente: lista blanca {action, data}, con `data` ignorado (no hay nada que leer
     * de él, ni id ni huella posible: la acción no referencia ningún trabajo en particular); usuarioId
     * ← sesión. Sin candado: cada trabajo se sincroniza en su propia transacción en la API y un folio
     * repetido no duplica nada, como Photino.
     */
    public BridgeResult sincronizarFps(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("usuarioId", usuario.userId());
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/sincronizar-fps", cuerpo), mapper);
    }

    /** Recurso auditado: "talleresExternos:sincronizarFps" (sin id: no referencia ningún trabajo en particular). */
    public static String recursoSincronizarFps(ObjectNode payload, Object dataRespuesta) {
        return "talleresExternos:sincronizarFps";
    }

    private static String nombreCampoSeguro(String clave) {
        return clave != null && clave.matches("[A-Za-z0-9_]{1,40}") ? clave : "?";
    }

    /** GetString de Photino: string o número → texto (incluida ""); null/ausente → null. */
    private static String textoOpcional(JsonNode nodo) {
        return nodo != null && (nodo.isString() || nodo.isNumber()) ? nodo.asString() : null;
    }

    /** GetDecimal de Photino: número o texto numérico → valor; cualquier otra cosa → null. */
    private static BigDecimal decimal(JsonNode nodo) {
        if (nodo == null || nodo.isNull()) {
            return null;
        }
        if (nodo.isNumber()) {
            return nodo.decimalValue();
        }
        if (nodo.isString()) {
            try {
                String texto = nodo.asString().trim();
                return texto.isEmpty() ? null : new BigDecimal(texto);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** GetBool de Photino: true/false, o el string "true"/"false"; cualquier otra cosa → false. */
    private static boolean leerBool(JsonNode nodo) {
        if (nodo == null) {
            return false;
        }
        if (nodo.isBoolean()) {
            return nodo.asBoolean();
        }
        if (nodo.isString()) {
            return Boolean.parseBoolean(nodo.asString());
        }
        return false;
    }

    /** Lee "data.id" sin necesitar una instancia (para los métodos estáticos `recurso*`). */
    private static Long idDeData(ObjectNode payload) {
        JsonNode data = payload.get("data");
        return data != null && data.isObject() ? numero(data.get("id")) : null;
    }

    private BridgeResult get(SessionUser usuario, String path) {
        return InnpackRespuestas.reenviar(api.get(usuario, path), mapper);
    }

    private JsonNode data(ObjectNode payload) {
        JsonNode data = payload.get("data");
        return data != null && data.isObject() ? data : mapper.createObjectNode();
    }

    /** GetInt de Photino: número int32, o texto convertible (int.TryParse); si no → porDefecto. */
    private static long entero(JsonNode nodo, int porDefecto) {
        Long v = numero(nodo);
        return v != null && v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE ? v : porDefecto;
    }

    /** GetLong de Photino: número int64, o texto convertible (long.TryParse); si no → porDefecto. */
    private static long largo(JsonNode nodo, long porDefecto) {
        Long v = numero(nodo);
        return v != null ? v : porDefecto;
    }

    private static Long numero(JsonNode nodo) {
        if (nodo == null || nodo.isNull()) {
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
