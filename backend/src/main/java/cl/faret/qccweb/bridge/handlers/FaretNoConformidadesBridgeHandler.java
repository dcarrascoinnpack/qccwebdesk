package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.bridge.LecturasDeSesion;
import cl.faret.qccweb.upstream.FaretApiClient;
import cl.faret.qccweb.upstream.FaretRespuestas;
import cl.faret.qccweb.upstream.InnpackApiClient;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fase F3 - ESCRITURAS ADITIVAS de No Conformidades FARET contra MejoraContinua (sin Authorization: la API no tiene auth y un 401 es
 * un error normal; el gateway es el único control de acceso). Port de Photino 1.8.15 (9e1b556) FaretHandler.cs:
 * HandleNcSeguimientoCrear / HandleNcAnalisisGuardar / HandleNcAccionesCrear / HandleNcAdjuntosSubir (+ TryBuild*Request) y
 * FaretNoConformidadesApiService. Payload PLANO.
 *
 * IDENTIDAD: el frontend de Photino manda el autor desde sessionStorage (autor, creadoPor, actualizadoPor, subidoPor). Esas claves se
 * aceptan (son las que manda la vista) pero su valor NUNCA se usa: ActionPolicy las pisa con la sesión y el cuerpo se arma aquí con
 * el nombre de la sesión. Cualquier otra clave se rechaza sin llamar a la API. Sin reintentos (F0).
 *
 * analisis.guardar: Photino decide POST/PUT con el flag `existeAnalisis` del navegador. Aquí el flag se IGNORA: el gateway lee el
 * análisis vigente (GET) y decide POST (no existe) o PUT (existe). La API hace UPDATE en sitio sin versión ni historial, así que
 * además se exige que ESTA sesión haya leído el análisis (faret.nc.analisis.get, que registra la huella) y que siga igual al
 * releerlo: si otra persona lo cambió, no se escribe nada (misma defensa que noConformidades.analisis.guardar de INNPACK).
 */
public class FaretNoConformidadesBridgeHandler {

    static final String MENSAJE_NC_NO_ENCONTRADA = "No conformidad no encontrada";
    static final String MENSAJE_ANALISIS_AJENO = "El análisis indicado no corresponde a la no conformidad.";
    static final String MENSAJE_AUTOR_LARGO = "El nombre del usuario de la sesión supera el máximo permitido.";
    static final String MARCA_OPERACION = "__qccAuditoriaOperacion";
    static final String SIN_ANALISIS = "SIN_ANALISIS";

    private static final String RUTA = "/api/no-conformidades";
    /** Columnas de MejoraContinua (migration/sqlserver/mejora_continua_sqlserver_schema.sql). */
    private static final int MAX_DESCRIPCION_ACCION = 1000;
    private static final int MAX_RESPONSABLE = 200;
    private static final int MAX_TEXTO_ANALISIS = 500;
    private static final int MAX_AUTOR_SEGUIMIENTO = 120;
    private static final int MAX_AUTOR_ANALISIS_ACCION = 200;
    private static final int MAX_AUTOR_ADJUNTO = 150;

    private static final Set<String> CLAVES_SEGUIMIENTO = Set.of("action", "id", "comentario", "autor");
    private static final Set<String> CLAVES_ACCION = Set.of("action", "id", "analisisId", "creadoPor", "descripcion", "responsable",
            "fechaLimite", "prioridad");
    private static final Set<String> CLAVES_ANALISIS = Set.of("action", "id", "existeAnalisis", "creadoPor", "actualizadoPor",
            "metodologia", "problemaDetectado", "porque1", "porque2", "porque3", "porque4", "porque5", "causaRaiz", "conclusion");
    private static final List<String> TEXTOS_ANALISIS = List.of("problemaDetectado", "porque1", "porque2", "porque3", "porque4",
            "porque5", "causaRaiz", "conclusion");
    /** textareas de la vista; el resto de los campos son inputs de una línea. */
    private static final Set<String> MULTILINEA = Set.of("problemaDetectado", "causaRaiz", "conclusion");
    /** NVARCHAR(MAX): tope anti-abuso, igual que los demás textos largos del gateway. */
    private static final int MAX_BYTES_TEXTO_LARGO = 65_535;
    private static final Set<String> METODOLOGIAS = Set.of("CINCO_PORQUES", "ISHIKAWA", "MIXTA");
    private static final Set<String> PRIORIDADES = Set.of("ALTA", "MEDIA", "BAJA");
    private static final List<String> CAMPOS_HUELLA = List.of("id", "metodologia", "problemaDetectado", "porque1", "porque2",
            "porque3", "porque4", "porque5", "causaRaiz", "conclusion", "creadoPor", "fechaCreacion", "actualizadoPor",
            "fechaActualizacion");

