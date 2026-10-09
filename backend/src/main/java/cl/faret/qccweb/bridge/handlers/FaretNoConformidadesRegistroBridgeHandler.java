package cl.faret.qccweb.bridge.handlers;

import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.Invalido;
import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.cerrada;
import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.enteroPositivo;
import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.texto;
import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesGestionBridgeHandler.fechaOpcional;
import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesGestionBridgeHandler.ponerTexto;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.FaretApiClient;
import cl.faret.qccweb.upstream.FaretRespuestas;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fase F4b - registros de Data PNC desde No Conformidades FARET: faret.nc.crearRegistro (2 pasos) y faret.nc.actualizarRegistro. Port
 * de Photino 1.8.15 (9e1b556) FaretHandler.cs: HandleNcCrearRegistro / HandleNcActualizarRegistro / BuildPncPayload (+ TryBuildNcRequest
 * para el paso 2) y FaretImportacionApiService (CrearPncAsync / ActualizarPncAsync). Payload PLANO. Sin reintentos (F0).
 *
 * QualityControlFaret.Api (importacion_pnc) lleva el Bearer del usuario de la sesión y registra el autor desde el `sub` del JWT:
 * ninguna clave de identidad está en la lista cerrada. MejoraContinua (paso 2) va SIN Authorization, como Photino.
 *
 * crearRegistro (SIN compensación, como Photino): 1) POST api/importaciones/pnc con los 30 campos de BuildPncPayload; 2) POST
 * api/no-conformidades con sistemaOrigen=DATA_FARET y origenId=<pncId>. Si el paso 2 falla, el registro de Data YA existe (huérfano,
 * visible en el módulo Data): el error informa el pncId y la auditoría lo registra. La cabecera de la NC (tipo, origen, título,
 * descripción, severidad, proceso, fecha de detección) se RECALCULA aquí con la misma lógica que el JS de la vista (_guardarNuevoPnc);
 * lo que mande el navegador en esas claves se descarta.
 *
 * actualizarRegistro: PUT api/importaciones/pnc/{id} con los mismos 30 campos; null = no cambia ("" sí se aplica). Photino NO hace un
 * segundo paso hacia MejoraContinua (el título/descripción de la NC vinculada no se actualizan). La API lee la fila y escribe solo los
 * campos que llegan, así que dos personas que editan campos distintos se combinan; si editan el MISMO campo, gana el último en guardar
 * (sin versión en la API; no se puede detectar). Ventana mínima entre la lectura y la escritura de la API.
 */
public class FaretNoConformidadesRegistroBridgeHandler {

    static final String MARCA_PNC = "__qccAuditoriaPnc";
    static final String MARCA_NC = "__qccAuditoriaNcCreada";
    static final String MENSAJE_SIN_ID_PNC = "La API de Faret no devolvió el id del registro creado.";
    static final String MENSAJE_FALTA_ID_REGISTRO = "Falta el id del registro a actualizar";

    private static final String RUTA_PNC = "/api/importaciones/pnc";
    private static final String RUTA_NC = "/api/no-conformidades";
    private static final ZoneId ZONA_PLANTA = ZoneId.of("America/Santiago");
    /** DECIMAL(18,2): tope de magnitud (16 enteros). */
    private static final BigDecimal MAX_CANTIDAD = new BigDecimal("9999999999999999.99");

