package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackRespuestas;
import cl.faret.qccweb.upstream.SapApiClient;
import cl.faret.qccweb.upstream.UriEscape;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Módulo "Despachos Diarios" (INNPACK) — SOLO LECTURA. Port de Photino
 * src/Backend/Modules/DespachosDiarios/DespachosDiariosHandler.cs (1.8.23). El payload viaja dentro de "data".
 *
 * despachosDiarios.resumen → GET api/despachos-diarios[?fechaDesde=&fechaHasta=] (QualityControlInnpack.Api → fps-api).
 * Photino reenvía las fechas como texto escapado; aquí además deben ser yyyy-MM-dd (lo que manda el input date).
 *
 * despachosDiarios.sap.{resumen,detalle,categorias} → GET apisapfaret api/despachos/{ruta}, con la API key del servidor.
 * "empresa" es un FILTRO de negocio ("" = INNPACK y FARET), no el scope de sesión: solo INNPACK/FARET exactos. Rango como
 * Photino: hasta por defecto hoy, desde vacío o mayor a 399 días → se acota y se avisa con "acotado". Los filtros de
 * texto (categoria, seleccion, cliente) solo se acotan en largo y se escapan; la API valida su contenido (400).
 * Los mensajes de apisapfaret solo se muestran en un 400 (validación); un fallo de SAP/red/timeout es genérico.
 * La vista escapa todo lo que pinta (esc()), así que los textos de SAP pasan tal cual.
 */
public class DespachosDiariosBridgeHandler {

    static final String MENSAJE_FECHA = "Las fechas deben tener el formato yyyy-MM-dd";
    static final String MENSAJE_SAP_NO_CONFIGURADA = "SAP (apisapfaret) no está configurado en este equipo";
    static final String MENSAJE_EMPRESA = "La empresa debe ser INNPACK o FARET";
    static final String MENSAJE_CATEGORIAS = "La selección de categorías no es válida";
    static final String MENSAJE_CLIENTES = "La selección de clientes no es válida";
    static final String MENSAJE_RANGO = "La fecha desde no puede ser posterior a la fecha hasta";
    static final String MENSAJE_SAP_FALLO = "No se pudo consultar los despachos en SAP";
    static final String MENSAJE_SAP_INVALIDA = "Respuesta inválida de SAP (apisapfaret)";
    static final int MAX_DIAS_SAP = 399;
    private static final int MAX_CATEGORIAS = 400;
    private static final int MAX_CLIENTES = 1100;
    private static final int MAX_MENSAJE_SAP = 200;
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(java.time.format.ResolverStyle.STRICT);

    private final InnpackApiClient api;
    private final SapApiClient sap;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DespachosDiariosBridgeHandler(InnpackApiClient api, SapApiClient sap, ObjectMapper mapper, Clock clock) {
        this.api = api;
        this.sap = sap;
        this.mapper = mapper;
        this.clock = clock;
    }

    public BridgeResult resumen(ObjectNode payload, SessionUser usuario) {
        JsonNode data = data(payload);
        String desde = texto(data.get("fechaDesde")).trim();
        String hasta = texto(data.get("fechaHasta")).trim();
        if ((!desde.isEmpty() && parse(desde) == null) || (!hasta.isEmpty() && parse(hasta) == null)) {
            return BridgeResult.error(MENSAJE_FECHA);
        }
        StringBuilder path = new StringBuilder("/api/despachos-diarios");
        char separador = '?';
        if (!desde.isEmpty()) {
            path.append(separador).append("fechaDesde=").append(UriEscape.dataString(desde));
            separador = '&';
        }
        if (!hasta.isEmpty()) {
            path.append(separador).append("fechaHasta=").append(UriEscape.dataString(hasta));
        }
        return InnpackRespuestas.reenviar(api.get(usuario, path.toString()), mapper);
    }

    public BridgeResult sapResumen(ObjectNode payload, SessionUser usuario) {
        return sapConsultar("resumen", payload);
    }

    public BridgeResult sapDetalle(ObjectNode payload, SessionUser usuario) {
        return sapConsultar("detalle", payload);
    }

    public BridgeResult sapCategorias(ObjectNode payload, SessionUser usuario) {
        return sapConsultar("categorias", payload);
    }

