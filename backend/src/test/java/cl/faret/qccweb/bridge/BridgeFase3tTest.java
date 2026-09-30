package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import cl.faret.qccweb.auth.FakeInnpackApi.SeguimientoRecibido;
import cl.faret.qccweb.auth.SessionUser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Fase 3t — recepcion.estado.actualizar, igual que Photino para el usuario: la API hace un UPDATE directo del estado
 * del lote (sin autor, sin control de duplicados, sin validar el valor). Seguridad transparente: lote de la empresa de
 * sesión (la API no filtra empresa ni eliminado — hallazgo R5), estado restringido a los 3 valores del &lt;select&gt;
 * de Photino, detección de cambios concurrentes (huella del estado leído en el detalle) y candado por lote.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3tTest {

    private static final String ACTUALIZAR = "recepcion.estado.actualizar";
    private static final String MSG_FALTA = "Falta el lote o el estado";
    private static final String MSG_INVALIDO = "Estado inválido.";
    private static final String MSG_SIN_LEER = "Abre el detalle del lote antes de actualizar el estado.";
    private static final String MSG_CONFLICTO = "El estado del lote fue modificado por otra persona desde que lo abriste "
            + "(muestreo, muestra de Laboratorio o actualización de estado). Vuelve a abrirlo para ver los cambios.";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveEstadoLote#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(20, "admin1", PASS, "Admin Uno", "admin", true));
        API.agregar(new FakeInnpackApi.Usuario(25, "adminti1", PASS, "Admin TI", "admin_ti", true));
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

    @Autowired
    private ActionPolicy policy;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reiniciar() {
        API.reiniciarDashboard();
    }

    @AfterAll
    static void cerrar() {
        API.close();
    }

    /** Payload EXACTO de actualizarEstado de Photino INNPACK: {action, data:{loteId, estado}}. */
    private static String payload(Object loteId, Object estado) {
        return "{\"action\":\"" + ACTUALIZAR + "\",\"data\":{\"loteId\":" + loteId + ",\"estado\":" + estado + "}}";
    }

    /** Payload con un estado simple (sin comillas en el argumento); evita colisionar con el overload (Object, Object). */
    private static String pEstado(int loteId, String estado) {
        return "{\"action\":\"" + ACTUALIZAR + "\",\"data\":{\"loteId\":" + loteId + ",\"estado\":\"" + estado + "\"}}";
    }

    // ------------------------------------------------------------------ flujo exitoso y refresco

    @Test
    void actualizacionCorrectaConCuerpoExactoYRefresco() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode antes = abrir(s, 1);
        assertThat(antes.get("estado").asString()).isEqualTo("PendienteMuestreo");
        JsonNode json = json(enviar(s, pEstado(1, "RecibidaConforme")).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.at("/data/actualizado").asBoolean()).isTrue();
        assertThat(API.peticionesRecepcion()).containsExactly("GET /api/recepcion-calidad/1?empresa=INNPACK",
                "GET /api/recepcion-calidad/1?empresa=INNPACK", "PATCH /api/recepcion-calidad/1/estado");
        SeguimientoRecibido r = API.estadosRecibidos().get(0);
        assertThat(r.sub()).isEqualTo(10);
        assertThat(r.contentType()).startsWith("application/json");
        assertThat(mapper.readTree(r.cuerpo())).isEqualTo(mapper.readTree("{\"estado\":\"RecibidaConforme\"}"));
        // Refresco inmediato (la vista reabre el detalle): estado actualizado.
        assertThat(abrir(s, 1).get("estado").asString()).isEqualTo("RecibidaConforme");
    }

    @Test
    void losTresValoresDelSelectFuncionanEnCualquierEstadoComoPhotino() throws Exception {
        MockHttpSession s = login("operador1");
        for (String estado : List.of("RecibidaConforme", "RecibidaConObservacion", "NoConforme")) {
            abrir(s, 1);
            enviar(s, pEstado(1, estado)).andExpect(jsonPath("$.ok").value(true));
            assertThat(abrir(s, 1).get("estado").asString()).isEqualTo(estado);
        }
        // También sobre un lote de bobinas (otro flujo de estado) y volviendo a un valor anterior (sin transición restringida).
        abrir(s, 20);
        enviar(s, pEstado(20, "NoConforme")).andExpect(jsonPath("$.ok").value(true));
        abrir(s, 20);
        enviar(s, pEstado(20, "RecibidaConforme")).andExpect(jsonPath("$.ok").value(true));
        assertThat(abrir(s, 20).get("estado").asString()).isEqualTo("RecibidaConforme");
    }

    @Test
    void identidadEmpresaYRolesComoPhotino() throws Exception {
        for (String usuario : List.of("operador1", "admin1", "adminti1")) {
            MockHttpSession s = login(usuario);
            abrir(s, 1);
            enviar(s, pEstado(1, "RecibidaConforme")).andExpect(jsonPath("$.ok").value(true));
        }
        enviar(login("consulta1"), pEstado(1, "RecibidaConforme")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(policy.evaluar(ACTUALIZAR, usuario("FARET", "admin")))
                .isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(ACTUALIZAR)).findFirst().orElseThrow();
        assertThat(d.get("escritura")).isEqualTo(true);
        assertThat(d.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
        assertThat(d.get("identidad")).isEqualTo(Map.of());
        for (String otra : List.of("recepcion.crear", "recepcion.plan.generar")) {
            assertThat(policy.accionesRegistradas()).doesNotContain(otra);
        }
    }

    // ------------------------------------------------------------------ validaciones

    @Test
    void camposNoPermitidosSeRechazanSinLlamarALaApi() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 1);
        for (String campo : List.of("empresa", "usuarioId", "usuario", "usuarioNombre")) {
            ObjectNode raiz = (ObjectNode) mapper.readTree(pEstado(1, "RecibidaConforme"));
            raiz.put(campo, "FARET");
            enviar(s, raiz.toString()).andExpect(jsonPath("$.error").value("Campo no permitido: " + campo));
            ObjectNode enData = (ObjectNode) mapper.readTree(pEstado(1, "RecibidaConforme"));
            ((ObjectNode) enData.get("data")).put(campo, "Otra Persona");
            enviar(s, enData.toString()).andExpect(jsonPath("$.error").value("Campo no permitido: " + campo));
        }
        assertThat(API.estadosRecibidos()).isEmpty();
    }

    @Test
    void loteOEstadoFaltanteOInvalido() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 1);
        for (String lote : List.of("0", "-1", "\"abc\"", "true", "null", "1.5", "{}")) {
            enviar(s, payload(lote, "\"RecibidaConforme\"")).andExpect(jsonPath("$.error").value(MSG_FALTA));
        }
        // GetString de Photino: bool/objeto/array → "" (no string/número) → falta el estado.
        for (String estado : List.of("\"\"", "\"  \"", "null", "true", "{}")) {
            enviar(s, payload(1, estado)).andExpect(jsonPath("$.error").value(MSG_FALTA));
        }
        enviar(s, "{\"action\":\"" + ACTUALIZAR + "\",\"data\":{\"loteId\":1}}").andExpect(jsonPath("$.error").value(MSG_FALTA));
        enviar(s, "{\"action\":\"" + ACTUALIZAR + "\",\"data\":{}}").andExpect(jsonPath("$.error").value(MSG_FALTA));
        for (String estado : List.of("\"Aprobada\"", "\"recibidaconforme\"", "\"<script>\"", "123")) {
            enviar(s, payload(1, estado)).andExpect(jsonPath("$.error").value(MSG_INVALIDO));
        }
        assertThat(API.estadosRecibidos()).isEmpty();
    }

    @Test
    void loteInexistenteODeOtraEmpresaNoSePuedeActualizar() throws Exception {
        MockHttpSession s = login("operador1");
        // Nunca se pudo abrir (empresa≠INNPACK o inexistente en la simulación): sin huella registrada.
        crear(s, "{\"action\":\"recepcion.detalle\",\"data\":{\"id\":99}}").andExpect(jsonPath("$.ok").value(false));
        enviar(s, pEstado(99, "RecibidaConforme")).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        assertThat(API.estadosRecibidos()).isEmpty();
    }

    // ------------------------------------------------------------------ concurrencia

    @Test
    void sinAbrirElLoteOConLaAperturaDeOtraSesionNoSeActualiza() throws Exception {
        enviar(login("operador1"), pEstado(1, "RecibidaConforme")).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        MockHttpSession a = login("operador1");
        abrir(a, 1);
        MockHttpSession b = login("operador1");
        enviar(b, pEstado(1, "RecibidaConforme")).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        assertThat(API.estadosRecibidos()).isEmpty();
    }

    @Test
    void estadoCambiadoPorOtraPersonaSeDetectaYReabrirLoPermite() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 1);
        API.cambiarEstadoPorOtro(1, "NoConforme");
        enviar(s, pEstado(1, "RecibidaConforme")).andExpect(jsonPath("$.error").value(MSG_CONFLICTO));
        assertThat(API.estadosRecibidos()).isEmpty();
        assertThat(abrir(s, 1).get("estado").asString()).isEqualTo("NoConforme"); // reabre y ve el cambio
        enviar(s, pEstado(1, "RecibidaConforme")).andExpect(jsonPath("$.ok").value(true)); // a sabiendas: se permite
    }

    @Test
    void dosSesionesSecuencialesLaSegundaRecibeElConflicto() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        abrir(a, 1);
        abrir(b, 1);
        enviar(a, pEstado(1, "RecibidaConforme")).andExpect(jsonPath("$.ok").value(true));
        enviar(b, pEstado(1, "NoConforme")).andExpect(jsonPath("$.error").value(MSG_CONFLICTO));
        // Mismo usuario, sin reabrir (doble envío tardío): no actualiza de nuevo.
        enviar(a, pEstado(1, "NoConforme")).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        assertThat(API.estadosRecibidos()).hasSize(1);
    }

    @Test
    void dobleClicYDosSesionesSimultaneasActualizanUnaSolaVez() throws Exception {
        API.demoraEstado(400);
        MockHttpSession s = login("operador1");
        abrir(s, 1);
        List<JsonNode> doble = simultaneos(() -> json(enviar(s, pEstado(1, "RecibidaConforme")).andReturn()),
                () -> json(enviar(s, pEstado(1, "RecibidaConforme")).andReturn()));
        assertThat(doble).filteredOn(j -> j.get("ok").asBoolean()).hasSize(1);
        assertThat(doble).filteredOn(j -> !j.get("ok").asBoolean()).extracting(j -> j.get("error").asString()).containsExactly(MSG_SIN_LEER);
        assertThat(API.estadosRecibidos()).hasSize(1);

        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        abrir(a, 2);
        abrir(b, 2);
        List<JsonNode> dos = simultaneos(() -> json(enviar(a, pEstado(2, "RecibidaConObservacion")).andReturn()),
                () -> json(enviar(b, pEstado(2, "NoConforme")).andReturn()));
        assertThat(dos).filteredOn(j -> j.get("ok").asBoolean()).hasSize(1);
        assertThat(dos).filteredOn(j -> !j.get("ok").asBoolean()).extracting(j -> j.get("error").asString()).containsExactly(MSG_CONFLICTO);
        assertThat(API.estadosRecibidos()).hasSize(2);
    }

    // ------------------------------------------------------------------ CSRF, 401 y auditoría

    @Test
    void csrfY401() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 1);
        mockMvc.perform(post("/api/v1/bridge").session(s).contentType(MediaType.APPLICATION_JSON).content(pEstado(1, "RecibidaConforme")))
                .andExpect(status().isForbidden());
        API.revocarTokens(10);
        enviar(s, pEstado(1, "RecibidaConforme")).andExpect(status().isUnauthorized());
        assertThat(s.isInvalid()).isTrue();
        assertThat(API.estadosRecibidos()).isEmpty();
    }

    @Test
    void auditoriaConLoteYEstadoSinTokens(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 1);
        enviar(s, pEstado(1, "RecibidaConforme")).andExpect(jsonPath("$.ok").value(true));
        abrir(s, 2);
        API.cambiarEstadoPorOtro(2, "NoConforme");
        enviar(s, pEstado(2, "RecibidaConforme")).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=recepcion\\.estado\\.actualizar "
                + "recurso=recepcion:1:estado:RecibidaConforme resultado=OK ms=\\d+");
        assertThat(log).contains("accion=recepcion.estado.actualizar recurso=recepcion:2:estado:RecibidaConforme resultado=ERROR");
        assertThat(log).doesNotContain(FakeInnpackApi.firmaDeToken(10), PASS);
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
                            r.setRemoteAddr("10.24.0." + IP.getAndIncrement());
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

    private static SessionUser usuario(String empresa, String rol) {
        return new SessionUser(1, "u", "U", rol, empresa, "t", Instant.now(), Instant.now().plusSeconds(60));
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-3t");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