    /** Columnas de importacion_pnc (QualityControlFaret.Api/Sql/2026_importaciones.sql y 2026_03_pnc_familia_disposicion.sql). */
    private static final java.util.Map<String, Integer> TEXTOS = java.util.Map.ofEntries(
            java.util.Map.entry("npNv", 50), java.util.Map.entry("cliente", 150), java.util.Map.entry("codigo", 50),
            java.util.Map.entry("producto", 255), java.util.Map.entry("familiaProducto", 50), java.util.Map.entry("categoriaDefecto", 150),
            java.util.Map.entry("nivel", 30), java.util.Map.entry("tipoFalla", 100), java.util.Map.entry("area", 100),
            java.util.Map.entry("maquina", 100), java.util.Map.entry("operador", 150), java.util.Map.entry("supervisor", 150),
            java.util.Map.entry("revisadoPor", 150), java.util.Map.entry("impacto", 100));
    /** Columnas TEXT (textareas de la vista): multilínea, tope anti-abuso en bytes. */
    private static final Set<String> MULTILINEA = Set.of("descripcionDefecto", "observacion", "causaRaiz", "accionesCorrectivas",
            "verificacionSeguimiento");
    private static final Set<String> TIPOS_PNC = Set.of("", "Cuarentena", "Rechazo", "Rechazo Cliente", "Reclamo", "Interna");
    private static final Set<String> DISPOSICIONES = Set.of("", "No aplica", "Reposición", "Destrucción", "Reposición y destrucción");
    private static final List<String> NUMEROS = List.of("cantRequerida", "cantRechazada", "cantRecuperada", "pncReal", "cantDestruida",
            "cantRepuesta");
    private static final List<String> FECHAS = List.of("fechaIngreso", "fechaSalida", "fechaFabricacion");
    /** Orden de BuildPncPayload de Photino. */
    private static final List<String> ORDEN = List.of("fechaIngreso", "tipoPnc", "fechaSalida", "npNv", "cliente", "codigo", "producto",
            "familiaProducto", "cantRequerida", "cantRechazada", "cantRecuperada", "pncReal", "disposicion", "cantDestruida", "cantRepuesta",
            "fechaFabricacion", "descripcionDefecto", "categoriaDefecto", "nivel", "tipoFalla", "area", "maquina", "operador", "supervisor",
            "revisadoPor", "impacto", "observacion", "causaRaiz", "accionesCorrectivas", "verificacionSeguimiento");
    /** Cabecera de la NC que arma la vista: se acepta la clave pero se recalcula. */
    private static final List<String> CABECERA = List.of("tipo", "origen", "titulo", "descripcion", "severidad", "proceso", "fechaDeteccion");
    private static final Set<String> CLAVES_CREAR = union(ORDEN, CABECERA, "action");
    private static final Set<String> CLAVES_ACTUALIZAR = union(ORDEN, List.of(), "action", "id");
    private static final List<String> OBLIGATORIOS_CREAR = List.of("npNv", "cliente", "codigo", "producto", "categoriaDefecto", "nivel",
            "descripcionDefecto");

    private final FaretApiClient qualityControl;
    private final FaretApiClient mejoraContinua;
    private final ObjectMapper mapper;

    public FaretNoConformidadesRegistroBridgeHandler(FaretApiClient qualityControl, FaretApiClient mejoraContinua, ObjectMapper mapper) {
        this.qualityControl = qualityControl;
        this.mejoraContinua = mejoraContinua;
        this.mapper = mapper;
    }

    private static Set<String> union(List<String> a, List<String> b, String... extra) {
        Set<String> s = new HashSet<>(a);
        s.addAll(b);
        s.addAll(List.of(extra));
        return Set.copyOf(s);
    }

    // ------------------------------------------------------------------------------------------- crearRegistro

