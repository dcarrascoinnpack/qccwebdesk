package cl.faret.qccweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Fase 1a: el gateway arranca sin base de datos, sirve el frontend compartido, expone /version y
 * niega todo lo demás. Usa un www de prueba en un directorio temporal (no depende de Photino).
 */
@SpringBootTest(properties = "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME)
@AutoConfigureMockMvc
class GatewayFase1aTest {

    private static final Path FIXTURE = crearFixture();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.www-dir", () -> FIXTURE.resolve("photino-www").toString());
        registry.add("qcc.web.manifest-file", () -> FIXTURE.resolve("web-manifest.json").toString());
    }

    @Test
    void arrancaSinBaseDeDatos() {
        assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
    }

    @Test
    void raizRedirigeAlIndexDePhotino() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void sirveIndexConShimYCabecerasDeSeguridad() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("web/web-bridge.js")))
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "same-origin"));
    }

    @Test
    void sirveElWebBridge() throws Exception {
        mockMvc.perform(get("/web/web-bridge.js")).andExpect(status().isOk());
    }

    @Test
    void versionEsPublicaYLeeElManifest() throws Exception {
        mockMvc.perform(get("/version"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.photino.version").value("9.9.9"))
                .andExpect(jsonPath("$.photino.commit").value("abc123"))
                .andExpect(jsonPath("$.frontend.wwwSha256").value("deadbeef"))
                .andExpect(jsonPath("$.compatibilidad.estado").value("NO_EVALUADO"));
    }

    @Test
    void healthEsPublico() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void bridgeSinSesionResponde401() throws Exception {
        mockMvc.perform(post("/api/v1/bridge").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"inicio.getDashboard\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postSinTokenCsrfEsRechazado() throws Exception {
        mockMvc.perform(post("/api/v1/bridge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"inicio.getDashboard\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cualquierOtraRutaQuedaDenegada() throws Exception {
        mockMvc.perform(get("/api/v1/cualquier-cosa")).andExpect(status().isUnauthorized());
        // Aunque el archivo exista en el www, si no está en la lista blanca no se sirve.
        mockMvc.perform(get("/secreto.txt")).andExpect(status().isUnauthorized());
    }

    private static Path crearFixture() {
        try {
            Path root = Files.createTempDirectory("qcc-web-fixture");
            Path www = Files.createDirectories(root.resolve("photino-www"));
            Files.writeString(www.resolve("index.html"),
                    "<html><body><script src=\"web/web-bridge.js\"></script></body></html>");
            Files.createDirectories(www.resolve("web"));
            Files.writeString(www.resolve("web/web-bridge.js"), "// shim de prueba");
            Files.writeString(www.resolve("secreto.txt"), "no debe servirse");
            Files.writeString(root.resolve("web-manifest.json"),
                    "{\"photinoVersion\":\"9.9.9\",\"photinoCommit\":\"abc123\",\"wwwSha256\":\"deadbeef\"}");
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
