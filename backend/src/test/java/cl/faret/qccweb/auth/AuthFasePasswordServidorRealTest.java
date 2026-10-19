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
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Politica de contrasenas (Photino 1.8.23) contra el servidor HTTP real: lo que MockMvc no reproduce es la rotacion REAL del
 * token CSRF y del id de sesion al cambiar la clave. Tras POST /api/v1/auth/cambiar-password la respuesta emite el CSRF nuevo
 * y la primera peticion siguiente (el bridge, ya sin el bloqueo) responde 200 y no 403; el token anterior ya no sirve.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
            "qcc.web.auth.login-min-duration=0ms"
        })
class AuthFasePasswordServidorRealTest {

    private static final FakeInnpackApi INNPACK = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveReal#Pwd7a";
    private static final String NUEVA = "OtraClave#Real7a";

    static {
        INNPACK.agregar(new FakeInnpackApi.Usuario(11, "temporal", PASS, "Usuario Temporal", "admin", true));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.innpack-api-base-url", INNPACK::baseUrl);
        registry.add("qcc.web.www-dir", WWW::toString);
        registry.add("qcc.web.manifest-file", () -> WWW.resolve("no-existe.json").toString());
    }

    @AfterAll
    static void cerrar() {
        INNPACK.close();
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void cambioForzadoRotaCsrfYSesionYLaPrimeraPeticionSiguienteFunciona() throws Exception {
        INNPACK.restablecerPolitica();
        INNPACK.politicaLogin("temporal", ",\"debeCambiarPassword\":true,\"motivoCambioPassword\":\"Tu contrasena es temporal.\"");
        Navegador nav = new Navegador();
        nav.get("/api/v1/auth/session");
        HttpResponse<String> login = nav.post("/api/v1/auth/login", "{\"CodigoUsuario\":\"temporal\",\"Password\":\"" + PASS + "\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("\"DebeCambiarPassword\":true");

        // Bloqueado hasta cambiar la clave; permisos.mios sigue disponible.
        assertThat(nav.post("/api/v1/bridge", "{\"action\":\"permisos.mios\",\"_modulo\":\"cambiar-password\"}").statusCode()).isEqualTo(200);
        HttpResponse<String> bloqueado = nav.post("/api/v1/bridge", "{\"action\":\"inicio.getDashboard\",\"_modulo\":\"inicio\"}");
        assertThat(bloqueado.statusCode()).isEqualTo(403);
        assertThat(bloqueado.body()).contains("Debes cambiar tu contraseña para continuar.");

        String csrfAntes = nav.cookies.get("XSRF-TOKEN");
        String sesionAntes = nav.cookies.get("JSESSIONID");
        HttpResponse<String> cambio = nav.post("/api/v1/auth/cambiar-password",
                "{\"passwordActual\":\"" + PASS + "\",\"passwordNueva\":\"" + NUEVA + "\"}");
        assertThat(cambio.statusCode()).isEqualTo(200);
        assertThat(cambio.body()).doesNotContain(NUEVA).doesNotContain(PASS);
        String csrfDespues = nav.cookies.get("XSRF-TOKEN");
        assertThat(csrfDespues).isNotBlank().isNotEqualTo(csrfAntes);
        assertThat(cambio.headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("XSRF-TOKEN=" + csrfDespues));
        if (sesionAntes != null) {
            assertThat(nav.cookies.get("JSESSIONID")).isNotBlank().isNotEqualTo(sesionAntes);
        }

        // Primera peticion tras el cambio, con el CSRF nuevo: el bloqueo ya no aplica (la API responde algo, nunca 403 del gateway).
        HttpResponse<String> libre = nav.post("/api/v1/bridge", "{\"action\":\"inicio.getDashboard\",\"_modulo\":\"inicio\"}");
        assertThat(libre.statusCode()).isEqualTo(200);
        assertThat(libre.body()).doesNotContain("Debes cambiar tu contraseña");

        // El CSRF anterior al cambio ya no sirve.
        assertThat(nav.post("/api/v1/bridge", "{\"action\":\"permisos.mios\",\"_modulo\":\"inicio\"}", csrfAntes).statusCode()).isEqualTo(403);
        assertThat(nav.get("/api/v1/auth/session").body()).contains("\"ok\":true");
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
            Path www = Files.createTempDirectory("qcc-web-fixture-pwd-real");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
