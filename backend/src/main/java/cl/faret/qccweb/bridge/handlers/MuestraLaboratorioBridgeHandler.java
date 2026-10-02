package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.bridge.LecturasDeSesion;
import cl.faret.qccweb.upstream.FpsApiClient;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Laboratorio - Muestras" (INNPACK), parte INNPACK API. Port de Photino
 * src/Backend/Modules/MuestraLaboratorio/MuestraLaboratorioHandler.cs +
 * InnpackMuestraLaboratorioApiService. Payload dentro de "data". Referencia de la API:
 * qualitycontrolinnpack_sqlserver_port (la copia qualitycontrolinnpack no tiene estos endpoints).
 *
 * Sin empresa (menú solo INNPACK). Lecturas sin rol (Photino y la API solo exigen sesión); las
 * escrituras usan ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO (operador/admin/admin_ti),
 * igual que el resto de la matriz.
 *
 * adjunto.abrir: Photino previsualiza imagen/PDF y para el resto escribe %TEMP%\QCC_MuestraLaboratorio
 * + Process.Start. Aquí se valida igual que Control Documental (ControlDocumentalBridgeHandler.
 * adjuntoParaNavegador, ≤ 10 MB = máximo de la API) y web-bridge.js previsualiza o descarga.
 *
 * bool/objeto/array en filtros → "Parámetro de filtro inválido." (regla 2e; también maquinaId).
 *
 * materialesFps (Fase 3w): fps-api materiales-por-proceso, solo para el idProcesoFps de una muestra cuyo detalle
 * abrió esta sesión (el botón solo existe en el detalle; evita recorrer procesos FPS arbitrarios).
 *
 * Fase 4e-1 (piloto de las 25 escrituras): `crear` y `ph.guardar`. usuarioId/usuarioNombre (o
 * analistaUsuarioId/analistaNombre) SIEMPRE de sesión — ni siquiera son claves aceptadas del payload,
 * igual criterio que Control Documental. `ph.guardar` fija el patrón de "corrección" común a los 13
 * tipos de ensayo (ensayoOriginalId/motivoReemplazo, lo valida la API: motivo obligatorio si hay id,
 * original debe existir y estar Finalizado). Sin huella ni candado: son altas puras, la API no
 * verifica duplicados (mismo criterio que noConformidades.create/controlDocumental.create).
 *
 * Fase 4e-3: `anular`, `ensayo.anular`, `actualizarFechaEnsayo`. `id`/`ensayoId`/`motivo` se rechazan ANTES de llamar
 * a la API si faltan (igual que el handler C# de Photino); `ensayo.anular` no recibe autor (la API no tiene campo
 * para ello, auditoría obligatoria del gateway); `actualizarFechaEnsayo` restringe la fecha al formato real del
 * {@code <input type="datetime-local">} de Photino.
 *
 * Fase 4e-4: maestros de métodos/especificaciones (`metodo.guardar/activar`, `especificacion.guardar/activar`).
 * `metodo.guardar` es la única de las 4 con autor (usuarioNombre ← sesión); las otras 3 no reciben autor (la API no
 * tiene campo para ello). `*.activar` comparten un solo método (`activarComun`): `id` se rechaza antes de llamar a
 * la API, `activo` solo es `true` si el JSON trae literalmente el booleano `true` (igual que Photino).
 *
 * Fase 4e-5 (cierra las 25 escrituras de Laboratorio): `eliminar` (borrado lógico de la muestra, distinto de
 * `anular`) y `adjunto.eliminar`. Ninguna de las dos recibe autor (ni Photino ni la API lo registran); a diferencia
 * de `controlDocumental.eliminar`, estas dos SÍ reciben un error real de la API si el id no existe (no hace falta
 * releer antes de borrar).
 *
 * Fuera: consultarNp (Planificación FARET), consultarRegistroProduccion (FPS, solo en el alta de muestra, que depende
 * de consultarNp) y resolverBobina (SAP) — único trío que falta, requieren clientes de otras APIs externas.
 */
public class MuestraLaboratorioBridgeHandler {

    static final String MENSAJE_FILTRO_INVALIDO = "Parámetro de filtro inválido.";
    static final String MENSAJE_FALTA_NP = "Falta indicar la NP";
    static final String MENSAJE_FALTA_BOBINA = "Falta el número de bobina";
    static final String MENSAJE_FALTA_ADJUNTO = "Falta indicar el adjunto";
    static final int MAX_ADJUNTO_BYTES = 10 * 1024 * 1024;
    static final int MAX_BASE64_CHARS = ((MAX_ADJUNTO_BYTES + 2) / 3) * 4;

    /** Orden exacto de InnpackMuestraLaboratorioApiService.ListAsync (maquinaId va al final). */
    private static final String[] FILTROS_TEXTO = {
        "estado", "tipoMuestra", "np", "fechaDesde", "fechaHasta", "cliente", "codigoProducto",
        "descripcion", "origen", "analistaNombre", "bobina"};
    private static final String BASE = "/api/muestra-laboratorio";

    /** Mensajes de MuestraLaboratorioHandler de Photino (materialesFps). */
    static final String MENSAJE_FALTA_PROCESO = "Falta el idProceso (FPS) de la muestra";
    static final String MENSAJE_FPS_NO_CONFIGURADO = "FPS no está configurado en este equipo.";
    static final String MENSAJE_FPS_MATERIALES = "No fue posible consultar los materiales en FPS.";
    static final String MENSAJE_PROCESO_SIN_LEER = "Abre el detalle de la muestra antes de consultar los materiales FPS.";
    private static final java.util.regex.Pattern ID_PROCESO = java.util.regex.Pattern.compile("[1-9][0-9]{0,17}");

    private final InnpackApiClient api;
    private final FpsApiClient fps;
    private final ObjectMapper mapper;

    public MuestraLaboratorioBridgeHandler(InnpackApiClient api, FpsApiClient fps, ObjectMapper mapper) {
        this.api = api;
        this.fps = fps;
        this.mapper = mapper;
    }

    /** muestraLab.list → GET api/muestra-laboratorio[?filtros no vacíos...&maquinaId=n] */
    public BridgeResult list(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        StringBuilder query = new StringBuilder();
        for (String filtro : FILTROS_TEXTO) {
            JsonNode nodo = data.get(filtro);
            if (nodo == null || nodo.isNull()) {
                continue;
            }
            if (!(nodo.isString() || nodo.isNumber())) {
                return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
            }
            String valor = nodo.asString();
            if (!valor.isBlank()) {
                query.append(filtro).append('=').append(UriEscape.dataString(valor)).append('&');
            }
        }
        JsonNode maquina = data.get("maquinaId");
        if (maquina != null && !maquina.isNull()) {
            if (!(maquina.isString() || maquina.isNumber())) {
                return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
            }
            Integer maquinaId = entero(maquina);
            if (maquinaId != null) {
                query.append("maquinaId=").append(maquinaId).append('&');
            }
        }
        String path = query.isEmpty() ? BASE : BASE + "?" + query.substring(0, query.length() - 1);
        return get(usuario, path);
    }

    /** muestraLab.detalle → GET api/muestra-laboratorio/{id} (id ausente/inválido → 0, como Photino). */
    public BridgeResult detalle(ObjectNode payload, SessionUser usuario) {
        Integer id = entero(data(payload).get("id"));
        BridgeResult r = get(usuario, BASE + "/" + (id != null ? id : 0));
        // Fase 3w: el proceso FPS de la muestra abierta habilita "Materiales FPS" para ESTA sesión.
        if (r.ok() && r.data() instanceof JsonNode d) {
            String proceso = idProceso(d.get("idProcesoFps"));
            if (proceso != null) {
                LecturasDeSesion.registrar(recursoProcesoFps(proceso), "1");
            }
        }
        return r;
    }

    /**
     * muestraLab.materialesFps {data:{idProceso}} → GET fps-api materiales-por-proceso?ids={idProceso} (Photino:
     * GetLong ?? 0, mayor que 0; FpsMaterialesApiService). Respuesta = MaterialInsumoDto en camelCase {idProceso, itemCode,
     * itemName} (texto; número → texto), como la serializa Photino. La API key de fps-api es del servidor.
     */
    public BridgeResult materialesFps(ObjectNode payload, SessionUser usuario) {
        JsonNode nodo = data(payload).get("idProceso");
        if (nodo != null && !nodo.isNull() && !(nodo.isString() || nodo.isNumber())) {
            return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
        }
        String proceso = idProceso(nodo);
        if (proceso == null) {
            return BridgeResult.error(MENSAJE_FALTA_PROCESO);
        }
        if (!fps.configurada()) {
            return BridgeResult.error(MENSAJE_FPS_NO_CONFIGURADO);
        }
        if (LecturasDeSesion.huella(recursoProcesoFps(proceso)) == null) {
            return BridgeResult.error(MENSAJE_PROCESO_SIN_LEER);
        }
        InnpackApiClient.Respuesta r = fps.get("materiales-por-proceso?ids=" + proceso);
        JsonNode cuerpo = null;
        if (r.body() != null) {
            try {
                cuerpo = mapper.readTree(r.body());
            } catch (RuntimeException e) {
                cuerpo = null;
            }
        }
        if (r.status() < 200 || r.status() > 299 || cuerpo == null || !cuerpo.path("ok").isBoolean()
                || !cuerpo.path("ok").asBoolean()) {
            return BridgeResult.error(MENSAJE_FPS_MATERIALES);
        }
        ArrayNode lista = mapper.createArrayNode();
        JsonNode filas = cuerpo.get("data");
        if (filas != null && filas.isArray()) {
            for (JsonNode fila : filas) {
                ObjectNode m = lista.addObject();
                m.put("idProceso", textoFila(fila, "Id_Proceso"));
                m.put("itemCode", textoFila(fila, "ItemCode"));
                m.put("itemName", textoFila(fila, "ItemName"));
            }
        }
        return BridgeResult.ok(lista);
    }