    private BridgeResult sapConsultar(String ruta, ObjectNode payload) {
        if (!sap.configurada()) {
            return BridgeResult.error(MENSAJE_SAP_NO_CONFIGURADA);
        }
        JsonNode data = data(payload);
        String empresa = texto(data.get("empresa"));
        String categoria = texto(data.get("categoria"));
        String seleccion = texto(data.get("seleccion"));
        String cliente = texto(data.get("cliente"));
        if (!empresa.isEmpty() && !empresa.equals("INNPACK") && !empresa.equals("FARET")) {
            return BridgeResult.error(MENSAJE_EMPRESA);
        }
        if (categoria.length() > MAX_CATEGORIAS || seleccion.length() > MAX_CATEGORIAS
                || CONTROL.matcher(categoria).find() || CONTROL.matcher(seleccion).find()) {
            return BridgeResult.error(MENSAJE_CATEGORIAS);
        }
        if (cliente.length() > MAX_CLIENTES || CONTROL.matcher(cliente).find()) {
            return BridgeResult.error(MENSAJE_CLIENTES);
        }
        String textoHasta = texto(data.get("fechaHasta"));
        String textoDesde = texto(data.get("fechaDesde"));
        LocalDate hasta = parse(textoHasta);
        if (hasta == null) {
            hasta = LocalDate.now(clock);
        }
        LocalDate desde = parse(textoDesde);
        if (desde != null && desde.isAfter(hasta)) {
            return BridgeResult.error(MENSAJE_RANGO);
        }
        boolean acotado = false;
        if (desde == null || ChronoUnit.DAYS.between(desde, hasta) > MAX_DIAS_SAP) {
            desde = hasta.minusDays(MAX_DIAS_SAP);
            acotado = true;
        }
        DateTimeFormatter basico = DateTimeFormatter.BASIC_ISO_DATE;
        StringBuilder path = new StringBuilder("api/despachos/").append(ruta)
                .append("?desde=").append(desde.format(basico)).append("&hasta=").append(hasta.format(basico));
        if (!empresa.isEmpty()) {
            path.append("&empresa=").append(empresa);
        }
        if (!cliente.isEmpty()) {
            path.append("&cliente=").append(UriEscape.dataString(cliente));
        }
        if (ruta.equals("detalle") && !categoria.isEmpty()) {
            path.append("&categoria=").append(UriEscape.dataString(categoria));
        }
        if (ruta.equals("categorias") && !seleccion.isEmpty()) {
            path.append("&seleccion=").append(UriEscape.dataString(seleccion));
        }

        InnpackApiClient.Respuesta r = sap.get(path.toString());
        JsonNode cuerpo = null;
        if (r.body() != null) {
            try {
                cuerpo = mapper.readTree(r.body());
            } catch (RuntimeException e) {
                cuerpo = null;
            }
        }
        boolean ok = r.status() >= 200 && r.status() <= 299;
        JsonNode empresas = cuerpo != null && cuerpo.isObject() ? cuerpo.get("empresas") : null;
        if (!ok || empresas == null) {
            if (r.body() != null && cuerpo == null && ok) {
                return BridgeResult.error(MENSAJE_SAP_INVALIDA);
            }
            return BridgeResult.error(mensajeSap(r.status(), cuerpo));
        }
        ObjectNode salida = mapper.createObjectNode();
        salida.put("desde", desde.format(ISO));
        salida.put("hasta", hasta.format(ISO));
        salida.put("acotado", acotado);
        salida.set("empresas", empresas);
        JsonNode errores = cuerpo.get("errors");
        if (errores != null && errores.isArray()) {
            ArrayNode lista = salida.putArray("errors");
            for (JsonNode e : errores) {
                if (e.isString()) {
                    String t = e.asString();
                    lista.add(t.length() > MAX_MENSAJE_SAP ? t.substring(0, MAX_MENSAJE_SAP) : t);
                }
            }
        } else {
            salida.putNull("errors");
        }
        return BridgeResult.ok(salida);
    }

    /** Solo la validación de apisapfaret (400) llega al usuario; un fallo de SAP, red o timeout es genérico. */
    private static String mensajeSap(int status, JsonNode cuerpo) {
        if (status == 400 && cuerpo != null && cuerpo.isObject()) {
            JsonNode e = cuerpo.has("error") ? cuerpo.get("error") : cuerpo.get("message");
            if (e != null && e.isString() && !e.asString().isBlank() && e.asString().length() <= MAX_MENSAJE_SAP) {
                return e.asString();
            }
        }
        return MENSAJE_SAP_FALLO;
    }

    private static LocalDate parse(String valor) {
        try {
            return LocalDate.parse(valor, ISO);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private JsonNode data(ObjectNode payload) {
        JsonNode data = payload.get("data");
        return data != null && data.isObject() ? data : mapper.createObjectNode();
    }

    /** GetString de Photino: string o número → texto; cualquier otro tipo (o ausente) → "". */
    private static String texto(JsonNode nodo) {
        return nodo != null && (nodo.isString() || nodo.isNumber()) ? nodo.asString() : "";
    }
}
