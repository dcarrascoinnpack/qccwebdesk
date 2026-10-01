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
import cl.faret.qccweb.bridge.handlers.ControlDocumentalBridgeHandler;
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

/** Fase 2h — Control Documental, solo lectura (list/get/adjunto.abrir con payload PLANO). API SIMULADA. */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFase2hTest {

    private static final String LIST = "controlDocumental.list";
    private static final String GET = "controlDocumental.get";
    private static final String ADJUNTO = "controlDocumental.adjunto.abrir";
    private static final String BASE = "GET /api/control-documental";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveDocumental#2026";
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

    // ------------------------------------------------------------------------------- list

    @Test
    void listConLosFiltrosVaciosDeLaPantallaYRespuestaPaginada() throws Exception {
        MockHttpSession sesion = login("operador1");
        // Payload plano exacto del controller (_getFiltros con todo vacío).
        JsonNode json = json(accion(sesion, "{\"action\":\"" + LIST + "\",\"page\":1,\"pageSize\":50,\"texto\":\"\",\"tipoDocumento\":\"\","
                + "\"area\":\"\",\"estado\":\"\",\"alcanceEmpresa\":\"\"}").andExpect(status().isOk()).andReturn());
        List<String> campos = new ArrayList<>();
        json.propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("ok", "success", "data", "error");
        assertThat(json.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataControlDocumentalList(10, "page=1&pageSize=50")));
        assertThat(json.at("/data/items")).hasSize(3);
        assertThat(json.at("/data/total").asInt()).isEqualTo(42);
        assertThat(json.at("/data/pages").asInt()).isEqualTo(1);
        assertThat(json.at("/data/items/0/titulo").asString()).isEqualTo("Procedimiento ñandú «0»");
        // Sin nada → defaults 1/50. Exportar → pageSize 999999.
        accion(sesion, "{\"action\":\"" + LIST + "\"}").andExpect(status().isOk());
        accion(sesion, "{\"action\":\"" + LIST + "\",\"page\":1,\"pageSize\":999999,\"texto\":\"\"}").andExpect(status().isOk());
        assertThat(API.peticionesControlDocumental()).containsExactly(
                BASE + "?page=1&pageSize=50", BASE + "?page=1&pageSize=50", BASE + "?page=1&pageSize=999999");
    }

    /** Mismas reglas que InnpackControlDocumentalApiService.ListAsync + TryGetInt/TryGetString del handler. */
    @Test
    void mapeoDeFiltrosOrdenEscapadoYUtf8IgualQuePhotino() throws Exception {
        MockHttpSession sesion = login("admin1");
        String[][] casos = {
            {"{\"page\":3,\"pageSize\":\"25\"}", "?page=3&pageSize=25"},
            {"{\"page\":0,\"pageSize\":-5}", "?page=1&pageSize=50"},
            {"{\"page\":\"x\",\"pageSize\":2.5}", "?page=1&pageSize=50"},
            {"{\"page\":true,\"pageSize\":null}", "?page=1&pageSize=50"},
            {"{\"texto\":\"Juan Pérez & Cía / 100%\"}", "?page=1&pageSize=50&texto=Juan%20P%C3%A9rez%20%26%20C%C3%ADa%20%2F%20100%25"},
            {"{\"alcanceEmpresa\":\"AMBAS\",\"estado\":\"EN_REVISION\",\"area\":\"Calidad\",\"tipoDocumento\":\"Procedimiento\",\"texto\":\"ñ\"}",
                "?page=1&pageSize=50&texto=%C3%B1&tipoDocumento=Procedimiento&area=Calidad&estado=EN_REVISION&alcanceEmpresa=AMBAS"},
            {"{\"texto\":\"a&page=9\",\"alcanceEmpresa\":\"FARET\"}", "?page=1&pageSize=50&texto=a%26page%3D9&alcanceEmpresa=FARET"},
            {"{\"texto\":\"   \",\"area\":null,\"estado\":\"\"}", "?page=1&pageSize=50"},
            {"{\"texto\":4101,\"area\":1.5}", "?page=1&pageSize=50&texto=4101&area=1.5"},
        };
        for (String[] caso : casos) {
            String cuerpo = "{\"action\":\"" + LIST + "\"," + caso[0].substring(1);
            accion(sesion, cuerpo).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        }
        // "data" NO se lee en este módulo (payload plano): filtros dentro de data se ignoran.
        accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":{\"texto\":\"ignorado\",\"page\":7}}").andExpect(status().isOk());
        List<String> esperadas = new ArrayList<>();
        for (String[] caso : casos) {
            esperadas.add(BASE + caso[1]);
        }
        esperadas.add(BASE + "?page=1&pageSize=50");
        assertThat(API.peticionesControlDocumental()).containsExactlyElementsOf(esperadas);
    }

    @Test
    void tiposInvalidosYAlcanceFueraDeLaVistaSeRechazanSinLlamarALaApi() throws Exception {
        MockHttpSession sesion = login("operador1");
        for (String data : List.of("\"texto\":true", "\"area\":{\"x\":1}", "\"estado\":[\"a\"]", "\"alcanceEmpresa\":false")) {
            accion(sesion, "{\"action\":\"" + LIST + "\"," + data + "}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Parámetro de filtro inválido."));
        }
        for (String alcance : List.of("INNPACK SPA", "innpack", "OTRA", "AMBAS ", "INNPACK' OR 1=1")) {
            accion(sesion, "{\"action\":\"" + LIST + "\",\"alcanceEmpresa\":\"" + alcance.replace("'", "\\u0027") + "\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Filtro de alcance inválido."));
        }
        assertThat(API.peticionesControlDocumental()).isEmpty();
        // La empresa de la sesión NO se inyecta como alcance ("Todos" sigue siendo todos).
        accion(sesion, "{\"action\":\"" + LIST + "\",\"empresa\":\"INNPACK\",\"alcanceEmpresa\":\"\"}").andExpect(status().isOk());
        assertThat(API.peticionesControlDocumental()).containsExactly(BASE + "?page=1&pageSize=50");
    }

    @Test
    void datasetVacioYGrandeYErrorDeLaApi() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode vacio = json(accion(sesion, "{\"action\":\"" + LIST + "\",\"texto\":\"VACIO\"}").andReturn());
        assertThat(vacio.at("/data/items")).isEmpty();
        assertThat(vacio.at("/data/total").asInt()).isZero();
        JsonNode grande = json(accion(sesion, "{\"action\":\"" + LIST + "\",\"texto\":\"GRANDE\",\"pageSize\":999999}").andReturn());
        assertThat(grande.at("/data/items")).hasSize(300);
        assertThat(grande.at("/data/items/299/id").asInt()).isEqualTo(599);
        API.modoDashboard(ModoDashboard.ERROR_NEGOCIO);
        accion(sesion, "{\"action\":\"" + LIST + "\"}")
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Filtro de fecha inválido"));
    }

    // -------------------------------------------------------------------------------- get

    @Test
    void getConIdValidoInvalidoYNoEncontrado() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode ok = json(accion(sesion, "{\"action\":\"" + GET + "\",\"id\":301}").andExpect(status().isOk()).andReturn());
        assertThat(ok.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataControlDocumentalGet(301, 10)));
        assertThat(ok.at("/data/descripcion").asString()).isEqualTo("=SUMA(1;2) sin fórmula");
        accion(sesion, "{\"action\":\"" + GET + "\",\"id\":\" 302 \"}").andExpect(jsonPath("$.ok").value(true));
        // Photino: TryGetInt acepta 0 y negativos (no valida > 0) → van a la API tal cual.
        accion(sesion, "{\"action\":\"" + GET + "\",\"id\":0}").andExpect(jsonPath("$.ok").value(true));
        for (String data : List.of("", ",\"id\":\"abc\"", ",\"id\":true", ",\"id\":2.5", ",\"id\":null", ",\"data\":{\"id\":301}")) {
            accion(sesion, "{\"action\":\"" + GET + "\"" + data + "}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Falta el id del documento"));
        }
        accion(sesion, "{\"action\":\"" + GET + "\",\"id\":404}")
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Documento no encontrado"));
        assertThat(API.peticionesControlDocumental()).containsExactly(BASE + "/301", BASE + "/302", BASE + "/0", BASE + "/404");
    }

    // ------------------------------------------------------------------------ adjunto.abrir

    @Test
    void adjuntoPdfEImagenSonPrevisualizablesSinTocarElDisco() throws Exception {
        MockHttpSession sesion = login("operador1");
        long tmpAntes = archivosEnTemp();
        JsonNode pdf = json(accion(sesion, "{\"action\":\"" + ADJUNTO + "\",\"documentoVersionId\":1}").andExpect(status().isOk()).andReturn());
        assertThat(pdf.get("ok").asBoolean()).isTrue();
        List<String> campos = new ArrayList<>();
        pdf.get("data").propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("previsualizable", "nombreArchivo", "tipoMime", "contenidoBase64");
        assertThat(pdf.at("/data/previsualizable").asBoolean()).isTrue();
        assertThat(pdf.at("/data/nombreArchivo").asString()).isEqualTo("PR-CAL-001_v1.1.pdf");
        assertThat(pdf.at("/data/tipoMime").asString()).isEqualTo("application/pdf");
        assertThat(Base64.getDecoder().decode(pdf.at("/data/contenidoBase64").asString())).isEqualTo(FakeInnpackApi.pdfMinimo(1));

        JsonNode png = json(accion(sesion, "{\"action\":\"" + ADJUNTO + "\",\"documentoVersionId\":\"2\"}").andReturn());
        assertThat(png.at("/data/previsualizable").asBoolean()).isTrue();
        assertThat(png.at("/data/tipoMime").asString()).isEqualTo("image/png");
        assertThat(png.at("/data/nombreArchivo").asString()).isEqualTo("diagrama.png");

        assertThat(API.peticionesControlDocumental()).containsExactly(BASE + "/adjunto/1", BASE + "/adjunto/2");
        assertThat(archivosEnTemp()).isEqualTo(tmpAntes);
        assertThat(Files.exists(Path.of(System.getProperty("java.io.tmpdir"), "QCC_ControlDocumental"))).isFalse();
        assertThat(pdf.toString()).doesNotContain("path", "Temp", FakeInnpackApi.firmaDeToken(10));
    }

    @Test
    void adjuntoNoPrevisualizableSeEntregaParaDescargaConMimeSeguro() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode docx = json(accion(sesion, "{\"action\":\"" + ADJUNTO + "\",\"documentoVersionId\":3}").andReturn());
        assertThat(docx.at("/data/previsualizable").asBoolean()).isFalse();
        assertThat(docx.at("/data/nombreArchivo").asString()).isEqualTo("instructivo ñ.docx");
        assertThat(docx.at("/data/tipoMime").asString()).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(docx.at("/data/contenidoBase64").asString()).startsWith("UEsDB");

        // MIME declarado application/pdf pero contenido HTML → NO se previsualiza (firma real manda).
        JsonNode falso = json(accion(sesion, "{\"action\":\"" + ADJUNTO + "\",\"documentoVersionId\":4}").andReturn());
        assertThat(falso.at("/data/previsualizable").asBoolean()).isFalse();
        assertThat(falso.at("/data/tipoMime").asString()).isEqualTo("application/pdf");

        // text/html (no está en la lista segura) → application/octet-stream + nombre saneado.
        JsonNode html = json(accion(sesion, "{\"action\":\"" + ADJUNTO + "\",\"documentoVersionId\":900}").andReturn());
        assertThat(html.at("/data/previsualizable").asBoolean()).isFalse();
        assertThat(html.at("/data/tipoMime").asString()).isEqualTo("application/octet-stream");
        assertThat(html.at("/data/nombreArchivo").asString()).isEqualTo("evil_______.exe");

        JsonNode txt = json(accion(sesion, "{\"action\":\"" + ADJUNTO + "\",\"documentoVersionId\":5}").andReturn());
        assertThat(txt.at("/data/previsualizable").asBoolean()).isFalse();
        assertThat(txt.at("/data/tipoMime").asString()).isEqualTo("text/plain");
    }

    @Test
    void adjuntoInexistenteVacioCorruptoOExcesivoSeRechazaLimpiamente() throws Exception {
        MockHttpSession sesion = login("operador1");
        String[][] casos = {
            {"404", "Esta versión no tiene ningún archivo adjunto"},
            {"600", "El adjunto no trae contenido"},
            {"800", "El adjunto no es válido."},
            {"700", "El adjunto excede el tamaño máximo permitido."},
        };
        for (String[] caso : casos) {
            accion(sesion, "{\"action\":\"" + ADJUNTO + "\",\"documentoVersionId\":" + caso[0] + "}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.data").value((Object) null))
                    .andExpect(jsonPath("$.error").value(caso[1]));
        }
        for (String data : List.of("", ",\"documentoVersionId\":\"abc\"", ",\"documentoVersionId\":true", ",\"data\":{\"documentoVersionId\":1}")) {
            accion(sesion, "{\"action\":\"" + ADJUNTO + "\"" + data + "}")
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Falta el id de la versión"));
        }
        assertThat(API.peticionesControlDocumental()).hasSize(4);
    }

    @Test
    void helpersDeFirmaYNombre() {
        assertThat(ControlDocumentalBridgeHandler.firmaCoincide("application/pdf", "%PDF-1.7".getBytes())).isTrue();
        assertThat(ControlDocumentalBridgeHandler.firmaCoincide("application/pdf", "<html>".getBytes())).isFalse();
        assertThat(ControlDocumentalBridgeHandler.firmaCoincide("image/png", FakeInnpackApi.pngMinimo())).isTrue();
        assertThat(ControlDocumentalBridgeHandler.firmaCoincide("image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0})).isTrue();
        assertThat(ControlDocumentalBridgeHandler.firmaCoincide("image/svg+xml", "<svg>".getBytes())).isFalse();
        assertThat(ControlDocumentalBridgeHandler.firmaCoincide("text/html", "<html>".getBytes())).isFalse();
        assertThat(ControlDocumentalBridgeHandler.inicioDecodificado("%%%")).isNull();
        assertThat(ControlDocumentalBridgeHandler.inicioDecodificado("JVBERi0x")).isEqualTo("%PDF-1".getBytes());
        assertThat(ControlDocumentalBridgeHandler.nombreArchivoSeguro("../../etc/passwd", "x")).isEqualTo("passwd");
        assertThat(ControlDocumentalBridgeHandler.nombreArchivoSeguro("CON.docx", "x")).isEqualTo("_CON.docx");
        assertThat(ControlDocumentalBridgeHandler.nombreArchivoSeguro("  ", "adjunto_7")).isEqualTo("adjunto_7");
        assertThat(ControlDocumentalBridgeHandler.nombreArchivoSeguro("informe‮.fdp.docx", "x")).isEqualTo("informe.fdp.docx");
        assertThat(ControlDocumentalBridgeHandler.nombreArchivoSeguro("x".repeat(300), "x")).hasSize(150);
    }

    // ------------------------------------------------------------- escrituras / roles / empresa

    @Test
    void rolPermitidoYNoPermitido() throws Exception {
        for (String a : List.of(LIST, GET, ADJUNTO)) {
            accion(login("consulta1"), "{\"action\":\"" + a + "\",\"id\":301,\"documentoVersionId\":1}").andExpect(status().isForbidden());
        }
        assertThat(API.peticionesControlDocumental()).isEmpty();
        accion(login("operador1"), "{\"action\":\"" + LIST + "\"}").andExpect(status().isOk());
        accion(login("admin1"), "{\"action\":\"" + GET + "\",\"id\":301}").andExpect(status().isOk());
        assertThat(API.peticionesControlDocumental()).hasSize(2);
    }

    @Test
    void empresaDeSesionCorrectaEIncorrecta() {
        for (String a : List.of(LIST, GET, ADJUNTO)) {
            assertThat(policy.evaluar(a, usuario("INNPACK", "operador"))).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            assertThat(policy.evaluar(a, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        }
    }

    // --------------------------------------------------------- identidad y aislamiento

    @Test
    void identidadManipuladaSinEfecto() throws Exception {
        MockHttpSession sesion = login("operador1");
        accion(sesion, "{\"action\":\"" + GET + "\",\"id\":301,\"usuarioId\":20,\"rol\":\"admin\",\"empresa\":\"FARET\",\"actualizadoPor\":\"Admin Uno\",\"token\":\"x\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usuarioDelToken").value(10));
        assertThat(API.peticionesControlDocumental()).containsExactly(BASE + "/301");
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
                    JsonNode json = json(accion(esA ? a : b, "{\"action\":\"" + LIST + "\"}").andExpect(status().isOk()).andReturn());
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
        accion(a, "{\"action\":\"" + ADJUNTO + "\",\"documentoVersionId\":1}").andExpect(status().isUnauthorized());
        assertThat(a.isInvalid()).isTrue();
        accion(b, "{\"action\":\"" + LIST + "\"}").andExpect(status().isOk());
    }

    @Test
    void csrfObligatorio() throws Exception {
        MockHttpSession sesion = login("operador1");
        mockMvc.perform(post("/api/v1/bridge").session(sesion).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + ADJUNTO + "\",\"documentoVersionId\":1}"))
                .andExpect(status().isForbidden());
        assertThat(API.peticionesControlDocumental()).isEmpty();
    }

    // ------------------------------------------------------------------------- helpers

    private static long archivosEnTemp() throws IOException {
        try (Stream<Path> s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".pdf") || n.endsWith(".png") || n.endsWith(".docx") || n.startsWith("qcc_controldocumental");
            }).count();
        }
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.8.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-2h");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
