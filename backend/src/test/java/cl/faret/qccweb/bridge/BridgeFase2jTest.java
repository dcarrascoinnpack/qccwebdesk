package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import cl.faret.qccweb.auth.FakeInnpackApi.ModoDashboard;
import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.handlers.RecepcionCalidadBridgeHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/** Fase 2j — Recepción Calidad, solo lectura (list/detalle/foto.abrir, payload en "data"). API SIMULADA. */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFase2jTest {

    private static final String LIST = "recepcion.list";
    private static final String DETALLE = "recepcion.detalle";
    private static final String FOTO = "recepcion.foto.abrir";
    private static final List<String> NO_HABILITADAS = List.of("recepcion.crear", "recepcion.plan.generar");
    private static final String BASE = "GET /api/recepcion-calidad";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveRecepcion#2026";
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

    // ---------------------------------------------------------------------------- list

    @Test
    void listConElPayloadDeLaPantallaYEmpresaDeSesion() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode json = json(accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":{\"estado\":\"\"}}").andExpect(status().isOk()).andReturn());
        List<String> campos = new ArrayList<>();
        json.propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("ok", "success", "data", "error");
        assertThat(json.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataRecepcionList(10, "empresa=INNPACK")));
        assertThat(json.at("/data/0/proveedor").asString()).isEqualTo("Adhesivos Ñuñoa «1»");
        String[][] casos = {
            {"{\"estado\":\"PendienteLaboratorio\"}", "?estado=PendienteLaboratorio&empresa=INNPACK"},
            {"{\"estado\":\"No conforme & ñ\",\"tipoMateriaPrima\":\"PVA\"}", "?estado=No%20conforme%20%26%20%C3%B1&tipoMateriaPrima=PVA&empresa=INNPACK"},
            {"{\"estado\":\"a&empresa=FARET\"}", "?estado=a%26empresa%3DFARET&empresa=INNPACK"},
            {"{\"estado\":\"  \",\"tipoMateriaPrima\":null}", "?empresa=INNPACK"},
            {"{\"estado\":7}", "?estado=7&empresa=INNPACK"},
        };
        for (String[] caso : casos) {
            accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":" + caso[0] + "}").andExpect(jsonPath("$.ok").value(true));
        }
        // Sin "data", o con los filtros en la raíz (payload plano) → se ignoran.
        accion(sesion, "{\"action\":\"" + LIST + "\"}").andExpect(jsonPath("$.ok").value(true));
        accion(sesion, "{\"action\":\"" + LIST + "\",\"estado\":\"EnAnalisis\"}").andExpect(jsonPath("$.ok").value(true));
        List<String> esperadas = new ArrayList<>(List.of(BASE + "?empresa=INNPACK"));
        for (String[] caso : casos) {
            esperadas.add(BASE + caso[1]);
        }
        esperadas.add(BASE + "?empresa=INNPACK");
        esperadas.add(BASE + "?empresa=INNPACK");
        assertThat(API.peticionesRecepcion()).containsExactlyElementsOf(esperadas);
    }

    @Test
    void empresaDelPayloadNuncaLlegaALaApi() throws Exception {
        MockHttpSession sesion = login("operador1");
        accion(sesion, "{\"action\":\"" + LIST + "\",\"empresa\":\"FARET\",\"data\":{\"estado\":\"\",\"empresa\":\"FARET\"}}")
                .andExpect(jsonPath("$.ok").value(true));
        accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"id\":1,\"empresa\":\"FARET\"}}").andExpect(jsonPath("$.ok").value(true));
        assertThat(API.peticionesRecepcion()).containsExactly(BASE + "?empresa=INNPACK", BASE + "/1?empresa=INNPACK");
    }

    @Test
    void tiposInvalidosSeRechazanSinLlamarALaApi() throws Exception {
        MockHttpSession sesion = login("operador1");
        for (String data : List.of("{\"estado\":true}", "{\"estado\":{\"x\":1}}", "{\"tipoMateriaPrima\":[\"PVA\"]}", "{\"tipoMateriaPrima\":false}")) {
            accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":" + data + "}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Parámetro de filtro inválido."));
        }
        assertThat(API.peticionesRecepcion()).isEmpty();
    }

    @Test
    void errorDeLaApi() throws Exception {
        MockHttpSession sesion = login("operador1");
        API.modoDashboard(ModoDashboard.ERROR_NEGOCIO);
        for (String a : List.of(LIST, DETALLE)) {
            accion(sesion, "{\"action\":\"" + a + "\",\"data\":{\"id\":1}}")
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Filtro de fecha inválido"));
        }
    }

    // ------------------------------------------------------------------------- detalle

    @Test
    void detalleConIdValidoInvalidoYNoEncontrado() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode ok = json(accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"id\":1}}").andExpect(status().isOk()).andReturn());
        assertThat(ok.at("/data/tipoMateriaPrima").asString()).isEqualTo("PVA");
        accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"id\":\" 2 \"}}").andExpect(jsonPath("$.ok").value(true));
        // Photino: GetInt(..) ?? 0 → sin id válido va a la API como 0 (→ "Lote no encontrado").
        for (String data : List.of("{}", "{\"id\":\"abc\"}", "{\"id\":true}", "{\"id\":2.5}")) {
            accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":" + data + "}")
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Lote no encontrado"));
        }
        accion(sesion, "{\"action\":\"" + DETALLE + "\",\"id\":1}").andExpect(jsonPath("$.error").value("Lote no encontrado"));
        accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"id\":404}}").andExpect(jsonPath("$.error").value("Lote no encontrado"));
        assertThat(API.peticionesRecepcion()).containsExactly(BASE + "/1?empresa=INNPACK", BASE + "/2?empresa=INNPACK",
                BASE + "/0?empresa=INNPACK", BASE + "/0?empresa=INNPACK", BASE + "/0?empresa=INNPACK", BASE + "/0?empresa=INNPACK",
                BASE + "/0?empresa=INNPACK", BASE + "/404?empresa=INNPACK");
    }

    // ---------------------------------------------------------------------- foto.abrir

    @Test
    void fotoConfirmaElLoteDeLaEmpresaYEntregaElMimeReal() throws Exception {
        MockHttpSession sesion = login("operador1");
        long tmpAntes = archivosEnTemp();
        JsonNode jpeg = json(accion(sesion, "{\"action\":\"" + FOTO + "\",\"data\":{\"loteId\":1,\"tipoMateriaPrima\":\"PVA\"}}")
                .andExpect(status().isOk()).andReturn());
        List<String> campos = new ArrayList<>();
        jpeg.get("data").propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("base64", "mime");
        assertThat(jpeg.at("/data/mime").asString()).isEqualTo("image/jpeg");
        assertThat(Base64.getDecoder().decode(jpeg.at("/data/base64").asString())).isEqualTo(FakeInnpackApi.jpegMinimo());
        // PNG guardada como image/jpeg por la API → se entrega como image/png.
        JsonNode png = json(accion(sesion, "{\"action\":\"" + FOTO + "\",\"data\":{\"loteId\":\"2\",\"tipoMateriaPrima\":\"PliegoFaret\"}}").andReturn());
        assertThat(png.at("/data/mime").asString()).isEqualTo("image/png");
        assertThat(json(accion(sesion, "{\"action\":\"" + FOTO + "\",\"data\":{\"loteId\":3,\"tipoMateriaPrima\":\"PVA\"}}").andReturn())
                .at("/data/mime").asString()).isEqualTo("image/gif");
        assertThat(json(accion(sesion, "{\"action\":\"" + FOTO + "\",\"data\":{\"loteId\":4,\"tipoMateriaPrima\":\"PVA\"}}").andReturn())
                .at("/data/mime").asString()).isEqualTo("image/webp");
        assertThat(API.peticionesRecepcion()).containsExactly(
                BASE + "/1?empresa=INNPACK", BASE + "/1/foto?tipoMateriaPrima=PVA",
                BASE + "/2?empresa=INNPACK", BASE + "/2/foto?tipoMateriaPrima=PliegoFaret",
                BASE + "/3?empresa=INNPACK", BASE + "/3/foto?tipoMateriaPrima=PVA",
                BASE + "/4?empresa=INNPACK", BASE + "/4/foto?tipoMateriaPrima=PVA");
        assertThat(archivosEnTemp()).isEqualTo(tmpAntes);
        assertThat(jpeg.toString()).doesNotContain("path", "Temp", FakeInnpackApi.firmaDeToken(10));
    }

    @Test
    void fotoDeOtroLoteTipoOEmpresaNoSePide() throws Exception {
        MockHttpSession sesion = login("operador1");
        // Lote inexistente / de otra empresa (el detalle filtrado por la sesión no lo encuentra).
        accion(sesion, "{\"action\":\"" + FOTO + "\",\"data\":{\"loteId\":404,\"tipoMateriaPrima\":\"PVA\"}}")
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Lote no encontrado"));
        // Tipo del payload distinto al del lote.
        accion(sesion, "{\"action\":\"" + FOTO + "\",\"data\":{\"loteId\":1,\"tipoMateriaPrima\":\"PliegoFaret\"}}")
                .andExpect(jsonPath("$.error").value("Este lote no tiene fotografía cargada"));
        assertThat(API.peticionesRecepcion()).containsExactly(BASE + "/404?empresa=INNPACK", BASE + "/1?empresa=INNPACK");
        // Tipo sin foto o fuera de la lista → sin llamar a la API.
        for (String tipo : List.of("Bobina", "pva", "../x")) {
            accion(sesion, "{\"action\":\"" + FOTO + "\",\"data\":{\"loteId\":10,\"tipoMateriaPrima\":\"" + tipo + "\"}}")
                    .andExpect(jsonPath("$.error").value("Este lote no tiene fotografía cargada"));
        }
        for (String data : List.of("{}", "{\"loteId\":1}", "{\"tipoMateriaPrima\":\"PVA\"}", "{\"loteId\":0,\"tipoMateriaPrima\":\"PVA\"}",
                "{\"loteId\":-3,\"tipoMateriaPrima\":\"PVA\"}", "{\"loteId\":true,\"tipoMateriaPrima\":\"PVA\"}",
                "{\"loteId\":1,\"tipoMateriaPrima\":\"  \"}", "{\"loteId\":1,\"tipoMateriaPrima\":true}")) {
            accion(sesion, "{\"action\":\"" + FOTO + "\",\"data\":" + data + "}")
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Falta el lote o el tipo de materia prima"));
        }
        assertThat(API.peticionesRecepcion()).hasSize(2);
    }

    @Test
    void fotoNoImagenVaciaCorruptaOExcesivaSeRechaza() throws Exception {
        MockHttpSession sesion = login("operador1");
        String[][] casos = {
            {"5", "La fotografía no es válida."},
            {"6", "Este lote no tiene fotografía cargada"},
            {"7", "Este lote no tiene fotografía cargada"},
            {"8", "La fotografía excede el tamaño máximo permitido."},
            {"9", "La fotografía no es válida."},
        };
        for (String[] caso : casos) {
            accion(sesion, "{\"action\":\"" + FOTO + "\",\"data\":{\"loteId\":" + caso[0] + ",\"tipoMateriaPrima\":\"PVA\"}}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.data").value((Object) null))
                    .andExpect(jsonPath("$.error").value(caso[1]));
        }
    }

    @Test
    void mimeImagenPorFirma() {
        assertThat(RecepcionCalidadBridgeHandler.mimeImagen(FakeInnpackApi.jpegMinimo())).isEqualTo("image/jpeg");
        assertThat(RecepcionCalidadBridgeHandler.mimeImagen(FakeInnpackApi.pngMinimo())).isEqualTo("image/png");
        assertThat(RecepcionCalidadBridgeHandler.mimeImagen("GIF87a".getBytes())).isEqualTo("image/gif");
        assertThat(RecepcionCalidadBridgeHandler.mimeImagen("RIFF0000WEBP".getBytes())).isEqualTo("image/webp");
        assertThat(RecepcionCalidadBridgeHandler.mimeImagen("RIFF0000WAVE".getBytes())).isNull();
        assertThat(RecepcionCalidadBridgeHandler.mimeImagen("%PDF-1.7".getBytes())).isNull();
        assertThat(RecepcionCalidadBridgeHandler.mimeImagen("<svg>".getBytes())).isNull();
        assertThat(RecepcionCalidadBridgeHandler.mimeImagen(new byte[0])).isNull();
        assertThat(RecepcionCalidadBridgeHandler.mimeImagen(null)).isNull();
    }

    // ------------------------------------------------------------- escrituras / roles / empresa

    @Test
    void escriturasYSapSiguenBloqueadas() throws Exception {
        MockHttpSession admin = login("admin1");
        for (String accion : NO_HABILITADAS) {
            accion(admin, "{\"action\":\"" + accion + "\",\"data\":{\"loteId\":1,\"estado\":\"NoConforme\",\"desde\":\"20260901\",\"hasta\":\"20260930\"}}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
            assertThat(policy.accionesRegistradas()).doesNotContain(accion);
        }
        assertThat(API.peticionesRecepcion()).isEmpty();
    }

    @Test
    void rolPermitidoYNoPermitido() throws Exception {
        for (String a : List.of(LIST, DETALLE, FOTO)) {
            accion(login("consulta1"), "{\"action\":\"" + a + "\",\"data\":{\"id\":1,\"loteId\":1,\"tipoMateriaPrima\":\"PVA\"}}")
                    .andExpect(status().isForbidden());
        }
        assertThat(API.peticionesRecepcion()).isEmpty();
        accion(login("operador1"), "{\"action\":\"" + LIST + "\",\"data\":{}}").andExpect(status().isOk());
        accion(login("admin1"), "{\"action\":\"" + DETALLE + "\",\"data\":{\"id\":1}}").andExpect(status().isOk());
        assertThat(API.peticionesRecepcion()).hasSize(2);
    }

    @Test
    void empresaDeSesionCorrectaEIncorrecta() {
        for (String a : List.of(LIST, DETALLE, FOTO)) {
            assertThat(policy.evaluar(a, usuario("INNPACK", "operador"))).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            assertThat(policy.evaluar(a, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        }
    }

    // --------------------------------------------------------- identidad y aislamiento

    @Test
    void identidadManipuladaSinEfecto() throws Exception {
        MockHttpSession sesion = login("operador1");
        accion(sesion, "{\"action\":\"" + DETALLE + "\",\"usuarioId\":20,\"rol\":\"admin\",\"data\":{\"id\":1,\"usuarioId\":20,\"token\":\"x\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usuarioDelToken").value(10));
        assertThat(API.authorizationRecibidos()).allMatch(a -> a.endsWith(FakeInnpackApi.firmaDeToken(10)));
    }

    @Test
    void sesionesConcurrentesAisladas() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<int[]>> tareas = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                boolean esA = i % 2 == 0;
                tareas.add(() -> {
                    JsonNode json = json(accion(esA ? a : b, "{\"action\":\"" + DETALLE + "\",\"data\":{\"id\":1}}").andExpect(status().isOk()).andReturn());
                    return new int[] {esA ? 10 : 20, json.at("/data/usuarioDelToken").asInt()};
                });
            }
            for (Future<int[]> f : pool.invokeAll(tareas)) {
                assertThat(f.get()[1]).isEqualTo(f.get()[0]);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unauthorizedUpstreamInvalidaSoloEsaSesion() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        API.revocarTokens(10);
        accion(a, "{\"action\":\"" + FOTO + "\",\"data\":{\"loteId\":1,\"tipoMateriaPrima\":\"PVA\"}}").andExpect(status().isUnauthorized());
        assertThat(a.isInvalid()).isTrue();
        accion(b, "{\"action\":\"" + LIST + "\",\"data\":{}}").andExpect(status().isOk());
    }

    @Test
    void csrfObligatorio() throws Exception {
        MockHttpSession sesion = login("operador1");
        mockMvc.perform(post("/api/v1/bridge").session(sesion).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + LIST + "\",\"data\":{}}"))
                .andExpect(status().isForbidden());
        assertThat(API.peticionesRecepcion()).isEmpty();
    }

    // ------------------------------------------------------------------------- helpers

    private static long archivosEnTemp() throws IOException {
        try (var s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".jpeg");
            }).count();
        }
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.10.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private ResultActions accion(MockHttpSession sesion, String cuerpo) throws Exception {
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
            Path www = Files.createTempDirectory("qcc-web-fixture-2j");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
