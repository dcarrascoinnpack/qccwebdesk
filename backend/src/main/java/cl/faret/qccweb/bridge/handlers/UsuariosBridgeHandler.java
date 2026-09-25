package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import java.util.Iterator;
import java.util.Map;
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
 * Escrituras (create, delete, resetPassword) NO habilitadas.
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
            }
        } catch (IllegalArgumentException e) {
            // Photino (System.Text.Json) no convierte tipos: un campo con tipo distinto hace fallar la lista.
            return BridgeResult.error(MENSAJE_RESPUESTA_INVALIDA);
        }
        return BridgeResult.ok(salida);
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
