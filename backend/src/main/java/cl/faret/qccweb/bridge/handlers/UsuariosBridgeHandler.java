package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.bridge.PermisosModulo;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Gestión de Usuarios" (INNPACK) — SOLO LECTURA. Port de Photino
 * src/Backend/Modules/Usuarios/UsuariosHandler.cs (ListAsync) + InnpackUsuariosApiService.
 *
 * Solo admin/admin_ti (UsuariosHandler.IsAdmin y [Authorize(Roles = "admin,admin_ti")] en la API):
 * la restricción de rol vive en la regla de ActionPolicy, así que un operador ni siquiera llega aquí.
 *
 * usuarios.list: la API devuelve UsuarioDto en camelCase y la vista lee PascalCase. Photino
 * deserializa (case-insensitive) a un DTO de 7 campos y lo reproyecta; aquí se hace lo mismo con la
 * lista EXACTA de campos, de modo que cualquier dato extra de la API (p. ej. un hash) nunca llega al
 * navegador. Campos ausentes → los defaults del DTO de Photino (0, "", false, null).
 *
 * Photino 1.8.23: escrituras create, resetPassword, activo, cambiarRol, eliminarDefinitivo y permisos.{get,guardar,restablecer}
 * (usuarios.delete no la usa ningún frontend). Lista cerrada de claves por acción, id int32 positivo, textos acotados y sin
 * controles, contraseñas recortadas como Photino y nunca registradas ni auditadas. Además (web): una cuenta no puede
 * desactivarse, cambiarse el rol ni eliminarse a sí misma. La validación de negocio (rol válido, duplicados, política de
 * contraseña) sigue siendo de la API.
 */
public class UsuariosBridgeHandler {

    static final String MENSAJE_RESPUESTA_INVALIDA = "Error al comunicarse con la API Innpack";

    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public UsuariosBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /** usuarios.list → GET api/usuarios, reproyectado a {Id, CodigoUsuario, NombreCompleto, Rol, Activo, CreadoEn, ActualizadoEn}. */
    public BridgeResult list(ObjectNode payload, SessionUser usuario) {
        BridgeResult upstream = InnpackRespuestas.reenviar(api.get(usuario, "/api/usuarios"), mapper);
        if (!upstream.ok()) {
            return upstream;
        }
        ArrayNode salida = mapper.createArrayNode();
        JsonNode data = upstream.data() instanceof JsonNode n ? n : null;
        if (data == null || data.isNull()) {
            return BridgeResult.ok(salida);
        }
        if (!data.isArray()) {
            return BridgeResult.error(MENSAJE_RESPUESTA_INVALIDA);
        }
        try {
            for (JsonNode item : data) {
                if (!item.isObject()) {
                    throw new IllegalArgumentException();
                }
                ObjectNode u = salida.addObject();
                JsonNode id = campo(item, "id");
                if (id != null && !(id.isIntegralNumber() && id.canConvertToInt())) {
                    throw new IllegalArgumentException();
                }
                u.put("Id", id == null ? 0 : id.asInt());
                u.put("CodigoUsuario", texto(campo(item, "codigoUsuario"), ""));
                u.put("NombreCompleto", texto(campo(item, "nombreCompleto"), ""));
                u.put("Rol", texto(campo(item, "rol"), ""));
                JsonNode activo = campo(item, "activo");
                if (activo != null && !activo.isBoolean()) {
                    throw new IllegalArgumentException();
                }
                u.put("Activo", activo != null && activo.asBoolean());
                u.put("CreadoEn", texto(campo(item, "creadoEn"), null));
                u.put("ActualizadoEn", texto(campo(item, "actualizadoEn"), null));
                // Política de contraseñas (Photino 1.8.23, columna "Clave" de Gestión de Usuarios).
                u.put("PasswordActualizadaEn", texto(campo(item, "passwordActualizadaEn"), null));
                u.put("PasswordVenceEl", texto(campo(item, "passwordVenceEl"), null));
                for (String booleano : List.of("debeCambiarPassword", "passwordSinVencimiento")) {
                    JsonNode valor = campo(item, booleano);
                    if (valor != null && !valor.isBoolean()) {
                        throw new IllegalArgumentException();
                    }
                    u.put(Character.toUpperCase(booleano.charAt(0)) + booleano.substring(1), valor != null && valor.asBoolean());
                }
            }
        } catch (IllegalArgumentException e) {
            // Photino (System.Text.Json) no convierte tipos: un campo con tipo distinto hace fallar la lista.
            return BridgeResult.error(MENSAJE_RESPUESTA_INVALIDA);
        }
        return BridgeResult.ok(salida);
    }

