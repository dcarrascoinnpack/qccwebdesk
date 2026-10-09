package cl.faret.qccweb.bridge.handlers;

import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.Invalido;
import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.cerrada;
import static cl.faret.qccweb.bridge.handlers.FaretNoConformidadesBridgeHandler.enteroPositivo;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.FaretApiClient;
import cl.faret.qccweb.upstream.FaretRespuestas;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fase F5 - BORRADOS FARET (irreversibles; MejoraContinua y Calidad no tienen autenticación, el gateway es el único control: solo
 * admin, admin_ti y calidad, decisión del usuario). Port de Photino 1.8.15 (9e1b556) FaretHandler.cs: HandleNcAdjuntosEliminar,
 * HandleNcEliminarFila, HandleInspeccionesEliminar y HandleInspeccionesPalletEliminar. Sin reintentos (F0). Lista cerrada de claves
 * (solo ids), ids enteros positivos validados antes de la ruta (bool/objeto/array/decimal rechazados). Authorization solo hacia
 * QualityControlFaret.Api (Bearer del usuario); MejoraContinua y Calidad van sin él.
 *
 * <ul>
 * <li>faret.nc.adjuntos.eliminar: DELETE MC api/no-conformidades/{id}/adjuntos/{adjuntoId}. La API verifica que el adjunto pertenece a la
 * NC (UPDATE ... WHERE id AND no_conformidad_id AND eliminado = 0), responde 404 (NC o adjunto) y 409 (NC cerrada): no hace falta relectura.</li>
 * <li>faret.nc.eliminarFila: 2 pasos SIN compensación, en el orden de Photino: 1) dataId -> DELETE QC api/importaciones/pnc/{dataId}
 * (Bearer; borrado suave); 2) ncId -> DELETE MC api/no-conformidades/{ncId} (borrado suave). Un lado que falla NO detiene al otro ni
 * revierte: el error concatena "Data: ... | Gestión NC: ...". Al menos un id. Desviación menor de Photino: si hay ncId y MejoraContinua
 * no está configurada se rechaza ANTES de borrar el registro de Data (Photino lo borraba y luego informaba el error).</li>
 * <li>faret.inspecciones.eliminar / faret.inspeccionesPallet.eliminar: DELETE Calidad calidad-faret(-pallet)/registros/{id}.</li>
 * </ul>
 */
public class FaretBorradosBridgeHandler {

    static final String MARCA_FILA = "__qccAuditoriaFila";
    static final String MENSAJE_FALTA_ID = "id es requerido";
    static final String MENSAJE_FALTA_DATA_O_NC = "Falta dataId o ncId";

    private static final String RUTA_NC = "/api/no-conformidades";
    private static final String RUTA_PNC = "/api/importaciones/pnc";
    private static final String RUTA_INSPECCIONES = "/calidad-faret/registros";
    private static final String RUTA_PALLET = "/calidad-faret-pallet/registros";

    private static final Set<String> CLAVES_ID = Set.of("action", "id");
    private static final Set<String> CLAVES_ADJUNTO = Set.of("action", "id", "adjuntoId");
    private static final Set<String> CLAVES_FILA = Set.of("action", "dataId", "ncId");

    private final FaretApiClient qualityControl;
    private final FaretApiClient mejoraContinua;
    private final FaretApiClient calidad;
    private final ObjectMapper mapper;

