package cl.faret.qccweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Igual que GatewayFase1aTest pero contra el servidor HTTP real (puerto aleatorio): valida el
 * comportamiento que MockMvc no reproduce, como el reenvío de errores a /error.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME)
class GatewayFase1aServidorRealTest {

    private static final Path FIXTURE = crearFixture();

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.www-dir", () -> FIXTURE.toString());
        registry.add("qcc.web.manifest-file", () -> FIXTURE.resolve("no-existe.json").toString());
    }

    @Test
    void postSinCsrfConservaEl403() throws Exception {
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(url("/api/v1/bridge"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"inicio.getDashboard\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(403);
    }

    @Test
    void rutaNoPermitidaResponde401() throws Exception {
        assertThat(get("/api/v1/cualquier-cosa").statusCode()).isEqualTo(401);
    }

    @Test
    void headDelIndexEsPublico() throws Exception {
        HttpResponse<Void> res = http.send(
                HttpRequest.newBuilder(url("/index.html")).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(res.statusCode()).isEqualTo(200);
    }

    @Test
    void versionFuncionaAunqueFalteElManifest() throws Exception {
        HttpResponse<String> res = get("/version");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("NO_EVALUADO");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(url(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static Path crearFixture() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-real");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
