package cl.faret.qccweb.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * API INNPACK simulada para tests (NUNCA se usan credenciales reales ni la API de producción).
 * Replica el comportamiento real de QualityControlInnpack.Api AuthController/AuthService:
 * 400 si faltan campos; 401 con "Usuario no existe" / "Usuario desactivado" / "Contraseña
 * incorrecta"; 200 con ApiResponse { success, message, data { token, userId, codigoUsuario,
 * nombreCompleto, rol } } y un JWT cuyo payload trae exp.
 */
public final class FakeInnpackApi implements AutoCloseable {

    public record Usuario(int id, String codigo, String password, String nombre, String rol, boolean activo) {}

    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Usuario> usuarios = new ConcurrentHashMap<>();
    private final AtomicInteger llamadas = new AtomicInteger();
    private volatile Supplier<Instant> expiracionToken;
    private volatile boolean caida;

    public FakeInnpackApi(Clock clock) {
        this.expiracionToken = () -> clock.instant().plusSeconds(8 * 3600);
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/api/auth/login", this::login);
        server.createContext("/api/home/dashboard", this::dashboard);
        server.createContext("/api/maquinas-seguimiento/resumen", this::maquinasResumen);
        server.createContext("/api/dashboard/filtros", ex -> dashboardLectura(ex, true));
        server.createContext("/api/dashboard/resumen", ex -> dashboardLectura(ex, false));
        server.createContext("/api/registros-produccion/filtros", ex -> registrosProduccionLectura(ex, true));
        server.createContext("/api/registros-produccion/resumen", ex -> registrosProduccionLectura(ex, false));
        server.createContext("/api/registros-control", this::registrosControl);
        server.createContext("/api/producto-terminado", this::productoTerminado);
        server.createContext("/api/certificados-liberacion", this::certificadosLiberacion);
        server.createContext("/api/control-documental", this::controlDocumental);
        server.createContext("/api/no-conformidades", this::noConformidades);
        server.createContext("/api/nc-catalogos", this::noConformidades);
        server.createContext("/api/recepcion-calidad", this::recepcionCalidad);
        server.createContext("/api/usuarios", this::usuarios);
        server.createContext("/api/muestra-laboratorio", this::muestraLaboratorio);
        server.createContext("/api/talleres-externos", this::talleresExternos);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
    }

    /** Modo de respuesta de GET api/home/dashboard. */
    public enum ModoDashboard { NORMAL, ERROR_NEGOCIO, ERROR_500 }

    private final java.util.Set<Integer> revocados = ConcurrentHashMap.newKeySet();
    private final java.util.List<String> authorizationRecibidos = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final AtomicInteger llamadasDashboard = new AtomicInteger();
    private volatile ModoDashboard modoDashboard = ModoDashboard.NORMAL;

    /** Simula que la API invalida los tokens de un usuario (→ 401 en sus próximas llamadas). */
    public void revocarTokens(int userId) {
        revocados.add(userId);
    }

    public void modoDashboard(ModoDashboard modo) {
        this.modoDashboard = modo;
    }

    public int llamadasDashboard() {
        return llamadasDashboard.get();
    }

    public java.util.List<String> authorizationRecibidos() {
        return java.util.List.copyOf(authorizationRecibidos);
    }

    public void reiniciarDashboard() {
        revocados.clear();
        authorizationRecibidos.clear();
        queriesMaquinas.clear();
        peticionesDashboard.clear();
        peticionesProduccion.clear();
        peticionesControl.clear();
        peticionesProductoTerminado.clear();
        peticionesCertificados.clear();
        peticionesControlDocumental.clear();
        peticionesNoConformidades.clear();
        seguimientosRecibidos.clear();
        accionesRecibidas.clear();
        peticionesRecepcion.clear();
        peticionesUsuarios.clear();
        peticionesLaboratorio.clear();
        peticionesTalleres.clear();
        modoUsuarios = "NORMAL";
        modoDashboard = ModoDashboard.NORMAL;
    }

