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
import cl.faret.qccweb.bridge.handlers.CertificadosLiberacionBridgeHandler;
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
import java.util.stream.Stream;
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

/** Fase 2g — Certificados de Liberación: buscar + calidadPdf.descargar (PDF validado en gateway, descarga en navegador). API SIMULADA. */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFase2gTest {

    private static final String BUSCAR = "certificadosLiberacion.buscar";
    private static final String PDF = "certificadosLiberacion.calidadPdf.descargar";
    private static final String NO_HABILITADA = "certificadosLiberacion.pdf.descargar";
    private static final String BASE = "GET /api/certificados-liberacion";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveCertificados#2026";
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

    // ----------------------------------------------------------------------------- buscar

    @Test
    void buscarSinFiltrosYConFiltrosVaciosDeLaPantalla() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode json = json(accion(sesion, "{\"action\":\"" + BUSCAR + "\"}").andExpect(status().isOk()).andReturn());
        List<String> campos = new ArrayList<>();
        json.propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("ok", "success", "data", "error");
        assertThat(json.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataCertificados("")));
        assertThat(json.at("/data/0/cliente").asString()).isEqualTo("Cliente Ñandú «E2E»");
        // Los 8 filtros en blanco como los manda el controller → sin query string.
        accion(sesion, "{\"action\":\"" + BUSCAR + "\",\"data\":{\"folio\":\"\",\"np\":\"\",\"cliente\":\"\",\"operador\":\"\",\"inspector\":\"\","
                + "\"empresa\":\"\",\"fechaDesde\":\"\",\"fechaHasta\":\"\"}}").andExpect(status().isOk());
        assertThat(API.peticionesCertificados()).containsExactly(BASE, BASE);
    }

    /** Mismas reglas que InnpackCertificadosLiberacionApiService.BuscarAsync + GetString del handler. */
    @Test
    void mapeoDeFiltrosOrdenEscapadoYUtf8IgualQuePhotino() throws Exception {
        MockHttpSession sesion = login("admin1");
        String[][] casos = {
            {"{\"folio\":\"123456\"}", "?folio=123456"},
            {"{\"folio\":123456}", "?folio=123456"},
            {"{\"empresa\":\"FARET SPA\"}", "?empresa=FARET%20SPA"},
            {"{\"empresa\":\"INNPACK SPA\",\"np\":\"4101\"}", "?np=4101&empresa=INNPACK%20SPA"},
            // combinado desordenado → orden fijo de Photino
            {"{\"fechaHasta\":\"2026-09-24\",\"inspector\":\"María José Peña\",\"operador\":\"Op 1\",\"empresa\":\"FARET SPA\","
                + "\"cliente\":\"Juan Pérez & Cía / 100%\",\"np\":\"41\",\"folio\":\"7\",\"fechaDesde\":\"2026-09-01\"}",
                "?folio=7&np=41&cliente=Juan%20P%C3%A9rez%20%26%20C%C3%ADa%20%2F%20100%25&empresa=FARET%20SPA&operador=Op%201"
                    + "&inspector=Mar%C3%ADa%20Jos%C3%A9%20Pe%C3%B1a&fechaDesde=2026-09-01&fechaHasta=2026-09-24"},
            {"{\"np\":\"41&empresa=FARET SPA\"}", "?np=41%26empresa%3DFARET%20SPA"},
            {"{\"cliente\":\"a-b_c.d~e\"}", "?cliente=a-b_c.d~e"},
            // vacíos / blancos / null se omiten; bool/objeto/array → "" como el GetString de Photino
            {"{\"np\":\"   \",\"cliente\":null,\"operador\":true,\"inspector\":{\"a\":1},\"folio\":[1]}", ""},
            {"{\"np\":4101,\"folio\":1.5}", "?folio=1.5&np=4101"},
        };
        for (String[] caso : casos) {
            accion(sesion, "{\"action\":\"" + BUSCAR + "\",\"data\":" + caso[0] + "}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true));
        }
        accion(sesion, "{\"action\":\"" + BUSCAR + "\",\"np\":\"41\"}").andExpect(status().isOk());
        List<String> esperadas = new ArrayList<>();
        for (String[] caso : casos) {
            esperadas.add(BASE + caso[1]);
        }
        esperadas.add(BASE);
        assertThat(API.peticionesCertificados()).containsExactlyElementsOf(esperadas);
    }

    /** "empresa" es un filtro real (select de la vista), pero solo con sus valores exactos. */
    @Test
    void empresaComoFiltroSoloAdmiteLasOpcionesDeLaVista() throws Exception {
        MockHttpSession sesion = login("operador1");
        for (String empresa : List.of("INNPACK", "FARET", "faret spa", "FARET SPA ", "OTRA", "INNPACK SPA' OR 1=1")) {
            accion(sesion, "{\"action\":\"" + BUSCAR + "\",\"data\":{\"empresa\":\"" + empresa.replace("'", "\\u0027") + "\"}}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Filtro de empresa inválido."));
        }
        assertThat(API.peticionesCertificados()).isEmpty();
        // La empresa de la SESIÓN no se inyecta como filtro: "Todas" sigue siendo todas.
        accion(sesion, "{\"action\":\"" + BUSCAR + "\",\"empresa\":\"INNPACK\",\"data\":{\"empresa\":\"\",\"usuarioId\":99}}").andExpect(status().isOk());
        assertThat(API.peticionesCertificados()).containsExactly(BASE);
    }

    @Test
    void datasetVacioYErrorDelLegado() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode vacio = json(accion(sesion, "{\"action\":\"" + BUSCAR + "\",\"data\":{\"np\":\"VACIO\"}}").andReturn());
        assertThat(vacio.get("ok").asBoolean()).isTrue();
        assertThat(vacio.get("data")).isEmpty();
        API.modoDashboard(ModoDashboard.ERROR_NEGOCIO);
        accion(sesion, "{\"action\":\"" + BUSCAR + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("No fue posible consultar los certificados de liberación"));
    }

    // ------------------------------------------------------------- calidadPdf.descargar

    @Test
    void pdfValidoLlegaComoFileNameYBase64SinTocarElDisco() throws Exception {
        MockHttpSession sesion = login("operador1");
        long pdfsAntes = pdfsEnTemp();
        JsonNode json = json(accion(sesion, "{\"action\":\"" + PDF + "\",\"data\":{\"folio\":123456}}").andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        List<String> campos = new ArrayList<>();
        json.get("data").propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("fileName", "base64");
        assertThat(json.at("/data/fileName").asString()).isEqualTo("CertificadoCalidad_123456.pdf");
        byte[] bytes = Base64.getDecoder().decode(json.at("/data/base64").asString());
        assertThat(bytes).isEqualTo(FakeInnpackApi.pdfMinimo(123456));
        assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).endsWith("%%EOF\n");
        assertThat(API.peticionesCertificados()).containsExactly(BASE + "/123456/calidad-pdf");
        assertThat(pdfsEnTemp()).isEqualTo(pdfsAntes);
        // Ningún rastro del token ni de rutas en la respuesta.
        assertThat(json.toString()).doesNotContain("Downloads", "path", FakeInnpackApi.firmaDeToken(10));
    }

    @Test
    void folioComoPhotinoGetLong() throws Exception {
        MockHttpSession sesion = login("operador1");
        accion(sesion, "{\"action\":\"" + PDF + "\",\"data\":{\"folio\":\"123456\"}}").andExpect(jsonPath("$.ok").value(true));
        accion(sesion, "{\"action\":\"" + PDF + "\",\"data\":{\"folio\":\" 9007199254740993 \"}}").andExpect(jsonPath("$.ok").value(true));
        for (String data : List.of("{}", "{\"folio\":0}", "{\"folio\":-1}", "{\"folio\":\"abc\"}", "{\"folio\":true}", "{\"folio\":1.5}", "{\"folio\":{\"id\":1}}")) {
            accion(sesion, "{\"action\":\"" + PDF + "\",\"data\":" + data + "}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Falta indicar el folio"));
        }
        accion(sesion, "{\"action\":\"" + PDF + "\",\"folio\":5}").andExpect(jsonPath("$.error").value("Falta indicar el folio"));
        assertThat(API.peticionesCertificados()).containsExactly(BASE + "/123456/calidad-pdf", BASE + "/9007199254740993/calidad-pdf");
    }

    @Test
    void pdfNoEncontradoVacioCorruptoOExcesivoSeRechazaLimpiamente() throws Exception {
        MockHttpSession sesion = login("operador1");
        String[][] casos = {
            {"404", "No se encontraron datos para el certificado N° 404"},
            {"600", "El certificado no trae contenido"},
            {"500", "El certificado no es un PDF válido."},
            {"800", "El certificado no es un PDF válido."},
            {"700", "El certificado excede el tamaño máximo permitido."},
        };
        for (String[] caso : casos) {
            accion(sesion, "{\"action\":\"" + PDF + "\",\"data\":{\"folio\":" + caso[0] + "}}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.data").value((Object) null))
                    .andExpect(jsonPath("$.error").value(caso[1]));
        }
        assertThat(API.peticionesCertificados()).hasSize(5);
    }

    @Test
    void nombreDeArchivoSaneadoEnElGateway() throws Exception {
        MockHttpSession sesion = login("operador1");
        accion(sesion, "{\"action\":\"" + PDF + "\",\"data\":{\"folio\":900}}")
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.fileName").value("evil_______.exe.pdf"));
        assertThat(CertificadosLiberacionBridgeHandler.nombreArchivoSeguro("../../etc/passwd", "x.pdf")).isEqualTo("passwd.pdf");
        assertThat(CertificadosLiberacionBridgeHandler.nombreArchivoSeguro("CON.pdf", "x.pdf")).isEqualTo("_CON.pdf");
        assertThat(CertificadosLiberacionBridgeHandler.nombreArchivoSeguro("", "CertificadoCalidad_7.pdf")).isEqualTo("CertificadoCalidad_7.pdf");
        assertThat(CertificadosLiberacionBridgeHandler.nombreArchivoSeguro(" .pdf ", "CertificadoCalidad_7.pdf")).isEqualTo("CertificadoCalidad_7.pdf");
        assertThat(CertificadosLiberacionBridgeHandler.nombreArchivoSeguro("informe\u202e.fdp.pdf", "x.pdf")).isEqualTo("informe.fdp.pdf");
        assertThat(CertificadosLiberacionBridgeHandler.nombreArchivoSeguro("x".repeat(300) + ".pdf", "x.pdf")).hasSize(150).endsWith(".pdf");
        assertThat(CertificadosLiberacionBridgeHandler.esPdf(Base64.getEncoder().encodeToString("%PDF-1.7 ...".getBytes()))).isTrue();
        assertThat(CertificadosLiberacionBridgeHandler.esPdf(Base64.getEncoder().encodeToString("%PDF".getBytes()))).isFalse();
        assertThat(CertificadosLiberacionBridgeHandler.esPdf("UEsDBA==")).isFalse();
    }

    // ------------------------------------------------------------- no habilitadas / roles / empresa

    @Test
    void pdfDeTerminacionesNoEstaHabilitado() throws Exception {
        MockHttpSession admin = login("admin1");
        accion(admin, "{\"action\":\"" + NO_HABILITADA + "\",\"data\":{\"folio\":123456}}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(policy.accionesRegistradas()).doesNotContain(NO_HABILITADA);
        assertThat(API.peticionesCertificados()).isEmpty();
    }

    @Test
    void rolPermitidoYNoPermitido() throws Exception {
        for (String a : List.of(BUSCAR, PDF)) {
            accion(login("consulta1"), "{\"action\":\"" + a + "\",\"data\":{\"folio\":123456}}").andExpect(status().isForbidden());
        }
        assertThat(API.peticionesCertificados()).isEmpty();
        accion(login("operador1"), "{\"action\":\"" + BUSCAR + "\"}").andExpect(status().isOk());
        accion(login("admin1"), "{\"action\":\"" + PDF + "\",\"data\":{\"folio\":123456}}").andExpect(status().isOk());
        assertThat(API.peticionesCertificados()).hasSize(2);
    }

    @Test
    void empresaDeSesionCorrectaEIncorrecta() {
        for (String a : List.of(BUSCAR, PDF)) {
            assertThat(policy.evaluar(a, usuario("INNPACK", "operador"))).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            assertThat(policy.evaluar(a, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        }
    }

    // --------------------------------------------------------- identidad y aislamiento

    @Test
    void identidadManipuladaSinEfecto() throws Exception {
        MockHttpSession sesion = login("operador1");
        accion(sesion, "{\"action\":\"" + PDF + "\",\"usuarioId\":20,\"rol\":\"admin\",\"empresa\":\"FARET\","
                        + "\"data\":{\"folio\":123456,\"usuarioId\":20,\"rol\":\"admin\",\"token\":\"x\",\"path\":\"C:\\\\x.pdf\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        assertThat(API.peticionesCertificados()).containsExactly(BASE + "/123456/calidad-pdf");
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
                    JsonNode json = json(accion(esA ? a : b, "{\"action\":\"" + PDF + "\",\"data\":{\"folio\":" + (esA ? 10 : 20) + "}}")
                            .andExpect(status().isOk()).andReturn());
                    String pdf = new String(Base64.getDecoder().decode(json.at("/data/base64").asString()), StandardCharsets.ISO_8859_1);
                    return new int[] {esA ? 10 : 20, pdf.contains("(Certificado 10)") ? 10 : (pdf.contains("(Certificado 20)") ? 20 : -1)};
                });
            }
            for (Future<int[]> f : pool.invokeAll(tareas)) {
                assertThat(f.get()[1]).isEqualTo(f.get()[0]);
            }
            // Cada sesión llamó con SU token (10 → firma 10, 20 → firma 20), 20 veces cada una.
            assertThat(API.authorizationRecibidos().stream().filter(x -> x.endsWith(FakeInnpackApi.firmaDeToken(10))).count()).isEqualTo(20);
            assertThat(API.authorizationRecibidos().stream().filter(x -> x.endsWith(FakeInnpackApi.firmaDeToken(20))).count()).isEqualTo(20);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unauthorizedUpstreamInvalidaSoloEsaSesion() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        API.revocarTokens(10);
        accion(a, "{\"action\":\"" + PDF + "\",\"data\":{\"folio\":1}}").andExpect(status().isUnauthorized());
        assertThat(a.isInvalid()).isTrue();
        accion(b, "{\"action\":\"" + BUSCAR + "\"}").andExpect(status().isOk());
    }

    @Test
    void csrfObligatorio() throws Exception {
        MockHttpSession sesion = login("operador1");
        mockMvc.perform(post("/api/v1/bridge").session(sesion).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + PDF + "\",\"data\":{\"folio\":1}}"))
                .andExpect(status().isForbidden());
        assertThat(API.peticionesCertificados()).isEmpty();
    }

    // ------------------------------------------------------------------------- helpers

    private static long pdfsEnTemp() throws IOException {
        try (Stream<Path> s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf")).count();
        }
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.7.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-2g");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
