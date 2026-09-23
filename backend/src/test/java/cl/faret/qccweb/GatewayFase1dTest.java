package cl.faret.qccweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cl.faret.qccweb.auth.FakeInnpackApi;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * Fase 1d contra el servidor real: /version con el resultado del contract check y límite de
 * tamaño del cuerpo en /api/** (413 / 411). API INNPACK simulada.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
            "qcc.web.auth.login-min-duration=0ms",
            "qcc.web.api.max-body-bytes=1024"
        })
class GatewayFase1dTest {

    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path DIR = crearFixture();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.innpack-api-base-url", API::baseUrl);
        registry.add("qcc.web.www-dir", () -> DIR.resolve("www").toString());
        registry.add("qcc.web.manifest-file", () -> DIR.resolve("web-manifest.json").toString());
    }

    @AfterAll
    static void cerrar() {
        API.close();
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void versionMuestraLaCompatibilidadDelContrato() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(url("/version")).build(), HttpResponse.BodyHandlers.ofString());
        var c = mapper.readTree(res.body()).get("compatibilidad");
        assertThat(c.get("texto").asString()).isEqualTo("Photino 1.8.12 · Web compatible 1/236");
        assertThat(c.get("estado").asString()).isEqualTo("PARCIAL");
        assertThat(c.get("compatibles").asInt()).isEqualTo(1);
        assertThat(c.get("accionesFrontend").asInt()).isEqualTo(236);
        assertThat(c.get("revisar").asInt()).isZero();
    }

    @Test
    void cuerpoMayorAlLimiteRecibe413SinLlegarAlBridge() throws Exception {
        String grande = "{\"action\":\"inicio.getDashboard\",\"relleno\":\"" + "x".repeat(2000) + "\"}";
        HttpResponse<String> res = post("/api/v1/bridge", HttpRequest.BodyPublishers.ofString(grande));
        assertThat(res.statusCode()).isEqualTo(413);
        assertThat(res.body()).contains("\"ok\":false");

        HttpResponse<String> login = post("/api/v1/auth/login", HttpRequest.BodyPublishers.ofString(grande));
        assertThat(login.statusCode()).isEqualTo(413);
    }

    @Test
    void cuerpoChunkedSinContentLengthRecibe411() throws Exception {
        HttpResponse<String> res = post("/api/v1/bridge",
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream("{\"action\":\"inicio.getDashboard\"}".getBytes())));
        assertThat(res.statusCode()).isEqualTo(411);
    }

    @Test
    void cuerpoDentroDelLimiteSigueAlControlNormal() throws Exception {
        // Pasa el filtro de tamaño y lo frena Spring Security (sin CSRF → 403), como antes.
        HttpResponse<String> res = post("/api/v1/bridge", HttpRequest.BodyPublishers.ofString("{\"action\":\"inicio.getDashboard\"}"));
        assertThat(res.statusCode()).isEqualTo(403);
    }

    @Test
    void getYArchivosEstaticosNoSeVenAfectados() throws Exception {
        assertThat(http.send(HttpRequest.newBuilder(url("/index.html")).build(), HttpResponse.BodyHandlers.discarding())
                .statusCode()).isEqualTo(200);
    }

    @Test
    void guardiaDeContratoBloqueaSoloSiElManifestEsBloqueante() {
        var bloqueante = mapper.readTree("{\"bloqueante\":true,\"texto\":\"Photino 1.8.13 · Web compatible 0/236\"}");
        var ok = mapper.readTree("{\"bloqueante\":false}");
        assertThatThrownBy(() -> cl.faret.qccweb.version.ContractGuardTestAccess.verificar(bloqueante, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BLOQUEANTE");
        assertThatCode(() -> cl.faret.qccweb.version.ContractGuardTestAccess.verificar(bloqueante, false)).doesNotThrowAnyException();
        assertThatCode(() -> cl.faret.qccweb.version.ContractGuardTestAccess.verificar(ok, true)).doesNotThrowAnyException();
        assertThatCode(() -> cl.faret.qccweb.version.ContractGuardTestAccess.verificar(null, true)).doesNotThrowAnyException();
    }

    private HttpResponse<String> post(String path, HttpRequest.BodyPublisher cuerpo) throws Exception {
        return http.send(HttpRequest.newBuilder(url(path)).header("Content-Type", "application/json").POST(cuerpo).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static Path crearFixture() {
        try {
            Path dir = Files.createTempDirectory("qcc-web-fixture-1d");
            Files.createDirectories(dir.resolve("www"));
            Files.writeString(dir.resolve("www/index.html"), "<html></html>");
            Files.writeString(dir.resolve("web-manifest.json"), new ObjectMapper().writeValueAsString(Map.of(
                    "photinoVersion", "1.8.12",
                    "contrato", Map.of("estado", "PARCIAL", "texto", "Photino 1.8.12 · Web compatible 1/236",
                            "compatibles", 1, "accionesFrontend", 236, "pendientes", 235, "revisar", 0, "soloWeb", 0,
                            "bloqueante", false))));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
