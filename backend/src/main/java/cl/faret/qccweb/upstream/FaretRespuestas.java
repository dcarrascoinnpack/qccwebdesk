package cl.faret.qccweb.upstream;

import cl.faret.qccweb.bridge.BridgeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Traducción de las respuestas de las APIs FARET al resultado del bridge, con la lógica de FaretHandler.cs de Photino
 * 1.8.15 (9e1b556): {@link #desenvolver} = TryUnwrapApiResponse (QualityControlFaret.Api y Calidad) y {@link #crudoMc}
 * = la lectura cruda de MejoraContinua con ExtractMcErrorMessage. Mensajes genéricos: "Faret".
 */
public final class FaretRespuestas {

    public static final String ERROR_FARET = "Error al comunicarse con la API Faret";
    public static final String ERROR_MC = "Error al comunicarse con la API de Mejora Continua";

    private FaretRespuestas() {}

    /** Photino: {@code if (!TryUnwrapApiResponse(body, ...) || !ok) return Error(error);} y si no, Ok(data). */
    public static BridgeResult desenvolver(InnpackApiClient.Respuesta respuesta, ObjectMapper mapper) {
        boolean httpOk = httpOk(respuesta);
        JsonNode raiz = leer(respuesta.body(), mapper);
        if (raiz == null || !raiz.isObject()) {
            return BridgeResult.error(ERROR_FARET);
        }
        JsonNode data = null;
        boolean desenvuelto = false;

        JsonNode success = raiz.get("success");
        JsonNode okProp = raiz.get("ok");
        JsonNode errorProp = raiz.get("error");
        if (success != null) {
            if (!success.isBoolean()) {
                return BridgeResult.error(ERROR_FARET);
            }
            if (!success.asBoolean()) {
                return BridgeResult.error(mensaje(raiz.get("message")));
            }
            data = raiz.get("data");
            desenvuelto = data != null;
        } else if (okProp != null) {
            if (!okProp.isBoolean()) {
                return BridgeResult.error(ERROR_FARET);
            }
            if (!okProp.asBoolean()) {
                return BridgeResult.error(mensaje(raiz.get("message")));
            }
            data = raiz.get("data");
            desenvuelto = data != null;
        } else if (errorProp != null) {
            return BridgeResult.error(mensaje(errorProp));
        }
        if (!desenvuelto || !httpOk) {
            return BridgeResult.error(ERROR_FARET);
        }
        return BridgeResult.ok(data.isNull() ? null : data);
    }

    /**
     * MejoraContinua responde JSON crudo en éxito y, en error, ProblemDetails { title, detail }, { mensaje } o el
     * { ok, error } local: éxito (2xx) → el JSON tal cual; si no, ExtractMcErrorMessage.
     */
    public static BridgeResult crudoMc(InnpackApiClient.Respuesta respuesta, ObjectMapper mapper) {
        JsonNode raiz = leer(respuesta.body(), mapper);
        if (httpOk(respuesta)) {
            return raiz == null ? BridgeResult.error(ERROR_MC) : BridgeResult.ok(raiz.isNull() ? null : raiz);
        }
        return BridgeResult.error(mensajeMc(raiz));
    }

    /** Texto con el que MejoraContinua dice que la NC aún no tiene análisis (estado inicial normal, no un error). */
    public static final String SIN_ANALISIS = "aún no tiene un análisis";

    /**
     * faret.nc.analisis.get: como {@link #crudoMc}, salvo que un error cuyo mensaje contiene "aún no tiene un análisis"
     * (sin distinguir mayúsculas) es el estado inicial normal de una NC y se responde Ok(null), igual que Photino.
     */
    public static BridgeResult crudoMcAnalisis(InnpackApiClient.Respuesta respuesta, ObjectMapper mapper) {
        if (!httpOk(respuesta)) {
            String mensaje = mensajeMc(leer(respuesta.body(), mapper));
            if (mensaje.toLowerCase(java.util.Locale.ROOT).contains(SIN_ANALISIS)) {
                return BridgeResult.ok(null);
            }
            return BridgeResult.error(mensaje);
        }
        return crudoMc(respuesta, mapper);
    }

    /** ExtractMcErrorMessage: mensaje, error, title, detail (solo strings); si no, el genérico. */
    static String mensajeMc(JsonNode raiz) {
        if (raiz != null && raiz.isObject()) {
            for (String clave : new String[] {"mensaje", "error", "title", "detail"}) {
                JsonNode valor = raiz.get(clave);
                if (valor != null && valor.isString()) {
                    return valor.asString();
                }
            }
        }
        return ERROR_MC;
    }

    public static boolean httpOk(InnpackApiClient.Respuesta respuesta) {
        return respuesta.status() >= 200 && respuesta.status() < 300;
    }

    /** {@code m.GetString() ?? error}: string da ese texto; ausente, null JSON u otro tipo dan el genérico. */
    private static String mensaje(JsonNode nodo) {
        return nodo != null && nodo.isString() ? nodo.asString() : ERROR_FARET;
    }

    private static JsonNode leer(String body, ObjectMapper mapper) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
