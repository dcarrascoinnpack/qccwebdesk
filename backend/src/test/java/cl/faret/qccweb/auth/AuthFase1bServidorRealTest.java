package cl.faret.qccweb.auth;

import static org.assertj.core.api.Assertions.assertThat;

import cl.faret.qccweb.QccWebGatewayApplication;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Fase 1b contra el servidor HTTP real (Tomcat, puerto aleatorio): flags reales de las cookies,
 * que las peticiones anónimas no crean sesión, cookie de sesión falsificada, fijación de sesión con
 * cookie real y expiración por inactividad del contenedor. API INNPACK simulada.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
            "qcc.web.auth.login-min-duration=0ms",
            "qcc.web.auth.session-idle-timeout=2s"
        })
class AuthFase1bServidorRealTest {

    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveReal#Test1";

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.innpack-api-base-url", API::baseUrl);
        registry.add("qcc.web.www-dir", WWW::toString);
        registry.add("qcc.web.manifest-file", () -> WWW.resolve("no-existe.json").toString());
    }

    @AfterAll
    static void cerrar() {
        API.close();
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void peticionAnonimaEntregaCsrfPeroNoCreaSesion() throws Exception {
        HttpResponse<String> res = get("/api/v1/auth/session", Map.of());
        assertThat(res.body()).contains("\"ok\":false");
        assertThat(setCookie(res, "QCC_SESSION")).isEmpty();

        String csrf = setCookie(res, "XSRF-TOKEN").orElseThrow();
        assertThat(csrf).contains("SameSite=Strict").contains("Secure").contains("Path=/");
        assertThat(csrf).doesNotContainIgnoringCase("HttpOnly"); // debe poder leerlo web-bridge.js
    }

    @Test
    void cookieDeSesionTieneFlagsSeguros() throws Exception {
        Navegador nav = new Navegador();
        HttpResponse<String> res = nav.login("operador1", PASS);
        assertThat(res.statusCode()).isEqualTo(200);

        String sesion = setCookie(res, "QCC_SESSION").orElseThrow();
        assertThat(sesion).contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/");
        assertThat(res.body()).doesNotContain(FakeInnpackApi.firmaDeToken(10));
        assertThat(res.uri().toString()).doesNotContainIgnoringCase("jsessionid");
    }

    @Test
    void cookieDeSesionFalsificadaNoDaAcceso() throws Exception {
        HttpResponse<String> res = get("/api/v1/auth/session", Map.of("QCC_SESSION", "0123456789ABCDEF0123456789ABCDEF"));
        assertThat(res.body()).contains("\"ok\":false");
    }

    @Test
    void loginConCookieDeSesionPreviaEmiteOtroId() throws Exception {
        Navegador nav = new Navegador();
        nav.login("operador1", PASS);
        String primera = nav.cookies.get("QCC_SESSION");

        nav.login("operador1", PASS);
        String segunda = nav.cookies.get("QCC_SESSION");
        assertThat(segunda).isNotEqualTo(primera);

        // La cookie anterior ya no sirve.
        assertThat(get("/api/v1/auth/session", Map.of("QCC_SESSION", primera)).body()).contains("\"ok\":false");
        assertThat(nav.get("/api/v1/auth/session").body()).contains("\"ok\":true");
    }

    @Test
    void sesionExpiraPorInactividad() throws Exception {
        Navegador nav = new Navegador();
        nav.login("operador1", PASS);
        assertThat(nav.get("/api/v1/auth/session").body()).contains("\"ok\":true");

        Thread.sleep(3500);
        assertThat(nav.get("/api/v1/auth/session").body()).contains("\"ok\":false");
    }

    /** Cliente mínimo que guarda cookies y reenvía el token CSRF como lo hace web-bridge.js. */
    private final class Navegador {
        final Map<String, String> cookies = new LinkedHashMap<>();

        HttpResponse<String> get(String path) throws Exception {
            HttpResponse<String> res = AuthFase1bServidorRealTest.this.get(path, cookies);
            guardar(res);
            return res;
        }

        HttpResponse<String> login(String usuario, String password) throws Exception {
            get("/api/v1/auth/session"); // obtiene XSRF-TOKEN
            HttpRequest req = HttpRequest.newBuilder(url("/api/v1/auth/login"))
                    .header("Content-Type", "application/json")
                    .header("Cookie", cabeceraCookie(cookies))
                    .header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + password + "\"}"))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            guardar(res);
            return res;
        }

        private void guardar(HttpResponse<String> res) {
            for (String c : res.headers().allValues("Set-Cookie")) {
                String par = c.split(";", 2)[0];
                String[] kv = par.split("=", 2);
                if (kv.length == 2 && !kv[1].isEmpty()) {
                    cookies.put(kv[0], kv[1]);
                } else if (kv.length >= 1) {
                    cookies.remove(kv[0]);
                }
            }
        }
    }

    private HttpResponse<String> get(String path, Map<String, String> cookies) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(url(path)).GET();
        if (!cookies.isEmpty()) {
            b.header("Cookie", cabeceraCookie(cookies));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cabeceraCookie(Map<String, String> cookies) {
        return String.join("; ", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
    }

    private static Optional<String> setCookie(HttpResponse<?> res, String nombre) {
        List<String> todas = res.headers().allValues("Set-Cookie");
        return todas.stream().filter(c -> c.startsWith(nombre + "=")).findFirst();
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-auth-real");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
