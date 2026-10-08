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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Fase 6a contra el servidor HTTP real (Tomcat, puerto aleatorio) — lo que MockMvc no reproduce (su repositorio CSRF de
 * prueba): la respuesta de login y de logout EMITE el token CSRF nuevo, así la primera petición POST siguiente
 * (permisos.mios justo tras el login, como Photino; login inmediato tras un logout) responde 200 y no 403. Antes solo se
 * borraba la cookie y esa primera petición fallaba (el flujo INNPACK lo disimulaba con una segunda llamada; el FARET no).
 * APIs INNPACK y FARET simuladas.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
            "qcc.web.auth.login-min-duration=0ms"
        })
class AuthFase6aServidorRealTest {

    private static final FakeInnpackApi INNPACK = new FakeInnpackApi(Clock.systemUTC());
    private static final FakeFaretApi FARET = new FakeFaretApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveReal#Faret6a";

    static {
        INNPACK.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
        FARET.agregar(new FakeFaretApi.Usuario(20, "tito", "tito@faret.cl", PASS, "Tito TI", List.of("ADMIN_TI")));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.innpack-api-base-url", INNPACK::baseUrl);
        registry.add("qcc.web.auth.faret-api-base-url", FARET::baseUrl);
        registry.add("qcc.web.www-dir", WWW::toString);
        registry.add("qcc.web.manifest-file", () -> WWW.resolve("no-existe.json").toString());
    }

    @AfterAll
    static void cerrar() {
        INNPACK.close();
        FARET.close();
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void loginFaretEmiteCsrfNuevoYLaPrimeraPeticionSiguienteFunciona() throws Exception {
        Navegador nav = new Navegador();
        String csrfAntes = nav.get("/api/v1/auth/session") != null ? nav.cookies.get("XSRF-TOKEN") : null;
        HttpResponse<String> login = nav.post("/api/v1/auth/faret/login",
                "{\"identificador\":\"tito\",\"password\":\"" + PASS + "\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("\"username\":\"Tito TI\"").contains("\"role\":\"ADMIN_TI\"")
                .doesNotContain(FakeFaretApi.firmaDeToken(20));
        String csrfDespues = nav.cookies.get("XSRF-TOKEN");
        assertThat(csrfDespues).isNotBlank().isNotEqualTo(csrfAntes);
        assertThat(login.headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("XSRF-TOKEN=" + csrfDespues));

        // Primer POST tras el login (lo que hace App.cargarPermisos en Photino): 200 a la primera.
        HttpResponse<String> mios = nav.post("/api/v1/bridge", "{\"action\":\"permisos.mios\",\"_modulo\":\"faret-login\"}");
        assertThat(mios.statusCode()).isEqualTo(200);
        assertThat(mios.body()).contains("\"faret\":\"EDITAR\"").contains("\"faret-usuarios\":\"EDITAR\"").doesNotContain("\"inicio\"");

        // El token anterior al login ya no sirve (rotación real, protección contra fijación).
        if (csrfAntes != null) {
            HttpResponse<String> viejo = nav.post("/api/v1/bridge", "{\"action\":\"permisos.mios\",\"_modulo\":\"faret-login\"}", csrfAntes);
            assertThat(viejo.statusCode()).isEqualTo(403);
        }
    }

    @Test
    void logoutEmiteCsrfNuevoYElLoginInmediatoFuncionaEnAmbasEmpresas() throws Exception {
        Navegador nav = new Navegador();
        nav.get("/api/v1/auth/session");
        assertThat(nav.post("/api/v1/auth/login", "{\"CodigoUsuario\":\"operador1\",\"Password\":\"" + PASS + "\"}").statusCode()).isEqualTo(200);
        assertThat(nav.post("/api/v1/bridge", "{\"action\":\"permisos.mios\",\"_modulo\":\"auth\"}").statusCode()).isEqualTo(200);

        HttpResponse<String> logout = nav.post("/api/v1/auth/logout", "{}");
        assertThat(logout.statusCode()).isEqualTo(200);
        assertThat(nav.cookies.get("XSRF-TOKEN")).isNotBlank();
        assertThat(nav.get("/api/v1/auth/session").body()).contains("\"ok\":false");

        // Misma pestaña, sin recargar: login FARET inmediato con el token que dejó el logout.
        HttpResponse<String> faret = nav.post("/api/v1/auth/faret/login", "{\"identificador\":\"tito@faret.cl\",\"password\":\"" + PASS + "\"}");
        assertThat(faret.statusCode()).isEqualTo(200);
        assertThat(nav.get("/api/v1/auth/session").body()).contains("\"Empresa\":\"FARET\"");
        assertThat(nav.post("/api/v1/auth/logout", "{}").statusCode()).isEqualTo(200);
        assertThat(nav.post("/api/v1/auth/login", "{\"CodigoUsuario\":\"operador1\",\"Password\":\"" + PASS + "\"}").statusCode()).isEqualTo(200);
        assertThat(nav.get("/api/v1/auth/session").body()).contains("\"Empresa\":\"INNPACK\"");
    }

    /** Cliente mínimo que guarda cookies y reenvía el token CSRF como lo hace web-bridge.js. */
    private final class Navegador {
        final Map<String, String> cookies = new LinkedHashMap<>();

        HttpResponse<String> get(String path) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(url(path)).GET();
            if (!cookies.isEmpty()) {
                b.header("Cookie", cabeceraCookie());
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            guardar(res);
            return res;
        }

        HttpResponse<String> post(String path, String cuerpo) throws Exception {
            return post(path, cuerpo, cookies.get("XSRF-TOKEN"));
        }

        HttpResponse<String> post(String path, String cuerpo, String csrf) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(url(path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo));
            if (!cookies.isEmpty()) {
                b.header("Cookie", cabeceraCookie());
            }
            if (csrf != null) {
                b.header("X-XSRF-TOKEN", csrf);
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
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

        private String cabeceraCookie() {
            return String.join("; ", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        }
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-6a-real");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
