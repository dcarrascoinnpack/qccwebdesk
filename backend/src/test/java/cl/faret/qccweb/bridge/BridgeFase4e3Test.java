package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Fase 4e-3 — `muestraLab.anular`, `muestraLab.ensayo.anular`, `muestraLab.actualizarFechaEnsayo`, igual que Photino
 * para el usuario. Con esto Laboratorio queda 19/25. `id`/`ensayoId`/`motivo` se rechazan ANTES de llamar a la API si
 * faltan (igual que el handler C# de Photino, no solo la API); `ensayo.anular` no recibe autor (la API no tiene
 * campo para ello, auditoría obligatoria del gateway); `actualizarFechaEnsayo` restringe la fecha al formato real
 * del {@code <input type="datetime-local">} de Photino (AAAA-MM-DDTHH:mm[:ss]).
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase4e3Test {

    private static final String ANULAR = "muestraLab.anular";
    private static final String ENSAYO_ANULAR = "muestraLab.ensayo.anular";
    private static final String FECHA_ENSAYO = "muestraLab.actualizarFechaEnsayo";

    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveMuestraLab4e3#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(20, "admin1", PASS, "Admin Uno", "admin", true));
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

    // ------------------------------------------------------------------ anular

    @Test
    void anularCamposObligatoriosYUsuarioManipulado() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(ANULAR, mapper.createObjectNode().put("id", 0).put("motivo", "x")))
                .andExpect(jsonPath("$.error").value("Falta la muestra o el motivo de anulación"));
        enviar(s, acc(ANULAR, mapper.createObjectNode().put("id", 501).put("motivo", "")))
                .andExpect(jsonPath("$.error").value("Falta la muestra o el motivo de anulación"));

        JsonNode json = json(enviar(s, acc(ANULAR, mapper.createObjectNode().put("id", 501).put("motivo", "Muestra dañada")))
                .andExpect(status().isOk()).andReturn());
        assertThat(json.at("/data/id").asInt()).isEqualTo(501);
        JsonNode recibido = API.cuerposAnularMuestraLabRecibidos().get(0);
        assertThat(recibido.get("motivo").asString()).isEqualTo("Muestra dañada");
        assertThat(recibido.get("usuarioNombre").asString()).isEqualTo("Operador Uno");

        ObjectNode manipulado = mapper.createObjectNode().put("id", 502).put("motivo", "x").put("usuarioNombre", "Hacker");
        enviar(s, acc(ANULAR, manipulado)).andExpect(jsonPath("$.error").value("Campo no permitido: usuarioNombre"));
    }

    @Test
    void anularCampoNoPermitidoYYaAnuladaOInexistente() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode conExtra = mapper.createObjectNode().put("id", 501).put("motivo", "x").put("muestraId", 501);
        enviar(s, acc(ANULAR, conExtra)).andExpect(jsonPath("$.error").value("Campo no permitido: muestraId"));

        enviar(s, acc(ANULAR, mapper.createObjectNode().put("id", 404).put("motivo", "x")))
                .andExpect(jsonPath("$.error").value("No existe la muestra, fue eliminada, o ya estaba anulada"));

        enviar(s, acc(ANULAR, mapper.createObjectNode().put("id", 501).put("motivo", "x"))).andExpect(jsonPath("$.ok").value(true));
        enviar(s, acc(ANULAR, mapper.createObjectNode().put("id", 501).put("motivo", "otra vez")))
                .andExpect(jsonPath("$.error").value("No existe la muestra, fue eliminada, o ya estaba anulada"));
    }

    @Test
    void anularMotivoConCaracteresDeControlOHtmlSeRechaza() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(ANULAR, mapper.createObjectNode().put("id", 501).put("motivo", "<script>alert(1)</script>")))
                .andExpect(jsonPath("$.error").value("El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\")."));
    }

    // ------------------------------------------------------------------ ensayo.anular

    @Test
    void ensayoAnularCamposObligatoriosSinAutorEnElCuerpo() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(ENSAYO_ANULAR, mapper.createObjectNode().put("ensayoId", 0).put("motivo", "x")))
                .andExpect(jsonPath("$.error").value("Falta el ensayo o el motivo de anulacion"));
        enviar(s, acc(ENSAYO_ANULAR, mapper.createObjectNode().put("ensayoId", 9001).put("motivo", "")))
                .andExpect(jsonPath("$.error").value("Falta el ensayo o el motivo de anulacion"));

        JsonNode json = json(enviar(s, acc(ENSAYO_ANULAR, mapper.createObjectNode().put("ensayoId", 9001).put("motivo", "Error de lectura")))
                .andExpect(status().isOk()).andReturn());
        assertThat(json.at("/data/anulado").asBoolean()).isTrue();
        JsonNode recibido = API.cuerposEnsayoAnularLabRecibidos().get(0);
        assertThat(recibido.propertyNames()).containsExactly("motivo");
        assertThat(recibido.get("motivo").asString()).isEqualTo("Error de lectura");
    }

    @Test
    void ensayoAnularCampoNoPermitido() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode conExtra = mapper.createObjectNode().put("ensayoId", 9001).put("motivo", "x").put("analistaNombre", "Hacker");
        enviar(s, acc(ENSAYO_ANULAR, conExtra)).andExpect(jsonPath("$.error").value("Campo no permitido: analistaNombre"));
    }

    // ------------------------------------------------------------------ actualizarFechaEnsayo

    @Test
    void actualizarFechaEnsayoFormatoYCamposObligatorios() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(FECHA_ENSAYO, mapper.createObjectNode().put("id", 0).put("fechaEnsayo", "2026-10-02T10:30")))
                .andExpect(jsonPath("$.error").value("Falta indicar la muestra"));

        for (String malas : new String[] {"02-10-2026T10:30", "2026-10-02", "2026-10-02 10:30", ""}) {
            enviar(s, acc(FECHA_ENSAYO, mapper.createObjectNode().put("id", 501).put("fechaEnsayo", malas)))
                    .andExpect(jsonPath("$.error").value("Fecha inválida"));
        }

        JsonNode json = json(enviar(s, acc(FECHA_ENSAYO, mapper.createObjectNode().put("id", 501).put("fechaEnsayo", "2026-10-02T10:30")))
                .andExpect(status().isOk()).andReturn());
        assertThat(json.at("/data/id").asInt()).isEqualTo(501);
        JsonNode recibido = API.cuerposFechaEnsayoLabRecibidos().get(0);
        assertThat(recibido.get("fechaEnsayo").asString()).isEqualTo("2026-10-02T10:30");
        assertThat(recibido.get("usuarioNombre").asString()).isEqualTo("Operador Uno");

        // con segundos tambien es valida.
        enviar(s, acc(FECHA_ENSAYO, mapper.createObjectNode().put("id", 501).put("fechaEnsayo", "2026-10-02T10:30:15")))
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void actualizarFechaEnsayoMuestraAnuladaOInexistente() throws Exception {
        MockHttpSession s = login("operador1");
        API.anularMuestraLab(777);
        enviar(s, acc(FECHA_ENSAYO, mapper.createObjectNode().put("id", 777).put("fechaEnsayo", "2026-10-02T10:30")))
                .andExpect(jsonPath("$.error").value("El registro está anulado, no se puede editar la fecha"));

        enviar(s, acc(FECHA_ENSAYO, mapper.createObjectNode().put("id", 404).put("fechaEnsayo", "2026-10-02T10:30")))
                .andExpect(jsonPath("$.error").value("No existe la muestra o fue eliminada"));
    }

    @Test
    void actualizarFechaEnsayoCampoNoPermitido() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode conExtra = mapper.createObjectNode().put("id", 501).put("fechaEnsayo", "2026-10-02T10:30").put("usuarioNombre", "Hacker");
        enviar(s, acc(FECHA_ENSAYO, conExtra)).andExpect(jsonPath("$.error").value("Campo no permitido: usuarioNombre"));
    }

    // ------------------------------------------------------------------ roles / auditoría

    @Test
    void rolConsultaDenegado() throws Exception {
        MockHttpSession consulta = login("consulta1");
        for (String accion : new String[] {ANULAR, ENSAYO_ANULAR, FECHA_ENSAYO}) {
            enviar(consulta, acc(accion, mapper.createObjectNode().put("id", 501).put("ensayoId", 501)
                    .put("motivo", "x").put("fechaEnsayo", "2026-10-02T10:30")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        }
    }

    @Test
    void auditoriaConRecursoPorAccion(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(ANULAR, mapper.createObjectNode().put("id", 501).put("motivo", "x"))).andExpect(status().isOk());
        assertThat(salida.getAll()).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=muestraLab\\.anular recurso=muestraLab:501:anular resultado=OK ms=\\d+");

        enviar(s, acc(ENSAYO_ANULAR, mapper.createObjectNode().put("ensayoId", 9001).put("motivo", "x"))).andExpect(status().isOk());
        assertThat(salida.getAll()).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=muestraLab\\.ensayo\\.anular "
                        + "recurso=muestraLab:ensayo:9001:anular resultado=OK ms=\\d+");

        enviar(s, acc(FECHA_ENSAYO, mapper.createObjectNode().put("id", 502).put("fechaEnsayo", "2026-10-02T10:30"))).andExpect(status().isOk());
        assertThat(salida.getAll()).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=muestraLab\\.actualizarFechaEnsayo "
                        + "recurso=muestraLab:502:fechaEnsayo resultado=OK ms=\\d+");
    }

    // ------------------------------------------------------------------ helpers

    private String acc(String accion, ObjectNode data) {
        ObjectNode raiz = mapper.createObjectNode();
        raiz.put("action", accion);
        raiz.set("data", data);
        return raiz.toString();
    }

    private ResultActions enviar(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.31.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private JsonNode json(MvcResult res) throws Exception {
        return mapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-4e3");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
