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
import cl.faret.qccweb.upstream.UriEscape;
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

/** Fase 2c — Inspecciones Calidad, solo lectura (dashboard.obtenerFiltros / obtenerResumen). API SIMULADA. */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFase2cTest {

    private static final String FILTROS = "dashboard.obtenerFiltros";
    private static final String RESUMEN = "dashboard.obtenerResumen";
    private static final List<String> ESCRITURAS = List.of("dashboard.validarRegistro", "dashboard.rechazarRegistro",
            "dashboard.eliminarRegistro", "dashboard.validarTodo", "dashboard.rechazarTodo");
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveDashboard#2026";
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

    // ----------------------------------------------------------------- paridad filtros/resumen

    @Test
    void filtrosIdenticosALosDePhotino() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode json = json(accion(sesion, "{\"action\":\"" + FILTROS + "\"}").andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataDashboardFiltros()));
        assertThat(json.at("/data/usuarios/1/nombre").asString()).isEqualTo("María José Peña");
        assertThat(API.peticionesDashboard()).containsExactly("filtros");
    }

    @Test
    void resumenIdenticoAlDePhotino() throws Exception {
        MockHttpSession sesion = login("operador1");
        MvcResult res = accion(sesion, "{\"action\":\"" + RESUMEN + "\",\"data\":{\"fechaDesde\":\"\",\"fechaHasta\":\"\","
                + "\"inspector\":\"\",\"turno\":\"\",\"proceso\":\"\"}}").andExpect(status().isOk()).andReturn();
        JsonNode json = json(res);
        List<String> campos = new ArrayList<>();
        json.propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("ok", "success", "data", "error");
        String query = "fechaDesde=&fechaHasta=&inspector=&turno=&proceso=";
        assertThat(json.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataDashboardResumen(10, query)));
        assertThat(json.at("/data/ultimosRegistros/0/producto").asString()).isEqualTo("Estuche cartón ñandú «E2E»");
        assertThat(json.at("/data/cumplimientoGeneral").asDouble()).isEqualTo(93.5);
        assertThat(API.peticionesDashboard()).containsExactly("resumen?" + query);
    }

    /** Mismas reglas que DashboardHandler.cs + InnpackDashboardApiService (Uri.EscapeDataString). */
    @Test
    void mapeoDeFiltrosIgualQuePhotino() throws Exception {
        MockHttpSession sesion = login("admin1");
        String[][] casos = {
            {"{}", "fechaDesde=&fechaHasta=&inspector=&turno=&proceso="},
            {"{\"turno\":\"A\"}", "fechaDesde=&fechaHasta=&inspector=&turno=A&proceso="},
            {"{\"fechaDesde\":\"2026-09-01\",\"fechaHasta\":\"2026-09-23\",\"inspector\":\"20\",\"turno\":\"B\",\"proceso\":\"5\"}",
                "fechaDesde=2026-09-01&fechaHasta=2026-09-23&inspector=20&turno=B&proceso=5"},
            {"{\"inspector\":\"Juan Pérez & Cía / 100%\",\"turno\":null}",
                "fechaDesde=&fechaHasta=&inspector=Juan%20P%C3%A9rez%20%26%20C%C3%ADa%20%2F%20100%25&turno=&proceso="},
            {"{\"proceso\":\"5&inspector=20\"}", "fechaDesde=&fechaHasta=&inspector=&turno=&proceso=5%26inspector%3D20"},
            {"{\"turno\":\"a-b_c.d~e\"}", "fechaDesde=&fechaHasta=&inspector=&turno=a-b_c.d~e&proceso="},
        };
        for (String[] caso : casos) {
            accion(sesion, "{\"action\":\"" + RESUMEN + "\",\"data\":" + caso[0] + "}").andExpect(status().isOk());
        }
        // Filtros fuera de "data" se ignoran (Photino solo lee data).
        accion(sesion, "{\"action\":\"" + RESUMEN + "\",\"turno\":\"A\",\"inspector\":\"20\"}").andExpect(status().isOk());

        List<String> esperadas = new ArrayList<>();
        for (String[] caso : casos) {
            esperadas.add("resumen?" + caso[1]);
        }
        esperadas.add("resumen?fechaDesde=&fechaHasta=&inspector=&turno=&proceso=");
        assertThat(API.peticionesDashboard()).containsExactlyElementsOf(esperadas);
    }

    @Test
    void filtroNoStringEsErrorSinLlamarALaApi() throws Exception {
        MockHttpSession sesion = login("operador1");
        for (String data : List.of("{\"inspector\":20}", "{\"turno\":true}", "{\"proceso\":{\"id\":5}}")) {
            accion(sesion, "{\"action\":\"" + RESUMEN + "\",\"data\":" + data + "}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Parámetro de filtro inválido."));
        }
        assertThat(API.peticionesDashboard()).isEmpty();
    }

    @Test
    void uriEscapeEquivaleAUriEscapeDataString() {
        assertThat(UriEscape.dataString("")).isEmpty();
        assertThat(UriEscape.dataString(null)).isEmpty();
        assertThat(UriEscape.dataString("AZaz09-_.~")).isEqualTo("AZaz09-_.~");
        assertThat(UriEscape.dataString(" +*'()!")).isEqualTo("%20%2B%2A%27%28%29%21");
        assertThat(UriEscape.dataString("ñ€")).isEqualTo("%C3%B1%E2%82%AC");
    }

    @Test
    void datasetVacioYGrandePasanTalCual() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode vacio = json(accion(sesion, "{\"action\":\"" + RESUMEN + "\",\"data\":{\"inspector\":\"0\"}}").andReturn());
        assertThat(vacio.at("/data/ultimosRegistros")).isEmpty();
        assertThat(vacio.at("/data/desempenoIndividual")).isEmpty();
        assertThat(vacio.at("/data/controlesPeriodo").asInt()).isZero();

        JsonNode grande = json(accion(sesion, "{\"action\":\"" + RESUMEN + "\",\"data\":{\"proceso\":\"999\"}}").andReturn());
        assertThat(grande.at("/data/ultimosRegistros")).hasSize(400);
        assertThat(grande.at("/data/desempenoIndividual")).hasSize(40);
        assertThat(grande.at("/data/ultimosRegistros/399/id").asInt()).isEqualTo(899);
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

    // ------------------------------------------------------------- escrituras bloqueadas

    @Test
    void escriturasDelModuloSiguenBloqueadas() throws Exception {
        MockHttpSession admin = login("admin1");
        for (String escritura : ESCRITURAS) {
            accion(admin, "{\"action\":\"" + escritura + "\",\"id\":501}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
            assertThat(policy.accionesRegistradas()).doesNotContain(escritura);
        }
        assertThat(API.peticionesDashboard()).isEmpty();
    }

    // --------------------------------------------------------------- roles y empresa

    @Test
    void rolPermitidoYNoPermitido() throws Exception {
        accion(login("operador1"), "{\"action\":\"" + FILTROS + "\"}").andExpect(status().isOk());
        accion(login("admin1"), "{\"action\":\"" + RESUMEN + "\",\"data\":{}}").andExpect(status().isOk());
        accion(login("consulta1"), "{\"action\":\"" + FILTROS + "\"}").andExpect(status().isForbidden());
        accion(login("consulta1"), "{\"action\":\"" + RESUMEN + "\",\"data\":{}}").andExpect(status().isForbidden());
        assertThat(API.peticionesDashboard()).hasSize(2);
    }

    @Test
    void empresaCorrectaEIncorrecta() {
        for (String a : List.of(FILTROS, RESUMEN)) {
            assertThat(policy.evaluar(a, usuario("INNPACK", "admin"))).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            assertThat(policy.evaluar(a, usuario("FARET", "admin")))
                    .isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        }
    }

    // --------------------------------------------------------- identidad y aislamiento

    @Test
    void identidadManipuladaSinEfecto() throws Exception {
        MockHttpSession sesion = login("operador1");
        accion(sesion, "{\"action\":\"" + RESUMEN + "\",\"usuarioId\":20,\"rol\":\"admin\",\"empresa\":\"FARET\","
                        + "\"data\":{\"inspector\":\"20\",\"usuarioId\":20,\"empresa\":\"FARET\",\"rol\":\"admin\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usuarioDelToken").value(10));
        // "inspector" es un filtro de negocio (a quién se consulta), no la identidad: se respeta.
        assertThat(API.peticionesDashboard()).containsExactly("resumen?fechaDesde=&fechaHasta=&inspector=20&turno=&proceso=");
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
                    JsonNode json = json(accion(esA ? a : b, "{\"action\":\"" + RESUMEN + "\",\"data\":{}}")
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
        assertThat(API.peticionesDashboard()).isEmpty();
    }

    // ------------------------------------------------------------------------- helpers

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.3.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-2c");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
