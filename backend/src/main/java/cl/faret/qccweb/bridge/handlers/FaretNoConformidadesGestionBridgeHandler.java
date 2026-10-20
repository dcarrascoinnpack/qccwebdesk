package cl.faret.qccweb.bridge.handlers;

import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.Invalido;
import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.autorDeSesion;
import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.cerrada;
import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.enteroPositivo;
import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.idNc;
import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.texto;
import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.textoObligatorio;
import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.tieneSustitutoSuelto;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.bridge.LecturasDeSesion;
import cl.faret.qccweb.upstream.FaretApiClient;
import cl.faret.qccweb.upstream.FaretRespuestas;
import cl.faret.qccweb.upstream.InnpackApiClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fase F4a - ESCRITURAS MODIFICADORAS de No Conformidades FARET contra MejoraContinua (sin Authorization; el gateway es el único
 * control de acceso). Port de Photino 1.8.15 (9e1b556) FaretHandler.cs: HandleNcCreate / HandleNcUpdate / HandleNcGestionActualizar /
 * HandleNcCerrar / HandleNcAccionesActualizar (+ TryBuildNcRequest / TryBuildGestionRequest / TryBuildCerrarRequest /
 * TryBuildAccionActualizarRequest) y FaretNoConformidadesApiService. Payload PLANO. Sin reintentos (F0).
 *
 * IDENTIDAD: cerradoPor / actualizadoPor se aceptan en el payload (los manda la vista desde sessionStorage) pero ActionPolicy los
 * pisa con la sesión y el cuerpo se arma aquí con el nombre de la sesión. creadoPor/autor no existen en estas APIs. Los datos de
 * negocio (reportadoPor, responsable) los escribe el usuario y no se tocan.
 *
 * LOST UPDATE (update y gestion.actualizar): la API actualiza sin versión (PUT completo / PATCH con COALESCE). La vista de FARET abre
 * los formularios con los datos de faret.nc.list (no de faret.nc.get), así que faret.nc.list registra en la sesión la huella de CADA NC
 * y faret.nc.get la de la NC consultada (HuellasNc). Al guardar se exige que la sesión haya leído la NC, se relee y se compara; tras
 * escribir se vuelve a leer y a registrar (el modal sigue abierto y la lista no siempre se recarga). Límites: ventana mínima entre la
 * relectura y la escritura; varias pestañas de la MISMA sesión comparten la huella; Photino no protege.
 */
public class FaretNoConformidadesGestionBridgeHandler {

    static final String MENSAJE_NC_SIN_LEER = "Abre la no conformidad antes de editarla (actualiza la lista).";
    static final String MENSAJE_NC_CONFLICTO = "La no conformidad fue modificada por otra persona desde que cargaste la lista. "
            + "Actualiza la lista y vuelve a abrirla antes de guardar (no se guardó nada).";
    static final String MENSAJE_ACCION_SIN_LEER = "Abre el análisis de la no conformidad antes de actualizar la acción.";
    static final String MENSAJE_ACCION_CONFLICTO = "La acción fue modificada por otra persona desde que la abriste. "
            + "Vuelve a abrir el análisis para ver los cambios.";
    static final String MENSAJE_CIERRE_SIN_SEGUIMIENTO = "La no conformidad quedó cerrada, pero no se pudo registrar el comentario de "
            + "cierre en el seguimiento. Agrégalo como comentario de seguimiento.";
    static final String MENSAJE_CERRADA_SIN_PERMISO = "Solo administración o calidad puede dejar una no conformidad en estado CERRADA.";
    static final String MARCA_NC = "__qccAuditoriaNc";

    private static final String RUTA = "/api/no-conformidades";
    private static final String RUTA_ACCIONES = "/api/acciones-correctivas";
    /** Columnas de nc_no_conformidades (migration/sqlserver/mejora_continua_sqlserver_schema.sql). */
    private static final int MAX_TITULO = 255;
    private static final int MAX_PROCESO = 150;
    private static final int MAX_NORMA = 150;
    private static final int MAX_PERSONA = 150;
    private static final int MAX_AUTOR_CIERRE = 120;
    private static final int MAX_AUTOR_ACCION = 200;
    private static final int MAX_BYTES_TEXTO_LARGO = 65_535;

