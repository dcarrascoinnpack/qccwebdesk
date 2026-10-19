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

    /** Escritura recibida (F0): método, "ruta?query" crudo, cuerpo, Content-Type y usuario (sub del JWT; -1 = sin Bearer válido). */
    public record Escritura(String metodo, String uri, String cuerpo, String contentType, int sub) {}

    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Usuario> usuarios = new ConcurrentHashMap<>();
    private final Map<Integer, String> permisosPorUsuario = new ConcurrentHashMap<>();
    private final java.util.Set<Integer> misPermisosCaidos = ConcurrentHashMap.newKeySet();
    private final List<Integer> misPermisosConsultados = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final List<String> cuerposLogin = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final AtomicInteger llamadas = new AtomicInteger();
    private final List<String> lecturas = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final List<Integer> lecturasDeUsuario = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final Map<String, String[]> respuestasLectura = new ConcurrentHashMap<>();
    private final List<Escritura> escrituras = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final Map<String, String[]> respuestasEscritura = new ConcurrentHashMap<>();
    private volatile Supplier<Instant> expiracionToken;
    private volatile boolean caida;
    private volatile boolean lecturasRechazadas;

    public FakeFaretApi(Clock clock) {
        this.expiracionToken = () -> clock.instant().plusSeconds(8 * 3600);
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/api/Auth/login", this::login);
        server.createContext("/api/Auth/cambiar-password", this::cambiarPassword);
        server.createContext("/api/auth/mis-permisos", this::misPermisos);
        // 6b: lecturas de QualityControlFaret.Api que EXIGEN el Bearer del usuario (sin él o con firma mala: 401).
        server.createContext("/api/importaciones/pnc", this::lectura);
        server.createContext("/api/talleres-externos/resumen", this::lectura);
        // Fase 6c-A: catálogos y catálogos PNC (también con Bearer).
        server.createContext("/api/catalogos", this::lectura);
        server.createContext("/api/pnc-catalogos", this::lectura);
        // Fase 6c-C: talleres externos (list/catalogos) e importaciones (historial); pnc y resumen ya tienen contexto propio.
        server.createContext("/api/talleres-externos", this::lectura);
        server.createContext("/api/importaciones", this::lectura);
        // Fase 6c-F: Gestion de Usuarios FARET (list y permisos), tambien con Bearer.
        server.createContext("/api/usuarios", this::lectura);
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

    /** "GET /ruta?query" crudo de cada lectura 6b recibida (importaciones/pnc, talleres-externos/resumen). */
    public List<String> lecturas() {
        return List.copyOf(lecturas);
    }

    /** Usuario (sub del JWT) de cada lectura autenticada, en el mismo orden que {@link #lecturas()}. */
    public List<Integer> lecturasDeUsuario() {
        return List.copyOf(lecturasDeUsuario);
    }

    /** Respuesta fija para una ruta de lectura (sin query), p. ej. "/api/importaciones/pnc". */
    public void respuestaLectura(String ruta, int status, String body) {
        respuestasLectura.put(ruta, new String[] {String.valueOf(status), body});
    }

    /** Simula un token revocado/vencido en la API: toda lectura 6b responde 401 aunque el Bearer sea válido. */
    public void rechazarLecturas(boolean valor) {
        this.lecturasRechazadas = valor;
    }

    /** Escrituras (POST/PUT/PATCH/DELETE) recibidas en los contextos de lectura, en orden de llegada. */
    public List<Escritura> escrituras() {
        return List.copyOf(escrituras);
    }

    /** Respuesta fija para una escritura: clave "METODO /ruta" (sin query), p. ej. "POST /api/pnc-catalogos/clientes". */
    public void respuestaEscritura(String metodoYRuta, int status, String body) {
        respuestasEscritura.put(metodoYRuta, new String[] {String.valueOf(status), body});
    }

    public void limpiarLecturas() {
        escrituras.clear();
        respuestasEscritura.clear();
        lecturasRechazadas = false;
        lecturas.clear();
        lecturasDeUsuario.clear();
        respuestasLectura.clear();
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
        if (u == null || !passwordDe(u).equals(password)) {
            responder(ex, 401, fallo("Credenciales inválidas."));
            return;
        }
        String roles = u.roles().stream().map(r -> "\"" + r + "\"").reduce((a, b) -> a + "," + b).orElse("");
        responder(ex, 200, "{\"success\":true,\"message\":\"Login exitoso.\",\"data\":{\"token\":\"" + jwt(u)
                + "\",\"expiresAt\":\"" + expiracionToken.get() + "\",\"usuario\":{\"id\":" + u.id() + ",\"nombre\":\"" + u.nombre()
                + "\",\"correo\":" + (u.correo() == null ? "null" : "\"" + u.correo() + "\"") + ",\"username\":\"" + u.username()
                + "\",\"roles\":[" + roles + "]}" + politicaPorUsuario.getOrDefault(u.id(), "") + "},\"errors\":null}");
    }

    // ---- Política de contraseñas (Photino 1.8.23): campos extra del login y cambio de clave propio ----

    private final Map<Integer, String> politicaPorUsuario = new ConcurrentHashMap<>();
    private final Map<Integer, String> passwordsNuevas = new ConcurrentHashMap<>();
    private final List<String> cambiosRecibidos = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final AtomicInteger generacionToken = new AtomicInteger();
    private volatile int estadoCambioForzado;

    /** Campos extra de data en la respuesta de login del usuario, como fragmento JSON que empieza con coma. */
    public void politicaLogin(int userId, String fragmentoJson) {
        politicaPorUsuario.put(userId, fragmentoJson);
    }

    /** "Bearer ...|cuerpo" de cada POST api/Auth/cambiar-password recibido. */
    public List<String> cambiosRecibidos() {
        return List.copyOf(cambiosRecibidos);
    }

    /** Si es distinto de 0, cambiar-password responde ese estado con un cuerpo vacío (p. ej. 500). */
    public void estadoCambioForzado(int status) {
        this.estadoCambioForzado = status;
    }

    public void restablecerPolitica() {
        politicaPorUsuario.clear();
        passwordsNuevas.clear();
        cambiosRecibidos.clear();
        estadoCambioForzado = 0;
    }

    private String passwordDe(Usuario u) {
        return passwordsNuevas.getOrDefault(u.id(), u.password());
    }

    private void cambiarPassword(HttpExchange ex) throws IOException {
        Integer sub = subDeBearer(ex.getRequestHeaders().getFirst("Authorization"));
        byte[] crudo = ex.getRequestBody().readAllBytes();
        cambiosRecibidos.add(ex.getRequestHeaders().getFirst("Authorization") + "|" + new String(crudo, StandardCharsets.UTF_8));
        if (sub == null) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (estadoCambioForzado != 0) {
            responder(ex, estadoCambioForzado, "{\"title\":\"error interno\"}");
            return;
        }
        JsonNode body = mapper.readTree(crudo);
        String actual = body.path("passwordActual").asString("");
        String nueva = body.path("passwordNueva").asString("");
        Usuario u = usuarios.values().stream().filter(x -> x.id() == sub).findFirst().orElse(null);
        if (u == null || actual.isBlank() || nueva.isBlank()) {
            responder(ex, 400, fallo("La contraseña actual y la nueva son requeridas."));
            return;
        }
        if (!passwordDe(u).equals(actual)) {
            responder(ex, 400, fallo("La contraseña actual es incorrecta."));
            return;
        }
        if (nueva.equals(actual)) {
            responder(ex, 400, fallo("La contraseña nueva debe ser distinta de la actual."));
            return;
        }
        if (nueva.length() < 8) {
            responder(ex, 400, fallo("La contraseña nueva debe tener al menos 8 caracteres."));
            return;
        }
        passwordsNuevas.put(u.id(), nueva);
        generacionToken.incrementAndGet();
        String roles = u.roles().stream().map(r -> "\"" + r + "\"").reduce((a, b) -> a + "," + b).orElse("");
        responder(ex, 200, "{\"success\":true,\"message\":\"Contraseña actualizada.\",\"data\":{\"token\":\"" + jwt(u)
                + "\",\"expiresAt\":\"" + expiracionToken.get() + "\",\"usuario\":{\"id\":" + u.id() + ",\"nombre\":\"" + u.nombre()
                + "\",\"username\":\"" + u.username() + "\",\"roles\":[" + roles + "]},\"debeCambiarPassword\":false,"
                + "\"motivoCambioPassword\":null,\"diasParaVencer\":60,\"passwordVenceEl\":\"2026-12-18T00:00:00\"},\"errors\":null}");
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

    private void lectura(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            escritura(ex);
            return;
        }
        Integer sub = subDeBearer(ex.getRequestHeaders().getFirst("Authorization"));
        String ruta = ex.getRequestURI().getRawPath();
        String uri = ruta + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
        lecturas.add(ex.getRequestMethod() + " " + uri);
        if (sub == null || lecturasRechazadas) {
            lecturasDeUsuario.add(-1);
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        lecturasDeUsuario.add(sub);
        String[] fija = respuestasLectura.get(ruta);
        if (fija != null) {
            responder(ex, Integer.parseInt(fija[0]), fija[1]);
            return;
        }
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"ruta\":\"" + ruta + "\",\"usuario\":" + sub
                + "},\"errors\":null}");
    }

    /**
     * Escrituras de QualityControlFaret.Api: exigen el Bearer del usuario (sin él o con firma mala: 401). Respuesta fija por
     * "METODO /ruta" o, por defecto, 201 con ApiResponse y data = el cuerpo recibido + id 901 + activo (como CatalogoItemDto).
     */
    private void escritura(HttpExchange ex) throws IOException {
        Integer sub = subDeBearer(ex.getRequestHeaders().getFirst("Authorization"));
        String ruta = ex.getRequestURI().getRawPath();
        String uri = ruta + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
        String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        escrituras.add(new Escritura(ex.getRequestMethod(), uri, cuerpo, ex.getRequestHeaders().getFirst("Content-Type"),
                sub == null ? -1 : sub));
        if (sub == null || lecturasRechazadas) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        String[] fija = respuestasEscritura.get(ex.getRequestMethod() + " " + ruta);
        if (fija != null) {
            responder(ex, Integer.parseInt(fija[0]), fija[1]);
            return;
        }
        String extra = "";
        try {
            JsonNode b = mapper.readTree(cuerpo);
            if (b.isObject()) {
                StringBuilder sb = new StringBuilder();
                for (String k : b.propertyNames()) {
                    sb.append(",\"").append(k).append("\":").append(b.get(k).toString());
                }
                extra = sb.toString();
            }
        } catch (RuntimeException e) {
            // cuerpo no JSON: se responde sin eco
        }
        responder(ex, 201, "{\"success\":true,\"message\":\"Creado.\",\"data\":{\"id\":901" + extra + ",\"activo\":true},\"errors\":null}");
    }

    private String jwt(Usuario u) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(("{\"sub\":\"" + u.id() + "\",\"gen\":" + generacionToken.get() + ",\"exp\":"
                + expiracionToken.get().getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
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
        if (json == null) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
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
