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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Trazabilidad (INNPACK, solo lectura; Photino TrazabilidadHandler): trazabilidad.consultarNp junta Planificacion FARET
 * (sin auth), fps-api (falla suave) y paletizado de la API Innpack (falla suave). Textos escapados (la vista usa
 * innerHTML), solo campos de la lista exacta, np validado. APIs SIMULADAS.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFaseTrazabilidadTest {

    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveTraza#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(30, "consulta1", PASS, "Consulta Uno", "consulta", true));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.innpack-api-base-url", API::baseUrl);
        registry.add("qcc.web.planificacion.base-url", () -> API.baseUrl() + "/plan/");
        registry.add("qcc.web.fps.base-url", () -> API.baseUrl() + "/fpsmat");
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

    private static String consultar(String np) {
        return "{\"action\":\"trazabilidad.consultarNp\",\"_modulo\":\"trazabilidad\",\"data\":{\"np\":" + np + "}}";
    }

    @Test
    void juntaLasTresFuentesConTextosSegurosYSoloCamposDeLaLista() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(enviar(s, consultar("\" 4101 \"")).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        JsonNode d = json.get("data");
        assertThat(d.get("np").asString()).isEqualTo("4101");
        assertThat(d.get("avisoPlanificacion").isNull()).isTrue();
        // np (texto o numero) o nvInn coinciden; el otro NP y la fila no objeto se descartan.
        assertThat(d.get("procesos")).hasSize(3);
        assertThat(d.at("/procesos/0")).isEqualTo(mapper.readTree("{\"id\":88001,\"np\":\"4101\",\"nvInn\":\"NV-9\",\"cant\":1500.5,\"ent\":\"2026-10-30\","
                + "\"sec\":\"Imprenta\",\"rec\":\"Heidelberg\",\"est\":\"Planificada\",\"item\":\"CP-1\","
                + "\"itemName\":\"Estuche &lt;b&gt;carton&lt;/b&gt;\",\"cli\":\"Cliente &amp; Cia\",\"proc\":\"Impresion\",\"cantProd\":300,"
                + "\"vel\":1200.5,\"leadDias\":5,\"materiales\":[{\"idProceso\":\"88001\",\"itemCode\":\"INS-01\","
                + "\"itemName\":\"Tinta &lt;b&gt;negra&lt;/b&gt;\"},{\"idProceso\":\"88001\",\"itemCode\":\"INS-02\",\"itemName\":\"\"}]}"));
        // Tipos como GetDecimal/GetString de Photino: cant "x" -> 0, np numerico -> "4101", ent/vel/leadDias null.
        assertThat(d.at("/procesos/1")).isEqualTo(mapper.readTree("{\"id\":88002,\"np\":\"4101\",\"nvInn\":\"\",\"cant\":0,\"ent\":null,"
                + "\"sec\":\"Troquel\",\"rec\":\"R2\",\"est\":\"No Planificado\",\"item\":\"CP-1\",\"itemName\":\"Estuche\",\"cli\":\"Cliente\","
                + "\"proc\":\"Troquelado\",\"cantProd\":0,\"vel\":null,\"leadDias\":null,\"materiales\":[]}"));
        assertThat(d.at("/procesos/2/id").asLong()).isEqualTo(88004);
        assertThat(d.at("/procesos/2/materiales/0/itemName").asString()).isEqualTo("Cola");
        assertThat(d.get("paletizado")).isEqualTo(mapper.readTree("[{\"idPalet\":\"P-001\",\"fecha\":\"2026-10-02T10:15:00\","
                + "\"planta\":\"Planta &lt;i&gt;1&lt;/i&gt;\",\"npCliente\":\"4101\",\"notaVentaInnpack\":\"NV-9\",\"nombreCliente\":\"Cliente\","
                + "\"taller\":null,\"tipo\":\"EST\",\"cantidad\":250.5,\"descripcion\":\"Estuche &amp; caja\",\"fechaImpresion\":\"2026-10-02\"},"
                + "{\"idPalet\":\"P-002\",\"fecha\":null,\"planta\":\"\",\"npCliente\":\"\",\"notaVentaInnpack\":\"\",\"nombreCliente\":\"\","
                + "\"taller\":\"\",\"tipo\":\"\",\"cantidad\":100,\"descripcion\":\"\",\"fechaImpresion\":null}]"));
        assertThat(API.trazConsultas()).containsExactly("plan GET /plan/api/plan/vista-planificacion",
                "fps ids=88001,88002,88004", "pal np=4101");
        assertThat(json.toString()).doesNotContain("no-debe-llegar");
    }

    @Test
    void npInvalidoSeRechazaSinLlamarANadie() throws Exception {
        MockHttpSession s = login("operador1");
        for (String np : new String[] {"\"\"", "\"   \"", "null", "true", "{}", "[1]"}) {
            enviar(s, consultar(np)).andExpect(jsonPath("$.error").value("Debes indicar el NP a consultar"));
        }
        for (String np : new String[] {"\"" + "9".repeat(51) + "\"", "\"41\\n01\""}) {
            enviar(s, consultar(np)).andExpect(jsonPath("$.error").value("El NP no es válido."));
        }
        assertThat(API.trazConsultas()).isEmpty();
    }

    @Test
    void npConCaracteresEspecialesVaEscapadoAlPaletizado() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, consultar("\"4101&x=1 ñ\"")).andExpect(status().isOk());
        assertThat(API.trazConsultas()).contains("pal np=4101%26x%3D1%20%C3%B1");
    }

    @Test
    void sinCoincidenciasDevuelveListasVaciasSinLlamarAFps() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode d = json(enviar(s, consultar("\"55555\"")).andReturn()).get("data");
        assertThat(d.get("procesos")).isEmpty();
        assertThat(API.trazConsultas()).noneMatch(c -> c.startsWith("fps "));
    }

    @Test
    void erroresDePlanificacionSeMuestranAcotados() throws Exception {
        MockHttpSession s = login("operador1");
        API.modoPlan("ERROR_500");
        enviar(s, consultar("\"4101\"")).andExpect(jsonPath("$.error").value("No se pudo consultar Planificación FARET"));
        API.modoPlan("ERROR_MENSAJE");
        enviar(s, consultar("\"4101\"")).andExpect(jsonPath("$.error").value("Plan &lt;b&gt;caido&lt;/b&gt;"));
        API.modoPlan("JSON_INVALIDO");
        enviar(s, consultar("\"4101\"")).andExpect(jsonPath("$.error").value("Respuesta inválida de Planificación FARET."));
        API.modoPlan("NO_ARRAY");
        enviar(s, consultar("\"4101\"")).andExpect(jsonPath("$.error").value("Respuesta inesperada de Planificación FARET."));
        assertThat(API.trazConsultas()).noneMatch(c -> c.startsWith("pal "));
    }

    @Test
    void fpsYPaletizadoFallanEnSilencio() throws Exception {
        MockHttpSession s = login("operador1");
        API.modoFpsMat("ERROR_500");
        API.modoPaletizado("ERROR_500");
        JsonNode d = json(enviar(s, consultar("\"4101\"")).andReturn()).get("data");
        assertThat(d.get("procesos")).hasSize(3);
        assertThat(d.at("/procesos/0/materiales")).isEmpty();
        assertThat(d.get("paletizado")).isEmpty();
        API.modoFpsMat("NORMAL");
        API.modoPaletizado("SUCCESS_FALSE");
        assertThat(json(enviar(s, consultar("\"4101\"")).andReturn()).at("/data/paletizado")).isEmpty();
        API.modoPaletizado("TIPOS_RAROS");
        assertThat(json(enviar(s, consultar("\"4101\"")).andReturn()).at("/data/paletizado")).isEmpty();
    }

    @Test
    void soloRolesDeInnpack() throws Exception {
        enviar(login("consulta1"), consultar("\"4101\"")).andExpect(status().isForbidden());
        assertThat(API.trazConsultas()).isEmpty();
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.39.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-traza");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
