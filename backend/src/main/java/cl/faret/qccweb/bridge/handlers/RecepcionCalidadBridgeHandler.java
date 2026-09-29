package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.bridge.LecturasDeSesion;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ArrayNode;
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
 * Escrituras: bobinas.muestrear (Fase 3q), muestra.crear (Fase 3s) y estado.actualizar (Fase 3t). crear, nc.crear y
 * plan.generar NO habilitadas. sap.consultar/sap.lotes (apisapfaret, otra API con API key; solo se usan al crear un
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

    /**
     * recepcion.detalle → GET api/recepcion-calidad/{id}?empresa=.. (id ausente/inválido → 0, como Photino). Además
     * registra en la sesión la huella de la selección de bobinas muestreadas que el usuario queda viendo (detección de
     * cambios concurrentes en bobinas.muestrear).
     */
    public BridgeResult detalle(ObjectNode payload, SessionUser usuario) {
        Integer id = entero(data(payload).get("id"));
        BridgeResult upstream = detalleLote(id != null ? id : 0, usuario);
        if (upstream.ok() && id != null && id > 0) {
            LecturasDeSesion.registrar(recursoLecturaMuestreo(id), huellaMuestreadas(upstream.data()));
            LecturasDeSesion.registrar(recursoLecturaMuestra(id), huellaMuestra(upstream.data()));
            LecturasDeSesion.registrar(recursoLecturaEstado(id), huellaEstado(upstream.data()));
        }
        return upstream;
    }

    static final String MENSAJE_FALTA_LOTE = "Falta indicar el lote";
    static final String MENSAJE_MUESTRA_SIN_LEER = "Abre el detalle del lote antes de crear la muestra de Laboratorio.";
    static final String MENSAJE_MUESTRA_CONFLICTO = "El lote fue modificado por otra persona desde que lo abriste (muestra de "
            + "Laboratorio o estado). Vuelve a abrirlo para ver los cambios.";
    /** Claves de crearMuestra de Photino INNPACK ({action, data:{loteId}}). */
    private static final Set<String> CLAVES_RAIZ_MUESTRA = Set.of("action", "data");
    private static final Set<String> CLAVES_DATA_MUESTRA = Set.of("loteId");
    /** Un candado por lote: serializa en este gateway las creaciones del mismo lote (doble clic, dos sesiones). */
    private final java.util.concurrent.ConcurrentHashMap<Integer, Object> candadosMuestra = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * recepcion.muestra.crear → POST api/recepcion-calidad/{loteId}/muestra-laboratorio {empresa, usuarioId, usuarioNombre}
     * (Fase 3s), igual que Photino para el usuario: la API lee el lote, INSERTA una muestra en muestra_laboratorio
     * (origen ControlRecepcion, analista = usuario) y pone el lote en EnAnalisis, en cualquier estado y SIN control de
     * duplicados (Photino deja crear otra aunque ya exista una: la vista solo lo informa). Responde {muestraLaboratorioId}.
     *
     * Seguridad transparente: empresa, usuarioId y usuarioNombre ← sesión (Photino los toma de su sesión C#); lista blanca
     * {loteId}; el lote se confirma con el detalle de la EMPRESA DE SESIÓN (la API no filtra empresa ni eliminado).
     * Duplicados accidentales: exige haber abierto el detalle en esta sesión, serializa por lote en el gateway y relee el
     * detalle; si la muestra vinculada o el estado cambiaron desde la apertura (otra sesión, doble clic, reintento tras un
     * timeout que sí creó) rechaza en vez de crear otra. Crear otra a sabiendas (tras reabrir el lote) sigue permitido.
     * Ventana residual: creaciones desde Photino u otra instancia entre la relectura y el POST, y una segunda muestra
     * paralela cuando ya existía una en EnAnalisis (el detalle solo muestra TOP 1). Fallo parcial (API sin transacción):
     * muestra creada con el lote sin pasar a EnAnalisis; el reintento lo detecta la huella.
     */
    public BridgeResult muestraCrear(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ_MUESTRA.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_DATA_MUESTRA.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer loteId = entero(data.get("loteId"));
        if (loteId == null || loteId <= 0) {
            return BridgeResult.error(MENSAJE_FALTA_LOTE);
        }
        String leida = LecturasDeSesion.huella(recursoLecturaMuestra(loteId));
        if (leida == null) {
            return BridgeResult.error(MENSAJE_MUESTRA_SIN_LEER);
        }
        synchronized (candadosMuestra.computeIfAbsent(loteId, k -> new Object())) {
            // Una petición en espera (doble clic) ve aquí la huella ya olvidada por la anterior.
            if (!leida.equals(LecturasDeSesion.huella(recursoLecturaMuestra(loteId)))) {
                return BridgeResult.error(MENSAJE_MUESTRA_SIN_LEER);
            }
            BridgeResult vigente = detalleLote(loteId, usuario); // empresa de sesión: un lote ajeno no existe para esta sesión
            if (!vigente.ok()) {
                return vigente;
            }
            if (!huellaMuestra(vigente.data()).equals(leida)) {
                return BridgeResult.error(MENSAJE_MUESTRA_CONFLICTO);
            }
            ObjectNode cuerpo = mapper.createObjectNode();
            cuerpo.put("empresa", usuario.empresa());
            cuerpo.put("usuarioId", usuario.userId());
            cuerpo.put("usuarioNombre", autorDeSesion(usuario));
            BridgeResult resultado = InnpackRespuestas.reenviar(
                    api.postJson(usuario, BASE + "/" + loteId + "/muestra-laboratorio", cuerpo), mapper);
            if (resultado.ok()) {
                // La vista vuelve a abrir el detalle enseguida y registra la huella nueva.
                LecturasDeSesion.olvidar(recursoLecturaMuestra(loteId));
            }
            return resultado;
        }
    }

    /** Recurso auditado: "recepcion:<loteId>:muestra[:<muestraLaboratorioId>]". */
    public static String recursoMuestra(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer loteId = data == null ? null : entero(data.get("loteId"));
        JsonNode id = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("muestraLaboratorioId") : null;
        return "recepcion:" + (loteId != null && loteId > 0 ? loteId : "?") + ":muestra"
                + (id != null && id.canConvertToInt() ? ":" + id.asInt() : "");
    }

    static String recursoLecturaMuestra(int loteId) {
        return "recepcion-muestra:" + loteId;
    }

    /** Lo que cambia muestra.crear en el detalle: muestra vinculada (TOP 1) y estado del lote. */
    static String huellaMuestra(Object detalle) {
        JsonNode d = detalle instanceof JsonNode n && n.isObject() ? n : null;
        JsonNode m = d == null ? null : d.get("muestraLaboratorioId");
        return (m == null || m.isNull() ? "SIN_MUESTRA" : m.asString()) + "|" + (d == null ? "" : texto(d.get("estado")));
    }

    static final String MENSAJE_FALTA_LOTE_ESTADO = "Falta el lote o el estado";
    static final String MENSAJE_ESTADO_INVALIDO = "Estado inválido.";
    static final String MENSAJE_ESTADO_SIN_LEER = "Abre el detalle del lote antes de actualizar el estado.";
    static final String MENSAJE_ESTADO_CONFLICTO = "El estado del lote fue modificado por otra persona desde que lo abriste "
            + "(muestreo, muestra de Laboratorio o actualización de estado). Vuelve a abrirlo para ver los cambios.";
    /** Únicos valores que ofrece el <select id="rcqEstadoManual"> de Photino; la API no valida el valor. */
    private static final Set<String> ESTADOS_MANUALES = Set.of("RecibidaConforme", "RecibidaConObservacion", "NoConforme");
    /** Claves de actualizarEstado de Photino ({action, data:{loteId, estado}}). */
    private static final Set<String> CLAVES_RAIZ_ESTADO = Set.of("action", "data");
    private static final Set<String> CLAVES_DATA_ESTADO = Set.of("loteId", "estado");
    /** Un candado por lote: serializa en este gateway las actualizaciones del mismo lote (doble clic, dos sesiones). */
    private final java.util.concurrent.ConcurrentHashMap<Integer, Object> candadosEstado = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * recepcion.estado.actualizar → PATCH api/recepcion-calidad/{loteId}/estado {estado} (Fase 3t), igual que Photino
     * para el usuario: un UPDATE directo de `recepcion_lotes_control.estado`, sin autor ni control de duplicados ni
     * validación del valor en la API.
     *
     * Seguridad transparente: el lote se confirma con el detalle de la EMPRESA DE SESIÓN (la API no filtra empresa ni
     * eliminado — hallazgo R5); `estado` restringido a los 3 valores que ofrece el <select> de Photino (lo único
     * alcanzable por uso normal; un valor distinto solo vendría de un payload manipulado). Concurrencia: exige haber
     * abierto el detalle en esta sesión y lo relee antes del PATCH; si el estado cambió desde entonces (otra sesión,
     * `bobinas.muestrear` o `muestra.crear` también lo mutan) rechaza en vez de pisarlo. Candado por lote para doble
     * clic. Ventana residual: entre la relectura y el PATCH (milisegundos).
     */
    public BridgeResult estadoActualizar(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ_ESTADO.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_DATA_ESTADO.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer loteId = entero(data.get("loteId"));
        String estado = texto(data.get("estado")).trim();
        if (loteId == null || loteId <= 0 || estado.isEmpty()) {
            return BridgeResult.error(MENSAJE_FALTA_LOTE_ESTADO);
        }
        if (!ESTADOS_MANUALES.contains(estado)) {
            return BridgeResult.error(MENSAJE_ESTADO_INVALIDO);
        }
        String leida = LecturasDeSesion.huella(recursoLecturaEstado(loteId));
        if (leida == null) {
            return BridgeResult.error(MENSAJE_ESTADO_SIN_LEER);
        }
        synchronized (candadosEstado.computeIfAbsent(loteId, k -> new Object())) {
            // Una petición en espera (doble clic) ve aquí la huella ya olvidada por la anterior.
            if (!leida.equals(LecturasDeSesion.huella(recursoLecturaEstado(loteId)))) {
                return BridgeResult.error(MENSAJE_ESTADO_SIN_LEER);
            }
            BridgeResult vigente = detalleLote(loteId, usuario); // empresa de sesión: un lote ajeno no existe para esta sesión
            if (!vigente.ok()) {
                return vigente;
            }
            if (!huellaEstado(vigente.data()).equals(leida)) {
                return BridgeResult.error(MENSAJE_ESTADO_CONFLICTO);
            }
            ObjectNode cuerpo = mapper.createObjectNode();
            cuerpo.put("estado", estado);
            BridgeResult resultado = InnpackRespuestas.reenviar(
                    api.patchJson(usuario, BASE + "/" + loteId + "/estado", cuerpo), mapper);
            if (resultado.ok()) {
                // La vista vuelve a abrir el detalle enseguida y registra la huella nueva.
                LecturasDeSesion.olvidar(recursoLecturaEstado(loteId));
            }
            return resultado;
        }
    }

    /** Recurso auditado: "recepcion:<loteId>:estado:<valor>". */
    public static String recursoEstado(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer loteId = data == null ? null : entero(data.get("loteId"));
        String estado = data == null ? "" : texto(data.get("estado"));
        return "recepcion:" + (loteId != null && loteId > 0 ? loteId : "?") + ":estado" + (estado.isEmpty() ? "" : ":" + estado);
    }

    static String recursoLecturaEstado(int loteId) {
        return "recepcion-estado:" + loteId;
    }

    /** Lo único que cambia estado.actualizar en el detalle: el estado del lote. */
    static String huellaEstado(Object detalle) {
        JsonNode d = detalle instanceof JsonNode n && n.isObject() ? n : null;
        return d == null ? "" : texto(d.get("estado"));
    }

    static final String MENSAJE_FALTA_LOTE_BOBINAS = "Falta el lote o la lista de bobinas muestreadas";
    static final String MENSAJE_MUESTREO_SIN_LEER = "Abre el detalle del lote antes de guardar las bobinas muestreadas.";
    static final String MENSAJE_MUESTREO_CONFLICTO = "La selección de bobinas muestreadas fue modificada por otra persona desde que "
            + "abriste el lote. Vuelve a abrirlo para ver los cambios.";
    static final String MENSAJE_CAMPO_NO_PERMITIDO = "Campo no permitido: ";
    static final String MENSAJE_PARAMETRO_INVALIDO = "Parámetro inválido.";
    static final String MENSAJE_TEXTO_CARACTERES = "El texto contiene caracteres no permitidos.";
    static final String MENSAJE_TEXTO_HTML = "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").";
    static final String MARCA_BOBINAS = "__qccAuditoriaBobinas";
    /** Claves de _guardarMuestreadas de Photino ({action, data:{loteId, bobinas}}) + "usuario" de IdentityOverride. */
    private static final Set<String> CLAVES_RAIZ_MUESTREAR = Set.of("action", "data", "usuario");
    private static final Set<String> CLAVES_DATA_MUESTREAR = Set.of("loteId", "bobinas", "usuario");
    private static final Set<String> CLAVES_BOBINA = Set.of("numeroBobina", "seleccionTipo", "criterioManual");
    /** Valores que produce la vista (botón Aleatoria / selección manual); vacío → "Manual" como C# y la API. */
    private static final List<String> TIPOS_SELECCION = List.of("Manual", "Aleatoria");
    private static final Pattern CONTROL_UNA_LINEA = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final Pattern MARCADO_HTML = Pattern.compile("<[A-Za-z/!?]");

    /**
     * recepcion.bobinas.muestrear → POST api/recepcion-calidad/{loteId}/bobinas-muestreadas {bobinas[{numeroBobina,
     * seleccionTipo, criterioManual}], usuario}. PRIMERA ESCRITURA de Recepción (Fase 3q), igual que Photino para el
     * usuario: la API REEMPLAZA la selección del lote (DELETE + INSERT, sin transacción), valida que cada bobina
     * pertenezca al lote y pasa el estado PendienteMuestreo → PendienteLaboratorio (desde otros estados no lo cambia);
     * se puede volver a guardar en cualquier estado y re-seleccionar bobinas ya muestreadas (reemplazo), como en Photino.
     *
     * Seguridad transparente: `usuario` ← sesión (Photino lo toma de su sesión C#); lista blanca de claves y tipos;
     * número de bobina ≤ 100, tipo ≤ 20, criterio ≤ 255 una línea (columnas reales) sin controles ni HTML; bobinas
     * repetidas rechazadas (sin UNIQUE en la tabla: solo un payload manipulado las mandaría); el lote se confirma con el
     * detalle de la EMPRESA DE SESIÓN (ids ajenos → error) y la API valida la pertenencia de cada bobina. Concurrencia:
     * exige haber abierto el detalle en esta sesión y lo relee antes del POST; si la selección cambió desde entonces
     * (otra sesión la guardó) rechaza en vez de pisarla. Ventana residual: entre la relectura y el POST (milisegundos)
     * y el DELETE+INSERT no transaccional de la API (hardening futuro en la API).
     */
    public BridgeResult bobinasMuestrear(ObjectNode payload, SessionUser usuario) {
        for (String clave : payload.propertyNames()) {
            if (!CLAVES_RAIZ_MUESTREAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        JsonNode data = data(payload);
        for (String clave : data.propertyNames()) {
            if (!CLAVES_DATA_MUESTREAR.contains(clave)) {
                return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
            }
        }
        Integer loteId = entero(data.get("loteId"));
        JsonNode bobinas = data.get("bobinas");
        if (bobinas != null && !bobinas.isNull() && !bobinas.isArray()) {
            return BridgeResult.error(MENSAJE_PARAMETRO_INVALIDO);
        }
        if (loteId == null || loteId <= 0 || bobinas == null || bobinas.isNull() || bobinas.isEmpty()) {
            return BridgeResult.error(MENSAJE_FALTA_LOTE_BOBINAS);
        }
        ArrayNode lista = mapper.createArrayNode();
        Set<String> vistas = new HashSet<>();
        for (JsonNode b : bobinas) {
            if (!b.isObject()) {
                return BridgeResult.error(MENSAJE_PARAMETRO_INVALIDO);
            }
            for (String clave : b.propertyNames()) {
                if (!CLAVES_BOBINA.contains(clave)) {
                    return BridgeResult.error(MENSAJE_CAMPO_NO_PERMITIDO + nombreCampoSeguro(clave));
                }
            }
            for (String campo : new String[] {"numeroBobina", "seleccionTipo", "criterioManual"}) {
                JsonNode v = b.get(campo);
                if (v != null && !v.isNull() && !(v.isString() || v.isNumber() && campo.equals("numeroBobina"))) {
                    return BridgeResult.error(MENSAJE_PARAMETRO_INVALIDO);
                }
            }
            String numero = texto(b.get("numeroBobina"));
            String error = validarUnaLinea(numero, 100, "El número de bobina supera el máximo de 100 caracteres.");
            if (error != null) {
                return BridgeResult.error(error);
            }
            if (!numero.isEmpty() && !vistas.add(numero)) {
                return BridgeResult.error("La bobina " + numero + " está repetida en la selección.");
            }
            String tipo = texto(b.get("seleccionTipo")).trim();
            if (tipo.isEmpty()) {
                tipo = "Manual";
            }
            if (!TIPOS_SELECCION.contains(tipo)) {
                return BridgeResult.error("Tipo de selección inválido (Manual o Aleatoria).");
            }
            JsonNode c = b.get("criterioManual");
            String criterio = c == null || c.isNull() ? null : c.asString().strip();
            if (criterio != null) {
                error = validarUnaLinea(criterio, 255, "El motivo supera el máximo de 255 caracteres.");
                if (error != null) {
                    return BridgeResult.error(error);
                }
            }
            ObjectNode item = lista.addObject();
            item.put("numeroBobina", numero);
            item.put("seleccionTipo", tipo);
            if (criterio == null || criterio.isEmpty()) {
                item.putNull("criterioManual");
            } else {
                item.put("criterioManual", criterio);
            }
        }
        String leida = LecturasDeSesion.huella(recursoLecturaMuestreo(loteId));
        if (leida == null) {
            return BridgeResult.error(MENSAJE_MUESTREO_SIN_LEER);
        }
        BridgeResult vigente = detalleLote(loteId, usuario); // empresa de sesión: un lote ajeno no existe para esta sesión
        if (!vigente.ok()) {
            return vigente;
        }
        if (!huellaMuestreadas(vigente.data()).equals(leida)) {
            return BridgeResult.error(MENSAJE_MUESTREO_CONFLICTO);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.set("bobinas", lista);
        cuerpo.put("usuario", autorDeSesion(usuario));
        // Canal interno para la auditoría (cantidad de bobinas; el payload saneado no vuelve al navegador ni a la API).
        payload.put(MARCA_BOBINAS, lista.size());
        BridgeResult resultado = InnpackRespuestas.reenviar(
                api.postJson(usuario, BASE + "/" + loteId + "/bobinas-muestreadas", cuerpo), mapper);
        if (resultado.ok()) {
            // La vista vuelve a abrir el detalle enseguida y registra la huella de la nueva selección.
            LecturasDeSesion.olvidar(recursoLecturaMuestreo(loteId));
        }
        return resultado;
    }

    /** Recurso auditado: "recepcion:<loteId>:muestreadas:<n bobinas>" (sin números de bobina ni motivo). */
    public static String recursoMuestreo(ObjectNode payload, Object dataRespuesta) {
        JsonNode data = payload.get("data");
        Integer loteId = data == null ? null : entero(data.get("loteId"));
        JsonNode n = payload.get(MARCA_BOBINAS);
        return "recepcion:" + (loteId != null && loteId > 0 ? loteId : "?") + ":muestreadas"
                + (n != null && n.canConvertToInt() ? ":" + n.asInt() : "");
    }

    static String recursoLecturaMuestreo(int loteId) {
        return "recepcion-muestreo:" + loteId;
    }

    /** SHA-256 de la selección vigente (muestreadas del detalle): cambia si otra sesión la reemplaza. */
    static String huellaMuestreadas(Object detalle) {
        JsonNode m = detalle instanceof JsonNode d && d.isObject() ? d.get("muestreadas") : null;
        String base = m == null || m.isNull() ? "SIN_SELECCION" : m.toString();
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(base.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** ≤ max caracteres, una sola línea, sin controles, sustitutos sueltos ni marcado HTML; null si es válido. */
    private static String validarUnaLinea(String valor, int max, String largo) {
        if (valor.length() > max) {
            return largo;
        }
        if (CONTROL_UNA_LINEA.matcher(valor).find() || tieneSustitutoSuelto(valor)) {
            return MENSAJE_TEXTO_CARACTERES;
        }
        if (MARCADO_HTML.matcher(valor).find()) {
            return MENSAJE_TEXTO_HTML;
        }
        return null;
    }

    private static boolean tieneSustitutoSuelto(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isHighSurrogate(ch) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }

    private static String nombreCampoSeguro(String clave) {
        return clave != null && clave.matches("[A-Za-z0-9_]{1,40}") ? clave : "?";
    }

    /** Mismo criterio que la sesión C# de Photino (nombre completo; si no, el código). */
    private static String autorDeSesion(SessionUser usuario) {
        String nombre = usuario.nombreCompleto();
        return nombre != null && !nombre.isBlank() ? nombre : usuario.codigoUsuario();
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
