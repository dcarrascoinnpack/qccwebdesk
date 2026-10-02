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
 * Fase 4f — `inicio.frecuencias.actualizar`, `productoTerminado.eliminar` y
 * `productoTerminado.actualizarFecha`, igual que Photino para el usuario. Ninguna de las tres recibe autor real: las
 * dos de Inicio/eliminar no tienen campo para ello en la API; `actualizarFecha` sí lo tiene (`usuarioNombre`), pero
 * sale SIEMPRE de sesión (ni siquiera es clave aceptada del payload, igual que en Laboratorio), aunque en Photino lo
 * lea de `sessionStorage` en vez del servidor. `id` se rechaza ANTES de llamar a la API en las 3, igual que el
 * handler C# de Photino.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase4fTest {

    private static final String FRECUENCIAS = "inicio.frecuencias.actualizar";
    private static final String PT_ELIMINAR = "productoTerminado.eliminar";
    private static final String PT_FECHA = "productoTerminado.actualizarFecha";

    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "Clave4f#2026";
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

    // ------------------------------------------------------------------ inicio.frecuencias.actualizar

    @Test
    void frecuenciasParametrosInvalidosYCampoNoPermitido() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(FRECUENCIAS, mapper.createObjectNode().put("id", 0).put("frecuenciaMinutos", 30)))
                .andExpect(jsonPath("$.error").value("Parámetros inválidos para actualizar la frecuencia."));
        enviar(s, acc(FRECUENCIAS, mapper.createObjectNode().put("id", 5).put("frecuenciaMinutos", 0)))
                .andExpect(jsonPath("$.error").value("Parámetros inválidos para actualizar la frecuencia."));

        ObjectNode conExtra = mapper.createObjectNode().put("id", 5).put("frecuenciaMinutos", 30).put("usuarioNombre", "Hacker");
        enviar(s, acc(FRECUENCIAS, conExtra)).andExpect(jsonPath("$.error").value("Campo no permitido: usuarioNombre"));
    }

    @Test
    void frecuenciasExitoso() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(enviar(s, acc(FRECUENCIAS, mapper.createObjectNode().put("id", 5).put("frecuenciaMinutos", 45)))
                .andExpect(status().isOk()).andReturn());
        assertThat(json.at("/data/id").asInt()).isEqualTo(5);
        assertThat(API.cuerposFrecuenciaRecibidos().get(0).get("frecuenciaMinutos").asInt()).isEqualTo(45);
    }

    // ------------------------------------------------------------------ productoTerminado.eliminar

    @Test
    void ptEliminarFaltaIdYCampoNoPermitido() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(PT_ELIMINAR, mapper.createObjectNode().put("id", 0)))
                .andExpect(jsonPath("$.error").value("Falta el id de la inspección"));

        ObjectNode conExtra = mapper.createObjectNode().put("id", 501).put("empresa", "FARET");
        enviar(s, acc(PT_ELIMINAR, conExtra)).andExpect(jsonPath("$.error").value("Campo no permitido: empresa"));
    }

    @Test
    void ptEliminarExitosoEIdempotenteYNoExistente() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(PT_ELIMINAR, mapper.createObjectNode().put("id", 501))).andExpect(jsonPath("$.ok").value(true));

        enviar(s, acc(PT_ELIMINAR, mapper.createObjectNode().put("id", 501)))
                .andExpect(jsonPath("$.error").value("No se encontró la inspección solicitada"));

        enviar(s, acc(PT_ELIMINAR, mapper.createObjectNode().put("id", 404)))
                .andExpect(jsonPath("$.error").value("No se encontró la inspección solicitada"));
    }

    // ------------------------------------------------------------------ productoTerminado.actualizarFecha

    @Test
    void ptFechaFormatoYCamposObligatorios() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(PT_FECHA, mapper.createObjectNode().put("id", 0).put("fechaRegistro", "2026-10-02")))
                .andExpect(jsonPath("$.error").value("Falta el id de la inspección"));

        enviar(s, acc(PT_FECHA, mapper.createObjectNode().put("id", 501).put("fechaRegistro", "02-10-2026")))
                .andExpect(jsonPath("$.error").value("La fecha no es válida (formato AAAA-MM-DD)."));

        enviar(s, acc(PT_FECHA, mapper.createObjectNode().put("id", 501).put("fechaRegistro", "2026-10-02").put("horaRegistro", "10h30")))
                .andExpect(jsonPath("$.error").value("La hora no es válida (formato HH:mm)."));

        JsonNode json = json(enviar(s, acc(PT_FECHA, mapper.createObjectNode().put("id", 501).put("fechaRegistro", "2026-10-02").put("horaRegistro", "10:30")))
                .andExpect(status().isOk()).andReturn());
        assertThat(json.at("/data/id").asInt()).isEqualTo(501);
        JsonNode recibido = API.cuerposFechaProductoTerminadoRecibidos().get(0);
        assertThat(recibido.get("empresa").asString()).isEqualTo("INNPACK");
        assertThat(recibido.get("fechaRegistro").asString()).isEqualTo("2026-10-02");
        assertThat(recibido.get("horaRegistro").asString()).isEqualTo("10:30");
        assertThat(recibido.get("usuarioNombre").asString()).isEqualTo("Operador Uno");

        // horaRegistro es opcional: en blanco se acepta.
        enviar(s, acc(PT_FECHA, mapper.createObjectNode().put("id", 501).put("fechaRegistro", "2026-10-02")))
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void ptFechaUsuarioManipuladoSeRechazaYRegistroInexistente() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode manipulado = mapper.createObjectNode().put("id", 501).put("fechaRegistro", "2026-10-02").put("usuarioNombre", "Hacker");
        enviar(s, acc(PT_FECHA, manipulado)).andExpect(jsonPath("$.error").value("Campo no permitido: usuarioNombre"));

        enviar(s, acc(PT_FECHA, mapper.createObjectNode().put("id", 404).put("fechaRegistro", "2026-10-02")))
                .andExpect(jsonPath("$.error").value("No se encontró la inspección solicitada"));
    }

    // ------------------------------------------------------------------ roles / auditoría

    @Test
    void rolConsultaDenegado() throws Exception {
        MockHttpSession consulta = login("consulta1");
        for (String accion : new String[] {FRECUENCIAS, PT_ELIMINAR, PT_FECHA}) {
            enviar(consulta, acc(accion, mapper.createObjectNode().put("id", 1).put("frecuenciaMinutos", 30).put("fechaRegistro", "2026-10-02")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        }
    }

    @Test
    void auditoriaConRecursoPorAccion(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(FRECUENCIAS, mapper.createObjectNode().put("id", 5).put("frecuenciaMinutos", 30))).andExpect(status().isOk());
        assertThat(salida.getAll()).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=inicio\\.frecuencias\\.actualizar "
                        + "recurso=inicio:frecuencia:5 resultado=OK ms=\\d+");

        enviar(s, acc(PT_FECHA, mapper.createObjectNode().put("id", 501).put("fechaRegistro", "2026-10-02"))).andExpect(status().isOk());
        assertThat(salida.getAll()).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=productoTerminado\\.actualizarFecha "
                        + "recurso=productoTerminado:501:fecha resultado=OK ms=\\d+");

        enviar(s, acc(PT_ELIMINAR, mapper.createObjectNode().put("id", 502))).andExpect(status().isOk());
        assertThat(salida.getAll()).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=productoTerminado\\.eliminar "
                        + "recurso=productoTerminado:502:eliminar resultado=OK ms=\\d+");
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
                            r.setRemoteAddr("10.34.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-4f");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