    private static final Pattern CONTROL_UNA_LINEA = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final Pattern CONTROL_MULTILINEA = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern MARCADO_HTML = Pattern.compile("<[A-Za-z/!?]");
    private static final Pattern FECHA_ISO = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    private final FaretApiClient mejoraContinua;
    private final ObjectMapper mapper;

    public FaretNoConformidadesBridgeHandler(FaretApiClient mejoraContinua, ObjectMapper mapper) {
        this.mejoraContinua = mejoraContinua;
        this.mapper = mapper;
    }

    /** Error de validación del payload: el mensaje va tal cual al navegador. Sin stack trace. */
    static final class Invalido extends RuntimeException {
        Invalido(String mensaje) {
            super(mensaje, null, false, false);
        }
    }

    // ------------------------------------------------------------------------------------------- seguimiento.crear

    /**
     * faret.nc.seguimiento.crear -> POST api/no-conformidades/{id}/seguimiento {comentario, autor}. autor = nombre de la SESIÓN.
     * comentario obligatorio, sin controles (salvo \t\n\r) ni marcado HTML (la vista lo pinta con innerHTML) y &lt;= 65.535 bytes.
     * La NC debe existir (GET previo): la API no lo valida y la FK devolvería un 500.
     */
    public BridgeResult seguimientoCrear(ObjectNode payload, SessionUser usuario) {
        if (!mejoraContinua.configurada()) {
            return BridgeResult.error(FaretBridgeHandler.MENSAJE_MC_NO_CONFIGURADA);
        }
        int id;
        String comentario;
        String autor;
        try {
            cerrada(payload, CLAVES_SEGUIMIENTO);
            id = idNc(payload);
            comentario = comentario(payload.get("comentario"));
            autor = autorDeSesion(usuario, MAX_AUTOR_SEGUIMIENTO);
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        BridgeResult nc = existeNc(id, usuario);
        if (!nc.ok()) {
            return nc;
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("comentario", comentario);
        cuerpo.put("autor", autor);
        return proyectar(FaretRespuestas.crudoMc(mejoraContinua.postJson(usuario, RUTA + "/" + id + "/seguimiento", cuerpo), mapper), "id");
    }

    // ------------------------------------------------------------------------------------------- acciones.crear

    /**
     * faret.nc.acciones.crear -> POST api/no-conformidades/{id}/acciones {analisisId, descripcion, responsable, fechaLimite,
     * prioridad, creadoPor}. creadoPor = SESIÓN. descripcion/responsable/fechaLimite obligatorios (mensajes de Photino y de la API);
     * prioridad ALTA/MEDIA/BAJA o vacía. `analisisId` (opcional) debe ser el análisis de ESA NC (la API solo comprueba la FK). La API
     * valida que la NC exista (404). Una NC cerrada admite acciones, igual que en Photino.
     */
    public BridgeResult accionesCrear(ObjectNode payload, SessionUser usuario) {
        if (!mejoraContinua.configurada()) {
            return BridgeResult.error(FaretBridgeHandler.MENSAJE_MC_NO_CONFIGURADA);
        }
        int id;
        Integer analisisId = null;
        String descripcion;
        String responsable;
        String fechaLimite;
        String prioridad;
        String creadoPor;
        try {
            cerrada(payload, CLAVES_ACCION);
            id = idNc(payload);
            JsonNode analisisNodo = payload.get("analisisId");
            if (analisisNodo != null && !analisisNodo.isNull()) {
                if (!analisisNodo.isIntegralNumber() || !analisisNodo.canConvertToInt() || analisisNodo.asInt() <= 0) {
                    return BridgeResult.error(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
                }
                analisisId = analisisNodo.asInt();
            }
            descripcion = textoObligatorio(payload, "descripcion", MAX_DESCRIPCION_ACCION, "Falta la descripción de la acción", false);
            responsable = textoObligatorio(payload, "responsable", MAX_RESPONSABLE, "Falta el responsable", false);
            fechaLimite = textoObligatorio(payload, "fechaLimite", 64, "Falta la fecha límite", false);
            if (!FECHA_ISO.matcher(fechaLimite).matches()) {
                throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_FECHA_INVALIDA);
            }
            try {
                LocalDate.parse(fechaLimite);
            } catch (DateTimeParseException e) {
                throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_FECHA_INVALIDA);
            }
            prioridad = texto(payload, "prioridad", 30, false);
            if (prioridad != null && !prioridad.isEmpty() && !PRIORIDADES.contains(prioridad)) {
                throw new Invalido("Prioridad inválida (ALTA, MEDIA o BAJA).");
            }
            creadoPor = autorDeSesion(usuario, MAX_AUTOR_ANALISIS_ACCION);
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        if (analisisId != null) {
            BridgeResult analisis = FaretRespuestas.crudoMcAnalisis(mejoraContinua.get(usuario, RUTA + "/" + id + "/analisis"), mapper);
            if (!analisis.ok()) {
                return analisis;
            }
            JsonNode aId = analisis.data() instanceof JsonNode a && a.isObject() ? a.get("id") : null;
            if (aId == null || !aId.canConvertToInt() || aId.asInt() != analisisId) {
                return BridgeResult.error(MENSAJE_ANALISIS_AJENO);
            }
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        if (analisisId != null) {
            cuerpo.put("analisisId", analisisId.intValue());
        } else {
            cuerpo.putNull("analisisId");
        }
        cuerpo.put("descripcion", descripcion);
        cuerpo.put("responsable", responsable);
        cuerpo.put("fechaLimite", fechaLimite);
        if (prioridad == null || prioridad.isEmpty()) {
            cuerpo.putNull("prioridad");
        } else {
            cuerpo.put("prioridad", prioridad);
        }
        cuerpo.put("creadoPor", creadoPor);
        return proyectar(FaretRespuestas.crudoMc(mejoraContinua.postJson(usuario, RUTA + "/" + id + "/acciones", cuerpo), mapper), "id");
    }

    // ------------------------------------------------------------------------------------------- analisis.guardar

    /**
     * faret.nc.analisis.guardar -> POST (crear) o PUT (actualizar) api/no-conformidades/{id}/analisis; lo decide el GATEWAY con un GET
     * previo (`existeAnalisis` del navegador se valida como booleano y se ignora). Cuerpo en el orden de Photino: metodologia,
     * problemaDetectado, porque1..5, causaRaiz, conclusion, creadoPor, actualizadoPor (POST: creadoPor = sesión y actualizadoPor
     * null; PUT: al revés, que es lo que la API guarda). La API exige causaRaiz en el PUT (400 con su mensaje) y responde 409 si el POST
     * llega con un análisis ya creado (no ocurre: el GET previo y la huella lo impiden salvo una carrera mínima).
     *
     * Lost update: sin versión en la API; se compara la huella que leyó la sesión (faret.nc.analisis.get) con la del análisis vigente.
     * Límites: ventana mínima entre la relectura y el PUT, y varias pestañas de la MISMA sesión comparten la huella.
     */
    public BridgeResult analisisGuardar(ObjectNode payload, SessionUser usuario) {
        if (!mejoraContinua.configurada()) {
            return BridgeResult.error(FaretBridgeHandler.MENSAJE_MC_NO_CONFIGURADA);
        }
        int id;
        ObjectNode cuerpo = mapper.createObjectNode();
        String autor;
        try {
            cerrada(payload, CLAVES_ANALISIS);
            id = idNc(payload);
            JsonNode existe = payload.get("existeAnalisis");
            if (existe != null && !existe.isNull() && !existe.isBoolean()) {
                throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
            }
            String metodologia = texto(payload, "metodologia", 30, false);
            if (metodologia == null || metodologia.isEmpty()) {
                throw new Invalido("Falta la metodología");
            }
            if (!METODOLOGIAS.contains(metodologia)) {
                throw new Invalido("Metodología inválida. Valores permitidos: CINCO_PORQUES, ISHIKAWA, MIXTA");
            }
            cuerpo.put("metodologia", metodologia);
            for (String campo : TEXTOS_ANALISIS) {
                boolean multilinea = MULTILINEA.contains(campo);
                String valor = campo.equals("problemaDetectado")
                        ? textoObligatorio(payload, campo, MAX_TEXTO_ANALISIS, "Falta el problema detectado", multilinea)
                        : texto(payload, campo, campo.equals("conclusion") ? -1 : MAX_TEXTO_ANALISIS, multilinea);
                if (valor == null) {
                    cuerpo.putNull(campo);
                } else {
                    cuerpo.put(campo, valor);
                }
            }
            autor = autorDeSesion(usuario, MAX_AUTOR_ANALISIS_ACCION);
        } catch (Invalido e) {
            return BridgeResult.error(e.getMessage());
        }
        BridgeResult vigente = FaretRespuestas.crudoMcAnalisis(mejoraContinua.get(usuario, RUTA + "/" + id + "/analisis"), mapper);
        if (!vigente.ok()) {
            return vigente;
        }
        boolean existe = vigente.data() instanceof JsonNode d && d.isObject();
        String leida = LecturasDeSesion.huella(recursoLectura(id));
        if (leida == null) {
            return BridgeResult.error(NoConformidadesBridgeHandler.MENSAJE_ANALISIS_SIN_LEER);
        }
        if (!huella(vigente.data()).equals(leida)) {
            return BridgeResult.error(NoConformidadesBridgeHandler.MENSAJE_ANALISIS_CONFLICTO);
        }
        if (existe) {
            cuerpo.putNull("creadoPor");
            cuerpo.put("actualizadoPor", autor);
        } else {
            cuerpo.put("creadoPor", autor);
            cuerpo.putNull("actualizadoPor");
        }
        // Canal interno para la auditoría (el payload saneado no vuelve al navegador ni a la API).
        payload.put(MARCA_OPERACION, existe ? "REEMPLAZO" : "NUEVO");
        String ruta = RUTA + "/" + id + "/analisis";
        InnpackApiClient.Respuesta r = existe ? mejoraContinua.putJson(usuario, ruta, cuerpo) : mejoraContinua.postJson(usuario, ruta, cuerpo);
        BridgeResult resultado = proyectar(FaretRespuestas.crudoMc(r, mapper), "id", "mensaje");
        if (resultado.ok()) {
            // La vista relee el análisis enseguida (y vuelve a registrar la huella); sin relectura no se puede guardar encima de lo escrito.
            LecturasDeSesion.olvidar(recursoLectura(id));
        }
        return resultado;
    }

    /** Registra lo que la sesión leyó del análisis de la NC (lo llama faret.nc.analisis.get; data null = sin análisis). */
    static void registrarLectura(int ncId, Object data) {
        LecturasDeSesion.registrar(recursoLectura(ncId), huella(data));
    }

    static String recursoLectura(int ncId) {
        return "faret-nc-analisis:" + ncId;
    }

    /** SHA-256 de los campos del análisis vigente (o de "sin análisis"): detecta cualquier cambio de contenido. */
    static String huella(Object data) {
        if (!(data instanceof JsonNode d) || !d.isObject()) {
            return SIN_ANALISIS;
        }
        StringBuilder sb = new StringBuilder();
        for (String campo : CAMPOS_HUELLA) {
            JsonNode v = d.get(campo);
            sb.append(campo).append('=').append(v == null || v.isNull() ? "\u0000" : v.toString()).append('\n');
        }
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------- adjuntos.subir

    /**
     * faret.nc.adjuntos.subir -> POST api/no-conformidades/{id}/adjuntos {tipo, nombreArchivo, tipoMime, contenidoBase64, subidoPor}.
     * Llega SOLO por /api/v1/bridge/archivo. Validación común con noConformidades.adjuntos.subir (misma API: tipo CAUSA_RAIZ_PDF o
     * EVIDENCIA_FOTO, MIME coherente, base64 estricto, tamaño decodificado PDF 10 MB / foto 5 MB, FIRMA REAL PDF/JPEG/PNG, nombre
     * saneado); `subidoPor` = SESIÓN. La API aplica el resto: 404 NC inexistente, 409 NC cerrada o 10 fotos, reemplazo del PDF.
     */
    public BridgeResult adjuntosSubir(ObjectNode payload, SessionUser usuario) {
        if (!mejoraContinua.configurada()) {
            return BridgeResult.error(FaretBridgeHandler.MENSAJE_MC_NO_CONFIGURADA);
        }
        NoConformidadesBridgeHandler.AdjuntoSubir adjunto = NoConformidadesBridgeHandler.adjuntoSubir(payload, usuario, mapper);
        if (adjunto.error() != null) {
            return BridgeResult.error(adjunto.error());
        }
        if (adjunto.cuerpo().get("subidoPor").asString().length() > MAX_AUTOR_ADJUNTO) {
            return BridgeResult.error(MENSAJE_AUTOR_LARGO);
        }
        return proyectar(FaretRespuestas.crudoMc(mejoraContinua.postJson(usuario, RUTA + "/" + adjunto.id() + "/adjuntos", adjunto.cuerpo()), mapper), "id");
    }

    // ------------------------------------------------------------------------------------------- auditoría

    /** "faret:nc:<id>:seguimiento[:<seguimientoId>]". */
    public static String recursoSeguimiento(ObjectNode payload, Object dataRespuesta) {
        return recursoNc(payload) + ":seguimiento" + idCreado(dataRespuesta, ":");
    }

    /** "faret:nc:<id>:accion[:<accionId>]". */
    public static String recursoAccion(ObjectNode payload, Object dataRespuesta) {
        return recursoNc(payload) + ":accion" + idCreado(dataRespuesta, ":");
    }

    /** "faret:nc:<id>:adjunto[:<adjuntoId>]". */
    public static String recursoAdjunto(ObjectNode payload, Object dataRespuesta) {
        return recursoNc(payload) + ":adjunto" + idCreado(dataRespuesta, ":");
    }

    /** "faret:nc:<id>:analisis[:<analisisId>]:NUEVO|REEMPLAZO" (sin contenido del análisis). */
    public static String recursoAnalisis(ObjectNode payload, Object dataRespuesta) {
        StringBuilder r = new StringBuilder(recursoNc(payload)).append(":analisis").append(idCreado(dataRespuesta, ":"));
        JsonNode marca = payload.get(MARCA_OPERACION);
        if (marca != null && marca.isString()) {
            r.append(':').append(marca.asString());
        }
        return r.toString();
    }

    static String recursoNc(ObjectNode payload) {
        try {
            Integer id = enteroPositivo(payload.get("id"));
            return "faret:nc:" + (id == null ? "?" : id);
        } catch (Invalido e) {
            return "faret:nc:?";
        }
    }

    static String idCreado(Object dataRespuesta, String prefijo) {
        JsonNode id = dataRespuesta instanceof JsonNode d && d.isObject() ? d.get("id") : null;
        return id != null && id.isIntegralNumber() && id.canConvertToLong() ? prefijo + id.asLong() : "";
    }

    // ------------------------------------------------------------------------------------------- respuestas

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

    /** La API no valida la existencia de la NC al crear seguimiento: se comprueba antes (404 -> mensaje de la API de análisis/acciones). */
    private BridgeResult existeNc(int id, SessionUser usuario) {
        InnpackApiClient.Respuesta r = mejoraContinua.get(usuario, RUTA + "/" + id);
        if (r.status() == 404) {
            return BridgeResult.error(MENSAJE_NC_NO_ENCONTRADA);
        }
        BridgeResult crudo = FaretRespuestas.crudoMc(r, mapper);
        return crudo.ok() ? BridgeResult.ok(null) : crudo;
    }

    // ------------------------------------------------------------------------------------------- validación

    static void cerrada(ObjectNode payload, Set<String> permitidas) {
        String campo = NoConformidadesBridgeHandler.campoNoPermitido(payload, permitidas);
        if (campo != null) {
            throw new Invalido(campo);
        }
    }

    static int idNc(ObjectNode payload) {
        Integer id = enteroPositivo(payload.get("id"));
        if (id == null) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_ID_NC);
        }
        return id;
    }

    /** Entero int32 positivo: número entero o texto de dígitos; ausente, null, vacío o <= 0 -> null. bool/objeto/array/decimal se rechazan. */
    static Integer enteroPositivo(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        long valor;
        if (n.isIntegralNumber() && n.canConvertToLong()) {
            valor = n.asLong();
        } else if (n.isString()) {
            String t = n.asString().strip();
            if (t.isEmpty()) {
                return null;
            }
            try {
                valor = Long.parseLong(t);
            } catch (NumberFormatException e) {
                throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
            }
        } else {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
        }
        if (valor > Integer.MAX_VALUE) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
        }
        return valor > 0 ? (int) valor : null;
    }

    /** Mismo criterio que _usuarioActual() de Photino (nombre, si no el código), pero desde la SESIÓN y acotado al largo de la columna. */
    static String autorDeSesion(SessionUser usuario, int max) {
        String autor = NoConformidadesBridgeHandler.autorDeSesion(usuario);
        if (autor == null || autor.isBlank()) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
        }
        if (autor.length() > max) {
            throw new Invalido(MENSAJE_AUTOR_LARGO);
        }
        return autor;
    }

