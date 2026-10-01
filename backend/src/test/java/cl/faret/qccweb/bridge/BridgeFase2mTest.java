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

/** Fase 2m — Talleres Externos, solo lectura (list/catalogos/historialLiberaciones, payload en "data"). API SIMULADA. */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFase2mTest {

    private static final String LIST = "talleresExternos.list";
    private static final String CATALOGOS = "talleresExternos.catalogos";
    private static final String HISTORIAL = "talleresExternos.historialLiberaciones";
    private static final String BASE = "GET /api/talleres-externos";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveTalleres#2026";
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

    @Test
    void listaCompletaComoLaPantallaPaginasDe200() throws Exception {
        MockHttpSession sesion = login("operador1");
        List<Integer> tamanos = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            JsonNode json = json(accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":{\"page\":" + page + ",\"pageSize\":200}}")
                    .andExpect(status().isOk()).andReturn());
            if (page == 1) {
                List<String> campos = new ArrayList<>();
                json.propertyNames().forEach(campos::add);
                assertThat(campos).containsExactly("ok", "success", "data", "error");
                assertThat(json.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataTalleresList(10, 1, 200)));
                assertThat(json.at("/data/items/0/producto").asString()).isEqualTo("Caja ñ «0»");
            }
            assertThat(json.at("/data/totalCount").asInt()).isEqualTo(450);
            tamanos.add(json.at("/data/items").size());
        }
        assertThat(tamanos).containsExactly(200, 200, 50);
        assertThat(API.peticionesTalleres()).containsExactly(BASE + "?page=1&pageSize=200", BASE + "?page=2&pageSize=200",
                BASE + "?page=3&pageSize=200");
    }

    /** GetInt de Photino: defaults 1/50, texto numérico aceptado, sin validar > 0 (la API normaliza). */
    @Test
    void mapeoDePaginacionIgualQuePhotino() throws Exception {
        MockHttpSession sesion = login("admin1");
        String[][] casos = {
            {"{}", "?page=1&pageSize=50"},
            {"{\"page\":\"2\",\"pageSize\":\" 25 \"}", "?page=2&pageSize=25"},
            {"{\"page\":0,\"pageSize\":-5}", "?page=0&pageSize=-5"},
            {"{\"page\":\"x\",\"pageSize\":2.5}", "?page=1&pageSize=50"},
            {"{\"page\":true,\"pageSize\":null}", "?page=1&pageSize=50"},
            {"{\"page\":99999999999,\"pageSize\":{\"a\":1}}", "?page=1&pageSize=50"},
        };
        List<String> esperadas = new ArrayList<>();
        for (String[] caso : casos) {
            accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":" + caso[0] + "}").andExpect(jsonPath("$.ok").value(true));
            esperadas.add(BASE + caso[1]);
        }
        // Sin "data" o con la paginación en la raíz → defaults.
        accion(sesion, "{\"action\":\"" + LIST + "\",\"page\":7}").andExpect(jsonPath("$.ok").value(true));
        esperadas.add(BASE + "?page=1&pageSize=50");
        assertThat(API.peticionesTalleres()).containsExactlyElementsOf(esperadas);
    }

    @Test
    void catalogosEHistorial() throws Exception {
        MockHttpSession sesion = login("operador1");
        accion(sesion, "{\"action\":\"" + CATALOGOS + "\",\"data\":{\"x\":\"ignorado\"}}")
                .andExpect(jsonPath("$.data.procesos[0].nombre").value("Troquelado ñ"));
        accion(sesion, "{\"action\":\"" + HISTORIAL + "\",\"data\":{\"id\":9001}}")
                .andExpect(jsonPath("$.data[0].folioFps").value("F-1"))
                .andExpect(jsonPath("$.data[0].cantidad").value(1500.5));
        accion(sesion, "{\"action\":\"" + HISTORIAL + "\",\"data\":{\"id\":\"404\"}}").andExpect(jsonPath("$.data.length()").value(0));
        accion(sesion, "{\"action\":\"" + HISTORIAL + "\",\"data\":{\"id\":9999999999}}").andExpect(jsonPath("$.ok").value(true));
        accion(sesion, "{\"action\":\"" + HISTORIAL + "\",\"data\":{\"id\":500}}")
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Error al comunicarse con la API Innpack"));
        for (String data : List.of("{}", "{\"id\":0}", "{\"id\":-1}", "{\"id\":\"abc\"}", "{\"id\":true}", "{\"id\":1.5}")) {
            accion(sesion, "{\"action\":\"" + HISTORIAL + "\",\"data\":" + data + "}")
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Falta 'id' para consultar el historial."));
        }
        accion(sesion, "{\"action\":\"" + HISTORIAL + "\",\"id\":9001}").andExpect(jsonPath("$.error").value("Falta 'id' para consultar el historial."));
        assertThat(API.peticionesTalleres()).containsExactly(BASE + "/catalogos", BASE + "/9001/historial-liberaciones",
                BASE + "/404/historial-liberaciones", BASE + "/9999999999/historial-liberaciones", BASE + "/500/historial-liberaciones");
    }

    @Test
    void errorDeLaApi() throws Exception {
        MockHttpSession sesion = login("operador1");
        API.modoDashboard(ModoDashboard.ERROR_NEGOCIO);
        for (String a : List.of(LIST, CATALOGOS)) {
            accion(sesion, "{\"action\":\"" + a + "\",\"data\":{}}").andExpect(jsonPath("$.error").value("Filtro de fecha inválido"));
        }
    }

    @Test
    void rolYEmpresa() throws Exception {
        for (String a : List.of(LIST, CATALOGOS, HISTORIAL)) {
            accion(login("consulta1"), "{\"action\":\"" + a + "\",\"data\":{\"id\":9001}}").andExpect(status().isForbidden());
            assertThat(policy.evaluar(a, usuario("INNPACK", "operador"))).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            assertThat(policy.evaluar(a, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        }
        assertThat(API.peticionesTalleres()).isEmpty();
    }

    @Test
    void identidadManipuladaSinEfectoYConcurrencia() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        accion(a, "{\"action\":\"" + LIST + "\",\"usuarioId\":20,\"rol\":\"admin\",\"data\":{\"usuarioId\":20,\"empresa\":\"FARET\"}}")
                .andExpect(jsonPath("$.data.usuarioDelToken").value(10));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<int[]>> tareas = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                boolean esA = i % 2 == 0;
                tareas.add(() -> {
                    JsonNode json = json(accion(esA ? a : b, "{\"action\":\"" + LIST + "\",\"data\":{\"page\":1,\"pageSize\":1}}")
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
        assertThat(API.authorizationRecibidos()).allMatch(x -> x.endsWith(FakeInnpackApi.firmaDeToken(10)) || x.endsWith(FakeInnpackApi.firmaDeToken(20)));
    }

    @Test
    void unauthorizedUpstreamInvalidaSoloEsaSesionYCsrf() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        mockMvc.perform(post("/api/v1/bridge").session(b).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + LIST + "\",\"data\":{}}"))
                .andExpect(status().isForbidden());
        assertThat(API.peticionesTalleres()).isEmpty();
        API.revocarTokens(10);
        accion(a, "{\"action\":\"" + HISTORIAL + "\",\"data\":{\"id\":9001}}").andExpect(status().isUnauthorized());
        assertThat(a.isInvalid()).isTrue();
        accion(b, "{\"action\":\"" + CATALOGOS + "\",\"data\":{}}").andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------- helpers

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.13.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-2m");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