    private static final Set<String> TIPOS = Set.of("INTERNA", "EXTERNA");
    /** Opciones exactas de los <select> de la vista (fnc-form-origen / fnc-gdetalle-origen); la API responde 500 con cualquier otro. */
    private static final Set<String> ORIGENES = Set.of("AUDITORIA_INTERNA", "AUDITORIA_EXTERNA");
    private static final Set<String> SEVERIDADES = Set.of("ALTA", "MEDIA", "BAJA");
    /** sistemaOrigen que manda "Gestionar" (_confirmarCrearGestion). */
    private static final Set<String> SISTEMAS_ORIGEN = Set.of("DATA_FARET", "INSPECCION_FARET");
    /** Opciones exactas del <select id="fnc-gestion-estado">: PENDIENTE y CERRADA (la API aún acepta ASIGNADA/EN_GESTION). */
    private static final Set<String> ESTADOS_GESTION = Set.of("PENDIENTE", "CERRADA");
    /** Mismos roles que faret.nc.cerrar (BridgeConfig.ROLES_FARET_NC_CERRAR): CERRADA por gestion.actualizar no puede saltarse esa restricción. */
    private static final Set<String> ROLES_CIERRE = Set.of("admin", "admin_ti", "calidad");
    private static final Set<String> ESTADOS_ACCION =Set.of("PENDIENTE", "EN_PROCESO", "COMPLETADA", "CANCELADA");

    private static final Set<String> CLAVES_NC_BASE = Set.of("action", "tipo", "origen", "titulo", "descripcion", "severidad", "proceso",
            "norma", "reportadoPor", "responsable", "fechaDeteccion");
    private static final Set<String> CLAVES_CREAR = union(CLAVES_NC_BASE, "sistemaOrigen", "origenId");
    private static final Set<String> CLAVES_ACTUALIZAR = union(CLAVES_NC_BASE, "id");
    private static final Set<String> CLAVES_GESTION = Set.of("action", "id", "responsable", "estadoGestion", "fechaCompromiso",
            "actualizadoPor");
    private static final Set<String> CLAVES_CERRAR = Set.of("action", "id", "cerradoPor", "comentarioCierre");
    private static final Set<String> CLAVES_ACCION_ACTUALIZAR = Set.of("action", "accionId", "descripcion", "responsable", "fechaLimite",
            "prioridad", "estado", "actualizadoPor");

    private static final Pattern CONTROL_MULTILINEA = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern MARCADO_HTML = Pattern.compile("<[A-Za-z/!?]");
    private static final Pattern FECHA_ISO = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern SOLO_DIGITOS = Pattern.compile("[0-9]{1,18}");

    private final FaretApiClient mejoraContinua;
    private final ObjectMapper mapper;

    public FaretNoConformidadesGestionBridgeHandler(FaretApiClient mejoraContinua, ObjectMapper mapper) {
        this.mejoraContinua = mejoraContinua;
        this.mapper = mapper;
    }

    private static Set<String> union(Set<String> base, String... extra) {
        java.util.HashSet<String> s = new java.util.HashSet<>(base);
        s.addAll(List.of(extra));
        return Set.copyOf(s);
    }

    // ------------------------------------------------------------------------------------------- create