    // ------------------------------------------------------------------ Photino 1.8.23: escrituras de Gestión de Usuarios
    static final String MENSAJE_DATOS = "Datos inválidos";
    static final String MENSAJE_ID = "Id inválido";
    static final String MENSAJE_CODIGO = "El código de usuario es obligatorio";
    static final String MENSAJE_NOMBRE = "El nombre completo es obligatorio";
    static final String MENSAJE_PASSWORD = "La contraseña es obligatoria";
    static final String MENSAJE_NUEVA_PASSWORD = "La nueva contraseña es obligatoria";
    static final String MENSAJE_ROL = "El rol es obligatorio";
    static final String MENSAJE_PERMISOS = "No se recibieron permisos";
    static final String MENSAJE_PERMISOS_INVALIDOS = "Los permisos recibidos no son válidos";
    static final String MENSAJE_SI_MISMO = "No puedes aplicar esta acción sobre tu propio usuario";
    static final int MAX_CODIGO = 50;
    static final int MAX_NOMBRE = 150;
    static final int MAX_ROL = 30;
    static final int MAX_PASSWORD = 256;
    private static final java.util.regex.Pattern CONTROL = java.util.regex.Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final Set<String> NIVELES = Set.of("SIN_ACCESO", "VER", "EDITAR");
    private static final Set<String> CLAVES_CREATE = Set.of("codigoUsuario", "nombreCompleto", "password", "rol", "activo");
    private static final Set<String> CLAVES_RESET = Set.of("id", "nuevaPassword");
    private static final Set<String> CLAVES_ACTIVO = Set.of("id", "activo");
    private static final Set<String> CLAVES_ROL = Set.of("id", "rol");
    private static final Set<String> CLAVES_ID = Set.of("id");
    private static final Set<String> CLAVES_PERMISOS_GET = Set.of("id", "rol");
    private static final Set<String> CLAVES_PERMISOS_GUARDAR = Set.of("id", "permisos");