    public FaretBorradosBridgeHandler(FaretApiClient qualityControl, FaretApiClient mejoraContinua, FaretApiClient calidad,
            ObjectMapper mapper) {
        this.qualityControl = qualityControl;
        this.mejoraContinua = mejoraContinua;
        this.calidad = calidad;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------------------------------- nc.adjuntos.eliminar

    /** faret.nc.adjuntos.eliminar. Éxito: {mensaje}. */
    public BridgeResult adjuntoEliminar(ObjectNode payload, SessionUser usuario) {
        if (!mejoraContinua.configurada()) {
            return BridgeResult.error(FaretBridgeHandler.MENSAJE_MC_NO_CONFIGURADA);
        }
        int id;
        int adjuntoId;
        try {
            cerrada(payload, CLAVES_ADJUNTO);
            Integer idNc = enteroPositivo(payload.get("id"));
            if (idNc == null) {
                return BridgeResult.error(FaretBridgeHandler.MENSAJE_FALTA_ID_NC);
            }
            Integer idAdjunto = enteroPositivo(payload.get("adjuntoId"));
            if (idAdjunto == null) {
                return BridgeResult.error(FaretBridgeHandler.MENSAJE_FALTA_ID_ADJUNTO);
            }
            id = idNc;
            adjuntoId = idAdjunto;
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        BridgeResult r = FaretRespuestas.crudoMc(mejoraContinua.delete(usuario, RUTA_NC + "/" + id + "/adjuntos/" + adjuntoId), mapper);
        if (!r.ok()) {
            return r;
        }
        ObjectNode salida = mapper.createObjectNode();
        salida.put("mensaje", "Adjunto eliminado correctamente");
        return BridgeResult.ok(salida);
    }

    /** "faret:nc:<id>:adjunto:<adjuntoId>:eliminar". */
    public static String recursoAdjuntoEliminar(ObjectNode payload, Object dataRespuesta) {
        return "faret:nc:" + idAuditoria(payload, "id") + ":adjunto:" + idAuditoria(payload, "adjuntoId") + ":eliminar";
    }

    // ------------------------------------------------------------------------------------------- nc.eliminarFila

    /** faret.nc.eliminarFila: ver la clase. Éxito: {eliminado: true}. */
    public BridgeResult eliminarFila(ObjectNode payload, SessionUser usuario) {
        Integer dataId;
        Integer ncId;
        try {
            cerrada(payload, CLAVES_FILA);
            dataId = idOpcional(payload.get("dataId"));
            ncId = idOpcional(payload.get("ncId"));
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        if (dataId == null && ncId == null) {
            return BridgeResult.error(MENSAJE_FALTA_DATA_O_NC);
        }
        if (ncId != null && !mejoraContinua.configurada()) {
            return BridgeResult.error(FaretBridgeHandler.MENSAJE_MC_NO_CONFIGURADA);
        }
        List<String> errores = new ArrayList<>();
        StringBuilder marca = new StringBuilder();
        if (dataId != null) {
            // Paso 1 (Bearer). Una sesión sin token o un 401 lanza antes de borrar nada y invalida solo esa sesión.
            BridgeResult data = FaretRespuestas.desenvolver(qualityControl.delete(usuario, RUTA_PNC + "/" + dataId), mapper);
            marca.append(":data:").append(dataId).append(data.ok() ? ":OK" : ":ERROR");
            if (!data.ok()) {
                errores.add("Data: " + data.error());
            }
        }
        if (ncId != null) {
            // Paso 2 (sin Authorization). No se detiene si el paso 1 falló, igual que Photino.
            BridgeResult nc = FaretRespuestas.crudoMc(mejoraContinua.delete(usuario, RUTA_NC + "/" + ncId), mapper);
            marca.append(":nc:").append(ncId).append(nc.ok() ? ":OK" : ":ERROR");
            if (!nc.ok()) {
                errores.add("Gestión NC: " + nc.error());
            }
        }
        // Canal interno para la auditoría (resultado parcial por lado); no vuelve al navegador ni a las APIs.
        payload.put(MARCA_FILA, marca.toString());
        if (!errores.isEmpty()) {
            return BridgeResult.error(String.join(" | ", errores));
        }
        ObjectNode salida = mapper.createObjectNode();
        salida.put("eliminado", true);
        return BridgeResult.ok(salida);
    }

    /** "faret:nc:fila[:data:<id>:OK|ERROR][:nc:<id>:OK|ERROR]:eliminar" ("?" si la validación falló antes de borrar). */
    public static String recursoEliminarFila(ObjectNode payload, Object dataRespuesta) {
        JsonNode marca = payload.get(MARCA_FILA);
        return "faret:nc:fila" + (marca != null && marca.isString() ? marca.asString() : ":?") + ":eliminar";
    }

    // ------------------------------------------------------------------------------------------- inspecciones

    /** faret.inspecciones.eliminar -> DELETE calidad-faret/registros/{id}. */
    public BridgeResult inspeccionEliminar(ObjectNode payload, SessionUser usuario) {
        return eliminarCalidad(payload, usuario, RUTA_INSPECCIONES);
    }

    /** faret.inspeccionesPallet.eliminar -> DELETE calidad-faret-pallet/registros/{id}. */
    public BridgeResult inspeccionPalletEliminar(ObjectNode payload, SessionUser usuario) {
        return eliminarCalidad(payload, usuario, RUTA_PALLET);
    }

    private BridgeResult eliminarCalidad(ObjectNode payload, SessionUser usuario, String ruta) {
        if (!calidad.configurada()) {
            return BridgeResult.error(FaretBridgeHandler.MENSAJE_CALIDAD_NO_CONFIGURADA);
        }
        Integer id;
        try {
            cerrada(payload, CLAVES_ID);
            id = enteroPositivo(payload.get("id"));
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        if (id == null) {
            return BridgeResult.error(MENSAJE_FALTA_ID);
        }
        BridgeResult r = FaretRespuestas.desenvolver(calidad.delete(usuario, ruta + "/" + id), mapper);
        return r.ok() ? BridgeResult.ok(null) : r;
    }

    /** "faret:inspeccion:<id>:eliminar". */
    public static String recursoInspeccionEliminar(ObjectNode payload, Object dataRespuesta) {
        return "faret:inspeccion:" + idAuditoria(payload, "id") + ":eliminar";
    }

    /** "faret:inspeccionPallet:<id>:eliminar". */
    public static String recursoInspeccionPalletEliminar(ObjectNode payload, Object dataRespuesta) {
        return "faret:inspeccionPallet:" + idAuditoria(payload, "id") + ":eliminar";
    }

    // ------------------------------------------------------------------------------------------- ids

    /**
     * dataId / ncId: ausente o null = no viene (la vista omite el lado que la fila no tiene); si viene debe ser un entero int32
     * positivo (Photino trataba 0 o negativos como ausentes; aquí un valor presente e inválido se rechaza).
     */
    private static Integer idOpcional(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        Integer id = enteroPositivo(n);
        if (id == null) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
        }
        return id;
    }

    private static String idAuditoria(ObjectNode payload, String clave) {
        try {
            Integer id = enteroPositivo(payload.get(clave));
            return id == null ? "?" : String.valueOf(id);
        } catch (Invalido e) {
            return "?";
        }
    }
}
