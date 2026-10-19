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
 * Laboratorio, lecturas del alta de muestra (Photino 1.8.23): muestraLab.consultarNp (Planificacion FARET),
 * consultarRegistroProduccion (fps-api, solo para procesos devueltos por consultarNp en la misma sesion) y
 * resolverBobina (apisapfaret). APIs SIMULADAS.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFaseLabLecturasTest {

    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveLab#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(11, "operador2", PASS, "Operador Dos", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(30, "consulta1", PASS, "Consulta Uno", "consulta", true));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.innpack-api-base-url", API::baseUrl);
        registry.add("qcc.web.planificacion.base-url", () -> API.baseUrl() + "/plan/");
        registry.add("qcc.web.fps.base-url", () -> API.baseUrl() + "/fpsmat");
        registry.add("qcc.web.fps.api-key", () -> FakeInnpackApi.FPS_API_KEY);
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

    private static String accion(String accion, String data) {
        return "{\"action\":\"muestraLab." + accion + "\",\"_modulo\":\"muestra-laboratorio\",\"data\":" + data + "}";
    }

    @Test
    void consultarNpMapeaLosProcesosDelNp() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(enviar(s, accion("consultarNp", "{\"np\":\"4101\"}")).andExpect(status().isOk()).andReturn());
        assertThat(json.get("data")).isEqualTo(mapper.readTree("["
                + "{\"idProceso\":88001,\"cliente\":\"Cliente & Cia\",\"codigoProducto\":\"CP-1\",\"descripcion\":\"Estuche <b>carton</b>\","
                + "\"proceso\":\"Impresion\"},"
                + "{\"idProceso\":88002,\"cliente\":\"Cliente\",\"codigoProducto\":\"CP-1\",\"descripcion\":\"Estuche\",\"proceso\":\"Troquelado\"},"
                + "{\"idProceso\":88004,\"cliente\":\"\",\"codigoProducto\":\"\",\"descripcion\":\"\",\"proceso\":\"Pegado\"}]"));
        assertThat(API.trazConsultas()).containsExactly("plan GET /plan/api/plan/vista-planificacion");
        // Photino compara sin recortar: " 4101" no coincide con ningun NP.
        enviar(s, accion("consultarNp", "{\"np\":\" 4101\"}")).andExpect(jsonPath("$.error").value("No se encontró la NP  4101 en Planificación FARET."));
    }

    @Test
    void consultarNpValidaYAcotaLosErrores() throws Exception {
        MockHttpSession s = login("operador1");
        for (String np : new String[] {"\"\"", "\"  \"", "null", "true", "{}"}) {
            enviar(s, accion("consultarNp", "{\"np\":" + np + "}")).andExpect(jsonPath("$.error").value("Falta indicar la NP"));
        }
        for (String np : new String[] {"\"" + "9".repeat(51) + "\"", "\"41\\n01\""}) {
            enviar(s, accion("consultarNp", "{\"np\":" + np + "}")).andExpect(jsonPath("$.error").value("La NP no es válida."));
        }
        assertThat(API.trazConsultas()).isEmpty();
        enviar(s, accion("consultarNp", "{\"np\":\"55555\"}")).andExpect(jsonPath("$.error").value("No se encontró la NP 55555 en Planificación FARET."));
        API.modoPlan("ERROR_500");
        enviar(s, accion("consultarNp", "{\"np\":\"4101\"}")).andExpect(jsonPath("$.error").value("No se pudo consultar Planificación FARET"));
        API.modoPlan("ERROR_MENSAJE");
        enviar(s, accion("consultarNp", "{\"np\":\"4101\"}")).andExpect(jsonPath("$.error").value("Plan <b>caido</b>"));
        API.modoPlan("JSON_INVALIDO");
        enviar(s, accion("consultarNp", "{\"np\":\"4101\"}")).andExpect(jsonPath("$.error").value("Respuesta inválida de Planificación FARET."));
        API.modoPlan("NO_ARRAY");
        enviar(s, accion("consultarNp", "{\"np\":\"4101\"}")).andExpect(jsonPath("$.error").value("Respuesta inesperada de Planificación FARET."));
    }

    @Test
    void registroProduccionSoloParaProcesosDevueltosPorConsultarNpEnLaSesion() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, accion("consultarRegistroProduccion", "{\"idProceso\":88001}"))
                .andExpect(jsonPath("$.error").value("Consulta primero la NP para elegir el proceso."));
        assertThat(API.trazConsultas()).isEmpty();
        enviar(s, accion("consultarNp", "{\"np\":\"4101\"}")).andExpect(jsonPath("$.ok").value(true));
        API.limpiarTraz();

        JsonNode json = json(enviar(s, accion("consultarRegistroProduccion", "{\"idProceso\":\"88001\"}")).andExpect(status().isOk()).andReturn());
        assertThat(json.get("data")).isEqualTo(mapper.readTree("["
                + "{\"recordKey\":5001,\"idProceso\":88001,\"maquina\":\"M1\",\"maquinaDescripcion\":\"Heidelberg <b>1</b>\","
                + "\"operador\":\"Operador & Co\",\"productionStartDate\":\"2026-10-02T08:00:00&quot;&lt;x&gt;\",\"productionEndDate\":\"\","
                + "\"productionQuantity\":\"1200.5\"},"
                + "{\"recordKey\":5002,\"idProceso\":0,\"maquina\":\"M2\",\"maquinaDescripcion\":\"\",\"operador\":\"\","
                + "\"productionStartDate\":\"\",\"productionEndDate\":\"\",\"productionQuantity\":\"\"}]"));
        assertThat(API.trazConsultas()).containsExactly("fpsreg /fpsmat/registros-por-proceso/88001");
        // Proceso devuelto por la NP pero sin sesiones: lista vacia (la vista lo informa).
        assertThat(json(enviar(s, accion("consultarRegistroProduccion", "{\"idProceso\":88002}")).andReturn()).get("data")).isEmpty();
        // Un proceso que NO salio de consultarNp en esta sesion no se consulta; tampoco desde otra sesion.
        enviar(s, accion("consultarRegistroProduccion", "{\"idProceso\":99999}"))
                .andExpect(jsonPath("$.error").value("Consulta primero la NP para elegir el proceso."));
        enviar(login("operador2"), accion("consultarRegistroProduccion", "{\"idProceso\":88001}"))
                .andExpect(jsonPath("$.error").value("Consulta primero la NP para elegir el proceso."));
        assertThat(API.trazConsultas()).hasSize(2);
    }

    @Test
    void registroProduccionValidaElIdYAcotaLosErrores() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, accion("consultarNp", "{\"np\":\"4101\"}"));
        for (String id : new String[] {"0", "-5", "\"abc\"", "null", "1.5", "99999999999999999999"}) {
            enviar(s, accion("consultarRegistroProduccion", "{\"idProceso\":" + id + "}"))
                    .andExpect(jsonPath("$.error").value("Falta indicar el proceso (selecciona primero la NP)"));
        }
        for (String id : new String[] {"true", "{}", "[1]"}) {
            enviar(s, accion("consultarRegistroProduccion", "{\"idProceso\":" + id + "}")).andExpect(jsonPath("$.error").value("Parámetro de filtro inválido."));
        }
        API.limpiarTraz();
        API.modoFpsMat("ERROR_500");
        enviar(s, accion("consultarRegistroProduccion", "{\"idProceso\":88001}")).andExpect(jsonPath("$.error").value("No fue posible consultar FPS."));
        API.modoFpsMat("OK_FALSE");
        enviar(s, accion("consultarRegistroProduccion", "{\"idProceso\":88001}")).andExpect(jsonPath("$.error").value("No fue posible consultar FPS."));
    }

    @Test
    void resolverBobinaDevuelveSoloLosCamposDelPrimerLote() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(enviar(s, accion("resolverBobina", "{\"lote\":\"L 1/ñ\"}")).andExpect(status().isOk()).andReturn());
        assertThat(json.get("data")).isEqualTo(mapper.readTree("{\"empresa\":\"INNPACK\",\"itemCode\":\"1095SC\",\"itemName\":\"Kraft 125\","
                + "\"unidad\":\"KG\",\"stock\":\"1250.5\",\"ubicacion\":\"BOD-1\"}"));
        assertThat(API.trazConsultas()).containsExactly("sap /sap/api/lotes/L%201%2F%C3%B1");
        assertThat(json.toString()).doesNotContain("no-debe-llegar");
    }

    @Test
    void resolverBobinaValidaYAcotaLosErrores() throws Exception {
        MockHttpSession s = login("operador1");
        for (String lote : new String[] {"\"\"", "\"  \"", "null", "true"}) {
            enviar(s, accion("resolverBobina", "{\"lote\":" + lote + "}")).andExpect(jsonPath("$.error").value("Falta indicar el lote/bobina"));
        }
        for (String lote : new String[] {"\"" + "L".repeat(51) + "\"", "\"L\\n1\""}) {
            enviar(s, accion("resolverBobina", "{\"lote\":" + lote + "}")).andExpect(jsonPath("$.error").value("El lote/bobina no es válido."));
        }
        assertThat(API.trazConsultas()).isEmpty();
        enviar(s, accion("resolverBobina", "{\"lote\":\"NOEXISTE\"}")).andExpect(jsonPath("$.error").value("No se encontró el lote NOEXISTE en SAP."));
        enviar(s, accion("resolverBobina", "{\"lote\":\"L404\"}")).andExpect(jsonPath("$.error").value("No se encontró el lote L404 en SAP."));
        enviar(s, accion("resolverBobina", "{\"lote\":\"BAD\"}")).andExpect(jsonPath("$.error").value("lote invalido"));
        API.modoSap("ERROR_502");
        MvcResult r = enviar(s, accion("resolverBobina", "{\"lote\":\"L1\"}")).andReturn();
        assertThat(json(r).get("error").asString()).isEqualTo("No fue posible consultar SAP (apisapfaret).").doesNotContain("B1SESSION");
        API.modoSap("JSON_INVALIDO");
        enviar(s, accion("resolverBobina", "{\"lote\":\"L1\"}")).andExpect(jsonPath("$.error").value("Respuesta inválida de SAP (apisapfaret)."));
    }

    @Test
    void soloRolesDeInnpack() throws Exception {
        MockHttpSession consulta = login("consulta1");
        for (String a : new String[] {"consultarNp", "consultarRegistroProduccion", "resolverBobina"}) {
            enviar(consulta, accion(a, "{\"np\":\"4101\",\"idProceso\":88001,\"lote\":\"L1\"}")).andExpect(status().isForbidden());
        }
        assertThat(API.trazConsultas()).isEmpty();
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.40.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-lab-lecturas");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