    /**
     * Valida el Bearer como la API real ([Authorize]): 401 sin cuerpo si falta, no lo emitió esta API
     * o fue revocado. En data devuelve el "sub" del token recibido para que los tests comprueben con
     * qué identidad llamó el gateway.
     */
    private void dashboard(HttpExchange ex) throws IOException {
        llamadasDashboard.incrementAndGet();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        switch (modoDashboard) {
            case ERROR_NEGOCIO -> responder(ex, 400, fallo("No se pudo calcular el dashboard"));
            case ERROR_500 -> responder(ex, 500, "{\"type\":\"about:blank\",\"title\":\"Internal Server Error\",\"status\":500,\"detail\":\"SqlException: timeout en calidad_db\"}");
            default -> responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"kpis\":{\"controlesHoy\":42,"
                    + "\"noConformesHoy\":3,\"mermaHoy\":12.5},\"alertas\":[],\"usuarioDelToken\":" + sub + "},\"errors\":null}");
        }
    }

    private final java.util.List<String> queriesMaquinas = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Queries recibidas en GET api/maquinas-seguimiento/resumen (para verificar el mapeo de parámetros). */
    public java.util.List<String> queriesMaquinas() {
        return java.util.List.copyOf(queriesMaquinas);
    }

    /** Respuesta con la forma real de MaquinasSeguimientoResumenDto (camelCase, ApiResponse). */
    public static String dataMaquinas(int sub) {
        return "{\"totalMaquinas\":2,\"maquinasConRegistros\":1,\"registrosMaquinaSeleccionada\":1,"
                + "\"rechazosMaquinaSeleccionada\":0,\"maquinas\":[{\"id\":5,\"nombre\":\"Corrugadora 1\",\"proceso\":\"Corrugado\"},"
                + "{\"id\":7,\"nombre\":\"Troqueladora 2\",\"proceso\":\"Troquelado\"}],\"registros\":[{\"id\":901,"
                + "\"fechaRegistro\":\"23-09-2026\",\"horaRegistro\":\"10:15\",\"usuario\":\"Operador Uno\",\"proceso\":\"Corrugado\","
                + "\"maquina\":\"Corrugadora 1\",\"formulario\":\"Control visual\",\"np\":\"3996\",\"producto\":\"Caja 40x30\","
                + "\"turno\":\"A\",\"estado\":\"Validado\",\"observacion\":\"ñandú — ok\"}],\"usuarioDelToken\":" + sub + "}";
    }

    private void maquinasResumen(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        queriesMaquinas.add(String.valueOf(ex.getRequestURI().getRawQuery()));
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Máquina no encontrada"));
            return;
        }
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + dataMaquinas(sub) + ",\"errors\":null}");
    }

    private final java.util.List<String> peticionesDashboard = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** "filtros" o "resumen?<query cruda>" recibidos en api/dashboard/* (para verificar el mapeo). */
    public java.util.List<String> peticionesDashboard() {
        return java.util.List.copyOf(peticionesDashboard);
    }

    public static String dataDashboardFiltros() {
        return "{\"usuarios\":[{\"id\":10,\"nombre\":\"Operador Uno\"},{\"id\":20,\"nombre\":\"María José Peña\"}],"
                + "\"procesos\":[{\"id\":4,\"nombre\":\"Pegado\"},{\"id\":5,\"nombre\":\"Termoformado\"}]}";
    }

    /**
     * Forma real de DashboardResumenDto (camelCase). Con inspector=0 devuelve listas vacías y KPIs en
     * cero; con proceso=999 un dataset grande (400 registros, 40 inspectores).
     */
    public static String dataDashboardResumen(int sub, String query) {
        boolean vacio = query != null && query.contains("inspector=0");
        int registros = vacio ? 0 : (query != null && query.contains("proceso=999") ? 400 : 2);
        int inspectores = vacio ? 0 : (registros == 400 ? 40 : 2);
        StringBuilder desempeno = new StringBuilder();
        for (int i = 0; i < inspectores; i++) {
            desempeno.append(i > 0 ? "," : "").append("{\"inspector\":\"Inspector ").append(i)
                    .append("\",\"cumplimiento\":").append(90 + i % 10).append(",\"controlesProgramados\":50,\"controlesRealizados\":")
                    .append(45 + i % 5).append(",\"noConformidades\":").append(i % 3).append(",\"estado\":\"OK\"}");
        }
        StringBuilder ultimos = new StringBuilder();
        for (int i = 0; i < registros; i++) {
            ultimos.append(i > 0 ? "," : "").append("{\"id\":").append(500 + i)
                    .append(",\"fechaRegistro\":\"23-09-2026\",\"horaRegistro\":\"09:").append(String.format("%02d", i % 60))
                    .append("\",\"usuario\":\"María José Peña\",\"proceso\":\"Pegado\",\"maquina\":\"Pegadora 3\",\"formulario\":\"Control visual\","
                            + "\"np\":\"41").append(String.format("%02d", i % 100)).append("\",\"codigoProducto\":\"CP-").append(i)
                    .append("\",\"producto\":\"Estuche cartón ñandú «E2E»\",\"turno\":\"A\",\"estado\":\"Conforme\",\"observacion\":\"Sin novedad\","
                            + "\"tipoMerma\":\"Pegado\",\"cantidadMerma\":\"1.50\",\"tipoDefecto\":\"\",\"bobinaLote\":\"L-1\",\"bobinaCodigo\":\"B-1\","
                            + "\"bobinaDescripcion\":\"Bobina 120g\",\"bobinaObservacion\":\"\",\"estadoValidacion\":\"Pendiente\","
                            + "\"fechaValidacion\":\"\",\"usuarioValidacion\":\"\",\"imagenUrl\":\"\"}");
        }
        return "{\"controlesHoy\":" + (vacio ? 0 : 12) + ",\"controlesPeriodo\":" + registros + ",\"cumplimientoGeneral\":" + (vacio ? 0 : 93.5)
                + ",\"noConformidadesDetectadas\":" + (vacio ? 0 : 3) + ",\"mermaHoy\":" + (vacio ? 0 : 4.25) + ",\"registrosConObservacionHoy\":1,"
                + "\"cumplimientoPorInspector\":[" + (vacio ? "" : "{\"inspector\":\"María José Peña\",\"total\":10,\"porcentaje\":95.5}") + "],"
                + "\"noConformidadesPorInspector\":[" + (vacio ? "" : "{\"inspector\":\"Operador Uno\",\"total\":2,\"porcentaje\":20}") + "],"
                + "\"controlesPorProceso\":[" + (vacio ? "" : "{\"proceso\":\"Pegado\",\"inspector\":\"Operador Uno\",\"total\":7}") + "],"
                + "\"tendenciaCumplimiento\":[" + (vacio ? "" : "{\"fecha\":\"2026-09-22\",\"cumplimiento\":91.2},{\"fecha\":\"2026-09-23\",\"cumplimiento\":93.5}") + "],"
                + "\"desempenoIndividual\":[" + desempeno + "],\"ultimosRegistros\":[" + ultimos + "],\"usuarioDelToken\":" + sub + "}";
    }

    private void dashboardLectura(HttpExchange ex, boolean filtros) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String query = ex.getRequestURI().getRawQuery();
        peticionesDashboard.add(filtros ? "filtros" + (query == null ? "" : "?" + query) : "resumen?" + query);
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Rango de fechas inválido"));
            return;
        }
        String data = filtros ? dataDashboardFiltros() : dataDashboardResumen(sub, query);
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + data + ",\"errors\":null}");
    }

    private final java.util.List<String> peticionesProduccion = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** "filtros" o "resumen?<query cruda>" recibidos en api/registros-produccion/* (para verificar el mapeo). */
    public java.util.List<String> peticionesProduccion() {
        return java.util.List.copyOf(peticionesProduccion);
    }

    /**
     * Misma forma que Dashboard (en la API real ambos módulos comparten ResumenOperacionalService,
     * solo cambia el área) con un marcador "area" para que los tests distingan la ruta atendida.
     */
    public static String dataProduccionResumen(int sub, String query) {
        String base = dataDashboardResumen(sub, query);
        return base.substring(0, base.length() - 1) + ",\"area\":\"PRODUCCION\"}";
    }

    private void registrosProduccionLectura(HttpExchange ex, boolean filtros) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String query = ex.getRequestURI().getRawQuery();
        peticionesProduccion.add(filtros ? "filtros" + (query == null ? "" : "?" + query) : "resumen?" + query);
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Rango de fechas inválido"));
            return;
        }
        String data = filtros ? dataDashboardFiltros() : dataProduccionResumen(sub, query);
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + data + ",\"errors\":null}");
    }

    private final java.util.List<String> peticionesControl = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** "<ruta>?<query cruda>" recibidos en api/registros-control* (para verificar el mapeo exacto). */
    public java.util.List<String> peticionesControl() {
        return java.util.List.copyOf(peticionesControl);
    }

    /**
     * Forma real de la respuesta paginada de GET api/registros-control: { items, total, page, pages }.
     * Con np=VACIO devuelve 0 items; con limit=999999 devuelve todo (300 items en una página);
     * si no, 2 items de un total de 45 y "page" = el recibido.
     */
    public static String dataRegistrosControl(int sub, String query) {
        boolean vacio = query != null && query.contains("np=VACIO");
        boolean todo = query != null && query.contains("limit=999999");
        int items = vacio ? 0 : (todo ? 300 : 2);
        int total = vacio ? 0 : (todo ? 300 : 45);
        int page = 1;
        if (query != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|&)page=(\\d+)").matcher(query);
            if (m.find()) {
                page = Integer.parseInt(m.group(1));
            }
        }
        int pages = vacio ? 1 : (todo ? 1 : 3);
        StringBuilder lista = new StringBuilder();
        for (int i = 0; i < items; i++) {
            lista.append(i > 0 ? "," : "").append("{\"id\":").append(7000 + i)
                    .append(",\"fechaRegistro\":\"24-09-2026\",\"horaRegistro\":\"08:").append(String.format("%02d", i % 60))
                    .append("\",\"usuario\":\"María José Peña\",\"proceso\":\"Pegado\",\"parametro\":\"Adhesivo\",\"maquina\":\"Pegadora 3\","
                            + "\"np\":\"41").append(String.format("%02d", i % 100)).append("\",\"producto\":\"Estuche cartón ñandú «E2E»\","
                            + "\"turno\":\"A\",\"valor\":\"12.5\",\"unidad\":\"g\",\"estado\":\"Conforme\",\"estadoValidacion\":\"Pendiente\","
                            + "\"observacion\":\"=SUMA(1;2) sin fórmula\",\"imagenUrl\":\"\"}");
        }
        return "{\"items\":[" + lista + "],\"total\":" + total + ",\"page\":" + page + ",\"pages\":" + pages
                + ",\"usuarioDelToken\":" + sub + "}";
    }

    private void registrosControl(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String query = ex.getRequestURI().getRawQuery();
        peticionesControl.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath() + "?" + query);
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Rango de fechas inválido"));
            return;
        }
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + dataRegistrosControl(sub, query) + ",\"errors\":null}");
    }

    private final java.util.List<String> peticionesProductoTerminado = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** "GET <ruta>?<query cruda>" recibidos en api/producto-terminado* (para verificar el mapeo exacto). */
    public java.util.List<String> peticionesProductoTerminado() {
        return java.util.List.copyOf(peticionesProductoTerminado);
    }

    public static String dataProductoTerminadoFiltros(String empresa) {
        return "{\"maquinas\":[\"Termoformadora 1\",\"Pegadora 3\"],\"inspectores\":[{\"id\":10,\"nombre\":\"Operador Uno\"},"
                + "{\"id\":20,\"nombre\":\"María José Peña\"}],\"defectos\":[{\"id\":1,\"nombre\":\"Rebaba\"}],"
                + "\"origenes\":[{\"id\":3,\"nombre\":\"Máquina\"}],\"empresaConsultada\":\"" + empresa + "\"}";
    }

    public static String dataProductoTerminadoResumen(int sub, String empresa) {
        return "{\"totalInspecciones\":45,\"conformes\":40,\"noConformes\":5,\"porcentajeConformidad\":88.9,"
                + "\"pareto\":[{\"defecto\":\"Rebaba\",\"cantidad\":3}],\"origenes\":[{\"origen\":\"Máquina\",\"cantidad\":2}],"
                + "\"tendencia\":[{\"fecha\":\"2026-09-23\",\"inspecciones\":20,\"noConformes\":2}],"
                + "\"comparacion\":[{\"proceso\":\"Pegado\",\"inspecciones\":25,\"noConformes\":3}],"
                + "\"empresaConsultada\":\"" + empresa + "\",\"usuarioDelToken\":" + sub + "}";
    }

    /** Filas de la lista/exportación (22 campos, los que arma construirTablaExportTemp en Photino). */
    private static String filaProductoTerminado(int i) {
        return "{\"inspeccionId\":" + (9000 + i) + ",\"fecha\":\"24-09-2026\",\"inspector\":\"María José Peña\",\"np\":\"41" + String.format("%02d", i % 100)
                + "\",\"cliente\":\"Cliente Ñandú «E2E»\",\"codigoProducto\":\"CP-" + i + "\",\"descripcionProducto\":\"Estuche cartón =SUMA(1;2)\","
                + "\"proceso\":\"Pegado\",\"cantidadLote\":\"1.500\",\"maquina\":\"Pegadora 3\",\"nivelInspeccion\":\"II\",\"aql\":\"1,5\","
                + "\"letraCodigo\":\"K\",\"tamanoMuestra\":125,\"ac\":5,\"re\":6,\"unidadesNoConformes\":" + (i % 3) + ",\"defectosTotales\":" + (i % 4)
                + ",\"resultado\":\"" + (i % 3 == 0 ? "NO CONFORME" : "CONFORME") + "\",\"hallazgoCorrelativo\":" + (i % 2 == 0 ? "null" : "1")
                + ",\"defecto\":\"" + (i % 2 == 0 ? "" : "Rebaba") + "\",\"origen\":\"" + (i % 2 == 0 ? "" : "Máquina") + "\"}";
    }

    /** Lista paginada { items, total, page, limit }: np=VACIO → 0; np=GRANDE → 500 en una página; si no 2 de 45. */
    public static String dataProductoTerminadoList(int sub, String query) {
        boolean vacio = query.contains("np=VACIO");
        boolean grande = query.contains("np=GRANDE");
        int n = vacio ? 0 : (grande ? 500 : 2);
        int page = 1;
        int limit = 50;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|&)page=(\\d+)").matcher(query);
        if (m.find()) {
            page = Integer.parseInt(m.group(1));
        }
        m = java.util.regex.Pattern.compile("(?:^|&)limit=(\\d+)").matcher(query);
        if (m.find()) {
            limit = Integer.parseInt(m.group(1));
        }
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < n; i++) {
            items.append(i > 0 ? "," : "").append(filaProductoTerminado(i));
        }
        return "{\"items\":[" + items + "],\"total\":" + (vacio ? 0 : (grande ? 500 : 45)) + ",\"page\":" + page + ",\"limit\":" + limit
                + ",\"usuarioDelToken\":" + sub + "}";
    }

    /** exportar-detalle devuelve un array plano de filas (una por inspección/hallazgo). */
    public static String dataProductoTerminadoExport(String query) {
        int n = query.contains("np=VACIO") ? 0 : (query.contains("np=GRANDE") ? 500 : 3);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            sb.append(i > 0 ? "," : "").append(filaProductoTerminado(i));
        }
        return sb.append("]").toString();
    }

    public static String dataProductoTerminadoDetalle(int id, String empresa, int sub) {
        return "{\"id\":" + id + ",\"fechaRegistro\":\"24-09-2026\",\"horaRegistro\":\"10:15\",\"inspector\":\"María José Peña\","
                + "\"np\":\"4101\",\"resultado\":\"CONFORME\",\"hallazgos\":[{\"correlativo\":1,\"defecto\":\"Rebaba\",\"origen\":\"Máquina\",\"cantidad\":2}],"
                + "\"empresaConsultada\":\"" + empresa + "\",\"usuarioDelToken\":" + sub + "}";
    }

    private void productoTerminado(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        String query = String.valueOf(ex.getRequestURI().getRawQuery());
        peticionesProductoTerminado.add(ex.getRequestMethod() + " " + path + "?" + query);
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            responder(ex, 200, "{\"success\":true,\"message\":\"NO DEBERIA LLEGAR\",\"data\":null,\"errors\":null}");
            return;
        }
        // Misma validación que ProductoTerminadoController: empresa obligatoria INNPACK|FARET.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|&)empresa=([^&]*)").matcher(query);
        String empresa = m.find() ? m.group(1) : "";
        if (!empresa.equals("INNPACK") && !empresa.equals("FARET")) {
            responder(ex, 400, fallo("Falta indicar la empresa (INNPACK o FARET)"));
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Rango de fechas inválido"));
            return;
        }
        String data;
        if (path.equals("/api/producto-terminado/filtros")) {
            data = dataProductoTerminadoFiltros(empresa);
        } else if (path.equals("/api/producto-terminado/resumen")) {
            data = dataProductoTerminadoResumen(sub, empresa);
        } else if (path.equals("/api/producto-terminado/exportar-detalle")) {
            data = dataProductoTerminadoExport(query);
        } else if (path.equals("/api/producto-terminado")) {
            data = dataProductoTerminadoList(sub, query);
        } else {
            int id = Integer.parseInt(path.substring("/api/producto-terminado/".length()));
            if (id == 404) {
                responder(ex, 404, fallo("Inspección no encontrada"));
                return;
            }
            data = dataProductoTerminadoDetalle(id, empresa, sub);
        }
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + data + ",\"errors\":null}");
    }

    private final java.util.List<String> peticionesCertificados = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** "GET <ruta>[?<query cruda>]" recibidos en api/certificados-liberacion* (para verificar el mapeo). */
    public java.util.List<String> peticionesCertificados() {
        return java.util.List.copyOf(peticionesCertificados);
    }

    /** Búsqueda: lista de CertificadoLiberacionDto (np=VACIO → []). */
    public static String dataCertificados(String query) {
        if (query != null && query.contains("np=VACIO")) {
            return "[]";
        }
        return "[{\"folio\":123456,\"empresa\":\"INNPACK SPA\",\"np\":\"4101\",\"cliente\":\"Cliente Ñandú «E2E»\",\"item\":\"IT-1\","
                + "\"codigoArticulo\":\"CP-1\",\"descripcionArticulo\":\"Estuche cartón =SUMA(1;2)\",\"cantidadBase\":1500,\"cantidadLiberacion\":1480,"
                + "\"operador\":\"Operador Uno\",\"inspector\":\"María José Peña\",\"fechaLiberacion\":\"2026-09-24T10:15:00\",\"bodegaDestino\":\"PT-01\"},"
                + "{\"folio\":123457,\"empresa\":\"FARET SPA\",\"np\":\"4102\",\"cliente\":\"Otro Cliente\",\"item\":\"IT-2\",\"codigoArticulo\":\"CP-2\","
                + "\"descripcionArticulo\":\"Caja\",\"cantidadBase\":100,\"cantidadLiberacion\":100,\"operador\":\"Operador Dos\",\"inspector\":\"Inspector X\","
                + "\"fechaLiberacion\":\"2026-09-23T08:00:00\",\"bodegaDestino\":\"PT-02\"}]";
    }

    /** PDF mínimo válido (xref con offsets correctos) para que abra en cualquier visor. */
    public static byte[] pdfMinimo(long folio) {
        String header = "%PDF-1.4\n";
        String o1 = "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n";
        String o2 = "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n";
        String o3 = "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R>>endobj\n";
        String texto = "BT /F1 12 Tf 20 100 Td (Certificado " + folio + ") Tj ET";
        String o4 = "4 0 obj<</Length " + texto.length() + ">>stream\n" + texto + "\nendstream\nendobj\n";
        int off1 = header.length();
        int off2 = off1 + o1.length();
        int off3 = off2 + o2.length();
        int off4 = off3 + o3.length();
        int xref = off4 + o4.length();
        String tabla = "xref\n0 5\n0000000000 65535 f \n" + String.format("%010d 00000 n \n%010d 00000 n \n%010d 00000 n \n%010d 00000 n \n", off1, off2, off3, off4)
                + "trailer<</Size 5/Root 1 0 R>>\nstartxref\n" + xref + "\n%%EOF\n";
        return (header + o1 + o2 + o3 + o4 + tabla).getBytes(StandardCharsets.ISO_8859_1);
    }

    private void certificadosLiberacion(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        peticionesCertificados.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 502, fallo("No fue posible consultar los certificados de liberación"));
            return;
        }
        if (path.equals("/api/certificados-liberacion")) {
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + dataCertificados(query) + ",\"errors\":null}");
            return;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^/api/certificados-liberacion/(\\d+)/(calidad-pdf|pdf)$").matcher(path);
        if (!m.matches()) {
            responder(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
            return;
        }
        long folio = Long.parseLong(m.group(1));
        String base64;
        if (folio == 404) {
            responder(ex, 404, fallo("No se encontraron datos para el certificado N° 404"));
            return;
        } else if (folio == 500) {
            base64 = Base64.getEncoder().encodeToString("<html>no soy un pdf</html>".getBytes(StandardCharsets.UTF_8));
        } else if (folio == 600) {
            base64 = "";
        } else if (folio == 700) {
            // Excede el máximo del gateway (15 MB): solo el largo importa, no se decodifica.
            base64 = "A".repeat(((15 * 1024 * 1024 + 2) / 3) * 4 + 4);
        } else if (folio == 800) {
            base64 = "%%%no-base64%%%";
        } else {
            base64 = Base64.getEncoder().encodeToString(pdfMinimo(folio));
        }
        String fileName = folio == 900 ? "..\\..\\evil<>:\"|?*.exe" : "CertificadoCalidad_" + folio + ".pdf";
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"folio\":" + folio + ",\"fileName\":\"" + fileName.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"base64\":\"" + base64 + "\",\"usuarioDelToken\":" + sub + "},\"errors\":null}");
    }

    private final java.util.List<String> peticionesControlDocumental = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** "<método> <ruta>[?<query cruda>]" recibidos en api/control-documental* (para verificar el mapeo). */
    public java.util.List<String> peticionesControlDocumental() {
        return java.util.List.copyOf(peticionesControlDocumental);
    }

    private static String documento(int i) {
        return "{\"id\":" + (300 + i) + ",\"codigo\":\"PR-CAL-" + String.format("%03d", i) + "\",\"titulo\":\"Procedimiento ñandú «" + i + "»\","
                + "\"tipoDocumento\":\"Procedimiento\",\"area\":\"Calidad\",\"estado\":\"VIGENTE\",\"alcanceEmpresa\":\"" + (i % 3 == 0 ? "AMBAS" : (i % 3 == 1 ? "INNPACK" : "FARET"))
                + "\",\"versionVigente\":\"1." + (i % 5) + "\",\"versionVigenteId\":" + (1000 + i) + ",\"tieneAdjuntoVigente\":" + (i % 2 == 0)
                + ",\"fechaVigencia\":\"2026-09-24\",\"actualizadoPor\":\"María José Peña\"}";
    }

    /** Lista paginada { items, total, page, pageSize, pages }: texto=VACIO → 0; texto=GRANDE → 300; si no 3 de 42. */
    public static String dataControlDocumentalList(int sub, String query) {
        boolean vacio = query.contains("texto=VACIO");
        boolean grande = query.contains("texto=GRANDE");
        int n = vacio ? 0 : (grande ? 300 : 3);
        int page = 1;
        int pageSize = 50;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|&)page=(\\d+)").matcher(query);
        if (m.find()) {
            page = Integer.parseInt(m.group(1));
        }
        m = java.util.regex.Pattern.compile("(?:^|&)pageSize=(\\d+)").matcher(query);
        if (m.find()) {
            pageSize = Integer.parseInt(m.group(1));
        }
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < n; i++) {
            items.append(i > 0 ? "," : "").append(documento(i));
        }
        int total = vacio ? 0 : (grande ? 300 : 42);
        return "{\"items\":[" + items + "],\"total\":" + total + ",\"page\":" + page + ",\"pageSize\":" + pageSize
                + ",\"pages\":" + Math.max(1, (total + pageSize - 1) / pageSize) + ",\"usuarioDelToken\":" + sub + "}";
    }

    public static String dataControlDocumentalGet(int id, int sub) {
        return "{\"id\":" + id + ",\"codigo\":\"PR-CAL-001\",\"titulo\":\"Procedimiento ñandú «1»\",\"descripcion\":\"=SUMA(1;2) sin fórmula\","
                + "\"tipoDocumento\":\"Procedimiento\",\"area\":\"Calidad\",\"estado\":\"VIGENTE\",\"alcanceEmpresa\":\"AMBAS\",\"versiones\":[{\"id\":1001,"
                + "\"version\":\"1.1\",\"vigente\":true,\"tieneAdjunto\":true,\"nombreArchivo\":\"PR-CAL-001_v1.1.pdf\"}],\"usuarioDelToken\":" + sub + "}";
    }

    /** PNG de 1x1 válido (firma + IHDR). */
    public static byte[] pngMinimo() {
        return Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
    }

    /** Adjunto por versionId: 1 PDF, 2 PNG, 3 DOCX (zip), 4 HTML declarado como PDF, 5 texto plano; errores 404/600/700/800/900. */
    private void controlDocumental(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        peticionesControlDocumental.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            responder(ex, 200, "{\"success\":true,\"message\":\"NO DEBERIA LLEGAR\",\"data\":null,\"errors\":null}");
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Filtro de fecha inválido"));
            return;
        }
        if (path.equals("/api/control-documental")) {
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + dataControlDocumentalList(sub, String.valueOf(query)) + ",\"errors\":null}");
            return;
        }
        java.util.regex.Matcher adj = java.util.regex.Pattern.compile("^/api/control-documental/adjunto/(\\d+)$").matcher(path);
        if (adj.matches()) {
            int versionId = Integer.parseInt(adj.group(1));
            String nombre = "adjunto_" + versionId;
            String mime = "application/octet-stream";
            String base64 = "";
            switch (versionId) {
                case 404 -> { responder(ex, 404, fallo("Esta versión no tiene ningún archivo adjunto")); return; }
                case 1 -> { nombre = "PR-CAL-001_v1.1.pdf"; mime = "application/pdf"; base64 = Base64.getEncoder().encodeToString(pdfMinimo(1)); }
                case 2 -> { nombre = "diagrama.png"; mime = "image/png"; base64 = Base64.getEncoder().encodeToString(pngMinimo()); }
                case 3 -> { nombre = "instructivo ñ.docx"; mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    base64 = Base64.getEncoder().encodeToString(new byte[] {0x50, 0x4b, 0x03, 0x04, 0x14, 0, 0, 0, 1, 2, 3}); }
                case 4 -> { nombre = "falso.pdf"; mime = "application/pdf"; base64 = Base64.getEncoder().encodeToString("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)); }
                case 5 -> { nombre = "notas.txt"; mime = "text/plain"; base64 = Base64.getEncoder().encodeToString("hola ñandú".getBytes(StandardCharsets.UTF_8)); }
                case 600 -> { nombre = "vacio.pdf"; mime = "application/pdf"; }
                case 700 -> { nombre = "grande.pdf"; mime = "application/pdf"; base64 = "A".repeat(((25 * 1024 * 1024 + 2) / 3) * 4 + 4); }
                case 800 -> { nombre = "roto.pdf"; mime = "application/pdf"; base64 = "%%%no-base64%%%"; }
                case 900 -> { nombre = "..\\..\\evil<>:\"|?*.exe"; mime = "text/html"; base64 = Base64.getEncoder().encodeToString("<b>x</b>".getBytes(StandardCharsets.UTF_8)); }
                default -> { nombre = "PR-CAL-001_v1.0.pdf"; mime = "application/pdf"; base64 = Base64.getEncoder().encodeToString(pdfMinimo(versionId)); }
            }
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"nombreArchivo\":\"" + nombre.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\",\"tipoMime\":\"" + mime + "\",\"contenidoBase64\":\"" + base64 + "\",\"usuarioDelToken\":" + sub + "},\"errors\":null}");
            return;
        }
        java.util.regex.Matcher doc = java.util.regex.Pattern.compile("^/api/control-documental/(\\d+)$").matcher(path);
        if (doc.matches()) {
            int id = Integer.parseInt(doc.group(1));
            if (id == 404) {
                responder(ex, 404, fallo("Documento no encontrado"));
                return;
            }
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + dataControlDocumentalGet(id, sub) + ",\"errors\":null}");
            return;
        }
        responder(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
    }

    private final java.util.List<String> peticionesNoConformidades = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Cuerpo EXACTO recibido en POST api/no-conformidades/{id}/seguimiento y el "sub" del JWT usado. */
    public record SeguimientoRecibido(int ncId, int sub, String cuerpo, String contentType) {}

    private final java.util.List<SeguimientoRecibido> seguimientosRecibidos = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public java.util.List<SeguimientoRecibido> seguimientosRecibidos() {
        return java.util.List.copyOf(seguimientosRecibidos);
    }

    private final java.util.List<SeguimientoRecibido> accionesRecibidas = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Cuerpo EXACTO recibido en POST api/no-conformidades/{id}/acciones. */
    public java.util.List<SeguimientoRecibido> accionesRecibidas() {
        return java.util.List.copyOf(accionesRecibidas);
    }

    /** "<método> <ruta>[?<query cruda>]" recibidos en api/no-conformidades* y api/nc-catalogos*. */
    public java.util.List<String> peticionesNoConformidades() {
        return java.util.List.copyOf(peticionesNoConformidades);
    }

    private static String noConformidad(int i) {
        return "{\"id\":" + (500 + i) + ",\"codigo\":\"NC-" + String.format("%04d", i) + "\",\"cliente\":\"Viña Ñandú «" + i + "»\","
                + "\"tipoPnc\":\"" + (i % 2 == 0 ? "Cuarentena" : "Reclamo") + "\",\"nivel\":\"" + (i % 3 == 0 ? "Crítico" : "Menor")
                + "\",\"estadoGestion\":\"" + (i % 2 == 0 ? "ASIGNADA" : "CERRADA") + "\",\"area\":\"Impresión\",\"fechaIngreso\":\"2026-09-2" + (i % 10)
                + "\",\"familiaProducto\":\"Cajas\",\"categoriaDefecto\":\"=SUMA(1;2)\",\"cantRechazada\":" + (10 * i) + ",\"maquina\":\"Bobst 1\"}";
    }

    /** Lista paginada { items, total, page, pageSize, pages }: cliente=VACIO → 0; cliente=GRANDE → 300; si no 3 de 42. */
    public static String dataNoConformidadesList(int sub, String query) {
        boolean vacio = query.contains("cliente=VACIO");
        boolean grande = query.contains("cliente=GRANDE");
        int n = vacio ? 0 : (grande ? 300 : 3);
        int page = 1;
        int pageSize = 50;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|&)page=(\\d+)").matcher(query);
        if (m.find()) {
            page = Integer.parseInt(m.group(1));
        }
        m = java.util.regex.Pattern.compile("(?:^|&)pageSize=(\\d+)").matcher(query);
        if (m.find()) {
            pageSize = Integer.parseInt(m.group(1));
        }
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < n; i++) {
            items.append(i > 0 ? "," : "").append(noConformidad(i));
        }
        int total = vacio ? 0 : (grande ? 300 : 42);
        return "{\"items\":[" + items + "],\"total\":" + total + ",\"page\":" + page + ",\"pageSize\":" + pageSize
                + ",\"pages\":" + Math.max(1, (total + pageSize - 1) / pageSize) + ",\"usuarioDelToken\":" + sub + "}";
    }

    /** JPEG mínimo (solo SOI + APP0; basta para la validación de firma). */
    public static byte[] jpegMinimo() {
        return new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0x10, 0x4a, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1};
    }

    /**
     * No Conformidades + catálogos. Adjunto por adjuntoId: 1 PDF, 2 PNG, 3 JPEG (MIME en mayúsculas),
     * 4 HTML declarado PDF, 5 GIF real (fuera de la lista), 6 text/html, 900 PNG con nombre malicioso;
     * errores 404/600/700/800. NC 404 → no encontrada; adjuntos.list de la NC 900 trae nombres maliciosos.
     */
    private void noConformidades(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        peticionesNoConformidades.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        java.util.regex.Matcher acc = java.util.regex.Pattern.compile("^/api/no-conformidades/(\\d+)/acciones$").matcher(path);
        if (ex.getRequestMethod().equals("POST") && acc.matches()) {
            String cuerpoAcc = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            accionesRecibidas.add(new SeguimientoRecibido(Integer.parseInt(acc.group(1)), sub, cuerpoAcc,
                    ex.getRequestHeaders().getFirst("Content-Type")));
            String desc = mapper.readTree(cuerpoAcc).path("descripcion").asString("");
            if ("ERROR_API".equals(desc)) {
                responder(ex, 400, fallo("No se pudo registrar la acción"));
            } else if (acc.group(1).equals("777")) {
                responder(ex, 500, "{\"title\":\"error interno\"}");
            } else if ("CON_ID".equals(desc)) {
                responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"id\":77},\"errors\":null}");
            } else {
                // La API real responde Ok(new { }): no devuelve el id de la acción creada.
                responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{},\"errors\":null}");
            }
            return;
        }
        java.util.regex.Matcher seg = java.util.regex.Pattern.compile("^/api/no-conformidades/(\\d+)/seguimiento$").matcher(path);
        if (ex.getRequestMethod().equals("POST") && seg.matches()) {
            String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seguimientosRecibidos.add(new SeguimientoRecibido(Integer.parseInt(seg.group(1)), sub, cuerpo,
                    ex.getRequestHeaders().getFirst("Content-Type")));
            JsonNode b = mapper.readTree(cuerpo);
            if ("ERROR_API".equals(b.path("comentario").asString(""))) {
                responder(ex, 400, fallo("No se pudo registrar el seguimiento"));
            } else if (seg.group(1).equals("777")) {
                responder(ex, 500, "{\"title\":\"error interno\"}");
            } else {
                responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"id\":" + seg.group(1) + "},\"errors\":null}");
            }
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            responder(ex, 200, "{\"success\":true,\"message\":\"NO DEBERIA LLEGAR\",\"data\":null,\"errors\":null}");
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Filtro de fecha inválido"));
            return;
        }
        String ok = "{\"success\":true,\"message\":null,\"data\":";
        String fin = ",\"errors\":null}";
        java.util.regex.Matcher cat = java.util.regex.Pattern.compile("^/api/nc-catalogos/([A-Za-z]+)$").matcher(path);
        if (cat.matches()) {
            if (!java.util.List.of(cl.faret.qccweb.bridge.handlers.NoConformidadesBridgeHandler.CATALOGOS).contains(cat.group(1))) {
                responder(ex, 404, fallo("Catálogo no reconocido"));
                return;
            }
            responder(ex, 200, ok + "[{\"id\":1,\"nombre\":\"" + cat.group(1) + " ñ\",\"activo\":true,\"usuarioDelToken\":" + sub + "}]" + fin);
            return;
        }
        if (path.equals("/api/no-conformidades")) {
            responder(ex, 200, ok + dataNoConformidadesList(sub, String.valueOf(query)) + fin);
            return;
        }
        if (path.equals("/api/no-conformidades/resumen")) {
            responder(ex, 200, ok + "{\"total\":42,\"abiertas\":30,\"cerradas\":12,\"criticas\":5,\"usuarioDelToken\":" + sub + "}" + fin);
            return;
        }
        if (path.equals("/api/no-conformidades/filtros-opciones")) {
            responder(ex, 200, ok + "{\"clientes\":[\"Viña Ñandú\"],\"tiposPnc\":[\"Reclamo\"],\"areas\":[\"Impresión\"],\"usuarioDelToken\":" + sub + "}" + fin);
            return;
        }
        java.util.regex.Matcher adj = java.util.regex.Pattern.compile("^/api/no-conformidades/(\\d+)/adjuntos/(\\d+)$").matcher(path);
        if (adj.matches()) {
            int adjuntoId = Integer.parseInt(adj.group(2));
            String nombre = "adjunto_" + adjuntoId;
            String mime = "application/pdf";
            String base64 = "";
            switch (adjuntoId) {
                case 404 -> { responder(ex, 404, fallo("Adjunto no encontrado")); return; }
                case 1 -> { nombre = "causa raíz ñ.pdf"; base64 = Base64.getEncoder().encodeToString(pdfMinimo(1)); }
                case 2 -> { nombre = "foto1.png"; mime = "image/png"; base64 = Base64.getEncoder().encodeToString(pngMinimo()); }
                case 3 -> { nombre = "foto2.jpg"; mime = "IMAGE/JPEG"; base64 = Base64.getEncoder().encodeToString(jpegMinimo()); }
                case 4 -> { nombre = "falso.pdf"; base64 = Base64.getEncoder().encodeToString("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)); }
                case 5 -> { nombre = "anim.gif"; mime = "image/gif"; base64 = Base64.getEncoder().encodeToString("GIF89a....".getBytes(StandardCharsets.UTF_8)); }
                case 6 -> { nombre = "x.html"; mime = "text/html"; base64 = Base64.getEncoder().encodeToString("<b>x</b>".getBytes(StandardCharsets.UTF_8)); }
                case 600 -> nombre = "vacio.pdf";
                case 700 -> { nombre = "grande.pdf"; base64 = "A".repeat(((10 * 1024 * 1024 + 2) / 3) * 4 + 4); }
                case 800 -> { nombre = "roto.pdf"; base64 = "%%%no-base64%%%"; }
                case 900 -> { nombre = "..\\<img src=x onerror=alert(1)>\".png"; mime = "image/png"; base64 = Base64.getEncoder().encodeToString(pngMinimo()); }
                default -> base64 = Base64.getEncoder().encodeToString(pdfMinimo(adjuntoId));
            }
            responder(ex, 200, ok + "{\"id\":" + adjuntoId + ",\"nombreArchivo\":\"" + nombre.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\",\"tipoMime\":\"" + mime + "\",\"contenidoBase64\":\"" + base64 + "\",\"usuarioDelToken\":" + sub + "}" + fin);
            return;
        }
        java.util.regex.Matcher nc = java.util.regex.Pattern.compile("^/api/no-conformidades/(\\d+)(/[a-z]+)?$").matcher(path);
        if (nc.matches()) {
            int id = Integer.parseInt(nc.group(1));
            String rama = nc.group(2) == null ? "" : nc.group(2);
            if (id == 404) {
                responder(ex, 404, fallo("No conformidad no encontrada"));
                return;
            }
            switch (rama) {
                case "" -> responder(ex, 200, ok + "{\"id\":" + id + ",\"codigo\":\"NC-0001\",\"descripcion\":\"=HYPERLINK(1)\","
                        + "\"estadoGestion\":\"ASIGNADA\",\"usuarioDelToken\":" + sub + "}" + fin);
                case "/seguimiento" -> responder(ex, 200, ok + (id == 901
                        // Comentario malicioso guardado desde Photino (que no valida): la web debe mostrarlo sin ejecutarlo.
                        ? "[{\"id\":9,\"comentario\":\"<img src=x onerror=alert(1)> & 'x' \\\"y\\\" ñ\",\"autor\":\"<b>Mallory</b>\",\"creadoEn\":\"2026-09-20T10:00:00\"}]"
                        : "[{\"id\":1,\"comentario\":\"Revisión ñ\",\"autor\":\"María\",\"usuarioDelToken\":" + sub + "}]") + fin);
                case "/analisis" -> responder(ex, 200, ok + (id == 503 ? "null"
                        : "{\"id\":7,\"metodologia\":\"5 Por qué\",\"causaRaiz\":\"Tinta\",\"usuarioDelToken\":" + sub + "}") + fin);
                case "/acciones" -> responder(ex, 200, ok + (id == 901
                        ? "[{\"id\":4,\"descripcion\":\"<img src=x onerror=alert(1)> & ñ\",\"responsable\":\"<b>Mallory</b>\",\"prioridad\":\"ALTA\",\"estado\":\"PENDIENTE\"}]"
                        : "[{\"id\":3,\"descripcion\":\"Cambiar rodillo\",\"estado\":\"PENDIENTE\",\"usuarioDelToken\":" + sub + "}]") + fin);
                case "/adjuntos" -> {
                    String n1 = id == 900 ? "..\\\\<img src=x onerror=alert(1)>.pdf" : "causa raíz ñ.pdf";
                    responder(ex, 200, ok + "[{\"id\":1,\"tipo\":\"CAUSA_RAIZ_PDF\",\"nombreArchivo\":\"" + n1 + "\",\"tipoMime\":\"application/pdf\"},"
                            + "{\"id\":2,\"tipo\":\"EVIDENCIA_FOTO\",\"nombreArchivo\":\"\",\"tipoMime\":\"image/png\"},"
                            + "{\"id\":3,\"tipo\":\"EVIDENCIA_FOTO\",\"tipoMime\":\"image/jpeg\"}]" + fin);
                }
                default -> responder(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
            }
            return;
        }
        responder(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
    }

    private final java.util.List<String> peticionesRecepcion = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** "<método> <ruta>[?<query cruda>]" recibidos en api/recepcion-calidad*. */
    public java.util.List<String> peticionesRecepcion() {
        return java.util.List.copyOf(peticionesRecepcion);
    }

    /** Tipo de materia prima de cada lote de prueba (el resto de ids → 404 en el detalle). */
    private static String tipoLote(int id) {
        return switch (id) {
            case 1, 3, 4, 5, 6, 7, 8, 9 -> "PVA";
            case 2 -> "PliegoFaret";
            case 10 -> "Bobina";
            default -> null;
        };
    }

    public static String dataRecepcionList(int sub, String query) {
        return "[{\"id\":1,\"fechaCreacion\":\"2026-09-20 08:00\",\"tipoMateriaPrima\":\"PVA\",\"proveedor\":\"Adhesivos Ñuñoa «1»\",\"itemCode\":\"PVA-01\","
                + "\"descripcion\":\"=SUMA(1;2)\",\"cantidadTotalLote\":12.5,\"estado\":\"PendienteMuestreo\",\"totalBobinas\":0,\"totalMuestreadas\":0,"
                + "\"query\":\"" + query + "\",\"usuarioDelToken\":" + sub + "},"
                + "{\"id\":10,\"fechaCreacion\":\"2026-09-21 09:30\",\"tipoMateriaPrima\":\"Bobina\",\"proveedor\":\"Papelera\",\"itemCode\":\"B-99\","
                + "\"descripcion\":\"Bobina kraft\",\"cantidadTotalLote\":null,\"estado\":\"EnAnalisis\",\"totalBobinas\":8,\"totalMuestreadas\":2}]";
    }

    /**
     * Recepción Calidad. Detalle por id (empresa=INNPACK): 1,3-9 PVA, 2 PliegoFaret, 10 Bobina; 404 el
     * resto o empresa≠INNPACK. Foto por loteId: 1 JPEG, 2 PNG (guardada como image/jpeg), 3 GIF, 4 WEBP,
     * 5 HTML, 6 sin foto, 7 vacía, 8 excesiva, 9 base64 corrupto.
     */
    private void recepcionCalidad(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        peticionesRecepcion.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            responder(ex, 200, "{\"success\":true,\"message\":\"NO DEBERIA LLEGAR\",\"data\":null,\"errors\":null}");
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Filtro de fecha inválido"));
            return;
        }
        String ok = "{\"success\":true,\"message\":null,\"data\":";
        String fin = ",\"errors\":null}";
        String q = query == null ? "" : query;
        if (path.equals("/api/recepcion-calidad")) {
            responder(ex, 200, ok + dataRecepcionList(sub, q) + fin);
            return;
        }
        java.util.regex.Matcher foto = java.util.regex.Pattern.compile("^/api/recepcion-calidad/(\\d+)/foto$").matcher(path);
        if (foto.matches()) {
            int id = Integer.parseInt(foto.group(1));
            String base64;
            switch (id) {
                case 1 -> base64 = Base64.getEncoder().encodeToString(jpegMinimo());
                case 2 -> base64 = Base64.getEncoder().encodeToString(pngMinimo());
                case 3 -> base64 = Base64.getEncoder().encodeToString("GIF89a\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1));
                case 4 -> base64 = Base64.getEncoder().encodeToString("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1));
                case 5 -> base64 = Base64.getEncoder().encodeToString("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
                case 6 -> { responder(ex, 400, fallo("Este lote no tiene fotografía cargada")); return; }
                case 7 -> base64 = "";
                case 8 -> base64 = "/9j/" + "A".repeat(((10 * 1024 * 1024 + 2) / 3) * 4);
                case 9 -> base64 = "%%%no-base64%%%";
                default -> base64 = Base64.getEncoder().encodeToString(jpegMinimo());
            }
            responder(ex, 200, ok + "{\"base64\":\"" + base64 + "\",\"mime\":\"image/jpeg\",\"usuarioDelToken\":" + sub + "}" + fin);
            return;
        }
        java.util.regex.Matcher det = java.util.regex.Pattern.compile("^/api/recepcion-calidad/(-?\\d+)$").matcher(path);
        if (det.matches()) {
            int id = Integer.parseInt(det.group(1));
            String tipo = tipoLote(id);
            if (tipo == null || !q.equals("empresa=INNPACK")) {
                responder(ex, 404, fallo("Lote no encontrado"));
                return;
            }
            responder(ex, 200, ok + "{\"id\":" + id + ",\"tipoMateriaPrima\":\"" + tipo + "\",\"proveedor\":\"Adhesivos Ñuñoa\",\"estado\":\"PendienteMuestreo\","
                    + "\"totalBobinas\":0,\"bobinas\":[],\"pva\":{\"tieneFoto\":true},\"usuarioDelToken\":" + sub + "}" + fin);
            return;
        }
        responder(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
    }

    private final java.util.List<String> peticionesUsuarios = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile String modoUsuarios = "NORMAL";

    /** "<método> <ruta>" recibidos en api/usuarios*. */
    public java.util.List<String> peticionesUsuarios() {
        return java.util.List.copyOf(peticionesUsuarios);
    }

    /** NORMAL | VACIO (data []) | NULO (data null) | OBJETO (data {}) | ITEM_INVALIDO ([1]). */
    public void modoUsuarios(String modo) {
        this.modoUsuarios = modo;
    }

    /**
     * GET api/usuarios con [Authorize(Roles = "admin,admin_ti")] (403 sin cuerpo para otro rol del
     * JWT). Fila 1 trae campos EXTRA (passwordHash, token) que el gateway debe descartar; fila 2 viene
     * incompleta y con nombres en PascalCase/mayúsculas mezcladas.
     */
    private void usuarios(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        peticionesUsuarios.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath());
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        Usuario actor = usuarios.values().stream().filter(u -> u.id() == sub).findFirst().orElse(null);
        if (actor == null || !(actor.rol().equals("admin") || actor.rol().equals("admin_ti"))) {
            ex.sendResponseHeaders(403, -1);
            ex.close();
            return;
        }
        if (!ex.getRequestMethod().equals("GET") || !ex.getRequestURI().getRawPath().equals("/api/usuarios")) {
            responder(ex, 200, "{\"success\":true,\"message\":\"NO DEBERIA LLEGAR\",\"data\":null,\"errors\":null}");
            return;
        }
        String data = switch (modoUsuarios) {
            case "VACIO" -> "[]";
            case "NULO" -> "null";
            case "OBJETO" -> "{\"id\":1}";
            case "ITEM_INVALIDO" -> "[1]";
            case "ACTIVO_TEXTO" -> "[{\"id\":1,\"activo\":\"true\"}]";
            case "FECHA_NUMERO" -> "[{\"id\":1,\"creadoEn\":123}]";
            case "ID_TEXTO" -> "[{\"id\":\"1\"}]";
            default -> "[{\"id\":10,\"codigoUsuario\":\"operador1\",\"nombreCompleto\":\"María José Peña «ñ»\",\"rol\":\"operador\",\"activo\":true,"
                    + "\"creadoEn\":\"2026-07-29T10:15:00\",\"actualizadoEn\":null,\"passwordHash\":\"$2a$11$SECRETO\",\"token\":\"eyJSECRETO\"},"
                    + "{\"Id\":20,\"CODIGOUSUARIO\":\"admin1\",\"NombreCompleto\":\"<b>Admin</b>\",\"rol\":null}]";
        };
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + data + ",\"errors\":null}");
    }

    private final java.util.List<String> peticionesLaboratorio = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** "<método> <ruta>[?<query cruda>]" recibidos en api/muestra-laboratorio*. */
    public java.util.List<String> peticionesLaboratorio() {
        return java.util.List.copyOf(peticionesLaboratorio);
    }

    /**
     * Laboratorio - Muestras (lecturas). Detalle 404 → "Muestra no encontrada"; registro-produccion con
     * np=ERROR → 400. Adjunto por id: 1 PDF, 2 PNG, 3 DOCX, 4 HTML declarado PDF, 404, 600 vacío,
     * 700 excesivo (> 10 MB), 800 corrupto, 900 nombre malicioso.
     */
    private void muestraLaboratorio(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        peticionesLaboratorio.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            responder(ex, 200, "{\"success\":true,\"message\":\"NO DEBERIA LLEGAR\",\"data\":null,\"errors\":null}");
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Filtro de fecha inválido"));
            return;
        }
        String ok = "{\"success\":true,\"message\":null,\"data\":";
        String fin = ",\"errors\":null}";
        String q = query == null ? "" : query;
        String base = "/api/muestra-laboratorio";
        switch (path) {
            case "/api/muestra-laboratorio" -> {
                responder(ex, 200, ok + "[{\"id\":501,\"np\":\"NP-100\",\"cliente\":\"Viña Ñandú «1»\",\"estado\":\"EnAnalisis\",\"q\":\"" + q
                        + "\",\"usuarioDelToken\":" + sub + "}]" + fin);
                return;
            }
            case "/api/muestra-laboratorio/catalogos" -> {
                responder(ex, 200, ok + "{\"maquinas\":[{\"id\":3,\"nombre\":\"Corrugadora ñ\"}],\"usuarioDelToken\":" + sub + "}" + fin);
                return;
            }
            case "/api/muestra-laboratorio/indicadores" -> {
                responder(ex, 200, ok + "{\"total\":12,\"enAnalisis\":4,\"usuarioDelToken\":" + sub + "}" + fin);
                return;
            }
            case "/api/muestra-laboratorio/metodos" -> {
                responder(ex, 200, ok + "[{\"id\":1,\"tipoEnsayo\":\"HUMEDAD\",\"nombre\":\"Estufa\",\"activo\":true}]" + fin);
                return;
            }
            case "/api/muestra-laboratorio/especificaciones" -> {
                responder(ex, 200, ok + "[{\"id\":2,\"tipoEnsayo\":\"ECT\",\"minimo\":5.5}]" + fin);
                return;
            }
            case "/api/muestra-laboratorio/bobina-historial" -> {
                responder(ex, 200, ok + "[{\"muestraId\":7,\"q\":\"" + q + "\"}]" + fin);
                return;
            }
            case "/api/muestra-laboratorio/registro-produccion" -> {
                if (q.contains("np=ERROR")) {
                    responder(ex, 400, fallo("No hay controles para esa NP"));
                } else {
                    responder(ex, 200, ok + "[{\"control\":\"Humedad\",\"q\":\"" + q + "\"}]" + fin);
                }
                return;
            }
            default -> { }
        }
        java.util.regex.Matcher adj = java.util.regex.Pattern.compile("^/api/muestra-laboratorio/adjunto/(\\d+)$").matcher(path);
        if (adj.matches()) {
            int id = Integer.parseInt(adj.group(1));
            String nombre = "adjunto_" + id;
            String mime = "application/pdf";
            String base64 = "";
            switch (id) {
                case 404 -> { responder(ex, 404, fallo("Adjunto no encontrado")); return; }
                case 1 -> { nombre = "informe ñ.pdf"; base64 = Base64.getEncoder().encodeToString(pdfMinimo(1)); }
                case 2 -> { nombre = "foto.png"; mime = "image/png"; base64 = Base64.getEncoder().encodeToString(pngMinimo()); }
                case 3 -> { nombre = "certificado.docx"; mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    base64 = Base64.getEncoder().encodeToString(new byte[] {0x50, 0x4b, 0x03, 0x04, 0x14, 0, 0, 0, 1, 2, 3}); }
                case 4 -> { nombre = "falso.pdf"; base64 = Base64.getEncoder().encodeToString("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)); }
                case 600 -> nombre = "vacio.pdf";
                case 700 -> base64 = "A".repeat(((10 * 1024 * 1024 + 2) / 3) * 4 + 4);
                case 800 -> base64 = "%%%no-base64%%%";
                case 900 -> { nombre = "..\\..\\evil<>.docx"; mime = "application/msword"; base64 = Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8)); }
                default -> base64 = Base64.getEncoder().encodeToString(pdfMinimo(id));
            }
            responder(ex, 200, ok + "{\"nombreArchivo\":\"" + nombre.replace("\\", "\\\\") + "\",\"tipoMime\":\"" + mime
                    + "\",\"contenidoBase64\":\"" + base64 + "\"}" + fin);
            return;
        }
        java.util.regex.Matcher det = java.util.regex.Pattern.compile("^" + base + "/(-?\\d+)$").matcher(path);
        if (det.matches()) {
            int id = Integer.parseInt(det.group(1));
            if (id == 404 || id <= 0) {
                responder(ex, 404, fallo("Muestra no encontrada"));
                return;
            }
            responder(ex, 200, ok + "{\"id\":" + id + ",\"np\":\"NP-100\",\"ensayos\":[],\"adjuntos\":[{\"id\":1,\"nombreArchivo\":\"informe ñ.pdf\"}],"
                    + "\"usuarioDelToken\":" + sub + "}" + fin);
            return;
        }
        responder(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
    }

    private final java.util.List<String> peticionesTalleres = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** "<método> <ruta>[?<query cruda>]" recibidos en api/talleres-externos*. */
    public java.util.List<String> peticionesTalleres() {
        return java.util.List.copyOf(peticionesTalleres);
    }

    /** Página de trabajos: 450 en total; items de la página pedida (máx. 200 por página, como la API ≤ 500). */
    public static String dataTalleresList(int sub, int page, int pageSize) {
        int total = 450;
        int tam = pageSize < 1 ? 50 : Math.min(pageSize, 500);
        int desde = Math.max(0, (page - 1) * tam);
        int hasta = Math.min(total, desde + tam);
        StringBuilder items = new StringBuilder();
        for (int i = desde; i < hasta; i++) {
            items.append(i > desde ? "," : "").append("{\"id\":").append(9000 + i).append(",\"nv\":\"NV-").append(i)
                    .append("\",\"producto\":\"Caja ñ «").append(i).append("»\",\"tallerExternoNombre\":\"Taller Uno\",\"version\":1}");
        }
        return "{\"items\":[" + items + "],\"totalCount\":" + total + ",\"page\":" + page + ",\"pageSize\":" + tam
                + ",\"usuarioDelToken\":" + sub + "}";
    }

    /** Talleres Externos (lecturas). historial de id 404 → []; id 500 → 500 de la API. */
    private void talleresExternos(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        peticionesTalleres.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            responder(ex, 200, "{\"success\":true,\"message\":\"NO DEBERIA LLEGAR\",\"data\":null,\"errors\":null}");
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Filtro de fecha inválido"));
            return;
        }
        String ok = "{\"success\":true,\"message\":null,\"data\":";
        String fin = ",\"errors\":null}";
        if (path.equals("/api/talleres-externos")) {
            java.util.regex.Matcher p = java.util.regex.Pattern.compile("page=(-?\\d+)&pageSize=(-?\\d+)").matcher(String.valueOf(query));
            int page = p.find() ? Integer.parseInt(p.group(1)) : 1;
            int pageSize = p.find(0) ? Integer.parseInt(p.group(2)) : 50;
            responder(ex, 200, ok + dataTalleresList(sub, page, pageSize) + fin);
            return;
        }
        if (path.equals("/api/talleres-externos/catalogos")) {
            responder(ex, 200, ok + "{\"talleres\":[{\"id\":1,\"nombre\":\"Taller Uno\"}],\"procesos\":[{\"id\":2,\"nombre\":\"Troquelado ñ\"}],"
                    + "\"usuarioDelToken\":" + sub + "}" + fin);
            return;
        }
        java.util.regex.Matcher h = java.util.regex.Pattern.compile("^/api/talleres-externos/(\\d+)/historial-liberaciones$").matcher(path);
        if (h.matches()) {
            long id = Long.parseLong(h.group(1));
            if (id == 500) {
                responder(ex, 500, "{\"title\":\"error interno\"}");
            } else if (id == 404) {
                responder(ex, 200, ok + "[]" + fin);
            } else {
                responder(ex, 200, ok + "[{\"folioFps\":\"F-1\",\"fechaLiberacion\":\"2026-09-20T10:00:00\",\"cantidad\":1500.5}]" + fin);
            }
            return;
        }
        responder(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
    }

    private Integer subDeBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String[] partes = authorization.substring(7).split("\\.");
        if (partes.length != 3) {
            return null;
        }
        try {
            JsonNode payload = mapper.readTree(new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8));
            int sub = Integer.parseInt(payload.path("sub").asString(""));
            return partes[2].equals(firmaDeToken(sub)) ? sub : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void agregar(Usuario u) {
        usuarios.put(u.codigo().toLowerCase(), u);
    }

    public int llamadas() {
        return llamadas.get();
    }

    public void expiracionToken(Supplier<Instant> exp) {
        this.expiracionToken = exp;
    }

    public void caida(boolean valor) {
        this.caida = valor;
    }

    /** Token que emitiría la API para un usuario (para comprobar que nunca llega al navegador/logs). */
    public static String firmaDeToken(int userId) {
        return "firma-secreta-del-token-" + userId;
    }

    /** Cabeceras de encuadre del último login recibido: "Content-Length|Transfer-Encoding". */
    public String ultimoEncuadre() {
        return ultimoEncuadre;
    }

    private volatile String ultimoEncuadre;

    private void login(HttpExchange ex) throws IOException {
        llamadas.incrementAndGet();
        ultimoEncuadre = ex.getRequestHeaders().getFirst("Content-Length") + "|"
                + ex.getRequestHeaders().getFirst("Transfer-Encoding");
        if (caida) {
            responder(ex, 500, "{\"title\":\"error interno\"}");
            return;
        }
        JsonNode body = mapper.readTree(ex.getRequestBody().readAllBytes());
        String codigo = body.path("codigoUsuario").asString("");
        String password = body.path("password").asString("");
        if (codigo.isBlank() || password.isBlank()) {
            responder(ex, 400, fallo("Código de usuario y contraseña son requeridos."));
            return;
        }
        Usuario u = usuarios.get(codigo.toLowerCase());
        if (u == null) {
            responder(ex, 401, fallo("Usuario no existe"));
            return;
        }
        if (!u.activo()) {
            responder(ex, 401, fallo("Usuario desactivado"));
            return;
        }
        if (!u.password().equals(password)) {
            responder(ex, 401, fallo("Contraseña incorrecta"));
            return;
        }
        String token = jwt(u);
        responder(ex, 200, "{\"success\":true,\"message\":\"Login correcto\",\"data\":{\"token\":\"" + token
                + "\",\"userId\":" + u.id() + ",\"codigoUsuario\":\"" + u.codigo() + "\",\"nombreCompleto\":\""
                + u.nombre() + "\",\"rol\":\"" + u.rol() + "\"},\"errors\":null}");
    }

    private String jwt(Usuario u) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(("{\"sub\":\"" + u.id() + "\",\"role\":\"" + u.rol() + "\",\"exp\":"
                + expiracionToken.get().getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + firmaDeToken(u.id());
    }

    private static String fallo(String mensaje) {
        return "{\"success\":false,\"message\":\"" + mensaje + "\",\"data\":null,\"errors\":null}";
    }

    private static void responder(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
