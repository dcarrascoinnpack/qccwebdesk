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