    /** GetString de FpsMaterialesApiService: string, número → texto; cualquier otro → "". */
    private static String textoFila(JsonNode fila, String campo) {
        JsonNode v = fila != null && fila.isObject() ? fila.get(campo) : null;
        return v != null && (v.isString() || v.isNumber()) ? v.asString() : "";
    }

    /** GetLong de Photino (mayor que 0) como dígitos: número entero o texto convertible; si no, null. */
    private static String idProceso(JsonNode nodo) {
        if (nodo == null || nodo.isNull()) {
            return null;
        }
        String v = nodo.isIntegralNumber() ? nodo.asString() : nodo.isString() ? nodo.asString().trim() : null;
        if (v == null || !ID_PROCESO.matcher(v).matches()) {
            return null;
        }
        try {
            return Long.toString(Long.parseLong(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String recursoProcesoFps(String proceso) {
        return "fps-proceso:" + proceso;
    }

    /** muestraLab.catalogos → GET api/muestra-laboratorio/catalogos */
    public BridgeResult catalogos(ObjectNode payload, SessionUser usuario) {
        return get(usuario, BASE + "/catalogos");
    }

    /** muestraLab.indicadores → GET api/muestra-laboratorio/indicadores */
    public BridgeResult indicadores(ObjectNode payload, SessionUser usuario) {
        return get(usuario, BASE + "/indicadores");
    }

    /** muestraLab.metodo.list → GET api/muestra-laboratorio/metodos */
    public BridgeResult metodoList(ObjectNode payload, SessionUser usuario) {
        return get(usuario, BASE + "/metodos");
    }

    /** muestraLab.especificacion.list → GET api/muestra-laboratorio/especificaciones */
    public BridgeResult especificacionList(ObjectNode payload, SessionUser usuario) {
        return get(usuario, BASE + "/especificaciones");
    }

    /** muestraLab.bobinaHistorial → GET api/muestra-laboratorio/bobina-historial?numeroBobina=..&excluirMuestraId=n (default 0). */
    public BridgeResult bobinaHistorial(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        String numeroBobina = texto(data.get("numeroBobina"));
        if (numeroBobina.isBlank()) {
            return BridgeResult.error(MENSAJE_FALTA_BOBINA);
        }
        Integer excluir = entero(data.get("excluirMuestraId"));
        return get(usuario, BASE + "/bobina-historial?numeroBobina=" + UriEscape.dataString(numeroBobina)
                + "&excluirMuestraId=" + (excluir != null ? excluir : 0));
    }

    /** muestraLab.registroProduccion.list → GET api/muestra-laboratorio/registro-produccion?np=..[&lote=..] */
    public BridgeResult registroProduccionList(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        String np = texto(data.get("np"));
        if (np.isBlank()) {
            return BridgeResult.error(MENSAJE_FALTA_NP);
        }
        String lote = texto(data.get("lote"));
        return get(usuario, BASE + "/registro-produccion?np=" + UriEscape.dataString(np)
                + (lote.isBlank() ? "" : "&lote=" + UriEscape.dataString(lote)));
    }

    /** muestraLab.adjunto.abrir → GET api/muestra-laboratorio/adjunto/{adjuntoId}, validado para el navegador. */
    public BridgeResult adjuntoAbrir(ObjectNode payload, SessionUser usuario) {
        Integer adjuntoId = entero(data(payload).get("adjuntoId"));
        if (adjuntoId == null || adjuntoId <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_ADJUNTO);
        }
        return ControlDocumentalBridgeHandler.adjuntoParaNavegador(
                get(usuario, BASE + "/adjunto/" + adjuntoId), "adjunto_" + adjuntoId, MAX_BASE64_CHARS, mapper);
    }

    // ------------------------------------------------------------------ Fase 4e-1: escrituras (piloto)

    static final String MENSAJE_CAMPO_NO_PERMITIDO = "Campo no permitido: ";
    static final String MENSAJE_OPCION_INVALIDA = "Valor no permitido en ";
    static final String MENSAJE_PARAMETRO_INVALIDO = "Parámetro inválido.";
    static final String MENSAJE_TEXTO_CARACTERES = "El texto contiene caracteres no permitidos.";
    static final String MENSAJE_TEXTO_HTML = "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").";

    private static final Pattern CONTROL_UNA_LINEA = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final Pattern CONTROL_MULTILINEA = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern MARCADO_HTML = Pattern.compile("<[A-Za-z/!?]");

    /** Opciones exactas del {@code <select id="mlbNmOrigen">} de Photino. */
    private static final Set<String> ORIGENES = Set.of("ControlRecepcion", "Corrugado", "Monotapa", "Emplacado",
            "Troquelado", "Pegado", "ProductoTerminado", "MuestraExterna", "Pruebas", "Otro");
    /** Opciones exactas del {@code <select id="mlbNmTipoMuestra">} de Photino. */
    private static final Set<String> TIPOS_MUESTRA = Set.of("Papel", "Monotapa", "CartonCorrugadoEmplacado",
            "PliegoImpreso", "CajaPegada", "CajaMaster", "AdhesivoPVA", "AdhesivoCorrugado", "Otro");
    /** Opciones del {@code <select id="mlbNmEtapaOrigen">} ("" = -- Seleccionar --, solo si Origen=Pruebas). */
    private static final Set<String> ETAPAS_ORIGEN = Set.of("ControlRecepcion", "Corrugado", "Monotapa", "Emplacado",
            "Troquelado", "Pegado", "ProductoTerminado", "Otro");

    /** Textos de una línea (sin \n) de muestraLab.crear; el resto de los 29 campos se arma aparte. */
    private static final List<String> TEXTOS_UNA_LINEA_CREAR = List.of("np", "cliente", "codigoProducto",
            "descripcion", "maquina", "turno", "lote", "proveedor", "fechaEnsayo", "procesoTextoFps", "operadorTexto",
            "fechaProduccionFps", "fechaConsultaFps", "solicitante", "motivoSolicitud", "ensayosRequeridos",
            "pliegoRelacionado");

    /** Claves de muestraLab.crear de Photino ({@code {action, data:{...}}}); usuarioId/usuarioNombre SIEMPRE de sesión. */
    private static final Set<String> CLAVES_CREAR = concat(TEXTOS_UNA_LINEA_CREAR, "origen", "tipoMuestra",
            "observacion", "maquinaId", "tipoOndaId", "registroProduccionRecordKey", "idProcesoFps", "etapaOrigen",
            "pesoOndaExtendida", "pesoRecorte10x10", "longitudOndaExtendida", "alturaOnda", "monotapaRelacionadaId");

    /** Claves de muestraLab.ph.guardar; analistaUsuarioId/analistaNombre SIEMPRE de sesión. */
    private static final Set<String> CLAVES_PH_GUARDAR = Set.of(
            "muestraId", "metodo", "observacion", "valorTexto", "colorObservado", "ensayoOriginalId", "motivoReemplazo");

    private static Set<String> concat(List<String> base, String... extra) {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>(base);
        s.addAll(List.of(extra));
        return java.util.Collections.unmodifiableSet(s);
    }

    /**
     * muestraLab.crear → POST api/muestra-laboratorio {origen, tipoMuestra, ..., usuarioId, usuarioNombre}
     * (Fase 4e-1), igual que Photino: alta de una muestra de Laboratorio. Origen/Tipo de muestra obligatorios y la
     * FK de monotapaRelacionadaId (debe ser una muestra existente de origen Monotapa) las valida la API con sus
     * propios mensajes; el gateway no las repite. Sin huella ni candado: alta pura, sin control de duplicados.
     *
     * Seguridad transparente: usuarioId/usuarioNombre ← sesión (ni siquiera son claves aceptadas del payload);
     * origen/tipoMuestra/etapaOrigen restringidos a los {@code <select>} de Photino; el resto de los campos (incluido
     * el snapshot FPS: registroProduccionRecordKey/idProcesoFps/procesoTextoFps/operadorTexto/fechaProduccionFps/
     * fechaConsultaFps) viaja tal cual lo manda el cliente — Photino tampoco los valida contra una consulta real, son
     * estado local del formulario de "Nueva muestra" (igual nivel de confianza, no una relajación nueva); textos
     * sin caracteres de control ni marcado HTML.
     */
    public BridgeResult crear(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_CREAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = null;
        for (String campo : TEXTOS_UNA_LINEA_CREAR) {
            error = error != null ? error : ponerTexto(cuerpo, data, campo, CONTROL_UNA_LINEA);
        }
        error = error != null ? error : ponerTexto(cuerpo, data, "observacion", CONTROL_MULTILINEA);
        if (error == null) {
            String origen = texto(data.get("origen"));
            if (!origen.isEmpty() && !ORIGENES.contains(origen)) {
                error = MENSAJE_OPCION_INVALIDA + "origen.";
            }
            cuerpo.put("origen", origen);
        }
        if (error == null) {
            String tipoMuestra = texto(data.get("tipoMuestra"));
            if (!tipoMuestra.isEmpty() && !TIPOS_MUESTRA.contains(tipoMuestra)) {
                error = MENSAJE_OPCION_INVALIDA + "tipoMuestra.";
            }
            cuerpo.put("tipoMuestra", tipoMuestra);
        }
        if (error == null) {
            String etapaOrigen = texto(data.get("etapaOrigen"));
            if (!etapaOrigen.isEmpty() && !ETAPAS_ORIGEN.contains(etapaOrigen)) {
                error = MENSAJE_OPCION_INVALIDA + "etapaOrigen.";
            }
            cuerpo.put("etapaOrigen", etapaOrigen);
        }
        if (error != null) {
            return BridgeResult.error(error);
        }
        ponerEntero(cuerpo, data, "maquinaId");
        ponerEntero(cuerpo, data, "tipoOndaId");
        ponerEntero(cuerpo, data, "monotapaRelacionadaId");
        ponerLargo(cuerpo, data, "registroProduccionRecordKey");
        ponerLargo(cuerpo, data, "idProcesoFps");
        ponerDecimal(cuerpo, data, "pesoOndaExtendida");
        ponerDecimal(cuerpo, data, "pesoRecorte10x10");
        ponerDecimal(cuerpo, data, "longitudOndaExtendida");
        ponerDecimal(cuerpo, data, "alturaOnda");
        cuerpo.put("usuarioId", usuario.userId());
        cuerpo.put("usuarioNombre", autorDeSesion(usuario));
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE, cuerpo), mapper);
    }

