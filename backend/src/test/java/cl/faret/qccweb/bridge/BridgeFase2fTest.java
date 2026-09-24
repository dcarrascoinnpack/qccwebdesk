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

/** Fase 2f — Producto Terminado, solo lectura (filtros/resumen/list/detalle/exportarDetalle). API SIMULADA. */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFase2fTest {

    private static final String FILTROS = "productoTerminado.filtros";
    private static final String RESUMEN = "productoTerminado.resumen";
    private static final String LIST = "productoTerminado.list";
    private static final String DETALLE = "productoTerminado.detalle";
    private static final String EXPORTAR = "productoTerminado.exportarDetalle";
    private static final List<String> LECTURAS = List.of(FILTROS, RESUMEN, LIST, DETALLE, EXPORTAR);
    private static final List<String> ESCRITURAS = List.of("productoTerminado.eliminar", "productoTerminado.actualizarFecha");
    private static final String BASE = "GET /api/producto-terminado";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveProductoTerminado#2026";
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

    // ----------------------------------------------------------------- mapeo exacto de cada lectura

    @Test
    void filtrosSoloLlevaEmpresa() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode json = json(accion(sesion, "{\"action\":\"" + FILTROS + "\",\"data\":{\"empresa\":\"INNPACK\",\"np\":\"41\"}}")
                .andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataProductoTerminadoFiltros("INNPACK")));
        assertThat(json.at("/data/inspectores/1/nombre").asString()).isEqualTo("María José Peña");
        assertThat(API.peticionesProductoTerminado()).containsExactly(BASE + "/filtros?empresa=INNPACK");
    }

    @Test
    void resumenListYExportarConFiltrosVaciosDeLaPantalla() throws Exception {
        MockHttpSession sesion = login("operador1");
        // getFiltrosActuales() de Photino con todo en blanco (inspectorId/origenId llegan como "").
        String filtros = "\"empresa\":\"INNPACK\",\"fechaDesde\":\"\",\"fechaHasta\":\"\",\"np\":\"\",\"codigoProducto\":\"\","
                + "\"proceso\":\"\",\"maquina\":\"\",\"turno\":\"\",\"inspectorId\":\"\",\"resultado\":\"\",\"origenId\":\"\"";
        JsonNode resumen = json(accion(sesion, "{\"action\":\"" + RESUMEN + "\",\"data\":{" + filtros + "}}").andExpect(status().isOk()).andReturn());
        JsonNode list = json(accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":{" + filtros + ",\"page\":1,\"limit\":50}}").andExpect(status().isOk()).andReturn());
        JsonNode export = json(accion(sesion, "{\"action\":\"" + EXPORTAR + "\",\"data\":{" + filtros + "}}").andExpect(status().isOk()).andReturn());

        List<String> campos = new ArrayList<>();
        resumen.propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("ok", "success", "data", "error");
        assertThat(resumen.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataProductoTerminadoResumen(10, "INNPACK")));
        assertThat(list.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataProductoTerminadoList(10, "page=1&limit=50")));
        assertThat(list.at("/data/items")).hasSize(2);
        assertThat(list.at("/data/total").asInt()).isEqualTo(45);
        assertThat(export.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataProductoTerminadoExport("")));
        assertThat(export.get("data")).hasSize(3);
        assertThat(export.at("/data/0/cliente").asString()).isEqualTo("Cliente Ñandú «E2E»");

        assertThat(API.peticionesProductoTerminado()).containsExactly(
                BASE + "/resumen?empresa=INNPACK",
                BASE + "?empresa=INNPACK&page=1&limit=50",
                BASE + "/exportar-detalle?empresa=INNPACK");
    }

    /** Mismas reglas que BuildQuery de InnpackProductoTerminadoApiService + GetString/GetInt del handler. */
    @Test
    void mapeoDeFiltrosOrdenYEscapadoIgualQuePhotino() throws Exception {
        MockHttpSession sesion = login("admin1");
        String[][] casos = {
            {"{\"turno\":\"B\"}", "empresa=INNPACK&turno=B"},
            {"{\"inspectorId\":\"20\",\"origenId\":3}", "empresa=INNPACK&inspectorId=20&origenId=3"},
            // combinado desordenado → orden fijo de Photino
            {"{\"origenId\":\"3\",\"resultado\":\"NO CONFORME\",\"inspectorId\":20,\"turno\":\"A\",\"maquina\":\"Pegadora 3\","
                + "\"proceso\":\"Pegado\",\"codigoProducto\":\"CP-1\",\"np\":\"4101\",\"fechaHasta\":\"2026-09-24\",\"fechaDesde\":\"2026-09-01\"}",
                "empresa=INNPACK&fechaDesde=2026-09-01&fechaHasta=2026-09-24&np=4101&codigoProducto=CP-1&proceso=Pegado"
                    + "&maquina=Pegadora%203&turno=A&inspectorId=20&resultado=NO%20CONFORME&origenId=3"},
            // escapado Uri.EscapeDataString y UTF-8
            {"{\"np\":\"Juan Pérez & Cía / 100%\",\"codigoProducto\":\"a-b_c.d~e\"}",
                "empresa=INNPACK&np=Juan%20P%C3%A9rez%20%26%20C%C3%ADa%20%2F%20100%25&codigoProducto=a-b_c.d~e"},
            {"{\"np\":\"4101&empresa=FARET\"}", "empresa=INNPACK&np=4101%26empresa%3DFARET"},
            // vacíos / blancos / null se omiten; número en texto viaja como texto
            {"{\"np\":\"   \",\"turno\":null,\"maquina\":\"\",\"inspectorId\":null,\"np2\":\"x\"}", "empresa=INNPACK"},
            {"{\"np\":4101,\"resultado\":1.5}", "empresa=INNPACK&np=4101&resultado=1.5"},
            // GetString de Photino: bool/objeto/array → "" (se omiten, sin error)
            {"{\"np\":true,\"turno\":{\"a\":1},\"maquina\":[\"x\"]}", "empresa=INNPACK"},
            // GetInt: entero o string numérica; lo demás se omite
            {"{\"inspectorId\":\" 7 \",\"origenId\":\"+2\"}", "empresa=INNPACK&inspectorId=7&origenId=2"},
            {"{\"inspectorId\":\"x\",\"origenId\":2.5}", "empresa=INNPACK"},
            {"{\"inspectorId\":true,\"origenId\":99999999999}", "empresa=INNPACK"},
        };
        for (String[] caso : casos) {
            accion(sesion, "{\"action\":\"" + RESUMEN + "\",\"data\":" + caso[0] + "}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true));
        }
        // Filtros fuera de "data" se ignoran (Photino solo lee data).
        accion(sesion, "{\"action\":\"" + RESUMEN + "\",\"np\":\"41\",\"turno\":\"A\"}").andExpect(status().isOk());

        List<String> esperadas = new ArrayList<>();
        for (String[] caso : casos) {
            esperadas.add(BASE + "/resumen?" + caso[1]);
        }
        esperadas.add(BASE + "/resumen?empresa=INNPACK");
        assertThat(API.peticionesProductoTerminado()).containsExactlyElementsOf(esperadas);
    }

    @Test
    void listPageYLimitConDefaultsDePhotino() throws Exception {
        MockHttpSession sesion = login("operador1");
        String[][] casos = {
            {"{}", "empresa=INNPACK&page=1&limit=50"},
            {"{\"page\":3,\"limit\":\"25\",\"np\":\"41\"}", "empresa=INNPACK&np=41&page=3&limit=25"},
            {"{\"page\":\"x\",\"limit\":2.5}", "empresa=INNPACK&page=1&limit=50"},
            {"{\"page\":0,\"limit\":-1}", "empresa=INNPACK&page=0&limit=-1"},
        };
        for (String[] caso : casos) {
            accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":" + caso[0] + "}").andExpect(status().isOk());
        }
        accion(sesion, "{\"action\":\"" + LIST + "\"}").andExpect(status().isOk());
        List<String> esperadas = new ArrayList<>();
        for (String[] caso : casos) {
            esperadas.add(BASE + "?" + caso[1]);
        }
        esperadas.add(BASE + "?empresa=INNPACK&page=1&limit=50");
        assertThat(API.peticionesProductoTerminado()).containsExactlyElementsOf(esperadas);
    }

    @Test
    void detalleConIdValidoEInvalido() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode ok = json(accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"empresa\":\"INNPACK\",\"id\":9007}}")
                .andExpect(status().isOk()).andReturn());
        assertThat(ok.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataProductoTerminadoDetalle(9007, "INNPACK", 10)));
        accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"id\":\"12\"}}").andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        for (String data : List.of("{}", "{\"id\":0}", "{\"id\":-5}", "{\"id\":\"abc\"}", "{\"id\":true}", "{\"id\":2.5}")) {
            accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":" + data + "}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Falta el id de la inspección"));
        }
        // Error de negocio de la API (404 "no encontrada") se muestra como en Photino.
        accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"id\":404}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Inspección no encontrada"));
        assertThat(API.peticionesProductoTerminado()).containsExactly(
                BASE + "/9007?empresa=INNPACK", BASE + "/12?empresa=INNPACK", BASE + "/404?empresa=INNPACK");
    }

    @Test
    void datasetVacioYGrandePasanTalCual() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode vacio = json(accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":{\"np\":\"VACIO\"}}").andReturn());
        assertThat(vacio.at("/data/items")).isEmpty();
        assertThat(vacio.at("/data/total").asInt()).isZero();
        JsonNode exportVacio = json(accion(sesion, "{\"action\":\"" + EXPORTAR + "\",\"data\":{\"np\":\"VACIO\"}}").andReturn());
        assertThat(exportVacio.get("data")).isEmpty();

        JsonNode grande = json(accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":{\"np\":\"GRANDE\",\"limit\":500}}").andReturn());
        assertThat(grande.at("/data/items")).hasSize(500);
        assertThat(grande.at("/data/items/499/inspeccionId").asInt()).isEqualTo(9499);
        JsonNode exportGrande = json(accion(sesion, "{\"action\":\"" + EXPORTAR + "\",\"data\":{\"np\":\"GRANDE\"}}").andReturn());
        assertThat(exportGrande.get("data")).hasSize(500);
        // Las 22 columnas que arma construirTablaExportTemp en Photino vienen en cada fila.
        List<String> columnas = new ArrayList<>();
        exportGrande.at("/data/0").propertyNames().forEach(columnas::add);
        assertThat(columnas).containsExactly("inspeccionId", "fecha", "inspector", "np", "cliente", "codigoProducto",
                "descripcionProducto", "proceso", "cantidadLote", "maquina", "nivelInspeccion", "aql", "letraCodigo",
                "tamanoMuestra", "ac", "re", "unidadesNoConformes", "defectosTotales", "resultado", "hallazgoCorrelativo",
                "defecto", "origen");
        assertThat(exportGrande.at("/data/0/descripcionProducto").asString()).isEqualTo("Estuche cartón =SUMA(1;2)");
    }

    @Test
    void errorDeLaApiSeMuestraComoEnPhotino() throws Exception {
        MockHttpSession sesion = login("operador1");
        API.modoDashboard(ModoDashboard.ERROR_NEGOCIO);
        accion(sesion, "{\"action\":\"" + RESUMEN + "\",\"data\":{}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Rango de fechas inválido"));
    }

    // ------------------------------------------------------------- empresa: solo la de la sesión

    @Test
    void empresaManipuladaDesdeDevToolsSeIgnora() throws Exception {
        MockHttpSession sesion = login("operador1");
        for (String data : List.of("{\"empresa\":\"FARET\"}", "{\"empresa\":\"\"}", "{\"empresa\":null}", "{\"empresa\":\"faret\"}",
                "{\"empresa\":\"INNPACK&empresa=FARET\"}", "{}", "{\"empresa\":{\"x\":1}}")) {
            accion(sesion, "{\"action\":\"" + FILTROS + "\",\"empresa\":\"FARET\",\"data\":" + data + "}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.empresaConsultada").value("INNPACK"));
        }
        assertThat(API.peticionesProductoTerminado()).containsOnly(BASE + "/filtros?empresa=INNPACK").hasSize(7);
        // Lo mismo en detalle y list (la empresa va en la query de cada endpoint).
        accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"empresa\":\"FARET\",\"id\":5}}").andExpect(jsonPath("$.data.empresaConsultada").value("INNPACK"));
        accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":{\"empresa\":\"FARET\"}}").andExpect(status().isOk());
        assertThat(API.peticionesProductoTerminado()).endsWith(BASE + "/5?empresa=INNPACK", BASE + "?empresa=INNPACK&page=1&limit=50");
    }

    @Test
    void empresaDeLaSesionInvalidaNoLlegaALaApi() {
        // Sesión con una empresa fuera de INNPACK|FARET: la regla ya la deniega (EMPRESA_NO_PERMITIDA),
        // y aunque no lo hiciera el handler la valida con el mismo mensaje de Photino.
        assertThat(policy.evaluar(FILTROS, usuario("OTRA", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        assertThat(policy.evaluar(FILTROS, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        for (String a : LECTURAS) {
            assertThat(policy.evaluar(a, usuario("INNPACK", "operador"))).isInstanceOf(ActionPolicy.Decision.Permitida.class);
        }
    }

    // ------------------------------------------------------------- escrituras bloqueadas

    @Test
    void escriturasDelModuloSiguenBloqueadas() throws Exception {
        MockHttpSession admin = login("admin1");
        for (String escritura : ESCRITURAS) {
            accion(admin, "{\"action\":\"" + escritura + "\",\"data\":{\"empresa\":\"INNPACK\",\"id\":9001,\"fechaRegistro\":\"2026-09-24\"}}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
            assertThat(policy.accionesRegistradas()).doesNotContain(escritura);
        }
        assertThat(API.peticionesProductoTerminado()).isEmpty();
    }

    // --------------------------------------------------------------- roles

    @Test
    void rolPermitidoYNoPermitido() throws Exception {
        for (String a : LECTURAS) {
            accion(login("consulta1"), "{\"action\":\"" + a + "\",\"data\":{\"id\":1}}").andExpect(status().isForbidden());
        }
        assertThat(API.peticionesProductoTerminado()).isEmpty();
        accion(login("operador1"), "{\"action\":\"" + FILTROS + "\"}").andExpect(status().isOk());
        accion(login("admin1"), "{\"action\":\"" + RESUMEN + "\",\"data\":{}}").andExpect(status().isOk());
        assertThat(API.peticionesProductoTerminado()).hasSize(2);
    }

    // --------------------------------------------------------- identidad y aislamiento

    @Test
    void identidadManipuladaSinEfecto() throws Exception {
        MockHttpSession sesion = login("operador1");
        accion(sesion, "{\"action\":\"" + RESUMEN + "\",\"usuarioId\":20,\"rol\":\"admin\",\"empresa\":\"FARET\","
                        + "\"data\":{\"inspectorId\":\"20\",\"usuarioId\":20,\"empresa\":\"FARET\",\"rol\":\"admin\",\"token\":\"x\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usuarioDelToken").value(10))
                .andExpect(jsonPath("$.data.empresaConsultada").value("INNPACK"));
        // "inspectorId" es un filtro de negocio (a quién se consulta), no la identidad: se respeta.
        assertThat(API.peticionesProductoTerminado()).containsExactly(BASE + "/resumen?empresa=INNPACK&inspectorId=20");
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
                    JsonNode json = json(accion(esA ? a : b, "{\"action\":\"" + LIST + "\",\"data\":{}}")
                            .andExpect(status().isOk()).andReturn());
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
        accion(a, "{\"action\":\"" + FILTROS + "\"}").andExpect(status().isUnauthorized());
        assertThat(a.isInvalid()).isTrue();
        accion(b, "{\"action\":\"" + FILTROS + "\"}").andExpect(status().isOk());
    }

    @Test
    void csrfObligatorio() throws Exception {
        MockHttpSession sesion = login("operador1");
        mockMvc.perform(post("/api/v1/bridge").session(sesion).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + FILTROS + "\"}"))
                .andExpect(status().isForbidden());
        assertThat(API.peticionesProductoTerminado()).isEmpty();
    }

    // ------------------------------------------------------------------------- helpers

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.6.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-2f");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