    /** faret.nc.crearRegistro: ver la clase. Éxito: {pncId, nc: {id}} (la vista usa data.nc.id para subir los adjuntos). */
    public BridgeResult crearRegistro(ObjectNode payload, SessionUser usuario) {
        if (!mejoraContinua.configurada()) {
            return BridgeResult.error(FaretBridgeHandler.MENSAJE_MC_NO_CONFIGURADA);
        }
        ObjectNode pnc;
        ObjectNode nc;
        try {
            cerrada(payload, CLAVES_CREAR);
            ObjectNode campos = camposPnc(payload, true);
            for (String obligatorio : OBLIGATORIOS_CREAR) {
                if (vacio(campos.get(obligatorio))) {
                    return BridgeResult.error(NoConformidadesBridgeHandler.MENSAJE_NC_OBLIGATORIOS);
                }
            }
            if (campos.get("cantRequerida").isNull() || campos.get("cantRechazada").isNull()) {
                return BridgeResult.error(NoConformidadesBridgeHandler.MENSAJE_NC_OBLIGATORIOS);
            }
            String fechaIngreso = campos.get("fechaIngreso").isNull() ? LocalDate.now(ZONA_PLANTA).toString()
                    : campos.get("fechaIngreso").asString();
            campos.put("fechaIngreso", fechaIngreso);
            nc = cabeceraNc(campos, fechaIngreso);
            pnc = campos;
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        // Paso 1: el registro de Data (importacion_pnc). Falla -> nada más se hace.
        BridgeResult paso1 = FaretRespuestas.desenvolver(qualityControl.postJson(usuario, RUTA_PNC, pnc), mapper);
        if (!paso1.ok()) {
            return paso1;
        }
        JsonNode idNodo = paso1.data() instanceof JsonNode d && d.isObject() ? d.get("id") : null;
        if (idNodo == null || !idNodo.isIntegralNumber() || !idNodo.canConvertToLong() || idNodo.asLong() <= 0) {
            return BridgeResult.error(MENSAJE_SIN_ID_PNC);
        }
        long pncId = idNodo.asLong();
        // Canal interno para la auditoría (el payload saneado no vuelve al navegador ni a la API).
        payload.put(MARCA_PNC, pncId);
        // Paso 2: la gestión en MejoraContinua vinculada al registro. SIN compensación: si falla, el registro de Data queda creado.
        nc.put("origenId", Long.toString(pncId));
        BridgeResult paso2 = FaretRespuestas.crudoMc(mejoraContinua.postJson(usuario, RUTA_NC, nc), mapper);
        if (!paso2.ok()) {
            return BridgeResult.error(paso2.error() + " El registro de Data #" + pncId
                    + " ya fue creado (puedes gestionarlo desde la lista); no se creó su gestión de no conformidad.");
        }
        ObjectNode ncSalida = mapper.createObjectNode();
        JsonNode ncId = paso2.data() instanceof JsonNode d && d.isObject() ? d.get("id") : null;
        if (ncId != null && (ncId.isNumber() || ncId.isString())) {
            ncSalida.set("id", ncId);
            if (ncId.isIntegralNumber() && ncId.canConvertToLong()) {
                payload.put(MARCA_NC, ncId.asLong());
            }
        }
        ObjectNode salida = mapper.createObjectNode();
        salida.put("pncId", pncId);
        salida.set("nc", ncSalida);
        return BridgeResult.ok(salida);
    }

    // ------------------------------------------------------------------------------------------- actualizarRegistro

    /** faret.nc.actualizarRegistro: ver la clase. Éxito: {id}. */
    public BridgeResult actualizarRegistro(ObjectNode payload, SessionUser usuario) {
        int id;
        ObjectNode cuerpo;
        try {
            cerrada(payload, CLAVES_ACTUALIZAR);
            Integer idNodo = enteroPositivo(payload.get("id"));
            if (idNodo == null) {
                return BridgeResult.error(MENSAJE_FALTA_ID_REGISTRO);
            }
            id = idNodo;
            cuerpo = camposPnc(payload, false);
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        BridgeResult r = FaretRespuestas.desenvolver(qualityControl.putJson(usuario, RUTA_PNC + "/" + id, cuerpo), mapper);
        if (!r.ok()) {
            return r;
        }
        ObjectNode salida = mapper.createObjectNode();
        salida.put("id", id);
        return BridgeResult.ok(salida);
    }

    // ------------------------------------------------------------------------------------------- auditoría

    /** "faret:nc:registro:<pncId>:crear[:nc:<ncId>|:SIN_GESTION]" (el pncId se conoce aunque el paso 2 haya fallado). */
    public static String recursoCrearRegistro(ObjectNode payload, Object dataRespuesta) {
        JsonNode pnc = payload.get(MARCA_PNC);
        StringBuilder r = new StringBuilder("faret:nc:registro:")
                .append(pnc != null && pnc.isIntegralNumber() && pnc.canConvertToLong() ? pnc.asLong() : "?").append(":crear");
        if (pnc != null && pnc.isNumber()) {
            JsonNode nc = payload.get(MARCA_NC);
            r.append(nc != null && nc.isIntegralNumber() && nc.canConvertToLong() ? ":nc:" + nc.asLong() : ":SIN_GESTION");
        }
        return r.toString();
    }

    /** "faret:nc:registro:<id>:actualizar". */
    public static String recursoActualizarRegistro(ObjectNode payload, Object dataRespuesta) {
        Integer id;
        try {
            id = enteroPositivo(payload.get("id"));
        } catch (Invalido e) {
            id = null;
        }
        return "faret:nc:registro:" + (id == null ? "?" : id) + ":actualizar";
    }

    // ------------------------------------------------------------------------------------------- validación

    /**
     * Los 30 campos de BuildPncPayload en su orden: ausente/null -> null; texto (con trim) o número/fecha validados. En la creación,
     * los vacíos de texto se conservan ("" como Photino). Cantidades >= 0 (el input de la vista tiene min=0 y la API rechaza negativas).
     */
    private ObjectNode camposPnc(ObjectNode p, boolean creacion) {
        ObjectNode c = mapper.createObjectNode();
        for (String campo : ORDEN) {
            if (FECHAS.contains(campo)) {
                ponerTexto(c, campo, fechaOpcional(p, campo, "La fecha " + campo + " no es válida (formato AAAA-MM-DD)."));
            } else if (NUMEROS.contains(campo)) {
                BigDecimal v = cantidad(p, campo);
                if (v == null) {
                    c.putNull(campo);
                } else {
                    c.put(campo, v);
                }
            } else if (campo.equals("tipoPnc") || campo.equals("disposicion")) {
                String v = texto(p, campo, 50, false);
                if (v != null && !(campo.equals("tipoPnc") ? TIPOS_PNC : DISPOSICIONES).contains(v)) {
                    throw new Invalido(campo.equals("tipoPnc") ? "Tipo PNC inválido." : "Disposición inválida.");
                }
                ponerTexto(c, campo, v);
            } else if (MULTILINEA.contains(campo)) {
                ponerTexto(c, campo, texto(p, campo, -1, true));
            } else {
                ponerTexto(c, campo, texto(p, campo, TEXTOS.get(campo), false));
            }
        }
        return c;
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
                throw new Invalido("El número no es válido: " + clave + ".");
            }
        } else {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
        }
        if (v.signum() < 0 || v.compareTo(MAX_CANTIDAD) > 0) {
            throw new Invalido("El número no es válido: " + clave + ".");
        }
        return v.scale() < 0 ? v.setScale(0) : v;
    }

