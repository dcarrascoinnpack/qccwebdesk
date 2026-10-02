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

/** Fase 2l — Laboratorio - Muestras, solo lectura contra la API INNPACK (9 acciones, payload en "data"). API SIMULADA. */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFase2lTest {

    private static final String LIST = "muestraLab.list";
    private static final String DETALLE = "muestraLab.detalle";
    private static final String ADJUNTO = "muestraLab.adjunto.abrir";
    private static final String HISTORIAL = "muestraLab.bobinaHistorial";
    private static final String REGISTRO = "muestraLab.registroProduccion.list";
    private static final List<String> LECTURAS = List.of(LIST, DETALLE, "muestraLab.catalogos", "muestraLab.indicadores",
            "muestraLab.metodo.list", "muestraLab.especificacion.list", HISTORIAL, REGISTRO, ADJUNTO);
    private static final List<String> NO_HABILITADAS = List.of(
            "muestraLab.consultarNp", "muestraLab.consultarRegistroProduccion", "muestraLab.resolverBobina");
    private static final String BASE = "GET /api/muestra-laboratorio";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveLaboratorio#2026";
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
    void listConElPayloadExactoDeLaPantalla() throws Exception {
        MockHttpSession sesion = login("operador1");
        // cargarLista(): todos los filtros vacíos y maquinaId null.
        JsonNode json = json(accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":{\"estado\":\"\",\"np\":\"\",\"fechaDesde\":\"\",\"fechaHasta\":\"\","
                + "\"cliente\":\"\",\"codigoProducto\":\"\",\"descripcion\":\"\",\"origen\":\"\",\"maquinaId\":null,\"analistaNombre\":\"\",\"bobina\":\"\"}}")
                .andExpect(status().isOk()).andReturn());
        List<String> campos = new ArrayList<>();
        json.propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("ok", "success", "data", "error");
        assertThat(json.at("/data/0/cliente").asString()).isEqualTo("Viña Ñandú «1»");
        assertThat(API.peticionesLaboratorio()).containsExactly(BASE);
    }

    /** Mismas reglas que InnpackMuestraLaboratorioApiService.ListAsync (orden fijo, maquinaId al final, TrimEnd). */
    @Test
    void mapeoDeFiltrosOrdenEscapadoYMaquinaId() throws Exception {
        MockHttpSession sesion = login("admin1");
        String[][] casos = {
            {"{\"bobina\":\"B-1\",\"analistaNombre\":\"María\",\"maquinaId\":3,\"origen\":\"Recepcion\",\"descripcion\":\"Caja\",\"codigoProducto\":\"C1\","
                + "\"cliente\":\"Viña & Cía\",\"fechaHasta\":\"2026-09-30\",\"fechaDesde\":\"2026-09-01\",\"np\":\"NP-1\",\"tipoMuestra\":\"Papel\",\"estado\":\"EnAnalisis\"}",
                "?estado=EnAnalisis&tipoMuestra=Papel&np=NP-1&fechaDesde=2026-09-01&fechaHasta=2026-09-30&cliente=Vi%C3%B1a%20%26%20C%C3%ADa"
                    + "&codigoProducto=C1&descripcion=Caja&origen=Recepcion&analistaNombre=Mar%C3%ADa&bobina=B-1&maquinaId=3"},
            {"{\"maquinaId\":\"7\"}", "?maquinaId=7"},
            {"{\"maquinaId\":\"x\",\"np\":\"a&maquinaId=9\"}", "?np=a%26maquinaId%3D9"},
            {"{\"maquinaId\":2.5,\"estado\":\"   \"}", ""},
            {"{\"np\":4101}", "?np=4101"},
        };
        List<String> esperadas = new ArrayList<>();
        for (String[] caso : casos) {
            accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":" + caso[0] + "}").andExpect(jsonPath("$.ok").value(true));
            esperadas.add(BASE + caso[1]);
        }
        // Sin "data" o con filtros en la raíz → se ignoran.
        accion(sesion, "{\"action\":\"" + LIST + "\",\"np\":\"NP-1\"}").andExpect(jsonPath("$.ok").value(true));
        esperadas.add(BASE);
        assertThat(API.peticionesLaboratorio()).containsExactlyElementsOf(esperadas);
    }

    @Test
    void tiposInvalidosSeRechazanSinLlamarALaApi() throws Exception {
        MockHttpSession sesion = login("operador1");
        for (String data : List.of("{\"np\":true}", "{\"cliente\":{\"x\":1}}", "{\"bobina\":[\"a\"]}", "{\"maquinaId\":true}", "{\"maquinaId\":[3]}")) {
            accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":" + data + "}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Parámetro de filtro inválido."));
        }
        assertThat(API.peticionesLaboratorio()).isEmpty();
    }

    // ---------------------------------------------------------- lecturas simples y por id

    @Test
    void detalleCatalogosIndicadoresMetodosYEspecificaciones() throws Exception {
        MockHttpSession sesion = login("operador1");
        accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"id\":501}}").andExpect(jsonPath("$.data.id").value(501));
        accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"id\":\" 502 \"}}").andExpect(jsonPath("$.ok").value(true));
        // Photino: GetInt(..) ?? 0 → sin id válido va como 0 (la API: "Muestra no encontrada").
        for (String data : List.of("{}", "{\"id\":\"abc\"}", "{\"id\":true}")) {
            accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":" + data + "}").andExpect(jsonPath("$.error").value("Muestra no encontrada"));
        }
        accion(sesion, "{\"action\":\"muestraLab.catalogos\",\"data\":{}}").andExpect(jsonPath("$.data.maquinas[0].nombre").value("Corrugadora ñ"));
        accion(sesion, "{\"action\":\"muestraLab.indicadores\"}").andExpect(jsonPath("$.data.total").value(12));
        accion(sesion, "{\"action\":\"muestraLab.metodo.list\",\"data\":{}}").andExpect(jsonPath("$.data[0].tipoEnsayo").value("HUMEDAD"));
        accion(sesion, "{\"action\":\"muestraLab.especificacion.list\",\"data\":{}}").andExpect(jsonPath("$.data[0].tipoEnsayo").value("ECT"));
        assertThat(API.peticionesLaboratorio()).containsExactly(BASE + "/501", BASE + "/502", BASE + "/0", BASE + "/0", BASE + "/0",
                BASE + "/catalogos", BASE + "/indicadores", BASE + "/metodos", BASE + "/especificaciones");
    }

    @Test
    void bobinaHistorialYRegistroProduccion() throws Exception {
        MockHttpSession sesion = login("operador1");
        accion(sesion, "{\"action\":\"" + HISTORIAL + "\",\"data\":{\"numeroBobina\":\"B 1/ñ\",\"excluirMuestraId\":501}}").andExpect(jsonPath("$.ok").value(true));
        accion(sesion, "{\"action\":\"" + HISTORIAL + "\",\"data\":{\"numeroBobina\":\"B1\"}}").andExpect(jsonPath("$.ok").value(true));
        accion(sesion, "{\"action\":\"" + REGISTRO + "\",\"data\":{\"np\":\"NP-100\",\"lote\":\"L&1\"}}").andExpect(jsonPath("$.ok").value(true));
        accion(sesion, "{\"action\":\"" + REGISTRO + "\",\"data\":{\"np\":\"NP-100\",\"lote\":\" \"}}").andExpect(jsonPath("$.ok").value(true));
        accion(sesion, "{\"action\":\"" + REGISTRO + "\",\"data\":{\"np\":\"ERROR\"}}").andExpect(jsonPath("$.error").value("No hay controles para esa NP"));
        assertThat(API.peticionesLaboratorio()).containsExactly(
                BASE + "/bobina-historial?numeroBobina=B%201%2F%C3%B1&excluirMuestraId=501",
                BASE + "/bobina-historial?numeroBobina=B1&excluirMuestraId=0",
                BASE + "/registro-produccion?np=NP-100&lote=L%261",
                BASE + "/registro-produccion?np=NP-100",
                BASE + "/registro-produccion?np=ERROR");
        for (String data : List.of("{}", "{\"numeroBobina\":\"  \"}", "{\"numeroBobina\":true}")) {
            accion(sesion, "{\"action\":\"" + HISTORIAL + "\",\"data\":" + data + "}").andExpect(jsonPath("$.error").value("Falta el número de bobina"));
        }
        for (String data : List.of("{}", "{\"np\":\"\"}", "{\"np\":{}}")) {
            accion(sesion, "{\"action\":\"" + REGISTRO + "\",\"data\":" + data + "}").andExpect(jsonPath("$.error").value("Falta indicar la NP"));
        }
        assertThat(API.peticionesLaboratorio()).hasSize(5);
    }

    @Test
    void errorDeLaApi() throws Exception {
        MockHttpSession sesion = login("operador1");
        API.modoDashboard(ModoDashboard.ERROR_NEGOCIO);
        for (String a : List.of(LIST, DETALLE, "muestraLab.indicadores")) {
            accion(sesion, "{\"action\":\"" + a + "\",\"data\":{\"id\":1}}").andExpect(jsonPath("$.error").value("Filtro de fecha inválido"));
        }
    }

    // ------------------------------------------------------------------ adjunto.abrir

    @Test
    void adjuntoPdfEImagenPrevisualizablesYDocxParaDescarga() throws Exception {
        MockHttpSession sesion = login("operador1");
        long tmpAntes = archivosEnTemp();
        JsonNode pdf = json(accion(sesion, "{\"action\":\"" + ADJUNTO + "\",\"data\":{\"adjuntoId\":1}}").andExpect(status().isOk()).andReturn());
        List<String> campos = new ArrayList<>();
        pdf.get("data").propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("previsualizable", "nombreArchivo", "tipoMime", "contenidoBase64");
        assertThat(pdf.at("/data/previsualizable").asBoolean()).isTrue();
        assertThat(pdf.at("/data/nombreArchivo").asString()).isEqualTo("informe ñ.pdf");
        assertThat(Base64.getDecoder().decode(pdf.at("/data/contenidoBase64").asString())).isEqualTo(FakeInnpackApi.pdfMinimo(1));
        assertThat(json(accion(sesion, "{\"action\":\"" + ADJUNTO + "\",\"data\":{\"adjuntoId\":\"2\"}}").andReturn())
                .at("/data/tipoMime").asString()).isEqualTo("image/png");
        JsonNode docx = json(accion(sesion, "{\"action\":\"" + ADJUNTO + "\",\"data\":{\"adjuntoId\":3}}").andReturn());
        assertThat(docx.at("/data/previsualizable").asBoolean()).isFalse();
        assertThat(docx.at("/data/nombreArchivo").asString()).isEqualTo("certificado.docx");
        // HTML declarado como PDF → nunca previsualizable.
        JsonNode falso = json(accion(sesion, "{\"action\":\"" + ADJUNTO + "\",\"data\":{\"adjuntoId\":4}}").andReturn());
        assertThat(falso.at("/data/previsualizable").asBoolean()).isFalse();
        JsonNode malicioso = json(accion(sesion, "{\"action\":\"" + ADJUNTO + "\",\"data\":{\"adjuntoId\":900}}").andReturn());
        assertThat(malicioso.at("/data/nombreArchivo").asString()).isEqualTo("evil__.docx");
        assertThat(malicioso.at("/data/tipoMime").asString()).isEqualTo("application/msword");
        assertThat(API.peticionesLaboratorio()).containsExactly(BASE + "/adjunto/1", BASE + "/adjunto/2", BASE + "/adjunto/3",
                BASE + "/adjunto/4", BASE + "/adjunto/900");
        assertThat(archivosEnTemp()).isEqualTo(tmpAntes);
        assertThat(Files.exists(Path.of(System.getProperty("java.io.tmpdir"), "QCC_MuestraLaboratorio"))).isFalse();
    }

    @Test
    void adjuntoInexistenteVacioCorruptoExcesivoOIdInvalido() throws Exception {
        MockHttpSession sesion = login("operador1");
        String[][] casos = {
            {"404", "Adjunto no encontrado"},
            {"600", "El adjunto no trae contenido"},
            {"700", "El adjunto excede el tamaño máximo permitido."},
            {"800", "El adjunto no es válido."},
        };
        for (String[] caso : casos) {
            accion(sesion, "{\"action\":\"" + ADJUNTO + "\",\"data\":{\"adjuntoId\":" + caso[0] + "}}")
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.data").value((Object) null))
                    .andExpect(jsonPath("$.error").value(caso[1]));
        }
        for (String data : List.of("{}", "{\"adjuntoId\":0}", "{\"adjuntoId\":-1}", "{\"adjuntoId\":\"x\"}", "{\"adjuntoId\":true}")) {
            accion(sesion, "{\"action\":\"" + ADJUNTO + "\",\"data\":" + data + "}").andExpect(jsonPath("$.error").value("Falta indicar el adjunto"));
        }
        accion(sesion, "{\"action\":\"" + ADJUNTO + "\",\"adjuntoId\":1}").andExpect(jsonPath("$.error").value("Falta indicar el adjunto"));
        assertThat(API.peticionesLaboratorio()).hasSize(casos.length);
    }

    // ------------------------------------------------------------- escrituras / roles / empresa

    @Test
    void escriturasYLecturasExternasSiguenBloqueadas() throws Exception {
        MockHttpSession admin = login("admin1");
        assertThat(NO_HABILITADAS).hasSize(3); // Fase 3w: materialesFps habilitada; Fase 4e-1..4e-5: las 25 escrituras de Laboratorio habilitadas
        for (String accion : NO_HABILITADAS) {
            accion(admin, "{\"action\":\"" + accion + "\",\"data\":{\"id\":501,\"muestraId\":501,\"np\":\"NP-1\",\"idProceso\":5,\"lote\":\"L1\"}}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
            assertThat(policy.accionesRegistradas()).doesNotContain(accion);
        }
        assertThat(API.peticionesLaboratorio()).isEmpty();
    }

    @Test
    void rolYEmpresa() throws Exception {
        for (String a : LECTURAS) {
            accion(login("consulta1"), "{\"action\":\"" + a + "\",\"data\":{\"id\":1,\"adjuntoId\":1,\"np\":\"x\",\"numeroBobina\":\"x\"}}")
                    .andExpect(status().isForbidden());
            assertThat(policy.evaluar(a, usuario("INNPACK", "operador"))).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            assertThat(policy.evaluar(a, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        }
        assertThat(API.peticionesLaboratorio()).isEmpty();
    }

    // --------------------------------------------------------- identidad y aislamiento

    @Test
    void identidadManipuladaSinEfectoYConcurrencia() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        accion(a, "{\"action\":\"" + DETALLE + "\",\"usuarioId\":20,\"rol\":\"admin\",\"data\":{\"id\":501,\"usuarioId\":20}}")
                .andExpect(jsonPath("$.data.usuarioDelToken").value(10));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<int[]>> tareas = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                boolean esA = i % 2 == 0;
                tareas.add(() -> {
                    JsonNode json = json(accion(esA ? a : b, "{\"action\":\"" + DETALLE + "\",\"data\":{\"id\":501}}").andExpect(status().isOk()).andReturn());
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
    void unauthorizedUpstreamInvalidaSoloEsaSesionYCsrf() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        mockMvc.perform(post("/api/v1/bridge").session(b).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + LIST + "\",\"data\":{}}"))
                .andExpect(status().isForbidden());
        API.revocarTokens(10);
        accion(a, "{\"action\":\"" + ADJUNTO + "\",\"data\":{\"adjuntoId\":1}}").andExpect(status().isUnauthorized());
        assertThat(a.isInvalid()).isTrue();
        accion(b, "{\"action\":\"" + LIST + "\",\"data\":{}}").andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------- helpers

    private static long archivosEnTemp() throws IOException {
        try (var s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".pdf") || n.endsWith(".docx") || n.startsWith("qcc_muestralaboratorio");
            }).count();
        }
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.12.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-2l");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
