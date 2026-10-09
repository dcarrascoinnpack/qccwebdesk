package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeFaretApi;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Fase F1 — el límite de escrituras por usuario (EscrituraRateLimiter) también cubre las escrituras FARET: 429 sin tocar la API. */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=3"
})
@AutoConfigureMockMvc
class BridgeFaseF1LimiteTest {

    private static final FakeFaretApi QC = new FakeFaretApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveFaretF1L#2026";

    static {
        QC.agregar(new FakeFaretApi.Usuario(20, "ana", "ana@faret.cl", PASS, "Ana Calidad", List.of("CALIDAD")));
        QC.agregar(new FakeFaretApi.Usuario(10, "admin", "admin@faret.cl", PASS, "Admin Faret", List.of("ADMIN")));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.faret-api-base-url", QC::baseUrl);
        registry.add("qcc.web.www-dir", WWW::toString);
        registry.add("qcc.web.manifest-file", () -> WWW.resolve("no-existe.json").toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @AfterAll
    static void cerrar() {
        QC.close();
    }

    @Test
    void laCuartaEscrituraDelMismoUsuarioEnUnMinutoEs429SinLlamarALaApi() throws Exception {
        MockHttpSession ana = login("ana", "10.80.0.1");
        for (int i = 0; i < 3; i++) {
            escribir(ana, "Cliente " + i).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        }
        escribir(ana, "Cliente 4").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_LIMITE_ESCRITURAS));
        assertThat(QC.escrituras()).hasSize(3);
        // El límite es por usuario: otro usuario sigue pudiendo escribir.
        escribir(login("admin", "10.80.0.2"), "Cliente admin").andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertThat(QC.escrituras()).hasSize(4);
    }

    private org.springframework.test.web.servlet.ResultActions escribir(MockHttpSession sesion, String nombre) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"faret.pncCatalogos.clientes.crear\",\"_modulo\":\"faret-nc\",\"nombre\":\"" + nombre + "\"}"));
    }

    private MockHttpSession login(String identificador, String ip) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/faret/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr(ip);
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identificador\":\"" + identificador + "\",\"password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private static Path crearWww() {
        try {
            Path dir = Files.createTempDirectory("qcc-web-fixture-f1l");
            Files.writeString(dir.resolve("index.html"), "<!doctype html><title>fixture</title>");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
