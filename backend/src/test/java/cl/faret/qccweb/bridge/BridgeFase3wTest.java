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
 * Fase 3w — muestraLab.materialesFps (botón "Materiales FPS" del detalle de una muestra, Photino 1.8.14): GET fps-api
 * materiales-por-proceso con la API key del servidor; respuesta MaterialInsumoDto en camelCase. Seguridad
 * transparente: solo el proceso FPS de una muestra cuyo detalle abrió la sesión. API SIMULADA.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3wTest {

    private static final String MATERIALES = "muestraLab.materialesFps";
    private static final String FALLO = "No fue posible consultar los materiales en FPS.";
    private static final String SIN_LEER = "Abre el detalle de la muestra antes de consultar los materiales FPS.";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveMateriales#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(20, "admin1", PASS, "Admin Uno", "admin", true));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.innpack-api-base-url", API::baseUrl);
        registry.add("qcc.web.fps.base-url", () -> API.baseUrl() + "/fps");
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

    private static String materiales(String idProceso) {
        return "{\"action\":\"" + MATERIALES + "\",\"data\":{\"idProceso\":" + idProceso + "}}";
    }

    private void abrir(MockHttpSession s, int id) throws Exception {
        enviar(s, "{\"action\":\"muestraLab.detalle\",\"data\":{\"id\":" + id + "}}").andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void materialesDelProcesoDeLaMuestraAbiertaComoPhotino() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 501);
        JsonNode json = json(enviar(s, materiales("88001")).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.get("data")).isEqualTo(mapper.readTree("[{\"idProceso\":\"88001\",\"itemCode\":\"INS-01\",\"itemName\":\"Tinta <b>negra</b>\"},"
                + "{\"idProceso\":\"88001\",\"itemCode\":\"INS-02\",\"itemName\":\"\"}]"));
        assertThat(API.fpsConsultas()).containsExactly("ids=88001");
        // Texto del detalle ("88003") y número/texto con espacios: mismo proceso.
        abrir(s, 503);
        enviar(s, materiales("\" 88003 \"")).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(API.fpsConsultas()).containsExactly("ids=88001", "ids=88003");
    }

    @Test
    void soloProcesosDeMuestrasAbiertasEnLaSesion() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        enviar(a, materiales("88001")).andExpect(jsonPath("$.error").value(SIN_LEER));
        abrir(a, 502); // muestra sin proceso FPS
        enviar(a, materiales("88001")).andExpect(jsonPath("$.error").value(SIN_LEER));
        abrir(a, 501);
        enviar(a, materiales("88001")).andExpect(jsonPath("$.ok").value(true));
        // Otra sesión no hereda lo que abrió la primera; otro proceso tampoco.
        enviar(b, materiales("88001")).andExpect(jsonPath("$.error").value(SIN_LEER));
        enviar(a, materiales("88002")).andExpect(jsonPath("$.error").value(SIN_LEER));
        assertThat(API.fpsConsultas()).containsExactly("ids=88001");
    }

    @Test
    void validacionesConLosMensajesDePhotino() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 501);
        for (String malo : new String[] {"0", "-5", "null", "\"abc\"", "\"\"", "1.5", "\"88001,88002\"", "\"88001%2C1\"", "99999999999999999999"}) {
            enviar(s, materiales(malo)).andExpect(jsonPath("$.error").value("Falta el idProceso (FPS) de la muestra"));
        }
        enviar(s, "{\"action\":\"" + MATERIALES + "\",\"data\":{}}").andExpect(jsonPath("$.error").value("Falta el idProceso (FPS) de la muestra"));
        for (String malo : new String[] {"true", "{\"a\":1}", "[88001]"}) {
            enviar(s, materiales(malo)).andExpect(jsonPath("$.error").value("Parámetro de filtro inválido."));
        }
        assertThat(API.fpsConsultas()).isEmpty();
    }

    @Test
    void fallosDeFpsSinDetalles(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 501);
        for (String modo : new String[] {"ERROR_500", "JSON_INVALIDO", "OK_FALSE"}) {
            API.modoFps(modo);
            enviar(s, materiales("88001")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(FALLO));
        }
        assertThat(salida.getAll()).contains("accion=muestraLab.materialesFps resultado=ERROR")
                .doesNotContain(FakeInnpackApi.FPS_API_KEY, "SQL timeout");
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.35.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-3w");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
