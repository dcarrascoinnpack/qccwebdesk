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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * API FARET (QualityControlFaret.Api) simulada para tests — NUNCA credenciales reales ni la API de producción.
 * Replica AuthController/AuthService reales: POST api/Auth/login acepta `identificador` (correo o username) o
 * `correo`; 400 si faltan campos; 401 con mensaje si las credenciales no sirven; 200 con
 * ApiResponse { success, message, data: { token, expiresAt, usuario: { id, nombre, correo, username, roles[] } } }
 * (JWT con sub y exp). GET api/auth/mis-permisos [Authorize] con data = [{modulo, nivel}].
 */
public final class FakeFaretApi implements AutoCloseable {

    public record Usuario(int id, String username, String correo, String password, String nombre, List<String> roles) {}

    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Usuario> usuarios = new ConcurrentHashMap<>();
    private final Map<Integer, String> permisosPorUsuario = new ConcurrentHashMap<>();
    private final java.util.Set<Integer> misPermisosCaidos = ConcurrentHashMap.newKeySet();
    private final List<Integer> misPermisosConsultados = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final List<String> cuerposLogin = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final AtomicInteger llamadas = new AtomicInteger();
    private volatile Supplier<Instant> expiracionToken;
    private volatile boolean caida;

    public FakeFaretApi(Clock clock) {
        this.expiracionToken = () -> clock.instant().plusSeconds(8 * 3600);
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/api/Auth/login", this::login);
        server.createContext("/api/auth/mis-permisos", this::misPermisos);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void agregar(Usuario u) {
        usuarios.put(u.username().toLowerCase(), u);
        if (u.correo() != null) {
            usuarios.put(u.correo().toLowerCase(), u);
        }
    }

    public int llamadas() {
        return llamadas.get();
    }

    public void caida(boolean valor) {
        this.caida = valor;
    }

    public void expiracionToken(Supplier<Instant> exp) {
        this.expiracionToken = exp;
    }

    /** Permisos personalizados de un usuario (arreglo JSON crudo de data). */
    public void permisos(int userId, String dataJson) {
        permisosPorUsuario.put(userId, dataJson);
    }

    public void misPermisosCaido(int userId) {
        misPermisosCaidos.add(userId);
    }

    public void misPermisosRestablecer() {
        misPermisosCaidos.clear();
    }

    public List<Integer> misPermisosConsultados() {
        return List.copyOf(misPermisosConsultados);
    }

    /** Cuerpos JSON recibidos en login (para verificar el contrato identificador/password). */
    public List<String> cuerposLogin() {
        return List.copyOf(cuerposLogin);
    }

    public static String firmaDeToken(int userId) {
        return "firma-faret-del-token-" + userId;
    }

    private void login(HttpExchange ex) throws IOException {
        llamadas.incrementAndGet();
        if (caida) {
            responder(ex, 500, "{\"title\":\"error interno\"}");
            return;
        }
        String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        cuerposLogin.add(cuerpo);
        JsonNode body = mapper.readTree(cuerpo);
        String identificador = body.path("identificador").asString("");
        if (identificador.isBlank()) {
            identificador = body.path("correo").asString("");
        }
        String password = body.path("password").asString("");
        if (identificador.isBlank() || password.isBlank()) {
            responder(ex, 400, fallo("Identificador (correo o username) y password son requeridos."));
            return;
        }
        Usuario u = usuarios.get(identificador.toLowerCase());
        if (u == null || !u.password().equals(password)) {
            responder(ex, 401, fallo("Credenciales inválidas."));
            return;
        }
        String roles = u.roles().stream().map(r -> "\"" + r + "\"").reduce((a, b) -> a + "," + b).orElse("");
        responder(ex, 200, "{\"success\":true,\"message\":\"Login exitoso.\",\"data\":{\"token\":\"" + jwt(u)
                + "\",\"expiresAt\":\"" + expiracionToken.get() + "\",\"usuario\":{\"id\":" + u.id() + ",\"nombre\":\"" + u.nombre()
                + "\",\"correo\":" + (u.correo() == null ? "null" : "\"" + u.correo() + "\"") + ",\"username\":\"" + u.username()
                + "\",\"roles\":[" + roles + "]}},\"errors\":null}");
    }

    private void misPermisos(HttpExchange ex) throws IOException {
        Integer sub = subDeBearer(ex.getRequestHeaders().getFirst("Authorization"));
        if (sub == null) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        misPermisosConsultados.add(sub);
        if (misPermisosCaidos.contains(sub)) {
            responder(ex, 500, "{\"type\":\"about:blank\",\"title\":\"Internal Server Error\",\"status\":500}");
            return;
        }
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + permisosPorUsuario.getOrDefault(sub, "[]")
                + ",\"errors\":null}");
    }

    private String jwt(Usuario u) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(("{\"sub\":\"" + u.id() + "\",\"exp\":" + expiracionToken.get().getEpochSecond() + "}")
                .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + firmaDeToken(u.id());
    }

    private Integer subDeBearer(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        String[] partes = auth.substring(7).split("\\.");
        if (partes.length != 3) {
            return null;
        }
        try {
            JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(partes[1]));
            int sub = Integer.parseInt(payload.path("sub").asString(""));
            return partes[2].equals(firmaDeToken(sub)) ? sub : null;
        } catch (RuntimeException e) {
            return null;
        }
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
