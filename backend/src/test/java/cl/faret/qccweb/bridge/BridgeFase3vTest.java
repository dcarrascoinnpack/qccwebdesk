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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fase 3v — liberacionCalidad.inspectores contra fps-api (columna "Liberación Calidad" de PNC, Photino 1.8.14): GET
 * liberaciones/inspectores con la API key del servidor, tandas de 300, solo NP numéricas. Seguridad transparente:
 * solo dígitos ASCII hacia fps-api (arma OPENQUERY), tope total, filas de las NP pedidas y de la empresa de la sesión,
 * 7 campos. API SIMULADA (fps-api dentro de FakeInnpackApi).
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3vTest {

    private static final String FALLO = "No fue posible consultar las liberaciones en fps-api.";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveFps#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(20, "admin1", PASS, "Admin Uno", "admin", true));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.innpack-api-base-url", API::baseUrl);
        registry.add("qcc.web.fps.base-url", () -> API.baseUrl() + "/fps/");
        registry.add("qcc.web.fps.api-key", () -> FakeInnpackApi.FPS_API_KEY);
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

    private static String payload(String nps) {
        return "{\"action\":\"liberacionCalidad.inspectores\",\"data\":{\"nps\":" + nps + "}}";
    }

    @Test
    void consultaComoPhotinoYDevuelveSoloFilasDeLaEmpresaYNpPedidas() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(enviar(s, payload("[\"4001\",\" 4002 \",4003,\"4001\",\"4010\"]")).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        // Una sola llamada, NP sin duplicados en el orden recibido, coma escapada como Uri.EscapeDataString de Photino.
        assertThat(API.fpsConsultas()).containsExactly("nps=4001%2C4002%2C4003%2C4010");
        JsonNode data = json.get("data");
        assertThat(data).hasSize(3); // 4010 sin liberación; FARET y NP no pedida descartadas
        List<String> campos = new ArrayList<>();
        data.get(0).propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("Np", "Empresa", "CodigoArticulo", "Inspector", "UltimaLiberacion", "UltimoFolio", "Liberaciones");
        assertThat(data.get(0)).isEqualTo(mapper.readTree("{\"Np\":4001,\"Empresa\":\"INNPACK SPA\",\"CodigoArticulo\":\"C-4001\","
                + "\"Inspector\":\"Inspector <b>4001</b>\",\"UltimaLiberacion\":\"2026-09-21T00:00:00.000Z\",\"UltimoFolio\":40011,\"Liberaciones\":2}"));
        assertThat(json.toString()).doesNotContain("Faret", "No pedida", "no-debe-llegar", FakeInnpackApi.FPS_API_KEY);
    }

    @Test
    void soloDigitosAsciiLleganAFpsApi() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, payload("[\"4001'); DROP TABLE x--\",\"40 01\",\"-4001\",\"4001.5\",1.5,-3,null,\"\",\"١٢٣\",\"1234567890123456\",\"4002\"]"))
                .andExpect(jsonPath("$.ok").value(true));
        assertThat(API.fpsConsultas()).containsExactly("nps=4002");
        // Objetos/booleanos: parámetro inválido, sin llamar a fps-api.
        for (String malo : new String[] {"[{\"np\":1}]", "[true]", "[[\"4001\"]]"}) {
            enviar(s, payload(malo)).andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value("Parámetro de filtro inválido."));
        }
        // Sin NP válidas (o sin arreglo): como Photino, lista vacía sin llamar.
        for (String vacio : new String[] {"[]", "[\"abc\"]", "\"4001\"", "null"}) {
            enviar(s, payload(vacio)).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data.length()").value(0));
        }
        enviar(s, "{\"action\":\"liberacionCalidad.inspectores\"}").andExpect(jsonPath("$.data.length()").value(0));
        assertThat(API.fpsConsultas()).hasSize(1);
    }

    @Test
    void tandasDe300YTopeTotal() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, payload(nps(1, 650))).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.fpsConsultas()).hasSize(3);
        assertThat(API.fpsConsultas().get(0).split("%2C")).hasSize(300);
        assertThat(API.fpsConsultas().get(2).split("%2C")).hasSize(50);
        enviar(s, payload(nps(1, 3001))).andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value(FALLO));
        assertThat(API.fpsConsultas()).hasSize(3);
    }

    @Test
    void fallosDeFpsApiDanElMensajeDePhotinoSinDetalles() throws Exception {
        MockHttpSession s = login("operador1");
        for (String modo : new String[] {"ERROR_500", "JSON_INVALIDO", "GRANDE"}) {
            API.modoFps(modo);
            enviar(s, payload("[\"4001\"]")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(FALLO));
        }
        API.modoFps("SIN_DATA");
        enviar(s, payload("[\"4001\"]")).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void cualquierRolInnpackYSinSesionNada(CapturedOutput salida) throws Exception {
        enviar(login("admin1"), payload("[\"4001\"]")).andExpect(jsonPath("$.ok").value(true));
        mockMvc.perform(post("/api/v1/bridge").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload("[\"4001\"]")))
                .andExpect(status().isUnauthorized());
        assertThat(API.fpsConsultas()).hasSize(1);
        // Auditoría sin NP, sin nombres y sin la API key.
        assertThat(salida.getAll()).contains("accion=liberacionCalidad.inspectores resultado=OK")
                .doesNotContain(FakeInnpackApi.FPS_API_KEY, "Inspector <b>");
    }

    // ------------------------------------------------------------------ helpers

    private static String nps(int desde, int cantidad) {
        return "[" + String.join(",", IntStream.range(desde, desde + cantidad).mapToObj(i -> "\"" + (100000 + i) + "\"").toList()) + "]";
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.34.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private ResultActions enviar(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private JsonNode json(MvcResult res) throws Exception {
        return mapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-3v");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
