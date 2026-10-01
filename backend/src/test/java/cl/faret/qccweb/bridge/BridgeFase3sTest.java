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
 * Fase 3s — recepcion.muestra.crear, igual que Photino para el usuario: la API inserta una muestra de Laboratorio
 * (sin control de duplicados) y pone el lote EnAnalisis. Seguridad transparente: empresa/usuario de sesión, lista blanca,
 * lote de la empresa de sesión, creaciones serializadas por lote y detección de cambios (doble clic, otra sesión,
 * reintento tras error). API SIMULADA con estado.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3sTest {

    private static final String CREAR = "recepcion.muestra.crear";
    private static final String MSG_FALTA = "Falta indicar el lote";
    private static final String MSG_SIN_LEER = "Abre el detalle del lote antes de crear la muestra de Laboratorio.";
    private static final String MSG_CONFLICTO = "El lote fue modificado por otra persona desde que lo abriste (muestra de "
            + "Laboratorio o estado). Vuelve a abrirlo para ver los cambios.";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveMuestraLab#2026";
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

    /** Payload EXACTO de crearMuestra de Photino INNPACK: {action, data:{loteId}}. */
    private static String payload(Object loteId) {
        return "{\"action\":\"" + CREAR + "\",\"data\":{\"loteId\":" + loteId + "}}";
    }

    // ------------------------------------------------------------------ flujo exitoso y refresco

    @Test
    void creacionCorrectaConCuerpoExactoEstadoYRefresco() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode antes = abrir(s, 20);
        assertThat(antes.get("muestraLaboratorioId").isNull()).isTrue();
        assertThat(antes.get("estado").asString()).isEqualTo("PendienteMuestreo");
        JsonNode json = json(enviar(s, payload(20)).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.at("/data/muestraLaboratorioId").asInt()).isEqualTo(700);
        assertThat(API.peticionesRecepcion()).containsExactly("GET /api/recepcion-calidad/20?empresa=INNPACK",
                "GET /api/recepcion-calidad/20?empresa=INNPACK", "POST /api/recepcion-calidad/20/muestra-laboratorio");
        SeguimientoRecibido r = API.muestrasRecibidas().get(0);
        assertThat(r.sub()).isEqualTo(10);
        assertThat(r.contentType()).startsWith("application/json");
        assertThat(mapper.readTree(r.cuerpo())).isEqualTo(mapper.readTree(
                "{\"empresa\":\"INNPACK\",\"usuarioId\":10,\"usuarioNombre\":\"Operador Uno\"}"));
        // Refresco inmediato (la vista reabre el detalle): muestra vinculada y lote EnAnalisis.
        JsonNode despues = abrir(s, 20);
        assertThat(despues.get("muestraLaboratorioId").asInt()).isEqualTo(700);
        assertThat(despues.get("estado").asString()).isEqualTo("EnAnalisis");
    }

    @Test
    void crearOtraASabiendasYEnCualquierEstadoComoPhotino() throws Exception {
        MockHttpSession s = login("admin1");
        abrir(s, 20);
        enviar(s, payload(20)).andExpect(jsonPath("$.ok").value(true));
        // Photino deja crear otra aunque ya exista una (la vista solo lo informa): tras reabrir, se permite.
        assertThat(abrir(s, 20).get("muestraLaboratorioId").asInt()).isEqualTo(700);
        enviar(s, payload(20)).andExpect(jsonPath("$.data.muestraLaboratorioId").value(701));
        assertThat(API.muestrasCreadas()).isEqualTo(2);
        // Lote ya EnAnalisis: también se crea (la API no exige estado). loteId como string (GetInt de Photino).
        abrir(s, 22);
        enviar(s, payload("\"22\"")).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.estadoLote(22)).isEqualTo("EnAnalisis");
    }

    @Test
    void identidadSiempreDeSesionYRolesComoPhotino() throws Exception {
        String[][] casos = {{"operador1", "10", "Operador Uno"}, {"admin1", "20", "Admin Uno"}, {"adminti1", "25", "Admin TI"}};
        for (String[] c : casos) {
            MockHttpSession s = login(c[0]);
            abrir(s, 20);
            enviar(s, payload(20)).andExpect(jsonPath("$.ok").value(true));
        }
        for (int k = 0; k < 3; k++) {
            JsonNode cuerpo = mapper.readTree(API.muestrasRecibidas().get(k).cuerpo());
            assertThat(cuerpo.get("usuarioId").asInt()).isEqualTo(Integer.parseInt(casos[k][1]));
            assertThat(cuerpo.get("usuarioNombre").asString()).isEqualTo(casos[k][2]);
            assertThat(cuerpo.get("empresa").asString()).isEqualTo("INNPACK");
        }
        enviar(login("consulta1"), payload(20)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(policy.evaluar(CREAR, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(CREAR)).findFirst().orElseThrow();
        assertThat(d.get("escritura")).isEqualTo(true);
        assertThat(d.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
    }

    // ------------------------------------------------------------------ validaciones

    @Test
    void identidadEmpresaYCamposInyectadosSeRechazanSinLlamarALaApi() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        for (String campo : List.of("empresa", "usuarioId", "usuarioNombre", "estado")) {
            ObjectNode raiz = (ObjectNode) mapper.readTree(payload(20));
            raiz.put(campo, "FARET");
            enviar(s, raiz.toString()).andExpect(jsonPath("$.error").value("Campo no permitido: " + campo));
            ObjectNode enData = (ObjectNode) mapper.readTree(payload(20));
            ((ObjectNode) enData.get("data")).put(campo, "Otra Persona");
            enviar(s, enData.toString()).andExpect(jsonPath("$.error").value("Campo no permitido: " + campo));
        }
        assertThat(API.muestrasRecibidas()).isEmpty();
    }

    @Test
    void loteFaltanteInvalidoInexistenteODeOtraEmpresa() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        for (String lote : List.of("0", "-1", "\"abc\"", "true", "null", "1.5", "{}")) {
            enviar(s, payload(lote)).andExpect(jsonPath("$.error").value(MSG_FALTA));
        }
        enviar(s, "{\"action\":\"" + CREAR + "\",\"data\":{}}").andExpect(jsonPath("$.error").value(MSG_FALTA));
        enviar(s, "{\"action\":\"" + CREAR + "\"}").andExpect(jsonPath("$.error").value(MSG_FALTA));
        // Lote inexistente / de otra empresa: el detalle de la empresa de sesión no lo encuentra → no se puede abrir ni crear.
        crear(s, "{\"action\":\"recepcion.detalle\",\"data\":{\"id\":99}}").andExpect(jsonPath("$.ok").value(false));
        enviar(s, payload(99)).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        assertThat(API.muestrasRecibidas()).isEmpty();
    }

    // ------------------------------------------------------------------ duplicados y concurrencia

    @Test
    void sinAbrirElLoteOConLaAperturaDeOtraSesionNoSeCrea() throws Exception {
        enviar(login("operador1"), payload(20)).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        MockHttpSession a = login("operador1");
        abrir(a, 20);
        MockHttpSession b = login("operador1");
        enviar(b, payload(20)).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        assertThat(API.muestrasRecibidas()).isEmpty();
    }

    @Test
    void muestraCreadaPorOtraPersonaSeDetectaSinDuplicar() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        API.crearMuestraPorOtro(20);
        enviar(s, payload(20)).andExpect(jsonPath("$.error").value(MSG_CONFLICTO));
        assertThat(API.muestrasRecibidas()).isEmpty();
        assertThat(abrir(s, 20).get("muestraLaboratorioId").asInt()).isEqualTo(700); // reabre y ve la muestra
        enviar(s, payload(20)).andExpect(jsonPath("$.ok").value(true)); // crear otra a sabiendas: como Photino
    }

    @Test
    void dosSesionesSecuencialesLaSegundaRecibeElConflicto() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        abrir(a, 20);
        abrir(b, 20);
        enviar(a, payload(20)).andExpect(jsonPath("$.ok").value(true));
        enviar(b, payload(20)).andExpect(jsonPath("$.error").value(MSG_CONFLICTO));
        // Mismo usuario, sin reabrir (doble envío tardío): no crea otra.
        enviar(a, payload(20)).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        assertThat(API.muestrasCreadas()).isEqualTo(1);
    }

    @Test
    void dobleClicYDosSesionesSimultaneasCreanUnaSolaMuestra() throws Exception {
        API.demoraMuestra(400);
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        List<JsonNode> doble = simultaneos(() -> json(enviar(s, payload(20)).andReturn()), () -> json(enviar(s, payload(20)).andReturn()));
        assertThat(doble).filteredOn(j -> j.get("ok").asBoolean()).hasSize(1);
        assertThat(doble).filteredOn(j -> !j.get("ok").asBoolean()).extracting(j -> j.get("error").asString()).containsExactly(MSG_SIN_LEER);
        assertThat(API.muestrasCreadas()).isEqualTo(1);

        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        abrir(a, 22);
        abrir(b, 22);
        List<JsonNode> dos = simultaneos(() -> json(enviar(a, payload(22)).andReturn()), () -> json(enviar(b, payload(22)).andReturn()));
        assertThat(dos).filteredOn(j -> j.get("ok").asBoolean()).hasSize(1);
        assertThat(dos).filteredOn(j -> !j.get("ok").asBoolean()).extracting(j -> j.get("error").asString()).containsExactly(MSG_CONFLICTO);
        assertThat(API.muestrasCreadas()).isEqualTo(2);
    }

    @Test
    void falloParcialYReintentoNoDuplican() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 23);
        // La API inserta la muestra y falla al actualizar el estado (sin transacción): error genérico, sin reintento.
        enviar(s, payload(23)).andExpect(jsonPath("$.error").value("Error al comunicarse con la API Innpack"));
        assertThat(API.muestrasCreadas()).isEqualTo(1);
        // Reintento del usuario: la huella detecta la muestra ya creada → no se crea otra.
        enviar(s, payload(23)).andExpect(jsonPath("$.error").value(MSG_CONFLICTO));
        assertThat(API.muestrasRecibidas()).hasSize(1);
        JsonNode lote = abrir(s, 23);
        assertThat(lote.get("muestraLaboratorioId").asInt()).isEqualTo(700);
        assertThat(lote.get("estado").asString()).isEqualTo("PendienteMuestreo"); // estado parcial, igual que en Photino
    }

    // ------------------------------------------------------------------ CSRF, 401 y auditoría

    @Test
    void csrfY401() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        mockMvc.perform(post("/api/v1/bridge").session(s).contentType(MediaType.APPLICATION_JSON).content(payload(20)))
                .andExpect(status().isForbidden());
        API.revocarTokens(10);
        enviar(s, payload(20)).andExpect(status().isUnauthorized());
        assertThat(s.isInvalid()).isTrue();
        assertThat(API.muestrasRecibidas()).isEmpty();
    }

    @Test
    void auditoriaConLoteYMuestraSinTokens(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        enviar(s, payload(20)).andExpect(jsonPath("$.ok").value(true));
        abrir(s, 23);
        enviar(s, payload(23)).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=recepcion\\.muestra\\.crear "
                + "recurso=recepcion:20:muestra:700 resultado=OK ms=\\d+");
        assertThat(log).contains("accion=recepcion.muestra.crear recurso=recepcion:23:muestra resultado=ERROR");
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
            Path www = Files.createTempDirectory("qcc-web-fixture-3s");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