    private static String comentario(JsonNode n) {
        if (n != null && !n.isNull() && !n.isString()) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
        }
        String c = n == null || n.isNull() ? "" : n.asString().strip();
        if (c.isEmpty()) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_FALTA_COMENTARIO);
        }
        if (c.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES_TEXTO_LARGO) {
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

    static String textoObligatorio(ObjectNode p, String clave, int max, String mensajeFalta, boolean multilinea) {
        String t = texto(p, clave, max, multilinea);
        if (t == null || t.isEmpty()) {
            throw new Invalido(mensajeFalta);
        }
        return t;
    }

    /**
     * Texto opcional: ausente/null -> null; string (no número, bool, objeto ni array) con trim, sin controles ni sustitutos UTF-16
     * sueltos, sin marcado HTML y de largo <= max en unidades UTF-16 (como NVARCHAR); max < 0 = campo largo (tope en bytes UTF-8).
     */
    static String texto(ObjectNode p, String clave, int max, boolean multilinea) {
        JsonNode n = p.get(clave);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isString()) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
        }
        String crudo = n.asString();
        if ((multilinea ? CONTROL_MULTILINEA : CONTROL_UNA_LINEA).matcher(crudo).find() || tieneSustitutoSuelto(crudo)) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_TEXTO_CARACTERES);
        }
        String t = crudo.strip();
        if (MARCADO_HTML.matcher(t).find()) {
            throw new Invalido(NoConformidadesBridgeHandler.MENSAJE_TEXTO_HTML);
        }
        if (max < 0) {
            if (t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES_TEXTO_LARGO) {
                throw new Invalido("El campo " + clave + " supera el máximo permitido (65.535 bytes).");
            }
        } else if (t.length() > max) {
            throw new Invalido("El campo " + clave + " supera el máximo de " + max + " caracteres.");
        }
        return t;
    }

    static boolean tieneSustitutoSuelto(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return true;
            }
        }
        return false;
    }
}