    /** Recurso auditado: "muestraLab:&lt;id&gt;:crear". */
    public static String recursoCrear(ObjectNode payload, Object dataRespuesta) {
        JsonNode id = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("id") : null;
        return "muestraLab:" + (id != null && id.canConvertToInt() ? id.asInt() : "?") + ":crear";
    }

    /**
     * muestraLab.ph.guardar → POST api/muestra-laboratorio/ph {muestraId, metodo, observacion, valorTexto,
     * colorObservado, ensayoOriginalId, motivoReemplazo, analistaUsuarioId, analistaNombre} (Fase 4e-1), igual que
     * Photino: registra un ensayo de pH sobre una muestra. `ensayoOriginalId`/`motivoReemplazo` es el patrón común
     * de "corrección" de los 13 tipos de ensayo — la API exige motivo si viene un id y que el ensayo original exista
     * y esté Finalizado; mensajes propios de la API, el gateway no los repite.
     *
     * Seguridad transparente: analistaUsuarioId/analistaNombre ← sesión; muestraId/valorTexto obligatorios los
     * valida la API (mismo mensaje que Photino). Sin huella: cada ensayo es una fila nueva, no sobrescribe nada.
     */
    public BridgeResult phGuardar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_PH_GUARDAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = ponerTexto(cuerpo, data, "metodo", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "observacion", CONTROL_MULTILINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "valorTexto", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "colorObservado", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "motivoReemplazo", CONTROL_UNA_LINEA);
        if (error != null) {
            return BridgeResult.error(error);
        }
        Integer muestraId = entero(data.get("muestraId"));
        cuerpo.put("muestraId", muestraId != null ? muestraId : 0);
        ponerEntero(cuerpo, data, "ensayoOriginalId");
        cuerpo.put("analistaUsuarioId", usuario.userId());
        cuerpo.put("analistaNombre", autorDeSesion(usuario));
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/ph", cuerpo), mapper);
    }

    /** Recurso auditado: "muestraLab:&lt;muestraId&gt;:ph[:&lt;ensayoId&gt;]". */
    public static String recursoPhGuardar(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer muestraId = data == null ? null : entero(data.get("muestraId"));
        JsonNode ensayoId = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("ensayoId") : null;
        return "muestraLab:" + (muestraId != null ? muestraId : "?") + ":ph"
                + (ensayoId != null && ensayoId.canConvertToInt() ? ":" + ensayoId.asInt() : "");
    }

    // ------------------------------------------------------------------ Fase 4e-2: resto de ensayos + nc.crear + adjunto

    static final String MENSAJE_FALTA_MUESTRA = "Falta indicar la muestra";
    static final String MENSAJE_FALTA_ARCHIVO_NOMBRE = "Falta el nombre del archivo";
    static final String MENSAJE_FALTA_ARCHIVO_CONTENIDO = "Falta el contenido del archivo";
    static final String MENSAJE_ARCHIVO_INVALIDO = "El contenido del archivo no es válido";
    static final String MENSAJE_ARCHIVO_TAMANO = "El archivo supera el tamaño máximo permitido (10 MB)";
    static final String MENSAJE_ARCHIVO_TIPO = "Tipo de archivo no permitido. Formatos válidos: .pdf, .doc, .docx, .jpg, .jpeg, .png, .webp";
    static final String MENSAJE_ARCHIVO_FIRMA = "El archivo no corresponde al tipo declarado por su extensión.";
    static final int MAX_SUBIDA_BYTES = 10 * 1024 * 1024;
    static final int MAX_SUBIDA_BASE64 = ((MAX_SUBIDA_BYTES + 2) / 3) * 4;

    private static final java.util.Map<String, String> MIME_POR_EXTENSION_SUBIDA = java.util.Map.ofEntries(
            java.util.Map.entry(".pdf", "application/pdf"), java.util.Map.entry(".doc", "application/msword"),
            java.util.Map.entry(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            java.util.Map.entry(".jpg", "image/jpeg"), java.util.Map.entry(".jpeg", "image/jpeg"),
            java.util.Map.entry(".png", "image/png"), java.util.Map.entry(".webp", "image/webp"));
    private static final byte[] FIRMA_OLE2 = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};
    private static final byte[] FIRMA_ZIP = {0x50, 0x4b, 0x03, 0x04};

    private static final Set<String> CLAVES_ADJUNTO_SUBIR = Set.of("muestraId", "nombreArchivo", "contenidoBase64");
    private static final Set<String> CLAVES_NC_CREAR = Set.of("muestraId");
    /** Opciones del {@code <select>} de cada fila de "Muestreo de bobinas" (Humedad/Gramaje/Espesor). */
    private static final Set<String> POSICIONES_BOBINA = Set.of("Onda", "Liner", "Cartulina");
    private static final Set<String> CARAS_PROBETA_COBB = Set.of("Externa", "Interna");
    private static final Set<String> COMPONENTES_RESISTENCIA = Set.of("Liner", "Onda");
    private static final Set<String> METODOS_EQUIPO_HUMEDAD = Set.of("Higrometro", "Termobalanza", "Horno");
    private static final Set<String> ORIGENES_MUESTRA_HUMEDAD = Set.of("Bobinas", "SeparacionPapeles");
    private static final Set<String> TIPOS_MATERIAL_GRAMAJE = Set.of("Papel", "Cartulina", "Pliego", "ComplejoCorrugado");
    private static final Set<String> MODALIDADES_GRAMAJE = Set.of("ProbetaPeso", "Directo");
    private static final Set<String> TAMANOS_PROBETA_GRAMAJE = Set.of("10x10", "5x5", "10x5");
    private static final Set<String> TIPOS_MEDICION_ESPESOR = Set.of("Ubicacion", "Muestra");
    private static final Set<String> RESULTADOS_LUGOL = Set.of("Negativo", "Positivo", "NoConcluyente");

    /**
     * muestraLab.adjunto.subir → POST api/muestra-laboratorio/{muestraId}/adjunto {nombreArchivo, contenidoBase64,
     * subidoPor} (Fase 4e-2), igual que Photino: adjunta un archivo/foto al registro completo de la muestra (no por
     * ensayo). Mismo criterio de Control Documental: extensión + tamaño (10 MB) los valida la API; el gateway agrega
     * la firma real de los primeros bytes (Photino/la API solo validan por extensión).
     *
     * Seguridad transparente: subidoPor ← sesión; lista blanca {muestraId, nombreArchivo, contenidoBase64}; nombre
     * saneado; igual que el handler C# de Photino, `muestraId &lt;= 0` se rechaza ANTES de llamar a la API (no es la
     * API la que lo valida acá).
     */
    public BridgeResult adjuntoSubir(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_ADJUNTO_SUBIR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer muestraId = entero(data.get("muestraId"));
        if (muestraId == null || muestraId <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_MUESTRA);
        }
        String nombre = texto(data.get("nombreArchivo"));
        String contenidoBase64 = texto(data.get("contenidoBase64"));
        if (nombre.isBlank()) {
            return BridgeResult.error(MENSAJE_FALTA_ARCHIVO_NOMBRE);
        }
        if (contenidoBase64.isBlank()) {
            return BridgeResult.error(MENSAJE_FALTA_ARCHIVO_CONTENIDO);
        }
        String error = validarArchivoSubida(nombre, contenidoBase64);
        if (error != null) {
            return BridgeResult.error(error);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("nombreArchivo", ControlDocumentalBridgeHandler.nombreArchivoSeguro(nombre, "adjunto"));
        cuerpo.put("contenidoBase64", contenidoBase64);
        cuerpo.put("subidoPor", autorDeSesion(usuario));
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/" + muestraId + "/adjunto", cuerpo), mapper);
    }

    /** Recurso auditado: "muestraLab:&lt;muestraId&gt;:adjunto[:&lt;adjuntoId&gt;]". */
    public static String recursoAdjuntoSubir(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer muestraId = data == null ? null : entero(data.get("muestraId"));
        JsonNode id = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("adjuntoId") : null;
        return "muestraLab:" + (muestraId != null ? muestraId : "?") + ":adjunto"
                + (id != null && id.canConvertToInt() ? ":" + id.asInt() : "");
    }

    // ------------------------------------------------------------------ Fase 4e-3: anular / ensayo.anular / actualizarFechaEnsayo

    static final String MENSAJE_FALTA_MUESTRA_O_MOTIVO = "Falta la muestra o el motivo de anulación";
    static final String MENSAJE_FALTA_ENSAYO_O_MOTIVO = "Falta el ensayo o el motivo de anulacion";
    static final String MENSAJE_FECHA_INVALIDA = "Fecha inválida";

    private static final Set<String> CLAVES_ANULAR = Set.of("id", "motivo");
    private static final Set<String> CLAVES_ENSAYO_ANULAR = Set.of("ensayoId", "motivo");
    private static final Set<String> CLAVES_ACTUALIZAR_FECHA = Set.of("id", "fechaEnsayo");
    /** {@code <input type="datetime-local">} de Photino: "AAAA-MM-DDTHH:mm" (segundos opcionales). */
    private static final Pattern FECHA_HORA_LOCAL = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(:[0-9]{2})?");

    /**
     * muestraLab.anular → POST api/muestra-laboratorio/{id}/anular {motivo, usuarioNombre} (Fase 4e-3), igual que
     * Photino: anula el registro completo (conserva historial, no hay "desanular" en la UI). `id`/`motivo` se
     * rechazan ANTES de llamar a la API si faltan (igual que el handler C# de Photino, no solo la API).
     *
     * Seguridad transparente: usuarioNombre ← sesión; motivo (`prompt()` de una línea en Photino) sin caracteres de
     * control ni marcado HTML.
     */
    public BridgeResult anular(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_ANULAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer id = entero(data.get("id"));
        String motivoCrudo = texto(data.get("motivo"));
        if (id == null || id <= 0 || motivoCrudo.isBlank()) {
            return BridgeResult.error(MENSAJE_FALTA_MUESTRA_O_MOTIVO);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = ponerTexto(cuerpo, data, "motivo", CONTROL_UNA_LINEA);
        if (error != null) {
            return BridgeResult.error(error);
        }
        cuerpo.put("usuarioNombre", autorDeSesion(usuario));
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/" + id + "/anular", cuerpo), mapper);
    }

    /** Recurso auditado: "muestraLab:&lt;id&gt;:anular". */
    public static String recursoAnular(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer id = data == null ? null : entero(data.get("id"));
        return "muestraLab:" + (id != null ? id : "?") + ":anular";
    }

    /**
     * muestraLab.ensayo.anular → POST api/muestra-laboratorio/ensayos/{ensayoId}/anular {motivo} (Fase 4e-3), igual
     * que Photino: anula un ensayo puntual (no hay "desanular"). La API no recibe autor (sin campo para ello);
     * auditoría obligatoria del gateway. `ensayoId`/`motivo` se rechazan ANTES de llamar a la API si faltan.
     */
    public BridgeResult ensayoAnular(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_ENSAYO_ANULAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer ensayoId = entero(data.get("ensayoId"));
        String motivoCrudo = texto(data.get("motivo"));
        if (ensayoId == null || ensayoId <= 0 || motivoCrudo.isBlank()) {
            return BridgeResult.error(MENSAJE_FALTA_ENSAYO_O_MOTIVO);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = ponerTexto(cuerpo, data, "motivo", CONTROL_UNA_LINEA);
        if (error != null) {
            return BridgeResult.error(error);
        }
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/ensayos/" + ensayoId + "/anular", cuerpo), mapper);
    }

    /** Recurso auditado: "muestraLab:ensayo:&lt;ensayoId&gt;:anular". */
    public static String recursoEnsayoAnular(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer ensayoId = data == null ? null : entero(data.get("ensayoId"));
        return "muestraLab:ensayo:" + (ensayoId != null ? ensayoId : "?") + ":anular";
    }

    /**
     * muestraLab.actualizarFechaEnsayo → PUT api/muestra-laboratorio/{id}/fecha-ensayo {fechaEnsayo, usuarioNombre}
     * (Fase 4e-3), igual que Photino: corrige la fecha efectiva de un registro ya creado (auditada por la API:
     * `fechaEnsayoModificadaPor`/`fechaEnsayoFechaModificacion`). `id` se rechaza ANTES de llamar a la API si falta.
     *
     * Seguridad transparente: usuarioNombre ← sesión; fechaEnsayo restringida al formato real del
     * {@code <input type="datetime-local">} de Photino (AAAA-MM-DDTHH:mm[:ss]) — la API solo valida que sea una
     * fecha parseable, sin fijar el formato.
     */
    public BridgeResult actualizarFechaEnsayo(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_ACTUALIZAR_FECHA.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer id = entero(data.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_MUESTRA);
        }
        String fechaEnsayo = texto(data.get("fechaEnsayo"));
        if (!FECHA_HORA_LOCAL.matcher(fechaEnsayo).matches()) {
            return BridgeResult.error(MENSAJE_FECHA_INVALIDA);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("fechaEnsayo", fechaEnsayo);
        cuerpo.put("usuarioNombre", autorDeSesion(usuario));
        return InnpackRespuestas.reenviar(api.putJson(usuario, BASE + "/" + id + "/fecha-ensayo", cuerpo), mapper);
    }

    /** Recurso auditado: "muestraLab:&lt;id&gt;:fechaEnsayo". */
    public static String recursoActualizarFechaEnsayo(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer id = data == null ? null : entero(data.get("id"));
        return "muestraLab:" + (id != null ? id : "?") + ":fechaEnsayo";
    }

    // ------------------------------------------------------------------ Fase 4e-4: maestros de métodos y especificaciones

    /** Opciones del {@code <select id="mlbMetodoTipoEnsayo">} de Photino (9 valores). */
    private static final Set<String> TIPOS_ENSAYO_METODO = Set.of("HUMEDAD", "GRAMAJE", "ESPESOR", "COBB", "RCT",
            "FCT", "ECT", "BCT_MEDIDO", "BCT_TEORICO");
    /** Opciones del {@code <select id="mlbMetodoVariante">} ("" = no aplica). */
    private static final Set<String> VARIANTES_METODO = Set.of("Horno", "Higrometro", "Termobalanza");
    /** Opciones del {@code <select id="mlbEspecTipoEnsayo">} de Photino (12 valores, distinto del de métodos). */
    private static final Set<String> TIPOS_ENSAYO_ESPECIFICACION = Set.of("HUMEDAD", "GRAMAJE", "COBB", "ESPESOR",
            "RCT", "FCT", "ECT", "BCT_MEDIDO", "BCT_TEORICO", "VISCOSIDAD", "PH", "SOLIDOS");

    private static final Set<String> CLAVES_METODO_GUARDAR = Set.of("id", "tipoEnsayo", "variante", "nombre",
            "codigo", "version", "unidad");
    private static final Set<String> CLAVES_ACTIVAR = Set.of("id", "activo");
    private static final Set<String> CLAVES_ESPECIFICACION_GUARDAR = Set.of("id", "tipoMuestra", "tipoEnsayo",
            "codigoProducto", "limiteMin", "limiteMax", "unidad");

    /**
     * muestraLab.metodo.guardar → POST api/muestra-laboratorio/metodos {id?, tipoEnsayo, variante, nombre, codigo,
     * version, unidad, usuarioNombre} (Fase 4e-4), igual que Photino: crea o edita (según `id`) un método del
     * maestro (punto 32 del REG-LAB-04). `variante` viaja `null` (no `""`) cuando no aplica — el lookup del método
     * vigente de la API compara "variante IS NULL" literal en SQL (solo HUMEDAD usa variante).
     *
     * Seguridad transparente: usuarioNombre ← sesión; tipoEnsayo/variante restringidos a los {@code <select>} de
     * Photino (TipoEnsayo/Nombre obligatorios los valida la API).
     */
    public BridgeResult metodoGuardar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_METODO_GUARDAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        ponerEntero(cuerpo, data, "id");
        String error = ponerTexto(cuerpo, data, "nombre", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "codigo", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "version", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "unidad", CONTROL_UNA_LINEA);
        if (error == null) {
            String tipoEnsayo = texto(data.get("tipoEnsayo"));
            if (!tipoEnsayo.isEmpty() && !TIPOS_ENSAYO_METODO.contains(tipoEnsayo)) {
                error = MENSAJE_OPCION_INVALIDA + "tipoEnsayo.";
            } else {
                cuerpo.put("tipoEnsayo", tipoEnsayo);
            }
        }
        if (error == null) {
            String variante = texto(data.get("variante"));
            if (variante.isBlank()) {
                cuerpo.putNull("variante");
            } else if (!VARIANTES_METODO.contains(variante)) {
                error = MENSAJE_OPCION_INVALIDA + "variante.";
            } else {
                cuerpo.put("variante", variante);
            }
        }
        if (error != null) {
            return BridgeResult.error(error);
        }
        cuerpo.put("usuarioNombre", autorDeSesion(usuario));
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/metodos", cuerpo), mapper);
    }

    /** Recurso auditado: "muestraLab:metodo:&lt;id&gt;". */
    public static String recursoMetodoGuardar(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer id = data == null ? null : entero(data.get("id"));
        JsonNode idRespuesta = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("id") : null;
        Integer resuelto = id != null ? id : (idRespuesta != null && idRespuesta.canConvertToInt() ? idRespuesta.asInt() : null);
        return "muestraLab:metodo:" + (resuelto != null ? resuelto : "?");
    }

    /**
     * muestraLab.metodo.activar → PATCH api/muestra-laboratorio/metodos/{id}/activo {activo} (Fase 4e-4), igual que
     * Photino: activa/desactiva un método del maestro. `id` se rechaza ANTES de llamar a la API si falta (igual que
     * el handler C# de Photino). La API no recibe autor (sin campo para ello); auditoría obligatoria del gateway.
     * `activo` sigue el mismo criterio que Photino: solo el booleano JSON `true` cuenta como activar, cualquier otra
     * cosa (incluida su ausencia) es desactivar.
     */
    public BridgeResult metodoActivar(ObjectNode payload, SessionUser usuario) {
        return activarComun(payload, usuario, "metodos", MENSAJE_FALTA_METODO);
    }

    /** Recurso auditado: "muestraLab:metodo:&lt;id&gt;:activo". */
    public static String recursoMetodoActivar(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer id = data == null ? null : entero(data.get("id"));
        return "muestraLab:metodo:" + (id != null ? id : "?") + ":activo";
    }

    /**
     * muestraLab.especificacion.guardar → POST api/muestra-laboratorio/especificaciones {id?, tipoMuestra,
     * tipoEnsayo, codigoProducto, limiteMin, limiteMax, unidad} (Fase 4e-4), igual que Photino: crea o edita (según
     * `id`) una especificación del maestro. La API no recibe autor (sin campo para ello); auditoría obligatoria del
     * gateway. tipoMuestra/tipoEnsayo obligatorios y al menos un límite los valida la API con mensajes propios.
     *
     * Seguridad transparente: tipoMuestra (mismas 9 opciones que `muestraLab.crear`) y tipoEnsayo (12 opciones,
     * distintas de las 9 de `metodo.guardar`) restringidos a los {@code <select>} de Photino.
     */
    public BridgeResult especificacionGuardar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_ESPECIFICACION_GUARDAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        ponerEntero(cuerpo, data, "id");
        String error = ponerTexto(cuerpo, data, "codigoProducto", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "unidad", CONTROL_UNA_LINEA);
        if (error == null) {
            String tipoMuestra = texto(data.get("tipoMuestra"));
            if (!tipoMuestra.isEmpty() && !TIPOS_MUESTRA.contains(tipoMuestra)) {
                error = MENSAJE_OPCION_INVALIDA + "tipoMuestra.";
            } else {
                cuerpo.put("tipoMuestra", tipoMuestra);
            }
        }
        if (error == null) {
            String tipoEnsayo = texto(data.get("tipoEnsayo"));
            if (!tipoEnsayo.isEmpty() && !TIPOS_ENSAYO_ESPECIFICACION.contains(tipoEnsayo)) {
                error = MENSAJE_OPCION_INVALIDA + "tipoEnsayo.";
            } else {
                cuerpo.put("tipoEnsayo", tipoEnsayo);
            }
        }
        if (error != null) {
            return BridgeResult.error(error);
        }
        ponerDecimal(cuerpo, data, "limiteMin");
        ponerDecimal(cuerpo, data, "limiteMax");
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/especificaciones", cuerpo), mapper);
    }

    /** Recurso auditado: "muestraLab:especificacion:&lt;id&gt;". */
    public static String recursoEspecificacionGuardar(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer id = data == null ? null : entero(data.get("id"));
        JsonNode idRespuesta = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("id") : null;
        Integer resuelto = id != null ? id : (idRespuesta != null && idRespuesta.canConvertToInt() ? idRespuesta.asInt() : null);
        return "muestraLab:especificacion:" + (resuelto != null ? resuelto : "?");
    }

    /**
     * muestraLab.especificacion.activar → PATCH api/muestra-laboratorio/especificaciones/{id}/activo {activo}
     * (Fase 4e-4), igual que Photino. Mismo criterio que `metodo.activar`.
     */
    public BridgeResult especificacionActivar(ObjectNode payload, SessionUser usuario) {
        return activarComun(payload, usuario, "especificaciones", MENSAJE_FALTA_ESPECIFICACION);
    }

    /** Recurso auditado: "muestraLab:especificacion:&lt;id&gt;:activo". */
    public static String recursoEspecificacionActivar(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer id = data == null ? null : entero(data.get("id"));
        return "muestraLab:especificacion:" + (id != null ? id : "?") + ":activo";
    }

    static final String MENSAJE_FALTA_METODO = "Falta indicar el método";
    static final String MENSAJE_FALTA_ESPECIFICACION = "Falta indicar la especificación";

    /** metodo.activar/especificacion.activar comparten el mismo cuerpo {id, activo} y la misma validación en Photino. */
    private BridgeResult activarComun(ObjectNode payload, SessionUser usuario, String recurso, String mensajeFaltaId) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_ACTIVAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer id = entero(data.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(mensajeFaltaId);
        }
        JsonNode activoNodo = data.get("activo");
        boolean activo = activoNodo != null && activoNodo.isBoolean() && activoNodo.asBoolean();
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("activo", activo);
        return InnpackRespuestas.reenviar(api.patchJson(usuario, BASE + "/" + recurso + "/" + id + "/activo", cuerpo), mapper);
    }

    // ------------------------------------------------------------------ Fase 4e-5: eliminar / adjunto.eliminar

    static final String MENSAJE_FALTA_MUESTRA_ELIMINAR = "Falta la muestra a eliminar";
    private static final Set<String> CLAVES_ELIMINAR = Set.of("id");
    private static final Set<String> CLAVES_ADJUNTO_ELIMINAR = Set.of("adjuntoId");

    /**
     * muestraLab.eliminar → DELETE api/muestra-laboratorio/{id} (Fase 4e-5), igual que Photino: borrado lógico
     * (columna `eliminado`), distinto de `anular` (que conserva el registro con historial). `id` se rechaza ANTES de
     * llamar a la API si falta (igual que el handler C# de Photino). A diferencia de `controlDocumental.eliminar`,
     * la API SÍ verifica existencia (filas afectadas) y devuelve un error real si ya no existe o ya estaba
     * eliminada — el gateway no necesita releer antes de borrar. Sin autor: ni Photino ni la API lo registran;
     * auditoría obligatoria del gateway. No hay "deseliminar" en la UI (confirm() del navegador en Photino).
     */
    public BridgeResult eliminar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_ELIMINAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer id = entero(data.get("id"));
        if (id == null || id <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_MUESTRA_ELIMINAR);
        }
        return InnpackRespuestas.reenviar(api.delete(usuario, BASE + "/" + id), mapper);
    }

    /** Recurso auditado: "muestraLab:&lt;id&gt;:eliminar". */
    public static String recursoEliminar(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer id = data == null ? null : entero(data.get("id"));
        return "muestraLab:" + (id != null ? id : "?") + ":eliminar";
    }

    /**
     * muestraLab.adjunto.eliminar → DELETE api/muestra-laboratorio/adjunto/{adjuntoId} (Fase 4e-5), igual que
     * Photino: elimina un adjunto (sin "deseliminar" en la UI). `adjuntoId` se rechaza ANTES de llamar a la API si
     * falta. La API devuelve 404 real ("Adjunto no encontrado") si ya no existe; el gateway no necesita releer
     * antes de borrar. Sin autor: ni Photino ni la API lo registran; auditoría obligatoria del gateway.
     */
    public BridgeResult adjuntoEliminar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_ADJUNTO_ELIMINAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer adjuntoId = entero(data.get("adjuntoId"));
        if (adjuntoId == null || adjuntoId <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_ADJUNTO);
        }
        return InnpackRespuestas.reenviar(api.delete(usuario, BASE + "/adjunto/" + adjuntoId), mapper);
    }

    /** Recurso auditado: "muestraLab:adjunto:&lt;adjuntoId&gt;:eliminar". */
    public static String recursoAdjuntoEliminar(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer adjuntoId = data == null ? null : entero(data.get("adjuntoId"));
        return "muestraLab:adjunto:" + (adjuntoId != null ? adjuntoId : "?") + ":eliminar";
    }

    private static String validarArchivoSubida(String nombreArchivo, String base64) {
        int punto = nombreArchivo.lastIndexOf('.');
        String extension = punto < 0 ? "" : nombreArchivo.substring(punto).toLowerCase(java.util.Locale.ROOT);
        String mimeEsperado = MIME_POR_EXTENSION_SUBIDA.get(extension);
        if (mimeEsperado == null) {
            return MENSAJE_ARCHIVO_TIPO;
        }
        if (base64.length() > MAX_SUBIDA_BASE64) {
            return MENSAJE_ARCHIVO_TAMANO;
        }
        byte[] contenido;
        try {
            contenido = java.util.Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return MENSAJE_ARCHIVO_INVALIDO;
        }
        if (contenido.length > MAX_SUBIDA_BYTES) {
            return MENSAJE_ARCHIVO_TAMANO;
        }
        boolean firmaOk = switch (mimeEsperado) {
            case "application/msword" -> coincidePrefijo(contenido, FIRMA_OLE2);
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> coincidePrefijo(contenido, FIRMA_ZIP);
            default -> ControlDocumentalBridgeHandler.firmaCoincide(mimeEsperado, contenido);
        };
        return firmaOk ? null : MENSAJE_ARCHIVO_FIRMA;
    }

    private static boolean coincidePrefijo(byte[] contenido, byte[] firma) {
        if (contenido.length < firma.length) {
            return false;
        }
        for (int i = 0; i < firma.length; i++) {
            if (contenido[i] != firma[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * muestraLab.nc.crear → POST api/muestra-laboratorio/{muestraId}/nc {usuarioNombre} (Fase 4e-2), igual que
     * Photino: crea una No Conformidad vinculada a la muestra. La API exige que la muestra exista, que haya evaluado
     * "No cumple" y que no tenga ya una NC vinculada (mensajes propios); el gateway no los repite.
     *
     * Seguridad transparente: usuarioNombre ← sesión (creadoPor); lista blanca {muestraId}; `muestraId &lt;= 0` se
     * rechaza antes de llamar a la API (igual que el handler C# de Photino).
     */
    public BridgeResult ncCrear(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_NC_CREAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer muestraId = entero(data.get("muestraId"));
        if (muestraId == null || muestraId <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_MUESTRA);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("usuarioNombre", autorDeSesion(usuario));
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/" + muestraId + "/nc", cuerpo), mapper);
    }

    /** Recurso auditado: "muestraLab:&lt;muestraId&gt;:nc[:&lt;ncId&gt;]". */
    public static String recursoNcCrear(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer muestraId = data == null ? null : entero(data.get("muestraId"));
        JsonNode id = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("ncId") : null;
        return "muestraLab:" + (muestraId != null ? muestraId : "?") + ":nc"
                + (id != null && id.canConvertToInt() ? ":" + id.asInt() : "");
    }

    // ---- Los otros 12 tipos de ensayo (el 13° es ph.guardar, arriba) comparten el mismo cuerpo común: muestraId,
    // metodo, observacion, analista de sesión, y el patrón de corrección ensayoOriginalId/motivoReemplazo.

    private static final Set<String> CLAVES_HUMEDAD = Set.of("muestraId", "metodo", "observacion", "metodoEquipo",
            "higrometroIzquierdo", "higrometroCentro", "higrometroDerecho", "termobalanzaValor",
            "horno1PesoInicial", "horno1PesoFinal", "horno2PesoInicial", "horno2PesoFinal",
            "horno3PesoInicial", "horno3PesoFinal", "bobinaOnda", "bobinaLiner", "bobinaCartulina",
            "bobinas", "origenMuestra", "ensayoOriginalId", "motivoReemplazo");

    /** muestraLab.humedad.guardar → POST api/muestra-laboratorio/humedad (Fase 4e-2). */
    public BridgeResult humedadGuardar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_HUMEDAD.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = armarComunesEnsayo(cuerpo, data);
        error = error != null ? error : ponerTexto(cuerpo, data, "bobinaOnda", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "bobinaLiner", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "bobinaCartulina", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerBobinas(cuerpo, data, "bobinas");
        if (error == null) {
            String metodoEquipo = texto(data.get("metodoEquipo"));
            if (!metodoEquipo.isEmpty() && !METODOS_EQUIPO_HUMEDAD.contains(metodoEquipo)) {
                error = MENSAJE_OPCION_INVALIDA + "metodoEquipo.";
            } else {
                cuerpo.put("metodoEquipo", metodoEquipo);
            }
        }
        if (error == null) {
            String origenMuestra = texto(data.get("origenMuestra"));
            if (!origenMuestra.isEmpty() && !ORIGENES_MUESTRA_HUMEDAD.contains(origenMuestra)) {
                error = MENSAJE_OPCION_INVALIDA + "origenMuestra.";
            } else {
                cuerpo.put("origenMuestra", origenMuestra);
            }
        }
        if (error != null) {
            return BridgeResult.error(error);
        }
        ponerDecimal(cuerpo, data, "higrometroIzquierdo");
        ponerDecimal(cuerpo, data, "higrometroCentro");
        ponerDecimal(cuerpo, data, "higrometroDerecho");
        ponerDecimal(cuerpo, data, "termobalanzaValor");
        ponerDecimal(cuerpo, data, "horno1PesoInicial");
        ponerDecimal(cuerpo, data, "horno1PesoFinal");
        ponerDecimal(cuerpo, data, "horno2PesoInicial");
        ponerDecimal(cuerpo, data, "horno2PesoFinal");
        ponerDecimal(cuerpo, data, "horno3PesoInicial");
        ponerDecimal(cuerpo, data, "horno3PesoFinal");
        error = cerrarComunesEnsayo(cuerpo, data, usuario);
        if (error != null) {
            return BridgeResult.error(error);
        }
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/humedad", cuerpo), mapper);
    }

    public static String recursoHumedadGuardar(ObjectNode p, Object d) {
        return recursoEnsayo("humedad", p, d);
    }

    private static final Set<String> CLAVES_GRAMAJE = Set.of("muestraId", "metodo", "observacion", "tipoMaterial",
            "modalidad", "tamanoProbeta", "muestra1", "muestra2", "muestra3", "bobinaOnda", "bobinaLiner",
            "bobinaCartulina", "bobinas", "ensayoOriginalId", "motivoReemplazo");

    /** muestraLab.gramaje.guardar → POST api/muestra-laboratorio/gramaje (Fase 4e-2). */
    public BridgeResult gramajeGuardar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_GRAMAJE.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = armarComunesEnsayo(cuerpo, data);
        error = error != null ? error : ponerTexto(cuerpo, data, "bobinaOnda", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "bobinaLiner", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "bobinaCartulina", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerBobinas(cuerpo, data, "bobinas");
        if (error == null) {
            String tipoMaterial = texto(data.get("tipoMaterial"));
            if (!tipoMaterial.isEmpty() && !TIPOS_MATERIAL_GRAMAJE.contains(tipoMaterial)) {
                error = MENSAJE_OPCION_INVALIDA + "tipoMaterial.";
            } else {
                cuerpo.put("tipoMaterial", tipoMaterial);
            }
        }
        if (error == null) {
            String modalidad = texto(data.get("modalidad"));
            if (!modalidad.isEmpty() && !MODALIDADES_GRAMAJE.contains(modalidad)) {
                error = MENSAJE_OPCION_INVALIDA + "modalidad.";
            } else {
                cuerpo.put("modalidad", modalidad);
            }
        }
        if (error == null) {
            String tamanoProbeta = texto(data.get("tamanoProbeta"));
            if (!tamanoProbeta.isEmpty() && !TAMANOS_PROBETA_GRAMAJE.contains(tamanoProbeta)) {
                error = MENSAJE_OPCION_INVALIDA + "tamanoProbeta.";
            } else {
                cuerpo.put("tamanoProbeta", tamanoProbeta);
            }
        }
        if (error != null) {
            return BridgeResult.error(error);
        }
        ponerDecimal(cuerpo, data, "muestra1");
        ponerDecimal(cuerpo, data, "muestra2");
        ponerDecimal(cuerpo, data, "muestra3");
        error = cerrarComunesEnsayo(cuerpo, data, usuario);
        if (error != null) {
            return BridgeResult.error(error);
        }
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/gramaje", cuerpo), mapper);
    }

    public static String recursoGramajeGuardar(ObjectNode p, Object d) {
        return recursoEnsayo("gramaje", p, d);
    }

    private static final Set<String> CLAVES_ESPESOR = Set.of("muestraId", "metodo", "observacion", "tipoMedicion",
            "medicion1", "medicion2", "medicion3", "bobinaOnda", "bobinaLiner", "bobinaCartulina",
            "bobinas", "ensayoOriginalId", "motivoReemplazo");

    /** muestraLab.espesor.guardar → POST api/muestra-laboratorio/espesor (Fase 4e-2). */
    public BridgeResult espesorGuardar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_ESPESOR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = armarComunesEnsayo(cuerpo, data);
        error = error != null ? error : ponerTexto(cuerpo, data, "bobinaOnda", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "bobinaLiner", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "bobinaCartulina", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerBobinas(cuerpo, data, "bobinas");
        if (error == null) {
            String tipoMedicion = texto(data.get("tipoMedicion"));
            if (!tipoMedicion.isEmpty() && !TIPOS_MEDICION_ESPESOR.contains(tipoMedicion)) {
                error = MENSAJE_OPCION_INVALIDA + "tipoMedicion.";
            } else {
                cuerpo.put("tipoMedicion", tipoMedicion);
            }
        }
        if (error != null) {
            return BridgeResult.error(error);
        }
        ponerDecimal(cuerpo, data, "medicion1");
        ponerDecimal(cuerpo, data, "medicion2");
        ponerDecimal(cuerpo, data, "medicion3");
        error = cerrarComunesEnsayo(cuerpo, data, usuario);
        if (error != null) {
            return BridgeResult.error(error);
        }
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/espesor", cuerpo), mapper);
    }

    public static String recursoEspesorGuardar(ObjectNode p, Object d) {
        return recursoEnsayo("espesor", p, d);
    }

    private static final Set<String> CLAVES_COBB = Set.of("muestraId", "metodo", "observacion",
            "p1", "p2", "p3", "ensayoOriginalId", "motivoReemplazo");

    /** muestraLab.cobb.guardar → POST api/muestra-laboratorio/cobb (Fase 4e-2). */
    public BridgeResult cobbGuardar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_COBB.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = armarComunesEnsayo(cuerpo, data);
        error = error != null ? error : ponerProbetaCobb(cuerpo, data, "p1");
        error = error != null ? error : ponerProbetaCobb(cuerpo, data, "p2");
        error = error != null ? error : ponerProbetaCobb(cuerpo, data, "p3");
        if (error != null) {
            return BridgeResult.error(error);
        }
        error = cerrarComunesEnsayo(cuerpo, data, usuario);
        if (error != null) {
            return BridgeResult.error(error);
        }
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/cobb", cuerpo), mapper);
    }

    public static String recursoCobbGuardar(ObjectNode p, Object d) {
        return recursoEnsayo("cobb", p, d);
    }

    private static final Set<String> CLAVES_RCT = Set.of("muestraId", "metodo", "observacion", "componente",
            "strengthUnidad", "p1", "p2", "p3", "ensayoOriginalId", "motivoReemplazo");
    private static final Set<String> CLAVES_FCT = Set.of("muestraId", "metodo", "observacion",
            "strengthUnidad", "p1", "p2", "p3", "ensayoOriginalId", "motivoReemplazo");

    /** muestraLab.rct.guardar → POST api/muestra-laboratorio/rct (Fase 4e-2). */
    public BridgeResult rctGuardar(ObjectNode payload, SessionUser usuario) {
        return resistenciaGuardar(payload, usuario, true);
    }

    /** muestraLab.fct.guardar → POST api/muestra-laboratorio/fct (Fase 4e-2). */
    public BridgeResult fctGuardar(ObjectNode payload, SessionUser usuario) {
        return resistenciaGuardar(payload, usuario, false);
    }

    /** rct.guardar/fct.guardar comparten el mismo cuerpo en Photino; solo RCT lee/exige "componente". */
    private BridgeResult resistenciaGuardar(ObjectNode payload, SessionUser usuario, boolean esRct) {
        JsonNode data = data(payload);
        Set<String> claves = esRct ? CLAVES_RCT : CLAVES_FCT;
        for (String clave : data.propertyNames()) {
            if (!claves.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = armarComunesEnsayo(cuerpo, data);
        error = error != null ? error : ponerTexto(cuerpo, data, "strengthUnidad", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerProbetaResistencia(cuerpo, data, "p1");
        error = error != null ? error : ponerProbetaResistencia(cuerpo, data, "p2");
        error = error != null ? error : ponerProbetaResistencia(cuerpo, data, "p3");
        if (error == null && esRct) {
            String componente = texto(data.get("componente"));
            if (!componente.isEmpty() && !COMPONENTES_RESISTENCIA.contains(componente)) {
                error = MENSAJE_OPCION_INVALIDA + "componente.";
            } else {
                cuerpo.put("componente", componente);
            }
        } else if (error == null) {
            cuerpo.putNull("componente");
        }
        if (error != null) {
            return BridgeResult.error(error);
        }
        error = cerrarComunesEnsayo(cuerpo, data, usuario);
        if (error != null) {
            return BridgeResult.error(error);
        }
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + (esRct ? "/rct" : "/fct"), cuerpo), mapper);
    }

    public static String recursoRctGuardar(ObjectNode p, Object d) {
        return recursoEnsayo("rct", p, d);
    }

    public static String recursoFctGuardar(ObjectNode p, Object d) {
        return recursoEnsayo("fct", p, d);
    }

    private static final Set<String> CLAVES_ECT = Set.of("muestraId", "metodo", "observacion",
            "p1Force", "p2Force", "p3Force", "p4Force", "p5Force", "ensayoOriginalId", "motivoReemplazo");

    /** muestraLab.ect.guardar → POST api/muestra-laboratorio/ect (Fase 4e-2). */
    public BridgeResult ectGuardar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_ECT.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = armarComunesEnsayo(cuerpo, data);
        if (error != null) {
            return BridgeResult.error(error);
        }
        ponerDecimal(cuerpo, data, "p1Force");
        ponerDecimal(cuerpo, data, "p2Force");
        ponerDecimal(cuerpo, data, "p3Force");
        ponerDecimal(cuerpo, data, "p4Force");
        ponerDecimal(cuerpo, data, "p5Force");
        error = cerrarComunesEnsayo(cuerpo, data, usuario);
        if (error != null) {
            return BridgeResult.error(error);
        }
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/ect", cuerpo), mapper);
    }

    public static String recursoEctGuardar(ObjectNode p, Object d) {
        return recursoEnsayo("ect", p, d);
    }

    private static final Set<String> CLAVES_BCT_MEDIDO = Set.of("muestraId", "metodo", "observacion",
            "cajasEnsayadas", "motivoMenos3", "c1", "c2", "c3", "ensayoOriginalId", "motivoReemplazo");

    /** muestraLab.bctMedido.guardar → POST api/muestra-laboratorio/bct-medido (Fase 4e-2); cajasEnsayadas (1-3) lo
     * valida la API; c2/c3 se fuerzan a null si no corresponden (igual que el handler C# de Photino). */
    public BridgeResult bctMedidoGuardar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_BCT_MEDIDO.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = armarComunesEnsayo(cuerpo, data);
        error = error != null ? error : ponerTexto(cuerpo, data, "motivoMenos3", CONTROL_UNA_LINEA);
        if (error != null) {
            return BridgeResult.error(error);
        }
        int cajas = enteroODefault(data.get("cajasEnsayadas"), 0);
        cuerpo.put("cajasEnsayadas", cajas);
        error = ponerCajaBct(cuerpo, data, "c1");
        if (error == null) {
            if (cajas >= 2) {
                error = ponerCajaBct(cuerpo, data, "c2");
            } else {
                cuerpo.putNull("c2");
            }
        }
        if (error == null) {
            if (cajas >= 3) {
                error = ponerCajaBct(cuerpo, data, "c3");
            } else {
                cuerpo.putNull("c3");
            }
        }
        if (error != null) {
            return BridgeResult.error(error);
        }
        error = cerrarComunesEnsayo(cuerpo, data, usuario);
        if (error != null) {
            return BridgeResult.error(error);
        }
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/bct-medido", cuerpo), mapper);
    }

    public static String recursoBctMedidoGuardar(ObjectNode p, Object d) {
        return recursoEnsayo("bctMedido", p, d);
    }

    private static final Set<String> CLAVES_BCT_TEORICO = Set.of("muestraId", "metodo", "observacion",
            "ectEnsayoId", "espesorEnsayoId", "largoMm", "anchoMm", "ensayoOriginalId", "motivoReemplazo");

    /** muestraLab.bctTeorico.guardar → POST api/muestra-laboratorio/bct-teorico (Fase 4e-2); ectEnsayoId/
     * espesorEnsayoId deben ser ensayos ya Finalizados de la muestra, lo valida la API. */
    public BridgeResult bctTeoricoGuardar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_BCT_TEORICO.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = armarComunesEnsayo(cuerpo, data);
        if (error != null) {
            return BridgeResult.error(error);
        }
        cuerpo.put("ectEnsayoId", enteroODefault(data.get("ectEnsayoId"), 0));
        cuerpo.put("espesorEnsayoId", enteroODefault(data.get("espesorEnsayoId"), 0));
        cuerpo.put("largoMm", decimalODefault(data.get("largoMm")));
        cuerpo.put("anchoMm", decimalODefault(data.get("anchoMm")));
        error = cerrarComunesEnsayo(cuerpo, data, usuario);
        if (error != null) {
            return BridgeResult.error(error);
        }
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/bct-teorico", cuerpo), mapper);
    }

    public static String recursoBctTeoricoGuardar(ObjectNode p, Object d) {
        return recursoEnsayo("bctTeorico", p, d);
    }

    private static final Set<String> CLAVES_VISCOSIDAD = Set.of("muestraId", "metodo", "observacion",
            "tipoAdhesivo", "temperatura", "equipo", "husillo", "velocidadRpm", "resultadoCp",
            "ensayoOriginalId", "motivoReemplazo");

    /** muestraLab.viscosidad.guardar → POST api/muestra-laboratorio/viscosidad (Fase 4e-2). */
    public BridgeResult viscosidadGuardar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_VISCOSIDAD.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = armarComunesEnsayo(cuerpo, data);
        error = error != null ? error : ponerTexto(cuerpo, data, "tipoAdhesivo", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "equipo", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "husillo", CONTROL_UNA_LINEA);
        if (error != null) {
            return BridgeResult.error(error);
        }
        ponerDecimal(cuerpo, data, "temperatura");
        ponerDecimal(cuerpo, data, "velocidadRpm");
        ponerDecimal(cuerpo, data, "resultadoCp");
        error = cerrarComunesEnsayo(cuerpo, data, usuario);
        if (error != null) {
            return BridgeResult.error(error);
        }
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/viscosidad", cuerpo), mapper);
    }

    public static String recursoViscosidadGuardar(ObjectNode p, Object d) {
        return recursoEnsayo("viscosidad", p, d);
    }

    private static final Set<String> CLAVES_SOLIDOS = Set.of("muestraId", "metodo", "observacion",
            "d1", "d2", "d3", "ensayoOriginalId", "motivoReemplazo");

    /** muestraLab.solidos.guardar → POST api/muestra-laboratorio/solidos (Fase 4e-2). */
    public BridgeResult solidosGuardar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_SOLIDOS.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = armarComunesEnsayo(cuerpo, data);
        error = error != null ? error : ponerDeterminacionSolidos(cuerpo, data, "d1");
        error = error != null ? error : ponerDeterminacionSolidos(cuerpo, data, "d2");
        error = error != null ? error : ponerDeterminacionSolidos(cuerpo, data, "d3");
        if (error != null) {
            return BridgeResult.error(error);
        }
        error = cerrarComunesEnsayo(cuerpo, data, usuario);
        if (error != null) {
            return BridgeResult.error(error);
        }
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/solidos", cuerpo), mapper);
    }

    public static String recursoSolidosGuardar(ObjectNode p, Object d) {
        return recursoEnsayo("solidos", p, d);
    }

    private static final Set<String> CLAVES_LUGOL = Set.of("muestraId", "metodo", "observacion",
            "puntoMuestra", "coloracion", "resultado", "interpretacion", "cumplimiento",
            "ensayoOriginalId", "motivoReemplazo");

    /** muestraLab.lugol.guardar → POST api/muestra-laboratorio/lugol (Fase 4e-2); cumplimiento en blanco → "Sin
     * especificacion" (default del handler C# de Photino, no de la API). */
    public BridgeResult lugolGuardar(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_LUGOL.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        String error = armarComunesEnsayo(cuerpo, data);
        error = error != null ? error : ponerTexto(cuerpo, data, "puntoMuestra", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "coloracion", CONTROL_UNA_LINEA);
        error = error != null ? error : ponerTexto(cuerpo, data, "interpretacion", CONTROL_UNA_LINEA);
        if (error == null) {
            String resultado = texto(data.get("resultado"));
            if (!resultado.isEmpty() && !RESULTADOS_LUGOL.contains(resultado)) {
                error = MENSAJE_OPCION_INVALIDA + "resultado.";
            } else {
                cuerpo.put("resultado", resultado);
            }
        }
        if (error == null) {
            String cumplimiento = texto(data.get("cumplimiento"));
            if (CONTROL_UNA_LINEA.matcher(cumplimiento).find()) {
                error = MENSAJE_TEXTO_CARACTERES;
            } else if (MARCADO_HTML.matcher(cumplimiento).find()) {
                error = MENSAJE_TEXTO_HTML;
            } else {
                cuerpo.put("cumplimiento", cumplimiento.isBlank() ? "Sin especificacion" : cumplimiento);
            }
        }
        if (error != null) {
            return BridgeResult.error(error);
        }
        error = cerrarComunesEnsayo(cuerpo, data, usuario);
        if (error != null) {
            return BridgeResult.error(error);
        }
        return InnpackRespuestas.reenviar(api.postJson(usuario, BASE + "/lugol", cuerpo), mapper);
    }

    public static String recursoLugolGuardar(ObjectNode p, Object d) {
        return recursoEnsayo("lugol", p, d);
    }

    /** Recurso auditado común a los 12 ensayos de este bloque: "muestraLab:&lt;muestraId&gt;:&lt;tipo&gt;[:&lt;ensayoId&gt;]". */
    private static String recursoEnsayo(String tipo, ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer muestraId = data == null ? null : entero(data.get("muestraId"));
        JsonNode ensayoId = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("ensayoId") : null;
        return "muestraLab:" + (muestraId != null ? muestraId : "?") + ":" + tipo
                + (ensayoId != null && ensayoId.canConvertToInt() ? ":" + ensayoId.asInt() : "");
    }

    /** MuestraId (0 si falta/inválido), metodo y observacion — comunes a los 13 tipos de ensayo. */
    private static String armarComunesEnsayo(ObjectNode cuerpo, JsonNode data) {
        cuerpo.put("muestraId", enteroODefault(data.get("muestraId"), 0));
        String error = ponerTexto(cuerpo, data, "metodo", CONTROL_UNA_LINEA);
        return error != null ? error : ponerTexto(cuerpo, data, "observacion", CONTROL_MULTILINEA);
    }

    /** ensayoOriginalId/motivoReemplazo (patrón de corrección común a los 13 ensayos) + analista de sesión. */
    private static String cerrarComunesEnsayo(ObjectNode cuerpo, JsonNode data, SessionUser usuario) {
        ponerEntero(cuerpo, data, "ensayoOriginalId");
        String error = ponerTexto(cuerpo, data, "motivoReemplazo", CONTROL_UNA_LINEA);
        if (error != null) {
            return error;
        }
        cuerpo.put("analistaUsuarioId", usuario.userId());
        cuerpo.put("analistaNombre", autorDeSesion(usuario));
        return null;
    }

    /** GetBobinas de Photino: array de filas {numeroBobina,lote,posicion,valor1,valor2,valor3,observacion}; si la
     * clave no es un array, viaja null (silencioso, como Photino); filas que no son objeto se ignoran. */
    private String ponerBobinas(ObjectNode cuerpo, JsonNode data, String campo) {
        JsonNode nodo = data.get(campo);
        if (nodo == null || nodo.isNull() || !nodo.isArray()) {
            cuerpo.putNull(campo);
            return null;
        }
        ArrayNode lista = mapper.createArrayNode();
        for (JsonNode item : nodo) {
            if (item == null || !item.isObject()) {
                continue;
            }
            ObjectNode fila = mapper.createObjectNode();
            String error = ponerTexto(fila, item, "numeroBobina", CONTROL_UNA_LINEA);
            error = error != null ? error : ponerTexto(fila, item, "lote", CONTROL_UNA_LINEA);
            if (error != null) {
                return error;
            }
            String posicion = texto(item.get("posicion"));
            if (!posicion.isEmpty() && !POSICIONES_BOBINA.contains(posicion)) {
                return MENSAJE_OPCION_INVALIDA + "posicion.";
            }
            fila.put("posicion", posicion);
            ponerDecimal(fila, item, "valor1");
            ponerDecimal(fila, item, "valor2");
            ponerDecimal(fila, item, "valor3");
            error = ponerTexto(fila, item, "observacion", CONTROL_MULTILINEA);
            if (error != null) {
                return error;
            }
            lista.add(fila);
        }
        cuerpo.set(campo, lista);
        return null;
    }

    /** GetProbeta de Photino (Cobb): {bobina,cara,pesoInicial,pesoFinal,tiempo}; ausente/no objeto → null. */
    private String ponerProbetaCobb(ObjectNode cuerpo, JsonNode data, String campo) {
        JsonNode nodo = data.get(campo);
        if (nodo == null || nodo.isNull() || !nodo.isObject()) {
            cuerpo.putNull(campo);
            return null;
        }
        ObjectNode obj = mapper.createObjectNode();
        String error = ponerTexto(obj, nodo, "bobina", CONTROL_UNA_LINEA);
        if (error != null) {
            return error;
        }
        String cara = texto(nodo.get("cara"));
        if (!cara.isEmpty() && !CARAS_PROBETA_COBB.contains(cara)) {
            return MENSAJE_OPCION_INVALIDA + "cara.";
        }
        obj.put("cara", cara);
        ponerDecimal(obj, nodo, "pesoInicial");
        ponerDecimal(obj, nodo, "pesoFinal");
        error = ponerTexto(obj, nodo, "tiempo", CONTROL_UNA_LINEA);
        if (error != null) {
            return error;
        }
        cuerpo.set(campo, obj);
        return null;
    }

    /** GetResistenciaProbeta de Photino (RCT/FCT): {bobina,force,strength}; ausente/no objeto → null. */
    private String ponerProbetaResistencia(ObjectNode cuerpo, JsonNode data, String campo) {
        JsonNode nodo = data.get(campo);
        if (nodo == null || nodo.isNull() || !nodo.isObject()) {
            cuerpo.putNull(campo);
            return null;
        }
        ObjectNode obj = mapper.createObjectNode();
        String error = ponerTexto(obj, nodo, "bobina", CONTROL_UNA_LINEA);
        if (error != null) {
            return error;
        }
        ponerDecimal(obj, nodo, "force");
        ponerDecimal(obj, nodo, "strength");
        cuerpo.set(campo, obj);
        return null;
    }

    /** GetBctCaja de Photino: {largo,ancho,alto,tipoOnda,gramajeComplejo,espesorComplejo,resultadoLbf}. */
    private String ponerCajaBct(ObjectNode cuerpo, JsonNode data, String campo) {
        JsonNode nodo = data.get(campo);
        if (nodo == null || nodo.isNull() || !nodo.isObject()) {
            cuerpo.putNull(campo);
            return null;
        }
        ObjectNode obj = mapper.createObjectNode();
        ponerDecimal(obj, nodo, "largo");
        ponerDecimal(obj, nodo, "ancho");
        ponerDecimal(obj, nodo, "alto");
        String error = ponerTexto(obj, nodo, "tipoOnda", CONTROL_UNA_LINEA);
        if (error != null) {
            return error;
        }
        ponerDecimal(obj, nodo, "gramajeComplejo");
        ponerDecimal(obj, nodo, "espesorComplejo");
        ponerDecimal(obj, nodo, "resultadoLbf");
        cuerpo.set(campo, obj);
        return null;
    }

    /** GetSolidosDeterminacion de Photino: {m1,m2,m3}. */
    private String ponerDeterminacionSolidos(ObjectNode cuerpo, JsonNode data, String campo) {
        JsonNode nodo = data.get(campo);
        if (nodo == null || nodo.isNull() || !nodo.isObject()) {
            cuerpo.putNull(campo);
            return null;
        }
        ObjectNode obj = mapper.createObjectNode();
        ponerDecimal(obj, nodo, "m1");
        ponerDecimal(obj, nodo, "m2");
        ponerDecimal(obj, nodo, "m3");
        cuerpo.set(campo, obj);
        return null;
    }

    private static int enteroODefault(JsonNode nodo, int porDefecto) {
        Integer v = entero(nodo);
        return v != null ? v : porDefecto;
    }

    private static BigDecimal decimalODefault(JsonNode nodo) {
        BigDecimal v = decimal(nodo);
        return v != null ? v : BigDecimal.ZERO;
    }

    /** GetString de Photino + control de seguridad: string/número → texto; sin \\x00-\\x1F/\\x7F ni "&lt;tag". */
    private static String ponerTexto(ObjectNode cuerpo, JsonNode data, String campo, Pattern control) {
        JsonNode v = data.get(campo);
        if (v != null && !v.isNull() && !v.isString() && !v.isNumber()) {
            return MENSAJE_PARAMETRO_INVALIDO;
        }
        String valor = texto(v);
        if (control.matcher(valor).find()) {
            return MENSAJE_TEXTO_CARACTERES;
        }
        if (MARCADO_HTML.matcher(valor).find()) {
            return MENSAJE_TEXTO_HTML;
        }
        cuerpo.put(campo, valor);
        return null;
    }

    private static void ponerEntero(ObjectNode cuerpo, JsonNode data, String campo) {
        Integer v = entero(data.get(campo));
        if (v != null) {
            cuerpo.put(campo, v);
        } else {
            cuerpo.putNull(campo);
        }
    }

    private static void ponerLargo(ObjectNode cuerpo, JsonNode data, String campo) {
        Long v = largo(data.get(campo));
        if (v != null) {
            cuerpo.put(campo, v);
        } else {
            cuerpo.putNull(campo);
        }
    }

    private static void ponerDecimal(ObjectNode cuerpo, JsonNode data, String campo) {
        BigDecimal v = decimal(data.get(campo));
        if (v != null) {
            cuerpo.put(campo, v);
        } else {
            cuerpo.putNull(campo);
        }
    }

    /** GetLong de Photino: número entero int64, o string convertible; si no → null. */
    private static Long largo(JsonNode nodo) {
        if (nodo == null || nodo.isNull()) {
            return null;
        }
        if (nodo.isNumber()) {
            return nodo.isIntegralNumber() ? nodo.asLong() : null;
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

    /** GetDecimal de Photino: número, o string convertible; si no → null. */
    private static BigDecimal decimal(JsonNode nodo) {
        if (nodo == null || nodo.isNull()) {
            return null;
        }
        if (nodo.isNumber()) {
            try {
                return new BigDecimal(nodo.asString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (nodo.isString()) {
            try {
                return new BigDecimal(nodo.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String nombreCampoSeguro(String clave) {
        return clave != null && clave.matches("[A-Za-z0-9_]{1,40}") ? clave : "?";
    }

    /** Mismo criterio que la sesión C# de Photino (nombre completo; si no, el código). */
    private static String autorDeSesion(SessionUser usuario) {
        String nombre = usuario.nombreCompleto();
        return nombre != null && !nombre.isBlank() ? nombre : usuario.codigoUsuario();
    }

    private BridgeResult get(SessionUser usuario, String path) {
        return InnpackRespuestas.reenviar(api.get(usuario, path), mapper);
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
