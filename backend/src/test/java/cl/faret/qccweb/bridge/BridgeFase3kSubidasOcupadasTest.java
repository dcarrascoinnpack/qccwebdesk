package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fase 3k — límite de subidas simultáneas (0 cupos → 503 sin tocar la API). Contexto propio por la propiedad.
 * Resto del contrato en BridgeFase3kTest. Foco original: ruta de archivos con tope propio (y el resto sigue en 256 KB),
 * IDENTIDAD (subidoPor → sesión), firma real y tamaño decodificado, nombre saneado AL SUBIR (Photino escritorio lo lee
 * directo de la API y lo pinta con innerHTML). API SIMULADA: se verifica el cuerpo EXACTO que recibe upstream.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000",
    "qcc.web.bridge.subidas-simultaneas=0"
})
@AutoConfigureMockMvc
class BridgeFase3kSubidasOcupadasTest {

    private static final String SUBIR = "noConformidades.adjuntos.subir";
    private static final String RUTA = "/api/v1/bridge/archivo";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveAdjuntos#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String PDF = Base64.getEncoder().encodeToString(FakeInnpackApi.pdfMinimo(1));

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(20, "admin1", PASS, "Admin Uno", "admin", true));
        API.agregar(new FakeInnpackApi.Usuario(25, "adminti1", PASS, "Admin TI", "admin_ti", true));
        API.agregar(new FakeInnpackApi.Usuario(30, "consulta1", PASS, "Consulta Uno", "consulta", true));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.innpack-api-base-url", API::baseUrl);
        registry.add("qcc.web.www-dir", WWW::toString);
        registry.add("qcc.web.manifest-file", () -> WWW.resolve("no-existe.json").toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reiniciar() {
        API.reiniciarDashboard();
    }

    @AfterAll
    static void cerrar() {
        API.close();
    }

    /** Payload exacto de _subirPdfSeleccionado de Photino, con overrides opcionales. */
    private String payload(Map<String, Object> cambios) {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", SUBIR);
        p.put("id", 501);
        p.put("tipo", "CAUSA_RAIZ_PDF");
        p.put("nombreArchivo", "causa raíz ñ.pdf");
        p.put("tipoMime", "application/pdf");
        p.put("contenidoBase64", PDF);
        p.put("subidoPor", "Operador Uno");
        cambios.forEach((k, v) -> {
            if (v == Borrar.CAMPO) {
                p.remove(k);
            } else {
                p.set(k, mapper.valueToTree(v));
            }
        });
        return p.toString();
    }

    private enum Borrar { CAMPO }

    @Test
    void sinCuposDeSubidaResponde503SinLlamarUpstreamYLasLecturasSiguen() throws Exception {
        MockHttpSession s = login("operador1");
        subir(s, payload(Map.of())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Hay otras subidas de archivos en curso. Inténtalo de nuevo en unos segundos."));
        assertThat(API.adjuntosRecibidos()).isEmpty();
        mockMvc.perform(post("/api/v1/bridge").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"noConformidades.list\"}")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.19.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private ResultActions subir(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post(RUTA).session(sesion).with(csrf())
                .with(r -> {
                    r.setRequestedSessionId(sesion.getId());
                    return r;
                })
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }



    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-3k-ocupadas");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
