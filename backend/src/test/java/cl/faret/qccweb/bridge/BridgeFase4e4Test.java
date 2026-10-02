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
 * Fase 4e-4 — maestros de Laboratorio: `metodo.guardar`, `metodo.activar`, `especificacion.guardar`,
 * `especificacion.activar`, igual que Photino para el usuario. Con esto Laboratorio queda 23/25. `metodo.guardar`
 * es la única de las 4 con autor (usuarioNombre ← sesión, ni siquiera clave aceptada del payload); las otras 3 no
 * reciben autor (la API no tiene campo para ello). `tipoEnsayo`/`variante`/`tipoMuestra` restringidos a los
 * `<select>` de Photino (la API solo valida que los obligatorios no vengan en blanco). `*.activar` comparten el
 * mismo patrón: `id` se rechaza antes de llamar a la API, `activo` solo es `true` si el JSON trae literalmente el
 * booleano `true` (igual que Photino).
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase4e4Test {

    private static final String METODO_GUARDAR = "muestraLab.metodo.guardar";
    private static final String METODO_ACTIVAR = "muestraLab.metodo.activar";
    private static final String ESPEC_GUARDAR = "muestraLab.especificacion.guardar";
    private static final String ESPEC_ACTIVAR = "muestraLab.especificacion.activar";

    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveMuestraLab4e4#2026";
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

    // ------------------------------------------------------------------ metodo.guardar

    @Test
    void metodoGuardarMinimoYUsuarioManipuladoSeRechaza() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(enviar(s, acc(METODO_GUARDAR, cuerpoMetodo())).andExpect(status().isOk()).andReturn());
        assertThat(json.at("/data/id").asInt()).isGreaterThan(0);
        JsonNode recibido = API.cuerposMetodoGuardadoRecibidos().get(0);
        assertThat(recibido.get("usuarioNombre").asString()).isEqualTo("Operador Uno");
        assertThat(recibido.get("variante").isNull()).as("variante en blanco viaja null, no \"\"").isTrue();

        ObjectNode manipulado = cuerpoMetodo();
        manipulado.put("usuarioNombre", "Hacker");
        enviar(s, acc(METODO_GUARDAR, manipulado)).andExpect(jsonPath("$.error").value("Campo no permitido: usuarioNombre"));
    }

    @Test
    void metodoGuardarCamposObligatoriosYSelectsRestringidos() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(METODO_GUARDAR, cuerpoMetodo().put("tipoEnsayo", "")))
                .andExpect(jsonPath("$.error").value("Falta el tipo de ensayo"));
        enviar(s, acc(METODO_GUARDAR, cuerpoMetodo().put("nombre", "")))
                .andExpect(jsonPath("$.error").value("Falta el nombre del método"));
        enviar(s, acc(METODO_GUARDAR, cuerpoMetodo().put("tipoEnsayo", "LUGOL")))
                .andExpect(jsonPath("$.error").value("Valor no permitido en tipoEnsayo."));
        enviar(s, acc(METODO_GUARDAR, cuerpoMetodo().put("variante", "Microondas")))
                .andExpect(jsonPath("$.error").value("Valor no permitido en variante."));

        ObjectNode valido = cuerpoMetodo().put("variante", "Horno");
        enviar(s, acc(METODO_GUARDAR, valido)).andExpect(jsonPath("$.ok").value(true));
        var recibidos = API.cuerposMetodoGuardadoRecibidos();
        assertThat(recibidos.get(recibidos.size() - 1).get("variante").asString()).isEqualTo("Horno");
    }

    @Test
    void metodoGuardarCampoNoPermitidoYEdicionConId() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode conExtra = cuerpoMetodo().put("activo", true);
        enviar(s, acc(METODO_GUARDAR, conExtra)).andExpect(jsonPath("$.error").value("Campo no permitido: activo"));

        ObjectNode edicion = cuerpoMetodo().put("id", 42);
        enviar(s, acc(METODO_GUARDAR, edicion)).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.cuerposMetodoGuardadoRecibidos().get(0).get("id").asInt()).isEqualTo(42);
    }

    // ------------------------------------------------------------------ metodo.activar

    @Test
    void metodoActivarFaltaIdYSoloBooleanoLiteralActiva() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(METODO_ACTIVAR, mapper.createObjectNode().put("id", 0).put("activo", true)))
                .andExpect(jsonPath("$.error").value("Falta indicar el método"));

        JsonNode json = json(enviar(s, acc(METODO_ACTIVAR, mapper.createObjectNode().put("id", 42).put("activo", true)))
                .andExpect(status().isOk()).andReturn());
        assertThat(json.at("/data/activo").asBoolean()).isTrue();

        // "true" (string) o 1 no cuentan como activar: solo el booleano JSON literal.
        JsonNode json2 = json(enviar(s, acc(METODO_ACTIVAR, mapper.createObjectNode().put("id", 42).put("activo", "true")))
                .andExpect(status().isOk()).andReturn());
        assertThat(json2.at("/data/activo").asBoolean()).isFalse();
    }

    @Test
    void metodoActivarCampoNoPermitido() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode conExtra = mapper.createObjectNode().put("id", 42).put("activo", true).put("usuarioNombre", "Hacker");
        enviar(s, acc(METODO_ACTIVAR, conExtra)).andExpect(jsonPath("$.error").value("Campo no permitido: usuarioNombre"));
    }

    // ------------------------------------------------------------------ especificacion.guardar

    @Test
    void especificacionGuardarSinAutorEnElCuerpoYCamposObligatorios() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(ESPEC_GUARDAR, cuerpoEspecificacion().put("tipoMuestra", "")))
                .andExpect(jsonPath("$.error").value("Tipo de muestra y tipo de ensayo son obligatorios"));

        ObjectNode sinLimites = cuerpoEspecificacion();
        sinLimites.remove("limiteMin");
        sinLimites.remove("limiteMax");
        enviar(s, acc(ESPEC_GUARDAR, sinLimites)).andExpect(jsonPath("$.error").value("Debes indicar al menos un límite (mínimo o máximo)"));

        JsonNode json = json(enviar(s, acc(ESPEC_GUARDAR, cuerpoEspecificacion())).andExpect(status().isOk()).andReturn());
        assertThat(json.at("/data/id").asInt()).isGreaterThan(0);
        var recibidos = API.cuerposEspecificacionGuardadaRecibidos();
        JsonNode recibido = recibidos.get(recibidos.size() - 1);
        assertThat(recibido.propertyNames()).doesNotContain("usuarioNombre");
    }

    @Test
    void especificacionGuardarSelectsRestringidosYCampoNoPermitido() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(ESPEC_GUARDAR, cuerpoEspecificacion().put("tipoMuestra", "Inventado")))
                .andExpect(jsonPath("$.error").value("Valor no permitido en tipoMuestra."));
        enviar(s, acc(ESPEC_GUARDAR, cuerpoEspecificacion().put("tipoEnsayo", "LUGOL")))
                .andExpect(jsonPath("$.error").value("Valor no permitido en tipoEnsayo."));

        ObjectNode conExtra = cuerpoEspecificacion().put("usuarioNombre", "Hacker");
        enviar(s, acc(ESPEC_GUARDAR, conExtra)).andExpect(jsonPath("$.error").value("Campo no permitido: usuarioNombre"));
    }

    // ------------------------------------------------------------------ especificacion.activar

    @Test
    void especificacionActivarFaltaIdYCampoNoPermitido() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(ESPEC_ACTIVAR, mapper.createObjectNode().put("id", 0).put("activo", true)))
                .andExpect(jsonPath("$.error").value("Falta indicar la especificación"));

        JsonNode json = json(enviar(s, acc(ESPEC_ACTIVAR, mapper.createObjectNode().put("id", 7).put("activo", false)))
                .andExpect(status().isOk()).andReturn());
        assertThat(json.at("/data/activo").asBoolean()).isFalse();

        ObjectNode conExtra = mapper.createObjectNode().put("id", 7).put("activo", true).put("motivo", "x");
        enviar(s, acc(ESPEC_ACTIVAR, conExtra)).andExpect(jsonPath("$.error").value("Campo no permitido: motivo"));
    }

    // ------------------------------------------------------------------ roles / auditoría

    @Test
    void rolConsultaDenegado() throws Exception {
        MockHttpSession consulta = login("consulta1");
        for (String accion : new String[] {METODO_GUARDAR, METODO_ACTIVAR, ESPEC_GUARDAR, ESPEC_ACTIVAR}) {
            enviar(consulta, acc(accion, mapper.createObjectNode().put("id", 1).put("activo", true)
                    .put("tipoEnsayo", "HUMEDAD").put("nombre", "x").put("tipoMuestra", "Papel").put("limiteMin", 1)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        }
    }

    @Test
    void auditoriaConRecursoPorAccion(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode creado = json(enviar(s, acc(METODO_GUARDAR, cuerpoMetodo())).andExpect(status().isOk()).andReturn());
        int id = creado.at("/data/id").asInt();
        assertThat(salida.getAll()).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=muestraLab\\.metodo\\.guardar "
                        + "recurso=muestraLab:metodo:" + id + " resultado=OK ms=\\d+");

        enviar(s, acc(METODO_ACTIVAR, mapper.createObjectNode().put("id", id).put("activo", true))).andExpect(status().isOk());
        assertThat(salida.getAll()).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=muestraLab\\.metodo\\.activar "
                        + "recurso=muestraLab:metodo:" + id + ":activo resultado=OK ms=\\d+");

        JsonNode especCreada = json(enviar(s, acc(ESPEC_GUARDAR, cuerpoEspecificacion())).andExpect(status().isOk()).andReturn());
        int especId = especCreada.at("/data/id").asInt();
        assertThat(salida.getAll()).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=muestraLab\\.especificacion\\.guardar "
                        + "recurso=muestraLab:especificacion:" + especId + " resultado=OK ms=\\d+");

        enviar(s, acc(ESPEC_ACTIVAR, mapper.createObjectNode().put("id", especId).put("activo", true))).andExpect(status().isOk());
        assertThat(salida.getAll()).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=muestraLab\\.especificacion\\.activar "
                        + "recurso=muestraLab:especificacion:" + especId + ":activo resultado=OK ms=\\d+");
    }

    // ------------------------------------------------------------------ helpers

    private ObjectNode cuerpoMetodo() {
        return mapper.createObjectNode().put("tipoEnsayo", "HUMEDAD").put("variante", "")
                .put("nombre", "Método X").put("codigo", "C-1").put("version", "1.0").put("unidad", "%");
    }

    private ObjectNode cuerpoEspecificacion() {
        return mapper.createObjectNode().put("tipoMuestra", "Papel").put("tipoEnsayo", "HUMEDAD")
                .put("codigoProducto", "COD-1").put("limiteMin", 1).put("limiteMax", 10).put("unidad", "%");
    }

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
                            r.setRemoteAddr("10.32.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-4e4");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
