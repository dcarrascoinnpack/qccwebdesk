package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Máquinas y Procesos" (INNPACK), solo lectura. Port de Photino
 * src/Backend/Modules/MaquinasSeguimiento/MaquinasSeguimientoHandler.cs +
 * InnpackMaquinasSeguimientoApiService.ObtenerResumenAsync.
 */
public class MaquinasSeguimientoBridgeHandler {

    static final String MENSAJE_PARAMETRO_INVALIDO = "Parámetro maquinaId inválido.";

    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public MaquinasSeguimientoBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /**
     * maquinasSeguimiento.obtenerResumen → GET api/maquinas-seguimiento/resumen?sinLimite=..&maquinaId=..
     *
     * Mismas reglas de parámetros que Photino (solo se leen desde "data"):
     * - maquinaId: número entero, o string convertible a entero; cualquier otra cosa → sin filtro.
     *   Un número no entero hace fallar a Photino (GetInt32) → aquí también es error.
     * - sinLimite: true literal, o string "true" sin distinguir mayúsculas; cualquier otra cosa → false.
     */
    public BridgeResult obtenerResumen(ObjectNode payload, SessionUser usuario) {
        JsonNode data = payload.get("data");
        Integer maquinaId = null;
        boolean sinLimite = false;

        if (data != null && data.isObject()) {
            JsonNode id = data.get("maquinaId");
            if (id != null && id.isNumber()) {
                if (!id.isIntegralNumber() || !id.canConvertToInt()) {
                    return BridgeResult.error(MENSAJE_PARAMETRO_INVALIDO);
                }
                maquinaId = id.asInt();
            } else if (id != null && id.isString()) {
                maquinaId = parsearEntero(id.asString());
            }

            JsonNode limite = data.get("sinLimite");
            if (limite != null && limite.isBoolean()) {
                sinLimite = limite.asBoolean();
            } else if (limite != null && limite.isString()) {
                sinLimite = limite.asString().trim().equalsIgnoreCase("true");
            }
        }

        String path = "/api/maquinas-seguimiento/resumen?sinLimite=" + (sinLimite ? "true" : "false")
                + (maquinaId != null ? "&maquinaId=" + maquinaId : "");
        return InnpackRespuestas.reenviar(api.get(usuario, path), mapper);
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
