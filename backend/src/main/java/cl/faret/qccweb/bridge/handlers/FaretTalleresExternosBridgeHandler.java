package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.FaretApiClient;
import cl.faret.qccweb.upstream.FaretRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fase F2 - ESCRITURAS de Talleres Externos FARET contra QualityControlFaret.Api con el Bearer del usuario de la sesión. Port de
 * Photino 1.8.15 (9e1b556) FaretHandler.cs: HandleTalleresExternosCreate / Update / Eliminar / EliminarTaller / EliminarProceso
 * (+ TryBuildTrabajoRequest) y FaretTalleresExternosApiService. Payload PLANO (el frontend manda {action, ...campos} en la raíz).
 *
 * El autor lo pone la API desde el `sub` del JWT (el cuerpo de Photino no lleva usuarioId): ninguna clave de identidad del
 * navegador está en la lista cerrada. Concurrencia optimista real en la API (`version`, 404 si no existe, 409 "modificado por otro
 * usuario"): el gateway no la reimplementa, solo exige `version` entero positivo y deja pasar el mensaje de la API. Sin
 * reintentos (F0). NO se habilitan crearTaller/crearProceso ni lote (el frontend de 9e1b556 no los usa).
 *
 * Nota: Photino devuelve {ok:false, error, conflict} pero su MessageRouter.NormalizeResponse descarta `conflict`; el frontend
 * recibe solo `error` (el mensaje de la API). La web entrega lo mismo.
 */
public class FaretTalleresExternosBridgeHandler {

    public static final String MENSAJE_FALTA_ID_TRABAJO = "Falta el id del trabajo";
    public static final String MENSAJE_FALTA_ID_TALLER = "Falta el id del taller";
    public static final String MENSAJE_FALTA_ID_PROCESO = "Falta el id del proceso";
    public static final String MENSAJE_FALTA_NV = "Falta NV";
    public static final String MENSAJE_FALTA_PRODUCTO = "Falta Producto";
    public static final String MENSAJE_FALTA_ITEM = "Falta Ítem";
    public static final String MENSAJE_FALTA_VERSION_ACTUALIZAR = "Falta 'version' para actualizar (recarga el registro antes de guardar).";
    public static final String MENSAJE_FALTA_VERSION_ANULAR = "Falta 'version' para anular (recarga el registro antes de anular).";
    public static final String MENSAJE_FECHA_INVALIDA = "La fecha no es válida (formato AAAA-MM-DD).";
    public static final String MENSAJE_OPCION_INVALIDA = "Valor no permitido en ";
    public static final String MENSAJE_NUMERO_INVALIDO = "El número no es válido: ";

    private static final String RUTA = "/api/talleres-externos";
    /** Columnas reales (Sql/2026_03_talleres_externos.sql y 2026_09_..._precio.sql) y validaciones de TalleresExternosService. */
    private static final int MAX_NV = 50;
    private static final int MAX_PRODUCTO = 255;
    private static final int MAX_CODIGO = 50;
    private static final int MAX_ITEM = 20;
    private static final int MAX_NOMBRE = 150;
    private static final int MAX_PRECIO = 200;
    private static final int MAX_JUSTIFICACION = 500;
    private static final int MAX_OBSERVACIONES = 2000;
    /** talleres_externos_historial.detalle VARCHAR(1000). */
    private static final int MAX_MOTIVO = 1000;
    /** DECIMAL(18,2): tope de magnitud (16 enteros). */
    private static final BigDecimal MAX_CANTIDAD = new BigDecimal("9999999999999999.99");

    private static final Set<String> PRIORIDADES = Set.of("BAJA", "MEDIA", "ALTA");
    private static final Set<String> ESTADOS = Set.of("PENDIENTE_ASIGNACION", "ASIGNADO", "EN_PROCESO", "ENTREGADO", "ANULADO");

    /** TryBuildTrabajoRequest de Photino (sin id/version). */
    private static final Set<String> CLAVES_TRABAJO = Set.of("action", "nv", "producto", "codigoProducto", "item", "cliente",
            "fechaAsignacion", "tallerExternoId", "tallerExternoNombre", "procesoId", "procesoNombre", "responsableInternoId",
            "responsableInternoNombre", "prioridad", "fechaCompromiso", "estado", "cantidadARevisar", "cantidadRevisadaEntregada",
            "cantidadFaltanteAjusteManual", "cantidadFaltanteManual", "cantidadFaltanteJustificacion", "precioCotizacion",
            "precioTaller", "observaciones");
    private static final Set<String> CLAVES_ACTUALIZAR = con(CLAVES_TRABAJO, "id", "version");
    private static final Set<String> CLAVES_ELIMINAR = Set.of("action", "id", "version", "motivo");
    private static final Set<String> CLAVES_CATALOGO = Set.of("action", "id");
    /** Campos de TrabajoDto que se devuelven al navegador (nada más de la API llega a la vista). */
    private static final List<String> CAMPOS_TRABAJO_DTO = List.of("id", "nv", "producto", "codigoProducto", "item", "cliente",
            "fechaAsignacion", "tallerExternoId", "tallerExternoTexto", "procesoId", "procesoTexto", "responsableInternoId",
            "responsableInternoTexto", "prioridad", "fechaCompromiso", "estado", "cantidadARevisar", "cantidadRevisadaEntregada",
            "cantidadFaltante", "cantidadFaltanteAjusteManual", "cantidadFaltanteJustificacion", "precioCotizacion", "precioTaller",
            "observaciones", "version", "creadoPorNombre", "fechaCreacion", "actualizadoPorNombre", "fechaActualizacion", "atrasado");

    private static final Pattern CONTROL_UNA_LINEA = Pattern.compile("[\\x00-\\x1F\\x7F-\\x9F\\u2028\\u2029]");
    private static final Pattern CONTROL_MULTILINEA = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F-\\x9F\\u2028\\u2029]");
    private static final Pattern MARCADO_HTML = Pattern.compile("<[A-Za-z/!?]");
    private static final Pattern FECHA_ISO = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    private final FaretApiClient qualityControl;
    private final ObjectMapper mapper;

    public FaretTalleresExternosBridgeHandler(FaretApiClient qualityControl, ObjectMapper mapper) {
        this.qualityControl = qualityControl;
        this.mapper = mapper;
    }

    /** Error de validación del payload: el mensaje va tal cual al navegador. Sin stack trace. */
    private static final class Invalido extends RuntimeException {
        Invalido(String mensaje) {
            super(mensaje, null, false, false);
        }
    }

    private static Set<String> con(Set<String> base, String... extra) {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>(base);
        s.addAll(List.of(extra));
        return java.util.Collections.unmodifiableSet(s);
    }

    // ------------------------------------------------------------------------------------------- acciones

    /** faret.talleresExternos.create -> POST api/talleres-externos con los campos de TryBuildTrabajoRequest. */
    public BridgeResult crear(ObjectNode payload, SessionUser usuario) {
        ObjectNode cuerpo;
        try {
            cerrada(payload, CLAVES_TRABAJO);
            cuerpo = armarCuerpo(payload, null);
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        return trabajo(FaretRespuestas.desenvolver(qualityControl.postJson(usuario, RUTA, cuerpo), mapper));
    }

    /**
     * faret.talleresExternos.update -> PUT api/talleres-externos/{id}. Orden de Photino: id, NV/Producto/Ítem, version. 404 si el
     * trabajo no existe y 409 si `version` ya no coincide: su mensaje llega tal cual (la vista pide recargar la tabla).
     */
    public BridgeResult actualizar(ObjectNode payload, SessionUser usuario) {
        int id;
        ObjectNode cuerpo;
        try {
            cerrada(payload, CLAVES_ACTUALIZAR);
            Integer idNodo = enteroPositivo(payload.get("id"));
            if (idNodo == null) {
                return BridgeResult.error(MENSAJE_FALTA_ID_TRABAJO);
            }
            id = idNodo;
            cuerpo = armarCuerpo(payload, MENSAJE_FALTA_VERSION_ACTUALIZAR);
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        return trabajo(FaretRespuestas.desenvolver(qualityControl.putJson(usuario, RUTA + "/" + id, cuerpo), mapper));
    }

    /** faret.talleresExternos.eliminar -> DELETE api/talleres-externos/{id}?version=&motivo= (anulación lógica, nunca borrado físico). */
    public BridgeResult eliminar(ObjectNode payload, SessionUser usuario) {
        String ruta;
        try {
            cerrada(payload, CLAVES_ELIMINAR);
            Integer id = enteroPositivo(payload.get("id"));
            if (id == null) {
                return BridgeResult.error(MENSAJE_FALTA_ID_TRABAJO);
            }
            Integer version = enteroPositivo(payload.get("version"));
            if (version == null) {
                return BridgeResult.error(MENSAJE_FALTA_VERSION_ANULAR);
            }
            String motivo = texto(payload, "motivo", MAX_MOTIVO, false);
            ruta = RUTA + "/" + id + "?version=" + version + (motivo == null || motivo.isEmpty() ? "" : "&motivo=" + UriEscape.dataString(motivo));
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        return sinDatos(FaretRespuestas.desenvolver(qualityControl.delete(usuario, ruta), mapper));
    }

    /** faret.talleresExternos.catalogos.eliminarTaller -> DELETE api/talleres-externos/catalogos/talleres/{id} (desactiva; 404 si no existe). */
    public BridgeResult eliminarTaller(ObjectNode payload, SessionUser usuario) {
        return eliminarCatalogo(payload, usuario, "talleres", MENSAJE_FALTA_ID_TALLER);
    }

    /** faret.talleresExternos.catalogos.eliminarProceso -> DELETE api/talleres-externos/catalogos/procesos/{id}. */
    public BridgeResult eliminarProceso(ObjectNode payload, SessionUser usuario) {
        return eliminarCatalogo(payload, usuario, "procesos", MENSAJE_FALTA_ID_PROCESO);
    }

    private BridgeResult eliminarCatalogo(ObjectNode payload, SessionUser usuario, String segmento, String mensajeFalta) {
        Integer id;
        try {
            cerrada(payload, CLAVES_CATALOGO);
            id = enteroPositivo(payload.get("id"));
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        if (id == null) {
            return BridgeResult.error(mensajeFalta);
        }
        return sinDatos(FaretRespuestas.desenvolver(qualityControl.delete(usuario, RUTA + "/catalogos/" + segmento + "/" + id), mapper));
    }

    // ------------------------------------------------------------------------------------------- auditoría

    /** "faret:talleres:<id>:crear" (id de la respuesta). */
    public static String recursoCrear(ObjectNode payload, Object dataRespuesta) {
        JsonNode id = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("id") : null;
        return "faret:talleres:" + (id != null && id.isIntegralNumber() && id.canConvertToLong() ? id.asLong() : "?") + ":crear";
    }

    /** "faret:talleres:<id>:actualizar|eliminar" (id del payload; "?" si no es válido). */
    public static String recursoTrabajo(String operacion, ObjectNode payload, Object dataRespuesta) {
        return "faret:talleres:" + idAuditoria(payload) + ":" + operacion;
    }

    /** "faret:talleres:catalogo:<taller|proceso>:<id>:eliminar". */
    public static String recursoCatalogoEliminar(String tipo, ObjectNode payload, Object dataRespuesta) {
        return "faret:talleres:catalogo:" + tipo + ":" + idAuditoria(payload) + ":eliminar";
    }

    private static String idAuditoria(ObjectNode payload) {
        try {
            Integer id = enteroPositivo(payload.get("id"));
            return id == null ? "?" : String.valueOf(id);
        } catch (Invalido e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------------------------------- cuerpo

    /**
     * Cuerpo de TryBuildTrabajoRequest en el orden de Photino. `mensajeSinVersion` != null = actualización: agrega `version`
     * (obligatoria, entero positivo; en la creación Photino también manda version 0, que la API ignora, y aquí no se envía).
     */
    private ObjectNode armarCuerpo(ObjectNode p, String mensajeSinVersion) {
        String nv = requerido(p, "nv", MAX_NV, MENSAJE_FALTA_NV);
        String producto = requerido(p, "producto", MAX_PRODUCTO, MENSAJE_FALTA_PRODUCTO);
        String item = requerido(p, "item", MAX_ITEM, MENSAJE_FALTA_ITEM);
        Integer version = null;
        if (mensajeSinVersion != null) {
            version = enteroPositivo(p.get("version"));
            if (version == null) {
                throw new Invalido(mensajeSinVersion);
            }
        }
        ObjectNode c = mapper.createObjectNode();
        c.put("nv", nv);
        c.put("producto", producto);
        ponerTexto(c, "codigoProducto", texto(p, "codigoProducto", MAX_CODIGO, false));
        c.put("item", item);
        ponerTexto(c, "cliente", texto(p, "cliente", MAX_NOMBRE, false));
        ponerTexto(c, "fechaAsignacion", fecha(p, "fechaAsignacion"));
        ponerEntero(c, "tallerExternoId", idOpcional(p, "tallerExternoId"));
        ponerTexto(c, "tallerExternoNombre", texto(p, "tallerExternoNombre", MAX_NOMBRE, false));
        ponerEntero(c, "procesoId", idOpcional(p, "procesoId"));
        ponerTexto(c, "procesoNombre", texto(p, "procesoNombre", MAX_NOMBRE, false));
        ponerEntero(c, "responsableInternoId", idOpcional(p, "responsableInternoId"));
        ponerTexto(c, "responsableInternoNombre", texto(p, "responsableInternoNombre", MAX_NOMBRE, false));
        c.put("prioridad", opcion(p, "prioridad", PRIORIDADES, "MEDIA"));
        ponerTexto(c, "fechaCompromiso", fecha(p, "fechaCompromiso"));
        c.put("estado", opcion(p, "estado", ESTADOS, "PENDIENTE_ASIGNACION"));
        BigDecimal revisar = cantidad(p, "cantidadARevisar");
        BigDecimal entregada = cantidad(p, "cantidadRevisadaEntregada");
        c.put("cantidadARevisar", revisar == null ? BigDecimal.ZERO : revisar);
        c.put("cantidadRevisadaEntregada", entregada == null ? BigDecimal.ZERO : entregada);
        c.put("cantidadFaltanteAjusteManual", booleano(p, "cantidadFaltanteAjusteManual"));
        BigDecimal manual = cantidad(p, "cantidadFaltanteManual");
        if (manual == null) {
            c.putNull("cantidadFaltanteManual");
        } else {
            c.put("cantidadFaltanteManual", manual);
        }
        ponerTexto(c, "cantidadFaltanteJustificacion", texto(p, "cantidadFaltanteJustificacion", MAX_JUSTIFICACION, true));
        // Ausente/null conserva el precio al actualizar (la API); "" lo limpia.
        ponerTexto(c, "precioCotizacion", texto(p, "precioCotizacion", MAX_PRECIO, false));
        ponerTexto(c, "precioTaller", texto(p, "precioTaller", MAX_PRECIO, false));
        ponerTexto(c, "observaciones", texto(p, "observaciones", MAX_OBSERVACIONES, true));
        if (version != null) {
            c.put("version", version);
        }
        return c;
    }

    private static void ponerTexto(ObjectNode c, String clave, String valor) {
        if (valor == null) {
            c.putNull(clave);
        } else {
            c.put(clave, valor);
        }
    }

    private static void ponerEntero(ObjectNode c, String clave, Integer valor) {
        if (valor == null) {
            c.putNull(clave);
        } else {
            c.put(clave, valor.intValue());
        }
    }

    // ------------------------------------------------------------------------------------------- respuestas

    /** Reproyecta el TrabajoDto (ApiResponse ya desenvuelta) a su lista cerrada de campos escalares. */
    private BridgeResult trabajo(BridgeResult upstream) {
        if (!upstream.ok() || !(upstream.data() instanceof ObjectNode dto)) {
            return upstream.ok() ? BridgeResult.ok(null) : upstream;
        }
        ObjectNode salida = mapper.createObjectNode();
        for (String campo : CAMPOS_TRABAJO_DTO) {
            JsonNode v = dto.get(campo);
            if (v != null && (v.isString() || v.isNumber() || v.isBoolean() || v.isNull())) {
                salida.set(campo, v);
            }
        }
        return BridgeResult.ok(salida);
    }

    /** Anulación y desactivación de catálogo: la API responde data null; no se pasa nada de ella al navegador. */
    private static BridgeResult sinDatos(BridgeResult upstream) {
        return upstream.ok() ? BridgeResult.ok(null) : upstream;
    }

    // ------------------------------------------------------------------------------------------- validación

    private static void cerrada(ObjectNode payload, Set<String> permitidas) {
        String campo = NoConformidadesBridgeHandler.campoNoPermitido(payload, permitidas);
        if (campo != null) {
            throw new Invalido(campo);
        }
    }

    private static String requerido(ObjectNode p, String clave, int max, String mensajeFalta) {
        String t = texto(p, clave, max, false);
        if (t == null || t.isEmpty()) {
            throw new Invalido(mensajeFalta);
        }
        return t;
    }

    /**
     * Texto opcional: ausente/null -> null; string (no número, bool, objeto ni array) con trim, sin controles ni sustitutos UTF-16
     * sueltos (una línea, o multilínea si se permite), sin marcado HTML y de largo <= max (unidades UTF-16, conservador frente a la columna).
     */
    private static String texto(ObjectNode p, String clave, int max, boolean multilinea) {
        JsonNode n = p.get(clave);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isString()) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
        }
        String crudo = n.asString();
        if ((multilinea ? CONTROL_MULTILINEA : CONTROL_UNA_LINEA).matcher(crudo).find() || tieneSustitutoSuelto(crudo)) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_TEXTO_CARACTERES);
        }
        String t = crudo.strip();
        if (MARCADO_HTML.matcher(t).find()) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_TEXTO_HTML);
        }
        if (t.length() > max) {
            throw new Invalido("El campo " + clave + " supera el máximo de " + max + " caracteres.");
        }
        return t;
    }

    private static boolean tieneSustitutoSuelto(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** Fecha del date picker: ausente/null/vacía -> null; si viene, string AAAA-MM-DD de un día real. */
    private static String fecha(ObjectNode p, String clave) {
        String t = texto(p, clave, 64, false);
        if (t == null || t.isEmpty()) {
            return null;
        }
        if (!FECHA_ISO.matcher(t).matches()) {
            throw new Invalido(MENSAJE_FECHA_INVALIDA);
        }
        try {
            LocalDate.parse(t);
        } catch (DateTimeParseException e) {
            throw new Invalido(MENSAJE_FECHA_INVALIDA);
        }
        return t;
    }

    /** prioridad/estado: ausente/vacío -> el default de Photino; si no, una opción exacta de los selects. */
    private static String opcion(ObjectNode p, String clave, Set<String> validas, String porDefecto) {
        String t = texto(p, clave, 30, false);
        if (t == null || t.isEmpty()) {
            return porDefecto;
        }
        if (!validas.contains(t)) {
            throw new Invalido(MENSAJE_OPCION_INVALIDA + clave + ".");
        }
        return t;
    }

    /** Id opcional del catálogo/usuario: Photino lo manda solo si es > 0; ausente, null o <= 0 -> null. */
    private static Integer idOpcional(ObjectNode p, String clave) {
        return enteroPositivo(p.get(clave));
    }

    /**
     * Entero int32 positivo: número entero o texto de dígitos; ausente, null, vacío o <= 0 -> null. bool/objeto/array, decimales,
     * texto no numérico o fuera de int32 se rechazan.
     */
    private static Integer enteroPositivo(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        Long valor;
        if (n.isIntegralNumber() && n.canConvertToLong()) {
            valor = n.asLong();
        } else if (n.isString()) {
            String t = n.asString().strip();
            if (t.isEmpty()) {
                return null;
            }
            try {
                valor = Long.valueOf(t);
            } catch (NumberFormatException e) {
                throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
            }
        } else {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
        }
        if (valor > Integer.MAX_VALUE) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
        }
        return valor > 0 ? valor.intValue() : null;
    }

    /** Cantidad DECIMAL(18,2): número o texto numérico; ausente/null/vacío -> null; no negativa y dentro del rango de la columna. */
    private static BigDecimal cantidad(ObjectNode p, String clave) {
        JsonNode n = p.get(clave);
        if (n == null || n.isNull()) {
            return null;
        }
        BigDecimal v;
        if (n.isNumber()) {
            v = n.decimalValue();
        } else if (n.isString()) {
            String t = n.asString().strip();
            if (t.isEmpty()) {
                return null;
            }
            try {
                v = new BigDecimal(t);
            } catch (NumberFormatException e) {
                throw new Invalido(MENSAJE_NUMERO_INVALIDO + clave + ".");
            }
        } else {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
        }
        if (v.signum() < 0 || v.compareTo(MAX_CANTIDAD) > 0) {
            throw new Invalido(MENSAJE_NUMERO_INVALIDO + clave + ".");
        }
        return v.scale() < 0 ? v.setScale(0) : v;
    }

    /** TryGetBool de Photino: true/false o el texto "true"/"false"; ausente/null -> false; otro valor -> inválido. */
    private static boolean booleano(ObjectNode p, String clave) {
        JsonNode n = p.get(clave);
        if (n == null || n.isNull()) {
            return false;
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isString()) {
            String t = n.asString().strip();
            if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) {
                return Boolean.parseBoolean(t);
            }
        }
        throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
    }
}
