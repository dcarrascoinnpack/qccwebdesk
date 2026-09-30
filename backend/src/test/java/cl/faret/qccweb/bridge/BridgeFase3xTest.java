package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import cl.faret.qccweb.auth.FakeInnpackApi.SeguimientoRecibido;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Fase 3x — recepcion.nc.crear ("Crear No Conformidad" del detalle de un lote No conforme), igual que Photino para el
 * usuario: la API crea la NC y después la vincula al lote (sin transacción ni bloqueo). Seguridad transparente: autor de
 * sesión, lista blanca {loteId}, lote de la empresa de sesión, candado por lote + huella ncId|estado del detalle contra
 * NC duplicadas (doble clic, dos sesiones, reintento). API SIMULADA.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3xTest {

    private static final String CREAR = "recepcion.nc.crear";
    private static final String SIN_LEER = "Abre el detalle del lote antes de crear la No Conformidad.";
    private static final String CONFLICTO = "El lote fue modificado por otra persona desde que lo abriste (No Conformidad "
            + "vinculada o estado). Vuelve a abrirlo para ver los cambios.";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveNcRecepcion#2026";
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

    /** Payload EXACTO de crearNoConformidad de Photino INNPACK: {action, data:{loteId}}. */
    private static String payload(Object loteId) {
        return "{\"action\":\"" + CREAR + "\",\"data\":{\"loteId\":" + loteId + "}}";
    }

    @Test
    void creaConAutorDeSesionYElDetalleQuedaVinculado(CapturedOutput salida) throws Exception {
        API.cambiarEstadoPorOtro(1, "NoConforme");
        MockHttpSession s = login("operador1");
        assertThat(abrir(s, 1).get("ncId").isNull()).isTrue();
        JsonNode json = json(enviar(s, payload(1)).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.at("/data/codigo").asString()).isEqualTo("NC-2026-900");
        SeguimientoRecibido r = API.ncRecepcionRecibidas().get(0);
        assertThat(r.sub()).isEqualTo(10);
        assertThat(r.contentType()).startsWith("application/json");
        assertThat(mapper.readTree(r.cuerpo())).isEqualTo(mapper.readTree("{\"usuarioNombre\":\"Operador Uno\"}"));
        // La vista reabre el detalle: NC vinculada (Photino deja de mostrar el botón).
        JsonNode det = abrir(s, 1);
        assertThat(det.get("ncId").asInt()).isEqualTo(900);
        assertThat(det.get("ncCodigo").asString()).isEqualTo("NC-2026-900");
        assertThat(API.peticionesRecepcion()).contains("POST /api/recepcion-calidad/1/nc");
        assertThat(salida.getAll()).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=recepcion\\.nc\\.crear "
                + "recurso=recepcion:1:nc:900 resultado=OK ms=\\d+");
        assertThat(salida.getAll()).doesNotContain(FakeInnpackApi.firmaDeToken(10), PASS);
    }

    @Test
    void listaBlancaAutorNoFalsificableYLote() throws Exception {
        API.cambiarEstadoPorOtro(1, "NoConforme");
        MockHttpSession s = login("operador1");
        abrir(s, 1);
        enviar(s, "{\"action\":\"" + CREAR + "\",\"data\":{\"loteId\":1,\"usuarioNombre\":\"Otro\"}}")
                .andExpect(jsonPath("$.error").value("Campo no permitido: usuarioNombre"));
        enviar(s, "{\"action\":\"" + CREAR + "\",\"usuarioNombre\":\"Otro\",\"data\":{\"loteId\":1}}")
                .andExpect(jsonPath("$.error").value("Campo no permitido: usuarioNombre"));
        for (String malo : new String[] {"0", "-1", "null", "\"abc\"", "true", "1.5"}) {
            enviar(s, payload(malo)).andExpect(jsonPath("$.error").value("Falta indicar el lote"));
        }
        // Lote no abierto en la sesión (o de otra empresa / inexistente: su detalle no existe para esta sesión).
        enviar(s, payload(3)).andExpect(jsonPath("$.error").value(SIN_LEER));
        crear(s, "{\"action\":\"recepcion.detalle\",\"data\":{\"id\":99}}").andExpect(jsonPath("$.ok").value(false));
        enviar(s, payload(99)).andExpect(jsonPath("$.error").value(SIN_LEER));
        assertThat(API.ncRecepcionRecibidas()).isEmpty();
    }

    @Test
    void reglasDeLaApiSeMuestranComoEnPhotino() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 1); // PendienteMuestreo: la vista no muestra el botón; si llega igual, la API decide
        enviar(s, payload(1)).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Solo se puede crear una No Conformidad cuando el lote quedó \"No conforme\""));
        API.cambiarEstadoPorOtro(3, "NoConforme");
        API.crearNcRecepcionPorOtro(3);
        abrir(s, 3);
        enviar(s, payload(3)).andExpect(jsonPath("$.error").value("Este lote ya tiene una No Conformidad vinculada"));
        assertThat(API.ncRecepcionCreadas()).isEqualTo(1); // solo la creada "por otro"
    }

    @Test
    void ncCreadaPorOtroDesdeQueSeAbrioEsConflictoYReabrirLoMuestra() throws Exception {
        API.cambiarEstadoPorOtro(1, "NoConforme");
        MockHttpSession s = login("operador1");
        abrir(s, 1);
        API.crearNcRecepcionPorOtro(1);
        enviar(s, payload(1)).andExpect(jsonPath("$.error").value(CONFLICTO));
        assertThat(API.ncRecepcionRecibidas()).isEmpty();
        assertThat(abrir(s, 1).get("ncId").asInt()).isEqualTo(900);
        // Cambio de estado por otra sesión: también conflicto.
        API.cambiarEstadoPorOtro(4, "NoConforme");
        abrir(s, 4);
        API.cambiarEstadoPorOtro(4, "RecibidaConforme");
        enviar(s, payload(4)).andExpect(jsonPath("$.error").value(CONFLICTO));
        assertThat(API.ncRecepcionRecibidas()).isEmpty();
    }

    @Test
    void dobleClicYDosSesionesNoDuplicanLaNc() throws Exception {
        API.cambiarEstadoPorOtro(1, "NoConforme");
        API.cambiarEstadoPorOtro(5, "NoConforme");
        API.demoraNcRecepcion(300);
        MockHttpSession s = login("operador1");
        abrir(s, 1);
        List<JsonNode> r = simultaneos(() -> json(enviar(s, payload(1)).andReturn()), () -> json(enviar(s, payload(1)).andReturn()));
        assertThat(r.stream().filter(x -> x.get("ok").asBoolean()).count()).isEqualTo(1);
        assertThat(r.stream().filter(x -> !x.get("ok").asBoolean()).map(x -> x.get("error").asString())).containsExactly(SIN_LEER);
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        abrir(a, 5);
        abrir(b, 5);
        r = simultaneos(() -> json(enviar(a, payload(5)).andReturn()), () -> json(enviar(b, payload(5)).andReturn()));
        assertThat(r.stream().filter(x -> x.get("ok").asBoolean()).count()).isEqualTo(1);
        assertThat(r.stream().filter(x -> !x.get("ok").asBoolean()).map(x -> x.get("error").asString())).containsExactly(CONFLICTO);
        assertThat(API.ncRecepcionCreadas()).isEqualTo(2); // una por lote
    }

    @Test
    void falloParcialDeLaApiQuedaDocumentado() throws Exception {
        // Lote 24: la API crea la NC y falla al vincularla (sin transacción). El lote sigue sin NC: la web no puede
        // distinguirlo de un fallo sin efecto, y un reintento crea otra NC (residual de la API, documentado).
        API.cambiarEstadoPorOtro(24, "NoConforme");
        MockHttpSession s = login("operador1");
        abrir(s, 24);
        enviar(s, payload(24)).andExpect(jsonPath("$.ok").value(false));
        assertThat(API.ncRecepcionCreadas()).isEqualTo(1);
        assertThat(abrir(s, 24).get("ncId").isNull()).isTrue();
    }

    @Test
    void mismosRolesQuePhotino() throws Exception {
        API.cambiarEstadoPorOtro(1, "NoConforme");
        MockHttpSession c = login("consulta1");
        enviar(c, payload(1)).andExpect(status().isForbidden());
        MockHttpSession admin = login("admin1");
        abrir(admin, 1);
        enviar(admin, payload(1)).andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(API.ncRecepcionRecibidas().get(0).cuerpo()).get("usuarioNombre").asString()).isEqualTo("Admin Uno");
    }

    // ------------------------------------------------------------------ helpers

    private List<JsonNode> simultaneos(Callable<JsonNode> uno, Callable<JsonNode> dos) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<JsonNode> f1 = ex.submit(uno);
            Future<JsonNode> f2 = ex.submit(dos);
            return List.of(f1.get(), f2.get());
        } finally {
            ex.shutdownNow();
        }
    }

    /** "Ver" de Photino: recepcion.detalle (registra la huella en la sesión). */
    private JsonNode abrir(MockHttpSession sesion, int id) throws Exception {
        MvcResult res = crear(sesion, "{\"action\":\"recepcion.detalle\",\"data\":{\"id\":" + id + "}}").andExpect(jsonPath("$.ok").value(true)).andReturn();
        return json(res).get("data");
    }

    private ResultActions enviar(MockHttpSession sesion, String cuerpo) throws Exception {
        return crear(sesion, cuerpo);
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.36.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private ResultActions crear(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private JsonNode json(MvcResult res) throws Exception {
        return mapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-3x");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