    /**
     * Cabecera de la NC vinculada, igual que _guardarNuevoPnc de la vista: tipo INTERNA, origen AUDITORIA_INTERNA, titulo
     * "PNC <npNv> - <producto|cliente>", descripcion "<categoriaDefecto> - <descripcionDefecto>", severidad por nivel, proceso
     * tipoPnc | area | "PNC Nueva", fechaDeteccion = fechaIngreso. Vínculo DATA_FARET (el origenId se completa con el id creado).
     */
    private ObjectNode cabeceraNc(ObjectNode c, String fechaIngreso) {
        String producto = c.get("producto").asString();
        String titulo = ("PNC " + c.get("npNv").asString() + " - " + (producto.isEmpty() ? c.get("cliente").asString() : producto)).strip();
        if (titulo.length() > 255) {
            throw new Invalido("El título de la no conformidad (PNC + NP/NV + producto) supera el máximo de 255 caracteres.");
        }
        String descripcion = Stream.of(c.get("categoriaDefecto").asString(), c.get("descripcionDefecto").asString())
                .filter(s -> !s.isEmpty()).collect(Collectors.joining(" - "));
        String tipoPnc = textoDe(c, "tipoPnc");
        String area = textoDe(c, "area");
        String proceso = !tipoPnc.isEmpty() ? tipoPnc : !area.isEmpty() ? area : "PNC Nueva";
        ObjectNode nc = mapper.createObjectNode();
        nc.put("tipo", "INTERNA");
        nc.put("origen", "AUDITORIA_INTERNA");
        nc.put("sistemaOrigen", "DATA_FARET");
        nc.putNull("origenId");
        nc.put("titulo", titulo);
        nc.put("descripcion", descripcion);
        nc.put("severidad", NoConformidadesBridgeHandler.severidadDeNivel(c.get("nivel").asString()));
        nc.put("proceso", proceso);
        nc.putNull("norma");
        nc.putNull("reportadoPor");
        nc.putNull("responsable");
        nc.put("fechaDeteccion", fechaIngreso);
        return nc;
    }

    private static boolean vacio(JsonNode n) {
        return n == null || n.isNull() || n.asString().isEmpty();
    }

    private static String textoDe(ObjectNode c, String clave) {
        JsonNode n = c.get(clave);
        return n == null || n.isNull() ? "" : n.asString();
    }
}
