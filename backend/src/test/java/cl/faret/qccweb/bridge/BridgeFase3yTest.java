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
 * Fase 3y — recepcion.sap.consultar / recepcion.sap.lotes (formulario "Nuevo lote" de Recepción, Photino 1.8.14):
 * apisapfaret con la API key del servidor. Seguridad transparente: empresa de sesión, fechas yyyyMMdd válidas (Photino
 * concatena desde/hasta sin escapar), itemCode escapado, textos peligrosos para innerHTML escapados. API SIMULADA.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3yTest {

    private static final String CONSULTAR = "recepcion.sap.consultar";
    private static final String LOTES = "recepcion.sap.lotes";
    private static final String RANGO = "Debes indicar desde y hasta (yyyyMMdd)";
    private static final String FALTA_LOTES = "Debes indicar itemCode y fecha (yyyyMMdd)";
    private static final String FALLO = "No fue posible consultar SAP (apisapfaret).";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveSap#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(30, "consulta1", PASS, "Consulta Uno", "consulta", true));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.innpack-api-base-url", API::baseUrl);
        registry.add("qcc.web.sap.base-url", () -> API.baseUrl() + "/sap/");
        registry.add("qcc.web.sap.api-key", () -> FakeInnpackApi.SAP_API_KEY);
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

    private static String consultar(String desde, String hasta) {
        return "{\"action\":\"" + CONSULTAR + "\",\"data\":{\"desde\":" + desde + ",\"hasta\":" + hasta + "}}";
    }

    private static String lotes(String itemCode, String fecha) {
        return "{\"action\":\"" + LOTES + "\",\"data\":{\"itemCode\":" + itemCode + ",\"fecha\":" + fecha + "}}";
    }

    @Test
    void consultarComoPhotinoConEmpresaDeSesionYTextosSeguros() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(enviar(s, "{\"action\":\"" + CONSULTAR + "\",\"data\":{\"desde\":\"20260901\",\"hasta\":\"20260929\","
                + "\"empresa\":\"FARET\"}}").andExpect(status().isOk()).andReturn());
        assertThat(API.sapConsultas()).containsExactly("/api/recepcion/bobinas?desde=20260901&hasta=20260929&empresa=INNPACK");
        assertThat(json.get("data")).isEqualTo(mapper.readTree("[{\"docEntry\":501,\"lineNum\":0,\"fechaRecepcion\":\"20260926\","
                + "\"proveedor\":\"Papeles &amp; Cía &lt;b&gt;SA&lt;/b&gt;\",\"guia\":\"G-77\",\"itemCode\":\"1095SC21000090\",\"descripcion\":\"Kraft 125\","
                + "\"cantidadRecibida\":1250.5,\"anchoDeclarado\":1600,\"gramajeDeclarado\":125},"
                + "{\"docEntry\":502,\"lineNum\":1,\"fechaRecepcion\":\"20260927\",\"proveedor\":\"Papeles Sur\",\"guia\":\"G-78\","
                + "\"itemCode\":\"ABC-1\",\"descripcion\":\"Test\",\"cantidadRecibida\":10,\"anchoDeclarado\":null,\"gramajeDeclarado\":null}]"));
        // Tipos como GetInt/GetString/GetDecimal de Photino (defaults del DTO).
        API.modoSap("TIPOS_RAROS");
        JsonNode raro = json(enviar(s, consultar("\"20260901\"", "\"20260929\"")).andReturn()).at("/data/0");
        assertThat(raro).isEqualTo(mapper.readTree("{\"docEntry\":77,\"lineNum\":0,\"fechaRecepcion\":\"20260926\",\"proveedor\":\"\",\"guia\":\"\","
                + "\"itemCode\":\"\",\"descripcion\":\"\",\"cantidadRecibida\":0,\"anchoDeclarado\":1.25,\"gramajeDeclarado\":null}"));
    }

    @Test
    void fechasSoloYyyyMMddValidasSinInyeccion() throws Exception {
        MockHttpSession s = login("operador1");
        for (String[] malo : new String[][] {{"\"\"", "\"20260929\""}, {"null", "\"20260929\""}, {"\"20260901\"", "\"\""},
                {"\"2026-09-01\"", "\"20260929\""}, {"\"20260901&empresa=FARET\"", "\"20260929\""}, {"\"20261301\"", "\"20260929\""},
                {"\"20260230\"", "\"20260929\""}, {"true", "\"20260929\""}}) {
            enviar(s, consultar(malo[0], malo[1])).andExpect(jsonPath("$.error").value(RANGO));
        }
        for (String[] malo : new String[][] {{"\"\"", "\"20260926\""}, {"\"  \"", "\"20260926\""}, {"\"X1\"", "\"\""},
                {"\"X1\"", "\"2026-09-26\""}, {"\"" + "A".repeat(51) + "\"", "\"20260926\""}, {"\"X\\n1\"", "\"20260926\""}}) {
            enviar(s, lotes(malo[0], malo[1])).andExpect(jsonPath("$.error").value(FALTA_LOTES));
        }
        assertThat(API.sapConsultas()).isEmpty();
    }

    @Test
    void lotesConItemCodeEscapadoYNumerosDeBobinaSeguros() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(enviar(s, lotes("\"1095 SC/ñ&x=1\"", "\"20260926\"")).andExpect(status().isOk()).andReturn());
        assertThat(API.sapConsultas()).containsExactly("/api/recepcion/bobinas/lotes?itemCode=1095%20SC%2F%C3%B1%26x%3D1&fecha=20260926&empresa=INNPACK");
        assertThat(json.get("data")).isEqualTo(mapper.readTree("[{\"itemCode\":\"1095 SC/ñ&x=1\",\"numeroBobina\":\"B-001\",\"absEntry\":11,"
                + "\"fechaCreacion\":\"20260926\"},{\"itemCode\":\"1095 SC/ñ&x=1\",\"numeroBobina\":\"B&quot;&gt;&lt;img src=x&gt;\",\"absEntry\":12,"
                + "\"fechaCreacion\":\"20260926\"}]"));
    }

    @Test
    void fallosDeSapSinDetallesInternos(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        for (String modo : new String[] {"ERROR_502", "JSON_INVALIDO"}) {
            API.modoSap(modo);
            enviar(s, consultar("\"20260901\"", "\"20260929\"")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(FALLO));
        }
        assertThat(salida.getAll()).doesNotContain(FakeInnpackApi.SAP_API_KEY, "B1SESSION");
        assertThat(salida.getAll()).contains("accion=recepcion.sap.consultar resultado=ERROR");
    }

    @Test
    void rolesYSesionComoPhotino() throws Exception {
        enviar(login("consulta1"), consultar("\"20260901\"", "\"20260929\"")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/bridge").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(consultar("\"20260901\"", "\"20260929\"")))
                .andExpect(status().isUnauthorized());
        assertThat(API.sapConsultas()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.37.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-3y");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
