package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Control de Recepción - Calidad" — SOLO LECTURA. Port de Photino
 * src/Backend/Modules/RecepcionCalidad/RecepcionCalidadHandler.cs + InnpackRecepcionCalidadApiService.
 * El payload viaja dentro de "data".
 *
 * "empresa" es scope de sesión: el frontend INNPACK nunca la manda (default INNPACK) y el de Faret la
 * manda hardcodeada ("FARET"); la API filtra list/detalle por ella pero no la liga al JWT. Aquí sale
 * de la sesión (además de IdentityOverride en la regla), nunca del payload.
 *
 * foto.abrir — diferencias DEFENSIVAS: la API no filtra la foto por empresa ni por lote eliminado y
 * siempre guarda el MIME "image/jpeg". El gateway primero confirma el lote con el detalle de la
 * empresa de la sesión (y que su tipo coincida), solo acepta PVA/PliegoFaret, acota el tamaño y
 * entrega el MIME detectado por la firma real (JPEG/PNG/GIF/WEBP); si no es imagen, lo rechaza.
 * La vista lo muestra en la página (data:), sin archivos temporales.
 *
 * bool/objeto/array en filtros de texto → "Parámetro de filtro inválido." (regla 2e).
 *
 * Escrituras (crear, nc.crear, plan.generar, bobinas.muestrear, muestra.crear, estado.actualizar) NO
 * habilitadas. sap.consultar/sap.lotes (apisapfaret, otra API con API key; solo se usan al crear un
 * lote) tampoco: requieren un cliente upstream nuevo.
 */
public class RecepcionCalidadBridgeHandler {

    static final String MENSAJE_FILTRO_INVALIDO = "Parámetro de filtro inválido.";
    static final String MENSAJE_FALTA_FOTO = "Falta el lote o el tipo de materia prima";
    static final String MENSAJE_SIN_FOTO = "Este lote no tiene fotografía cargada";
    static final String MENSAJE_FOTO_INVALIDA = "La fotografía no es válida.";
    static final String MENSAJE_TAMANO = "La fotografía excede el tamaño máximo permitido.";
    static final int MAX_FOTO_BYTES = 10 * 1024 * 1024;
    static final int MAX_BASE64_CHARS = ((MAX_FOTO_BYTES + 2) / 3) * 4;

    /** Tipos con foto en la API (RecepcionCalidadRepository.ObtenerFoto). */
    private static final Set<String> TIPOS_CON_FOTO = Set.of("PVA", "PliegoFaret");
    private static final String BASE = "/api/recepcion-calidad";

    private final InnpackApiClient api;
    private final ObjectMapper mapper;

    public RecepcionCalidadBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    /** recepcion.list → GET api/recepcion-calidad?[estado=..&][tipoMateriaPrima=..&]empresa=.. */
    public BridgeResult list(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        StringBuilder path = new StringBuilder(BASE).append('?');
        for (String filtro : new String[] {"estado", "tipoMateriaPrima"}) {
            JsonNode nodo = data.get(filtro);
            if (nodo == null || nodo.isNull()) {
                continue;
            }
            if (!(nodo.isString() || nodo.isNumber())) {
                return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
            }
            String valor = nodo.asString();
            if (!valor.isBlank()) {
                path.append(filtro).append('=').append(UriEscape.dataString(valor)).append('&');
            }
        }
        path.append("empresa=").append(UriEscape.dataString(usuario.empresa()));
        return InnpackRespuestas.reenviar(api.get(usuario, path.toString()), mapper);
    }

    /** recepcion.detalle → GET api/recepcion-calidad/{id}?empresa=.. (id ausente/inválido → 0, como Photino). */
    public BridgeResult detalle(ObjectNode payload, SessionUser usuario) {
        Integer id = entero(data(payload).get("id"));
        return detalleLote(id != null ? id : 0, usuario);
    }

    /** recepcion.foto.abrir → (detalle de la empresa de sesión) + GET api/recepcion-calidad/{loteId}/foto?tipoMateriaPrima=.. */
    public BridgeResult fotoAbrir(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        Integer loteId = entero(data.get("loteId"));
        String tipo = texto(data.get("tipoMateriaPrima")).trim();
        if (loteId == null || loteId <= 0 || tipo.isEmpty()) {
            return BridgeResult.error(MENSAJE_FALTA_FOTO);
        }
        if (!TIPOS_CON_FOTO.contains(tipo)) {
            return BridgeResult.error(MENSAJE_SIN_FOTO);
        }
        BridgeResult lote = detalleLote(loteId, usuario);
        if (!lote.ok()) {
            return lote;
        }
        JsonNode detalle = lote.data() instanceof JsonNode n ? n : null;
        if (detalle == null || !tipo.equals(texto(detalle.get("tipoMateriaPrima")))) {
            return BridgeResult.error(MENSAJE_SIN_FOTO);
        }
        BridgeResult upstream = InnpackRespuestas.reenviar(
                api.get(usuario, BASE + "/" + loteId + "/foto?tipoMateriaPrima=" + UriEscape.dataString(tipo)), mapper);
        if (!upstream.ok()) {
            return upstream;
        }
        JsonNode foto = upstream.data() instanceof JsonNode n && n.isObject() ? n : null;
        String base64 = texto(foto == null ? null : foto.get("base64")).trim();
        if (base64.isEmpty()) {
            return BridgeResult.error(MENSAJE_SIN_FOTO);
        }
        if (base64.length() > MAX_BASE64_CHARS) {
            return BridgeResult.error(MENSAJE_TAMANO);
        }
        String mime = mimeImagen(ControlDocumentalBridgeHandler.inicioDecodificado(base64));
        if (mime == null) {
            return BridgeResult.error(MENSAJE_FOTO_INVALIDA);
        }
        ObjectNode salida = mapper.createObjectNode();
        salida.put("base64", base64);
        salida.put("mime", mime);
        return BridgeResult.ok(salida);
    }

    /** MIME de imagen según la firma real (JPEG/PNG/GIF/WEBP); null si no es una imagen reconocida. */
    public static String mimeImagen(byte[] b) {
        if (b == null) {
            return null;
        }
        if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0d && b[5] == 0x0a && b[6] == 0x1a && b[7] == 0x0a) {
            return "image/png";
        }
        if (b.length >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "image/gif";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private BridgeResult detalleLote(int id, SessionUser usuario) {
        return InnpackRespuestas.reenviar(
                api.get(usuario, BASE + "/" + id + "?empresa=" + UriEscape.dataString(usuario.empresa())), mapper);
    }

    private JsonNode data(ObjectNode payload) {
        JsonNode data = payload.get("data");
        return data != null && data.isObject() ? data : mapper.createObjectNode();
    }

    /** GetString de Photino: string o número (→ texto); cualquier otro tipo → "". */
    private static String texto(JsonNode nodo) {
        return nodo != null && (nodo.isString() || nodo.isNumber()) ? nodo.asString() : "";
    }

    /** GetInt de Photino: número entero int32, o string convertible (int.TryParse); si no → null. */
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