    /**
     * faret.nc.create -> POST api/no-conformidades {tipo, origen, sistemaOrigen, origenId, titulo, descripcion, severidad, proceso,
     * norma, reportadoPor, responsable, fechaDeteccion}. Lo usan "Nueva NC" (manual: sin sistemaOrigen/origenId) y "Gestionar" sobre una
     * fila de Data o de Inspección (sistemaOrigen DATA_FARET | INSPECCION_FARET + origenId como texto de dígitos). Obligatorios y mensajes
     * de Photino; además las opciones exactas de los select, los largos de las columnas, textos sin controles ni marcado HTML (la vista
     * los pinta con innerHTML) y fecha real. sistemaOrigen y origenId van juntos o ninguno. La API no valida duplicados de vínculo (igual
     * que Photino). Respuesta reproyectada a {id}.
     */
    public BridgeResult crear(ObjectNode payload, SessionUser usuario) {
        if (!mejoraContinua.configurada()) {
            return BridgeResult.error(FaretBridgeHandler.MENSAJE_MC_NO_CONFIGURADA);
        }
        ObjectNode cuerpo;
        try {
            cerrada(payload, CLAVES_CREAR);
            cuerpo = cuerpoNc(payload, true);
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        return proyectar(FaretRespuestas.crudoMc(mejoraContinua.postJson(usuario, RUTA, cuerpo), mapper), "id");
    }

    // ------------------------------------------------------------------------------------------- update

    /**
     * faret.nc.update -> PUT api/no-conformidades/{id} (PUT completo, sin versión) con el mismo cuerpo que create sin vínculo (la API
     * actualiza tipo, origen, titulo, descripcion, severidad, proceso, norma y responsable; ignora reportadoPor, fechaDeteccion y el
     * vínculo). Lost update por huella (ver la clase). Una NC cerrada se puede editar, igual que en Photino. Respuesta {mensaje}.
     *
     * Ojo (comportamiento de Photino, replicado): la API pisa `responsable` sin COALESCE; el formulario "Editar" de la lista lo envía
     * vacío (la vista no lo precarga) y lo borra; el detalle de "Gestionar" sí manda el responsable actual.
     */
    public BridgeResult actualizar(ObjectNode payload, SessionUser usuario) {
        if (!mejoraContinua.configurada()) {
            return BridgeResult.error(FaretBridgeHandler.MENSAJE_MC_NO_CONFIGURADA);
        }
        int id;
        ObjectNode cuerpo;
        try {
            cerrada(payload, CLAVES_ACTUALIZAR);
            id = idNc(payload);
            cuerpo = cuerpoNc(payload, false);
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        BridgeResult conflicto = verificarHuella(id, usuario);
        if (conflicto != null) {
            return conflicto;
        }
        BridgeResult resultado = proyectar(FaretRespuestas.crudoMc(mejoraContinua.putJson(usuario, RUTA + "/" + id, cuerpo), mapper), "mensaje");
        if (resultado.ok()) {
            reregistrar(id, usuario);
        }
        return resultado;
    }

    // ------------------------------------------------------------------------------------------- gestion.actualizar

    /**
     * faret.nc.gestion.actualizar -> PATCH api/no-conformidades/{id}/gestion {responsable, estadoGestion, fechaCompromiso,
     * actualizadoPor}. estadoGestion: PENDIENTE o CERRADA (las opciones de la vista; obligatorio: un "" lo guardaría la API como
     * estado vacío). La API usa COALESCE: un null no cambia el campo (no se puede borrar la fecha compromiso). CERRADA por esta vía
     * NO registra fecha de cierre ni quién cerró (eso lo hace faret.nc.cerrar): como Photino lo ofrece a todos los roles, aquí solo
     * se permite a admin/admin_ti/calidad (endurecimiento F4, decisión del usuario 2026-10-20); PENDIENTE sigue abierto a todos los
     * roles de la acción. actualizadoPor = sesión. Lost update por huella.
     */
    public BridgeResult gestionActualizar(ObjectNode payload, SessionUser usuario) {
        if (!mejoraContinua.configurada()) {
            return BridgeResult.error(FaretBridgeHandler.MENSAJE_MC_NO_CONFIGURADA);
        }
        int id;
        ObjectNode cuerpo = mapper.createObjectNode();
        try {
            cerrada(payload, CLAVES_GESTION);
            id = idNc(payload);
            String responsable = texto(payload, "responsable", MAX_PERSONA, false);
            String estado = texto(payload, "estadoGestion", 30, false);
            if (estado == null || estado.isEmpty()) {
                throw new Invalido("Falta el estado de gestión");
            }
            if (!ESTADOS_GESTION.contains(estado)) {
                throw new Invalido("Estado de gestión inválido. Valores permitidos: PENDIENTE, CERRADA");
            }
            if ("CERRADA".equals(estado) && (usuario.rol() == null || !ROLES_CIERRE.contains(usuario.rol().toLowerCase(java.util.Locale.ROOT)))) {
                throw new Invalido(MENSAJE_CERRADA_SIN_PERMISO);
            }
            String fecha = fechaOpcional(payload, "fechaCompromiso", "La fecha compromiso no es válida (formato AAAA-MM-DD).");
            ponerTexto(cuerpo, "responsable", responsable);
            cuerpo.put("estadoGestion", estado);
            ponerTexto(cuerpo, "fechaCompromiso", fecha);
            cuerpo.put("actualizadoPor", autorDeSesion(usuario, MAX_AUTOR_ACCION));
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        BridgeResult conflicto = verificarHuella(id, usuario);
        if (conflicto != null) {
            return conflicto;
        }
        BridgeResult resultado = proyectar(
                FaretRespuestas.crudoMc(mejoraContinua.patchJson(usuario, RUTA + "/" + id + "/gestion", cuerpo), mapper), "mensaje");
        if (resultado.ok()) {
            reregistrar(id, usuario);
        }
        return resultado;
    }

    // ------------------------------------------------------------------------------------------- cerrar

    /**
     * faret.nc.cerrar -> POST api/no-conformidades/{id}/cerrar {cerradoPor, comentarioCierre}. Solo admin, admin_ti y calidad
     * (decisión del usuario: el cierre es irreversible desde la vista). cerradoPor = SESIÓN (la API lo exige y lo guarda como quién
     * cerró, con la fecha). comentarioCierre opcional (textarea: multilínea, ≤ 65.535 bytes, sin controles ni HTML).
     *
     * Comportamiento de la API (replicado): cierra sin comprobar el estado (cerrar una NC ya cerrada vuelve a registrar quién, cuándo y
     * el comentario) y, DESPUÉS de cerrar, crea el seguimiento "NC cerrada. <comentario>" con autor = cerradoPor; si ese segundo paso
     * falla la API responde error con la NC YA cerrada. Aquí: se relee la NC antes (404 -> error) y, si el cierre falla con comentario,
     * se vuelve a leer y, si la NC pasó a cerrada, el error informa que quedó cerrada sin su comentario en el seguimiento.
     */
    public BridgeResult cerrar(ObjectNode payload, SessionUser usuario) {
        if (!mejoraContinua.configurada()) {
            return BridgeResult.error(FaretBridgeHandler.MENSAJE_MC_NO_CONFIGURADA);
        }
        int id;
        String comentario;
        String cerradoPor;
        try {
            cerrada(payload, CLAVES_CERRAR);
            id = idNc(payload);
            comentario = comentarioCierre(payload.get("comentarioCierre"));
            cerradoPor = autorDeSesion(usuario, MAX_AUTOR_CIERRE);
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        BridgeResult antes = leerNc(id, usuario);
        if (!antes.ok()) {
            return antes;
        }
        boolean yaCerrada = tieneFechaCierre(antes.data());
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("cerradoPor", cerradoPor);
        if (comentario == null) {
            cuerpo.putNull("comentarioCierre");
        } else {
            cuerpo.put("comentarioCierre", comentario);
        }
        BridgeResult resultado = proyectar(
                FaretRespuestas.crudoMc(mejoraContinua.postJson(usuario, RUTA + "/" + id + "/cerrar", cuerpo), mapper), "mensaje");
        if (resultado.ok()) {
            reregistrar(id, usuario);
            return resultado;
        }
        if (comentario != null && !yaCerrada) {
            BridgeResult despues = leerNc(id, usuario);
            if (despues.ok() && tieneFechaCierre(despues.data())) {
                HuellasNc.invalidar(id);
                return BridgeResult.error(MENSAJE_CIERRE_SIN_SEGUIMIENTO);
            }
        }
        return resultado;
    }

    // ------------------------------------------------------------------------------------------- acciones.actualizar

    /**
     * faret.nc.acciones.actualizar -> PUT api/acciones-correctivas/{accionId} {descripcion, responsable, fechaLimite, prioridad,
     * estado, actualizadoPor}. La vista (_actualizarEstadoAccion) solo deja cambiar el ESTADO y reenvía el resto copiado de
     * faret.nc.acciones.list. La web (igual que noConformidades.acciones.actualizar, 3n): exige que la acción se haya visto en esta
     * sesión (acciones.list registra NC + valores ORIGINALES, sin escapar), toma del navegador SOLO `estado` y envía
     * descripcion/responsable/fecha/prioridad ORIGINALES (así no hay doble escape ni falsificación de lo que la vista reescribe, porque
     * acciones.list entrega esos textos escapados como HTML); `actualizadoPor` = sesión; relee las acciones de la NC antes del PUT y
     * rechaza si la acción ya no existe o cambió (lost update). NC cerrada: permitido, igual que Photino.
     */
    public BridgeResult accionesActualizar(ObjectNode payload, SessionUser usuario) {
        if (!mejoraContinua.configurada()) {
            return BridgeResult.error(FaretBridgeHandler.MENSAJE_MC_NO_CONFIGURADA);
        }
        int accionId;
        String estado;
        String actualizadoPor;
        try {
            cerrada(payload, CLAVES_ACCION_ACTUALIZAR);
            Integer aid = enteroPositivo(payload.get("accionId"));
            if (aid == null) {
                throw new Invalido("Falta el id de la acción correctiva");
            }
            accionId = aid;
            for (String campo : new String[] {"descripcion", "responsable", "fechaLimite", "prioridad"}) {
                JsonNode n = payload.get(campo);
                if (n != null && !n.isNull() && !n.isString()) {
                    throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
                }
            }
            estado = textoObligatorio(payload, "estado", 30, "Falta el estado", false);
            if (!ESTADOS_ACCION.contains(estado)) {
                throw new Invalido("Estado inválido. Valores permitidos: PENDIENTE, EN_PROCESO, COMPLETADA, CANCELADA");
            }
            actualizadoPor = autorDeSesion(usuario, MAX_AUTOR_ACCION);
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        String registro = LecturasDeSesion.huella(recursoLecturaAccion(accionId));
        int corte = registro == null ? -1 : registro.indexOf('|');
        if (corte <= 0) {
            return BridgeResult.error(MENSAJE_ACCION_SIN_LEER);
        }
        int ncId = Integer.parseInt(registro.substring(0, corte));
        String original = registro.substring(corte + 1);
        BridgeResult vigentes = FaretRespuestas.crudoMc(mejoraContinua.get(usuario, RUTA + "/" + ncId + "/acciones"), mapper);
        if (!vigentes.ok()) {
            return vigentes;
        }
        JsonNode actual = null;
        if (vigentes.data() instanceof ArrayNode items) {
            for (JsonNode item : items) {
                JsonNode aid = item.get("id");
                if (aid != null && aid.canConvertToInt() && aid.asInt() == accionId) {
                    actual = item;
                }
            }
        }
        if (actual == null) {
            return BridgeResult.error("La acción correctiva ya no existe.");
        }
        if (!actual.toString().equals(original)) {
            return BridgeResult.error(MENSAJE_ACCION_CONFLICTO);
        }
        String fechaLimite = textoDe(actual, "fechaLimite");
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("descripcion", textoDe(actual, "descripcion"));
        cuerpo.put("responsable", textoDe(actual, "responsable"));
        cuerpo.put("fechaLimite", fechaLimite.length() > 10 ? fechaLimite.substring(0, 10) : fechaLimite);
        String prioridad = textoDe(actual, "prioridad");
        if (prioridad.isEmpty()) {
            cuerpo.putNull("prioridad");
        } else {
            cuerpo.put("prioridad", prioridad);
        }
        cuerpo.put("estado", estado);
        cuerpo.put("actualizadoPor", actualizadoPor);
        // Canal interno para la auditoría (el payload saneado no vuelve al navegador ni a la API).
        payload.put(MARCA_NC, ncId);
        BridgeResult resultado = proyectar(
                FaretRespuestas.crudoMc(mejoraContinua.putJson(usuario, RUTA_ACCIONES + "/" + accionId, cuerpo), mapper), "mensaje");
        if (resultado.ok()) {
            // La vista recarga acciones.list enseguida y vuelve a registrar la acción con su nuevo estado.
            LecturasDeSesion.olvidar(recursoLecturaAccion(accionId));
        }
        return resultado;
    }

    /** Registro por sesión de una acción tal como la devolvió la API (acciones.list, ANTES de escapar): "ncId|json". */
    static String recursoLecturaAccion(int accionId) {
        return "faret-nc-accion:" + accionId;
    }

    // ------------------------------------------------------------------------------------------- auditoría

    /** "faret:nc:<id>:crear" (id de la respuesta; "nueva" si no lo trae). */
    public static String recursoCrear(ObjectNode payload, Object dataRespuesta) {
        JsonNode id = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("id") : null;
        return "faret:nc:" + (id != null && id.isIntegralNumber() && id.canConvertToLong() ? id.asLong() : "nueva") + ":crear";
    }

    /** "faret:nc:<id>:actualizar". */
    public static String recursoActualizar(ObjectNode payload, Object dataRespuesta) {
        return FaretNoConformidadesBridgeHandler.recursoNc(payload) + ":actualizar";
    }

    /** "faret:nc:<id>:gestion[:ESTADO]" (solo estados válidos). */
    public static String recursoGestion(ObjectNode payload, Object dataRespuesta) {
        JsonNode e = payload.get("estadoGestion");
        String estado = e != null && e.isString() ? e.asString().strip() : "";
        return FaretNoConformidadesBridgeHandler.recursoNc(payload) + ":gestion" + (ESTADOS_GESTION.contains(estado) ? ":" + estado : "");
    }

    /** "faret:nc:<id>:cierre". */
    public static String recursoCierre(ObjectNode payload, Object dataRespuesta) {
        return FaretNoConformidadesBridgeHandler.recursoNc(payload) + ":cierre";
    }

    /** "faret:nc:<ncId>:accion:<accionId>[:ESTADO]" (ncId del registro de la sesión; solo valores validados). */
    public static String recursoAccionActualizada(ObjectNode payload, Object dataRespuesta) {
        JsonNode nc = payload.get(MARCA_NC);
        JsonNode a = payload.get("accionId");
        Integer accionId;
        try {
            accionId = enteroPositivo(a);
        } catch (Invalido e) {
            accionId = null;
        }
        JsonNode e = payload.get("estado");
        String estado = e != null && e.isString() ? e.asString().strip() : "";
        return "faret:nc:" + (nc != null && nc.canConvertToInt() ? nc.asInt() : "?") + ":accion:" + (accionId == null ? "?" : accionId)
                + (ESTADOS_ACCION.contains(estado) ? ":" + estado : "");
    }

    // ------------------------------------------------------------------------------------------- huella de lectura

    /** Lectura de una NC (404 -> "No conformidad no encontrada"). */
    private BridgeResult leerNc(int id, SessionUser usuario) {
        InnpackApiClient.Respuesta r = mejoraContinua.get(usuario, RUTA + "/" + id);
        if (r.status() == 404) {
            return BridgeResult.error(FaretNoConformidadesBridgeHandler.MENSAJE_NC_NO_ENCONTRADA);
        }
        return FaretRespuestas.crudoMc(r, mapper);
    }

    /** null = la sesión leyó la NC y sigue igual; si no, el resultado de error (sin leer, inexistente o modificada). */
    private BridgeResult verificarHuella(int id, SessionUser usuario) {
        String leida = HuellasNc.leida(id);
        if (leida == null) {
            return BridgeResult.error(MENSAJE_NC_SIN_LEER);
        }
        BridgeResult vigente = leerNc(id, usuario);
        if (!vigente.ok()) {
            return vigente;
        }
        if (!HuellasNc.de(vigente.data()).equals(leida)) {
            return BridgeResult.error(MENSAJE_NC_CONFLICTO);
        }
        return null;
    }

    /** Tras escribir: la NC que la vista queda mostrando es la recién guardada; si no se puede releer, la huella queda inválida. */
    private void reregistrar(int id, SessionUser usuario) {
        BridgeResult releida = leerNc(id, usuario);
        if (releida.ok()) {
            HuellasNc.registrar(id, releida.data());
        } else {
            HuellasNc.invalidar(id);
        }
    }

    private static boolean tieneFechaCierre(Object data) {
        JsonNode f = data instanceof JsonNode d && d.isObject() ? d.get("fechaCierre") : null;
        return f != null && !f.isNull() && !(f.isString() && f.asString().isBlank());
    }

    /**
     * Huellas de NC de la SESIÓN, en UNA sola entrada de LecturasDeSesion ("id=huella" por línea): faret.nc.list registra todas las NC
     * de la lista (reemplaza el conjunto) y faret.nc.get / una escritura registran una. Un valor INVALIDA nunca coincide con una huella.
     */
    public static final class HuellasNc {

        private static final String CLAVE = "faret-nc-huellas";
        private static final String INVALIDA = "INVALIDA";
        private static final int MAX_NC = 10_000;

        private HuellasNc() {}

        /** Huella (SHA-256 abreviado) de los campos del detalle vigente de una NC: cualquier cambio de contenido o estado la altera. */
        static String de(Object data) {
            if (!(data instanceof JsonNode d) || !d.isObject()) {
                return "SIN_NC";
            }
            StringBuilder sb = new StringBuilder();
            for (String campo : d.propertyNames().stream().sorted().toList()) {
                sb.append(campo).append('=').append(d.get(campo).toString()).append('\n');
            }
            try {
                byte[] h = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(h, 0, 16);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /** faret.nc.list: la sesión queda con la huella de cada NC de la lista (los ítems sin id entero se ignoran). */
        public static synchronized void registrarLista(Object data) {
            if (!(data instanceof ArrayNode items)) {
                return;
            }
            Map<Integer, String> mapa = new LinkedHashMap<>();
            for (JsonNode item : items) {
                JsonNode id = item.isObject() ? item.get("id") : null;
                if (id != null && id.isIntegralNumber() && id.canConvertToInt() && id.asInt() > 0 && mapa.size() < MAX_NC) {
                    mapa.put(id.asInt(), de(item));
                }
            }
            guardar(mapa);
        }

        public static synchronized void registrar(int id, Object data) {
            Map<Integer, String> mapa = leer();
            mapa.put(id, de(data));
            guardar(mapa);
        }

        public static synchronized void invalidar(int id) {
            Map<Integer, String> mapa = leer();
            mapa.put(id, INVALIDA);
            guardar(mapa);
        }

        static synchronized String leida(int id) {
            return leer().get(id);
        }

        private static Map<Integer, String> leer() {
            Map<Integer, String> mapa = new LinkedHashMap<>();
            String s = LecturasDeSesion.huella(CLAVE);
            if (s != null) {
                for (String linea : s.split("\n")) {
                    int i = linea.indexOf('=');
                    if (i > 0) {
                        try {
                            mapa.put(Integer.parseInt(linea.substring(0, i)), linea.substring(i + 1));
                        } catch (NumberFormatException e) {
                            // línea ajena: se descarta
                        }
                    }
                }
            }
            return mapa;
        }

        private static void guardar(Map<Integer, String> mapa) {
            StringBuilder sb = new StringBuilder();
            mapa.forEach((id, h) -> sb.append(id).append('=').append(h).append('\n'));
            LecturasDeSesion.registrar(CLAVE, sb.toString());
        }
    }

    // ------------------------------------------------------------------------------------------- validación

    /**
     * Cuerpo de create/update en el orden de TryBuildNcRequest. Las respuestas de Photino a un campo ausente son null; un texto vacío
     * (la vista manda "" en norma/reportadoPor/responsable) se conserva como "".
     */
    private ObjectNode cuerpoNc(ObjectNode p, boolean creacion) {
        String tipo = textoObligatorio(p, "tipo", 7, "Falta el tipo", false);
        if (!TIPOS.contains(tipo)) {
            throw new Invalido("Tipo inválido. Valores permitidos: INTERNA, EXTERNA");
        }
        String origen = textoObligatorio(p, "origen", 17, "Falta el origen", false);
        if (!ORIGENES.contains(origen)) {
            throw new Invalido("Origen inválido. Valores permitidos: AUDITORIA_INTERNA, AUDITORIA_EXTERNA");
        }
        String titulo = textoObligatorio(p, "titulo", MAX_TITULO, "Falta el título", false);
        String descripcion = textoObligatorio(p, "descripcion", -1, "Falta la descripción", true);
        String severidad = textoObligatorio(p, "severidad", 7, "Falta la severidad", false);
        if (!SEVERIDADES.contains(severidad)) {
            throw new Invalido("Severidad inválida. Valores permitidos: ALTA, MEDIA, BAJA");
        }
        String proceso = textoObligatorio(p, "proceso", MAX_PROCESO, "Falta el proceso", false);
        String fecha = fechaOpcional(p, "fechaDeteccion", "La fecha de detección no es válida (formato AAAA-MM-DD).");
        if (fecha == null) {
            throw new Invalido("Falta la fecha de detección");
        }
        String norma = texto(p, "norma", MAX_NORMA, false);
        String reportadoPor = texto(p, "reportadoPor", MAX_PERSONA, false);
        String responsable = texto(p, "responsable", MAX_PERSONA, false);
        String sistemaOrigen = null;
        String origenId = null;
        if (creacion) {
            sistemaOrigen = texto(p, "sistemaOrigen", 30, false);
            origenId = origenId(p.get("origenId"));
            if (sistemaOrigen != null && sistemaOrigen.isEmpty()) {
                sistemaOrigen = null;
            }
            if ((sistemaOrigen == null) != (origenId == null)) {
                throw new Invalido("El vínculo de origen requiere sistemaOrigen y origenId.");
            }
            if (sistemaOrigen != null && !SISTEMAS_ORIGEN.contains(sistemaOrigen)) {
                throw new Invalido("Sistema de origen inválido. Valores permitidos: DATA_FARET, INSPECCION_FARET");
            }
        }
        ObjectNode c = mapper.createObjectNode();
        c.put("tipo", tipo);
        c.put("origen", origen);
        ponerTexto(c, "sistemaOrigen", sistemaOrigen);
        ponerTexto(c, "origenId", origenId);
        c.put("titulo", titulo);
        c.put("descripcion", descripcion);
        c.put("severidad", severidad);
        c.put("proceso", proceso);
        ponerTexto(c, "norma", norma);
        ponerTexto(c, "reportadoPor", reportadoPor);
        ponerTexto(c, "responsable", responsable);
        c.put("fechaDeteccion", fecha);
        return c;
    }

    /** origenId del vínculo: texto de dígitos (la vista manda String(id)) o entero positivo; ausente/null/vacío -> null. */
    private static String origenId(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        String t;
        if (n.isString()) {
            t = n.asString().strip();
        } else if (n.isIntegralNumber() && n.canConvertToLong()) {
            t = Long.toString(n.asLong());
        } else {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
        }
        if (t.isEmpty()) {
            return null;
        }
        if (!SOLO_DIGITOS.matcher(t).matches() || Long.parseLong(t) <= 0) {
            throw new Invalido("El origenId no es válido.");
        }
        return t;
    }

    /** Fecha del date picker: ausente/null/vacía -> null; si viene, string AAAA-MM-DD de un día real. */
    static String fechaOpcional(ObjectNode p, String clave, String mensaje) {
        String t = texto(p, clave, 64, false);
        if (t == null || t.isEmpty()) {
            return null;
        }
        if (!FECHA_ISO.matcher(t).matches()) {
            throw new Invalido(mensaje);
        }
        try {
            LocalDate.parse(t);
        } catch (DateTimeParseException e) {
            throw new Invalido(mensaje);
        }
        return t;
    }

    /** Comentario de cierre (opcional): como el de seguimiento (multilínea, ≤ 65.535 bytes, sin controles ni marcado HTML). */
    private static String comentarioCierre(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isString()) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
        }
        String c = n.asString().strip();
        if (c.isEmpty()) {
            return null;
        }
        if (c.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES_TEXTO_LARGO) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_COMENTARIO_LARGO);
        }
        if (CONTROL_MULTILINEA.matcher(c).find() || tieneSustitutoSuelto(c)) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_COMENTARIO_CARACTERES);
        }
        if (MARCADO_HTML.matcher(c).find()) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_COMENTARIO_HTML);
        }
        return c;
    }

    static void ponerTexto(ObjectNode c, String clave, String valor) {
        if (valor == null) {
            c.putNull(clave);
        } else {
            c.put(clave, valor);
        }
    }

    private static String textoDe(JsonNode objeto, String campo) {
        JsonNode nodo = objeto == null ? null : objeto.get(campo);
        return nodo != null && (nodo.isString() || nodo.isNumber()) ? nodo.asString() : "";
    }

    /** Solo los campos escalares pedidos de la respuesta de la API; nada más de MejoraContinua llega al navegador. */
    private BridgeResult proyectar(BridgeResult upstream, String... campos) {
        if (!upstream.ok()) {
            return upstream;
        }
        ObjectNode salida = mapper.createObjectNode();
        if (upstream.data() instanceof JsonNode d && d.isObject()) {
            for (String campo : campos) {
                JsonNode v = d.get(campo);
                if (v != null && (v.isString() || v.isNumber() || v.isBoolean())) {
                    salida.set(campo, v);
                }
            }
        }
        return BridgeResult.ok(salida);
    }
}
