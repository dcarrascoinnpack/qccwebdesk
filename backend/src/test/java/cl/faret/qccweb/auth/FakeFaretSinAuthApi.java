package cl.faret.qccweb.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API FARET SIN autenticación simulada (MejoraContinua o Calidad) para tests — NUNCA las APIs reales. Localhost, puerto
 * efímero. Registra cada petición ("GET /ruta?query" crudo) y el valor del header Authorization (null si no llegó), para
 * verificar que el gateway NUNCA manda Bearer a estas APIs (Photino tampoco). Respuestas configurables por ruta exacta.
 *
 * @see FakeFaretApi para QualityControlFaret.Api (exige Bearer)
 */
public final class FakeFaretSinAuthApi implements AutoCloseable {

    private record Respuesta(int status, String body) {}

    private final HttpServer server;
    private final String prefijo;
    private final Map<String, Respuesta> respuestas = new ConcurrentHashMap<>();
    private final List<String> peticiones = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final List<String> authorizations = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** @param prefijo parte fija de la ruta que el gateway recibe en su base URL (p. ej. "/mejora-continua" o "/calidad/api") */
    public FakeFaretSinAuthApi(String prefijo) {
        this.prefijo = prefijo;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::atender);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
    }

    /** Base URL para el gateway: servidor + prefijo (igual que https://api.faret.cl/calidad/api). */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + prefijo;
    }

    /** Respuesta para la ruta exacta (sin prefijo ni query), p. ej. "/api/no-conformidades". */
    public void responder(String ruta, int status, String body) {
        respuestas.put(ruta, new Respuesta(status, body));
    }

    public void limpiar() {
        respuestas.clear();
        peticiones.clear();
        authorizations.clear();
    }

    /** "GET /ruta?query" de cada petición recibida, sin el prefijo. */
    public List<String> peticiones() {
        return List.copyOf(peticiones);
    }

    /** Header Authorization de cada petición (null = no llegó). */
    public List<String> authorizations() {
        synchronized (authorizations) {
            return new java.util.ArrayList<>(authorizations);
        }
    }

    private void atender(HttpExchange ex) throws IOException {
        String uri = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
        authorizations.add(ex.getRequestHeaders().getFirst("Authorization"));
        if (!ex.getRequestURI().getRawPath().startsWith(prefijo + "/")) {
            peticiones.add(ex.getRequestMethod() + " " + uri);
            enviar(ex, 404, "{\"title\":\"Not Found\"}");
            return;
        }
        String relativa = uri.substring(prefijo.length());
        peticiones.add(ex.getRequestMethod() + " " + relativa);
        String ruta = ex.getRequestURI().getRawPath().substring(prefijo.length());
        Respuesta r = respuestas.get(ruta);
        if (r == null) {
            enviar(ex, 404, "{\"title\":\"Not Found\",\"status\":404}");
            return;
        }
        enviar(ex, r.status(), r.body());
    }

    private static void enviar(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
