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

/** Fase 2e — Registros de Control, solo lectura (registrosControl.obtenerRegistros). API SIMULADA. */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFase2eTest {

    private static final String OBTENER = "registrosControl.obtenerRegistros";
    private static final String BASE = "GET /api/registros-control?";
    private static final String DEFAULTS = "page=1&limit=20";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveControl#2026";
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

    // ----------------------------------------------------------------- paridad con Photino

    @Test
    void defaultsSinDataYConDataVacia() throws Exception {
        MockHttpSession sesion = login("operador1");
        for (String cuerpo : List.of("{\"action\":\"" + OBTENER + "\"}", "{\"action\":\"" + OBTENER + "\",\"data\":{}}",
                "{\"action\":\"" + OBTENER + "\",\"data\":null}", "{\"action\":\"" + OBTENER + "\",\"data\":\"texto\"}")) {
            accion(sesion, cuerpo).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        }
        assertThat(API.peticionesControl()).containsOnly(BASE + DEFAULTS).hasSize(4);
    }

    @Test
    void respuestaPaginadaIdenticaALaDePhotino() throws Exception {
        MockHttpSession sesion = login("operador1");
        MvcResult res = accion(sesion, "{\"action\":\"" + OBTENER + "\",\"data\":{\"page\":2,\"limit\":20,\"fechaDesde\":\"\","
                + "\"fechaHasta\":\"\",\"np\":\"\",\"turno\":\"\",\"estado\":\"\"}}").andExpect(status().isOk()).andReturn();
        JsonNode json = json(res);
        List<String> campos = new ArrayList<>();
        json.propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("ok", "success", "data", "error");
        assertThat(json.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataRegistrosControl(10, "page=2&limit=20")));
        assertThat(json.at("/data/items")).hasSize(2);
        assertThat(json.at("/data/total").asInt()).isEqualTo(45);
        assertThat(json.at("/data/pages").asInt()).isEqualTo(3);
        assertThat(json.at("/data/page").asInt()).isEqualTo(2);
        assertThat(json.at("/data/items/0/producto").asString()).isEqualTo("Estuche cartón ñandú «E2E»");
        // Como en Photino: los filtros vacíos de la grilla no viajan a la API.
        assertThat(API.peticionesControl()).containsExactly(BASE + "page=2&limit=20");
    }

    /** Mismas reglas que RegistrosControlHandler.cs + InnpackRegistrosControlApiService. */
    @Test
    void mapeoDeFiltrosIgualQuePhotino() throws Exception {
        MockHttpSession sesion = login("admin1");
        String[][] casos = {
            // filtros individuales
            {"{\"fechaDesde\":\"2026-09-01\"}", DEFAULTS + "&fechaDesde=2026-09-01"},
            {"{\"np\":\"3996\"}", DEFAULTS + "&np=3996"},
            {"{\"turno\":\"B\"}", DEFAULTS + "&turno=B"},
            {"{\"estado\":\"Pendiente\"}", DEFAULTS + "&estado=Pendiente"},
            {"{\"id\":\"12\"}", DEFAULTS + "&id=12"},
            {"{\"id\":12}", DEFAULTS + "&id=12"},
            {"{\"procesoId\":4}", DEFAULTS + "&procesoId=4"},
            {"{\"parametroId\":\"9\"}", DEFAULTS + "&parametroId=9"},
            // combinado, en el orden fijo de Photino aunque el payload venga desordenado
            {"{\"parametroId\":9,\"estado\":\"Conforme\",\"id\":\"77\",\"limit\":\"50\",\"np\":\"41\",\"procesoId\":\"4\","
                + "\"page\":\"3\",\"turno\":\"A\",\"fechaHasta\":\"2026-09-23\",\"fechaDesde\":\"2026-09-01\"}",
                "page=3&limit=50&fechaDesde=2026-09-01&fechaHasta=2026-09-23&np=41&turno=A&estado=Conforme&id=77&procesoId=4&parametroId=9"},
            // escapado Uri.EscapeDataString y "traer todo" de Exportar/Imprimir
            {"{\"np\":\"Juan Pérez & Cía / 100%\",\"estado\":\"a-b_c.d~e\"}",
                DEFAULTS + "&np=Juan%20P%C3%A9rez%20%26%20C%C3%ADa%20%2F%20100%25&estado=a-b_c.d~e"},
            {"{\"np\":\"5&id=1\"}", DEFAULTS + "&np=5%26id%3D1"},
            {"{\"page\":1,\"limit\":999999,\"turno\":\"A\"}", "page=1&limit=999999&turno=A"},
            // vacíos / solo espacios se omiten; un string con espacios internos viaja escapado
            {"{\"np\":\"\",\"turno\":\"   \",\"estado\":null,\"id\":null,\"procesoId\":null}", DEFAULTS},
            {"{\"np\":\" 41 \"}", DEFAULTS + "&np=%2041%20"},
            // enteros: número entero o string numérica (con espacios/signo); lo demás → default/omitido
            {"{\"page\":\" 2 \",\"limit\":\"+30\"}", "page=2&limit=30"},
            {"{\"page\":\"x\",\"limit\":2.5,\"id\":\"abc\",\"id2\":1,\"procesoId\":\"4.5\",\"parametroId\":true}", DEFAULTS},
            {"{\"page\":0,\"limit\":-1,\"id\":\"12.0\",\"procesoId\":99999999999}", "page=0&limit=-1"},
            {"{\"procesoId\":{\"id\":4},\"parametroId\":[9]}", DEFAULTS},
            // números en filtros de texto viajan como texto (GetString → ToString)
            {"{\"np\":3996,\"estado\":1.5}", DEFAULTS + "&np=3996&estado=1.5"},
        };
        for (String[] caso : casos) {
            accion(sesion, "{\"action\":\"" + OBTENER + "\",\"data\":" + caso[0] + "}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true));
        }
        // Filtros fuera de "data" se ignoran (Photino solo lee data).
        accion(sesion, "{\"action\":\"" + OBTENER + "\",\"np\":\"41\",\"id\":5,\"limit\":5}").andExpect(status().isOk());

        List<String> esperadas = new ArrayList<>();
        for (String[] caso : casos) {
            esperadas.add(BASE + caso[1]);
        }
        esperadas.add(BASE + DEFAULTS);
        assertThat(API.peticionesControl()).containsExactlyElementsOf(esperadas);
    }

    /** Diferencia defensiva acordada: Photino reenviaría "True"/JSON crudo; aquí se rechaza sin llamar a la API. */
    @Test
    void tiposInvalidosEnFiltrosDeTextoSeRechazanSinLlamarALaApi() throws Exception {
        MockHttpSession sesion = login("operador1");
        for (String data : List.of("{\"np\":true}", "{\"turno\":false}", "{\"estado\":{\"x\":1}}", "{\"fechaDesde\":[\"a\"]}",
                "{\"fechaHasta\":{}}", "{\"id\":true}", "{\"id\":[12]}")) {
            accion(sesion, "{\"action\":\"" + OBTENER + "\",\"data\":" + data + "}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Parámetro de filtro inválido."));
        }
        assertThat(API.peticionesControl()).isEmpty();
    }

    @Test
    void datasetVacioYTraerTodoPasanTalCual() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode vacio = json(accion(sesion, "{\"action\":\"" + OBTENER + "\",\"data\":{\"np\":\"VACIO\"}}").andReturn());
        assertThat(vacio.at("/data/items")).isEmpty();
        assertThat(vacio.at("/data/total").asInt()).isZero();
        assertThat(vacio.at("/data/pages").asInt()).isEqualTo(1);

        // Exportar/Imprimir: page=1, limit=total||999999 → todo el dataset en una página.
        JsonNode todo = json(accion(sesion, "{\"action\":\"" + OBTENER + "\",\"data\":{\"page\":1,\"limit\":999999}}").andReturn());
        assertThat(todo.at("/data/items")).hasSize(300);
        assertThat(todo.at("/data/total").asInt()).isEqualTo(300);
        assertThat(todo.at("/data/items/299/id").asInt()).isEqualTo(7299);
        assertThat(todo.at("/data/items/0/observacion").asString()).isEqualTo("=SUMA(1;2) sin fórmula");
    }

    @Test
    void errorDeLaApiSeMuestraComoEnPhotino() throws Exception {
        MockHttpSession sesion = login("operador1");
        API.modoDashboard(ModoDashboard.ERROR_NEGOCIO);
        accion(sesion, "{\"action\":\"" + OBTENER + "\",\"data\":{}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Rango de fechas inválido"));
    }

    // --------------------------------------------------------------- roles y empresa

    @Test
    void rolPermitidoYNoPermitido() throws Exception {
        accion(login("operador1"), "{\"action\":\"" + OBTENER + "\"}").andExpect(status().isOk());
        accion(login("admin1"), "{\"action\":\"" + OBTENER + "\",\"data\":{}}").andExpect(status().isOk());
        accion(login("consulta1"), "{\"action\":\"" + OBTENER + "\"}").andExpect(status().isForbidden());
        assertThat(API.peticionesControl()).hasSize(2);
    }

    @Test
    void empresaCorrectaEIncorrecta() {
        assertThat(policy.evaluar(OBTENER, usuario("INNPACK", "admin"))).isInstanceOf(ActionPolicy.Decision.Permitida.class);
        assertThat(policy.evaluar(OBTENER, usuario("FARET", "admin")))
                .isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
    }

    // --------------------------------------------------------- identidad y aislamiento

    @Test
    void identidadManipuladaSinEfecto() throws Exception {
        MockHttpSession sesion = login("operador1");
        accion(sesion, "{\"action\":\"" + OBTENER + "\",\"usuarioId\":20,\"rol\":\"admin\",\"empresa\":\"FARET\","
                        + "\"data\":{\"id\":\"12\",\"usuarioId\":20,\"empresa\":\"FARET\",\"rol\":\"admin\",\"token\":\"x\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usuarioDelToken").value(10));
        assertThat(API.peticionesControl()).containsExactly(BASE + DEFAULTS + "&id=12");
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
                    JsonNode json = json(accion(esA ? a : b, "{\"action\":\"" + OBTENER + "\",\"data\":{}}")
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
        accion(a, "{\"action\":\"" + OBTENER + "\"}").andExpect(status().isUnauthorized());
        assertThat(a.isInvalid()).isTrue();
        accion(b, "{\"action\":\"" + OBTENER + "\"}").andExpect(status().isOk());
    }

    @Test
    void csrfObligatorio() throws Exception {
        MockHttpSession sesion = login("operador1");
        mockMvc.perform(post("/api/v1/bridge").session(sesion).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + OBTENER + "\"}"))
                .andExpect(status().isForbidden());
        assertThat(API.peticionesControl()).isEmpty();
    }

    // ------------------------------------------------------------------------- helpers

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.5.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-2e");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
