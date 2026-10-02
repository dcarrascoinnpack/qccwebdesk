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
 * Fase 4e-5 — `muestraLab.eliminar` / `muestraLab.adjunto.eliminar`, igual que Photino para el usuario. Cierra las
 * 25 escrituras de Laboratorio (25/25). Ninguna de las dos recibe autor (ni Photino ni la API lo registran) —
 * auditoría obligatoria del gateway. `id`/`adjuntoId` se rechazan ANTES de llamar a la API si faltan (igual que el
 * handler C# de Photino). A diferencia de `controlDocumental.eliminar`, la API de Laboratorio SÍ verifica
 * existencia (filas afectadas / 404 real), así que el gateway no necesita releer antes de borrar.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase4e5Test {

    private static final String ELIMINAR = "muestraLab.eliminar";
    private static final String ADJUNTO_ELIMINAR = "muestraLab.adjunto.eliminar";

    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveMuestraLab4e5#2026";
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

    // ------------------------------------------------------------------ eliminar

    @Test
    void eliminarFaltaIdYCampoNoPermitido() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(ELIMINAR, mapper.createObjectNode().put("id", 0)))
                .andExpect(jsonPath("$.error").value("Falta la muestra a eliminar"));

        ObjectNode conExtra = mapper.createObjectNode().put("id", 501).put("motivo", "x");
        enviar(s, acc(ELIMINAR, conExtra)).andExpect(jsonPath("$.error").value("Campo no permitido: motivo"));
    }

    @Test
    void eliminarExitosoEIdempotenteConElErrorRealDeLaApi() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(enviar(s, acc(ELIMINAR, mapper.createObjectNode().put("id", 501)))
                .andExpect(status().isOk()).andReturn());
        assertThat(json.at("/data/id").asInt()).isEqualTo(501);

        // segundo intento sobre la misma muestra: la API ya la encuentra eliminada (filas afectadas = 0).
        enviar(s, acc(ELIMINAR, mapper.createObjectNode().put("id", 501)))
                .andExpect(jsonPath("$.error").value("No existe la muestra o ya estaba eliminada"));
    }

    @Test
    void eliminarMuestraInexistente() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(ELIMINAR, mapper.createObjectNode().put("id", 404)))
                .andExpect(jsonPath("$.error").value("No existe la muestra o ya estaba eliminada"));
    }

    // ------------------------------------------------------------------ adjunto.eliminar

    @Test
    void adjuntoEliminarFaltaIdYCampoNoPermitido() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(ADJUNTO_ELIMINAR, mapper.createObjectNode().put("adjuntoId", 0)))
                .andExpect(jsonPath("$.error").value("Falta indicar el adjunto"));

        ObjectNode conExtra = mapper.createObjectNode().put("adjuntoId", 1).put("muestraId", 501);
        enviar(s, acc(ADJUNTO_ELIMINAR, conExtra)).andExpect(jsonPath("$.error").value("Campo no permitido: muestraId"));
    }

    @Test
    void adjuntoEliminarExitosoEInexistente() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(enviar(s, acc(ADJUNTO_ELIMINAR, mapper.createObjectNode().put("adjuntoId", 1)))
                .andExpect(status().isOk()).andReturn());
        assertThat(json.at("/data/adjuntoId").asInt()).isEqualTo(1);

        enviar(s, acc(ADJUNTO_ELIMINAR, mapper.createObjectNode().put("adjuntoId", 1)))
                .andExpect(jsonPath("$.error").value("Adjunto no encontrado"));

        API.eliminarAdjuntoLabPorOtro(777);
        enviar(s, acc(ADJUNTO_ELIMINAR, mapper.createObjectNode().put("adjuntoId", 777)))
                .andExpect(jsonPath("$.error").value("Adjunto no encontrado"));
    }

    // ------------------------------------------------------------------ roles / auditoría

    @Test
    void rolConsultaDenegado() throws Exception {
        MockHttpSession consulta = login("consulta1");
        enviar(consulta, acc(ELIMINAR, mapper.createObjectNode().put("id", 501))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        enviar(consulta, acc(ADJUNTO_ELIMINAR, mapper.createObjectNode().put("adjuntoId", 1))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
    }

    @Test
    void auditoriaConRecursoPorAccion(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(ELIMINAR, mapper.createObjectNode().put("id", 501))).andExpect(status().isOk());
        assertThat(salida.getAll()).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=muestraLab\\.eliminar "
                        + "recurso=muestraLab:501:eliminar resultado=OK ms=\\d+");

        enviar(s, acc(ADJUNTO_ELIMINAR, mapper.createObjectNode().put("adjuntoId", 1))).andExpect(status().isOk());
        assertThat(salida.getAll()).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=muestraLab\\.adjunto\\.eliminar "
                        + "recurso=muestraLab:adjunto:1:eliminar resultado=OK ms=\\d+");
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
                            r.setRemoteAddr("10.33.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-4e5");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