    /** usuarios.create → POST api/usuarios {codigoUsuario, nombreCompleto, password, rol, activo}. La contraseña nunca se registra. */
    public BridgeResult create(ObjectNode payload, SessionUser usuario) {
        JsonNode data = payload.get("data");
        if (data == null || !data.isObject() || soloClaves(data, CLAVES_CREATE) == null) {
            return BridgeResult.error(MENSAJE_DATOS);
        }
        String codigo = cadena(data, "codigoUsuario");
        String nombre = cadena(data, "nombreCompleto");
        String password = cadena(data, "password");
        String rol = cadena(data, "rol");
        Boolean activo = booleano(data, "activo", true);
        if (codigo == null || nombre == null || password == null || rol == null || activo == null) {
            return BridgeResult.error(MENSAJE_DATOS);
        }
        if (codigo.isEmpty()) {
            return BridgeResult.error(MENSAJE_CODIGO);
        }
        if (nombre.isEmpty()) {
            return BridgeResult.error(MENSAJE_NOMBRE);
        }
        if (password.isEmpty()) {
            return BridgeResult.error(MENSAJE_PASSWORD);
        }
        if (rol.isEmpty()) {
            return BridgeResult.error(MENSAJE_ROL);
        }
        if (codigo.length() > MAX_CODIGO || nombre.length() > MAX_NOMBRE || rol.length() > MAX_ROL
                || password.length() > MAX_PASSWORD || CONTROL.matcher(codigo + nombre + rol).find()) {
            return BridgeResult.error(MENSAJE_DATOS);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("codigoUsuario", codigo);
        cuerpo.put("nombreCompleto", nombre);
        cuerpo.put("password", password);
        cuerpo.put("rol", rol);
        cuerpo.put("activo", activo);
        BridgeResult r = InnpackRespuestas.reenviar(api.postJson(usuario, "/api/usuarios", cuerpo), mapper);
        if (!r.ok()) {
            return r;
        }
        ObjectNode salida = mapper.createObjectNode();
        salida.put("message", "Usuario creado correctamente");
        JsonNode id = r.data() instanceof JsonNode d && d.isObject() ? d.get("id") : null;
        if (id != null && id.isIntegralNumber() && id.canConvertToInt()) {
            salida.put("id", id.asInt());
        } else {
            salida.putNull("id");
        }
        return BridgeResult.ok(salida);
    }

    /** usuarios.resetPassword → PUT api/usuarios/{id}/password {nuevaPassword}. La contraseña nunca se registra. */
    public BridgeResult resetPassword(ObjectNode payload, SessionUser usuario) {
        JsonNode data = payload.get("data");
        if (data == null || !data.isObject() || soloClaves(data, CLAVES_RESET) == null) {
            return BridgeResult.error(MENSAJE_DATOS);
        }
        int id = entero(data.get("id"));
        String nueva = cadena(data, "nuevaPassword");
        if (nueva == null) {
            return BridgeResult.error(MENSAJE_DATOS);
        }
        if (id <= 0) {
            return BridgeResult.error(MENSAJE_ID);
        }
        if (nueva.isEmpty()) {
            return BridgeResult.error(MENSAJE_NUEVA_PASSWORD);
        }
        if (nueva.length() > MAX_PASSWORD) {
            return BridgeResult.error(MENSAJE_DATOS);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("nuevaPassword", nueva);
        return confirmar(api.putJson(usuario, "/api/usuarios/" + id + "/password", cuerpo), "Contraseña actualizada correctamente");
    }

    /** usuarios.activo → PUT api/usuarios/{id}/activo {activo}; desactivarse a uno mismo se rechaza. */
    public BridgeResult activo(ObjectNode payload, SessionUser usuario) {
        JsonNode data = datos(payload);
        if (soloClaves(data, CLAVES_ACTIVO) == null) {
            return BridgeResult.error(MENSAJE_DATOS);
        }
        int id = entero(data.get("id"));
        if (id <= 0) {
            return BridgeResult.error(MENSAJE_ID);
        }
        Boolean activo = booleano(data, "activo", true);
        if (activo == null) {
            return BridgeResult.error(MENSAJE_DATOS);
        }
        if (!activo && id == usuario.userId()) {
            return BridgeResult.error(MENSAJE_SI_MISMO);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("activo", activo);
        return confirmar(api.putJson(usuario, "/api/usuarios/" + id + "/activo", cuerpo),
                activo ? "Usuario activado correctamente" : "Usuario desactivado correctamente");
    }

    /** usuarios.eliminarDefinitivo → DELETE api/usuarios/{id}/definitivo (la API solo lo permite sin historial). */
    public BridgeResult eliminarDefinitivo(ObjectNode payload, SessionUser usuario) {
        JsonNode data = datos(payload);
        if (soloClaves(data, CLAVES_ID) == null) {
            return BridgeResult.error(MENSAJE_DATOS);
        }
        int id = entero(data.get("id"));
        if (id <= 0) {
            return BridgeResult.error(MENSAJE_ID);
        }
        if (id == usuario.userId()) {
            return BridgeResult.error(MENSAJE_SI_MISMO);
        }
        return confirmar(api.delete(usuario, "/api/usuarios/" + id + "/definitivo"), "Usuario eliminado definitivamente");
    }

    /** usuarios.cambiarRol → PUT api/usuarios/{id}/rol {rol}; cambiar el propio rol se rechaza. */
    public BridgeResult cambiarRol(ObjectNode payload, SessionUser usuario) {
        JsonNode data = datos(payload);
        if (soloClaves(data, CLAVES_ROL) == null) {
            return BridgeResult.error(MENSAJE_DATOS);
        }
        int id = entero(data.get("id"));
        String rol = cadena(data, "rol");
        if (rol == null) {
            return BridgeResult.error(MENSAJE_DATOS);
        }
        if (id <= 0) {
            return BridgeResult.error(MENSAJE_ID);
        }
        if (rol.isEmpty()) {
            return BridgeResult.error(MENSAJE_ROL);
        }
        if (rol.length() > MAX_ROL || CONTROL.matcher(rol).find()) {
            return BridgeResult.error(MENSAJE_DATOS);
        }
        if (id == usuario.userId()) {
            return BridgeResult.error(MENSAJE_SI_MISMO);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("rol", rol);
        return confirmar(api.putJson(usuario, "/api/usuarios/" + id + "/rol", cuerpo), "Rol actualizado correctamente");
    }

    /** usuarios.permisos.get {id, rol} → GET api/usuarios/{id}/permisos, armado como la matriz de Photino (PermisosService.ArmarMatriz). */
    public BridgeResult permisosGet(ObjectNode payload, SessionUser usuario) {
        JsonNode data = datos(payload);
        if (soloClaves(data, CLAVES_PERMISOS_GET) == null) {
            return BridgeResult.error(MENSAJE_DATOS);
        }
        int id = entero(data.get("id"));
        String rol = cadena(data, "rol");
        if (rol == null || rol.length() > MAX_ROL || CONTROL.matcher(rol).find()) {
            return BridgeResult.error(MENSAJE_DATOS);
        }
        if (id <= 0) {
            return BridgeResult.error(MENSAJE_ID);
        }
        BridgeResult r = InnpackRespuestas.reenviar(api.get(usuario, "/api/usuarios/" + id + "/permisos"), mapper);
        if (!r.ok()) {
            return r;
        }
        return BridgeResult.ok(PermisosModulo.armarMatriz("INNPACK", rol, r.data() instanceof JsonNode d ? d : null, mapper));
    }

    /**
     * usuarios.permisos.guardar {id, permisos:[{modulo, nivel}]} → PUT api/usuarios/{id}/permisos. Validado antes de enviar:
     * módulo de la lista de módulos INNPACK personalizables y nivel null (quita el personalizado) o SIN_ACCESO|VER|EDITAR.
     * Los cambios rigen para el usuario desde su próximo inicio de sesión.
     */
    public BridgeResult permisosGuardar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = datos(payload);
        if (soloClaves(data, CLAVES_PERMISOS_GUARDAR) == null) {
            return BridgeResult.error(MENSAJE_DATOS);
        }
        int id = entero(data.get("id"));
        if (id <= 0) {
            return BridgeResult.error(MENSAJE_ID);
        }
        JsonNode permisos = data.get("permisos");
        if (permisos == null || !permisos.isArray()) {
            return BridgeResult.error(MENSAJE_PERMISOS);
        }
        Set<String> validos = PermisosModulo.modulosPersonalizables("INNPACK");
        ArrayNode lista = mapper.createArrayNode();
        Set<String> vistos = new java.util.HashSet<>();
        for (JsonNode p : permisos) {
            JsonNode modulo = p.isObject() ? p.get("modulo") : null;
            JsonNode nivel = p.isObject() ? p.get("nivel") : null;
            boolean nivelOk = nivel == null || nivel.isNull() || (nivel.isString() && NIVELES.contains(nivel.asString()));
            if (modulo == null || !modulo.isString() || !validos.contains(modulo.asString()) || !nivelOk
                    || !vistos.add(modulo.asString())) {
                return BridgeResult.error(MENSAJE_PERMISOS_INVALIDOS);
            }
            ObjectNode fila = lista.addObject();
            fila.put("modulo", modulo.asString());
            if (nivel == null || nivel.isNull()) {
                fila.putNull("nivel");
            } else {
                fila.put("nivel", nivel.asString());
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.set("permisos", lista);
        return confirmar(api.putJson(usuario, "/api/usuarios/" + id + "/permisos", cuerpo), "Permisos guardados correctamente");
    }

    /** usuarios.permisos.restablecer → DELETE api/usuarios/{id}/permisos (vuelve al comportamiento por rol). */
    public BridgeResult permisosRestablecer(ObjectNode payload, SessionUser usuario) {
        JsonNode data = datos(payload);
        if (soloClaves(data, CLAVES_ID) == null) {
            return BridgeResult.error(MENSAJE_DATOS);
        }
        int id = entero(data.get("id"));
        if (id <= 0) {
            return BridgeResult.error(MENSAJE_ID);
        }
        return confirmar(api.delete(usuario, "/api/usuarios/" + id + "/permisos"), "Permisos restablecidos al comportamiento por rol");
    }

    /** Recurso auditado "usuarios:<id>:<accion>" (nunca contraseñas); create usa el código de usuario saneado. */
    public static String recurso(ObjectNode payload, String accion) {
        JsonNode data = payload.get("data");
        JsonNode id = data == null ? null : data.get("id");
        String referencia = id != null && id.isIntegralNumber() ? id.asString() : "?";
        return "usuarios:" + referencia + ":" + accion;
    }

    public static String recursoCreate(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        JsonNode codigo = data == null ? null : data.get("codigoUsuario");
        String c = codigo != null && codigo.isString() ? CONTROL.matcher(codigo.asString().trim()).replaceAll("") : "?";
        return "usuarios:crear:" + (c.isEmpty() ? "?" : c);
    }

    private BridgeResult confirmar(InnpackApiClient.Respuesta respuesta, String mensaje) {
        BridgeResult r = InnpackRespuestas.reenviar(respuesta, mapper);
        if (!r.ok()) {
            return r;
        }
        ObjectNode salida = mapper.createObjectNode();
        salida.put("message", mensaje);
        return BridgeResult.ok(salida);
    }

    private JsonNode datos(ObjectNode payload) {
        JsonNode data = payload.get("data");
        return data != null && data.isObject() ? data : mapper.createObjectNode();
    }

    /** Devuelve data si todas sus claves están permitidas (lista cerrada); si no, null. */
    private static JsonNode soloClaves(JsonNode data, Set<String> permitidas) {
        for (String clave : data.propertyNames()) {
            if (!permitidas.contains(clave)) {
                return null;
            }
        }
        return data;
    }

    /** GetString de Photino con Trim: ausente → ""; no es string → null (inválido). */
    private static String cadena(JsonNode data, String campo) {
        JsonNode v = data.get(campo);
        if (v == null) {
            return "";
        }
        return v.isString() ? v.asString().trim() : null;
    }

    /** GetBool de Photino: true/false o texto booleano; ausente → por defecto; otro tipo → por defecto; null solo si es objeto/array. */
    private static Boolean booleano(JsonNode data, String campo, boolean porDefecto) {
        JsonNode v = data.get(campo);
        if (v == null || v.isNull()) {
            return porDefecto;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isString()) {
            String t = v.asString().trim();
            return t.equalsIgnoreCase("true") ? Boolean.TRUE : t.equalsIgnoreCase("false") ? Boolean.FALSE : Boolean.valueOf(porDefecto);
        }
        return (v.isObject() || v.isArray()) ? null : Boolean.valueOf(porDefecto);
    }

    /** GetInt de Photino: número int32 o texto convertible; si no, 0. */
    private static int entero(JsonNode v) {
        if (v == null) {
            return 0;
        }
        if (v.isIntegralNumber() && v.canConvertToInt()) {
            return v.asInt();
        }
        if (v.isString()) {
            try {
                return Integer.parseInt(v.asString().trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /** Propiedad por nombre sin distinguir mayúsculas (PropertyNameCaseInsensitive de Photino). */
    private static JsonNode campo(JsonNode objeto, String nombre) {
        JsonNode exacto = objeto.get(nombre);
        if (exacto != null) {
            return exacto;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = objeto.properties().iterator(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().equalsIgnoreCase(nombre)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Campo string del DTO: ausente → porDefecto; null → null; string → valor; otro tipo → inválido. */
    private static String texto(JsonNode nodo, String porDefecto) {
        if (nodo == null) {
            return porDefecto;
        }
        if (nodo.isNull()) {
            return null;
        }
        if (!nodo.isString()) {
            throw new IllegalArgumentException();
        }
        return nodo.asString();
    }
}
