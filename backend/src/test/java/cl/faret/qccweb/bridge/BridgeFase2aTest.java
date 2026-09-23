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

/**
 * Fase 2a — Máquinas y Procesos (maquinasSeguimiento.obtenerResumen). API INNPACK SIMULADA.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFase2aTest {

    private static final String ACCION = "maquinasSeguimiento.obtenerResumen";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveMaquinas#2026";
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

    // -------------------------------------------------------------- paridad de respuesta

    @Test
    void respuestaIdenticaALaDePhotino() throws Exception {
        MockHttpSession sesion = login("operador1");
        MvcResult res = accion(sesion, "{\"action\":\"" + ACCION + "\",\"data\":{\"maquinaId\":5}}")
                .andExpect(status().isOk()).andReturn();

        JsonNode json = mapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> campos = new ArrayList<>();
        json.propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("ok", "success", "data", "error");
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.get("error").isNull()).isTrue();
        // data = exactamente el data de la API (passthrough, como JsonElement en Photino), UTF-8 incluido.
        assertThat(json.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataMaquinas(10)));
        assertThat(json.at("/data/registros/0/observacion").asString()).isEqualTo("ñandú — ok");
    }

    @Test
    void errorDeLaApiSeMuestraComoEnPhotino() throws Exception {
        MockHttpSession sesion = login("operador1");
        API.modoDashboard(ModoDashboard.ERROR_NEGOCIO);
        accion(sesion, "{\"action\":\"" + ACCION + "\",\"data\":{\"maquinaId\":999}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error").value("Máquina no encontrada"));
    }

    /** Mismas reglas de parámetros que MaquinasSeguimientoHandler.cs (Photino). */
    @Test
    void mapeoDeParametrosIgualQuePhotino() throws Exception {
        MockHttpSession sesion = login("admin1");
        String[][] casos = {
            // data enviado por el frontend                      query que Photino envía a la API
            {"{\"maquinaId\":5}",                                "sinLimite=false&maquinaId=5"},
            {"{\"maquinaId\":5,\"sinLimite\":true}",             "sinLimite=true&maquinaId=5"},
            {"{\"maquinaId\":\"7\",\"sinLimite\":\"True\"}",     "sinLimite=true&maquinaId=7"},
            {"{\"maquinaId\":\" 7 \",\"sinLimite\":\" true \"}", "sinLimite=true&maquinaId=7"},
            {"{\"maquinaId\":null}",                             "sinLimite=false"},
            {"{}",                                               "sinLimite=false"},
            {"{\"maquinaId\":\"abc\",\"sinLimite\":\"si\"}",     "sinLimite=false"},
            {"{\"maquinaId\":\"5&sinLimite=true\"}",             "sinLimite=false"},
            {"{\"sinLimite\":1}",                                "sinLimite=false"},
            {"{\"sinLimite\":false}",                            "sinLimite=false"},
        };
        for (String[] caso : casos) {
            accion(sesion, "{\"action\":\"" + ACCION + "\",\"data\":" + caso[0] + "}").andExpect(status().isOk());
        }
        // Parámetros fuera de "data" se ignoran (Photino solo lee data).
        accion(sesion, "{\"action\":\"" + ACCION + "\",\"maquinaId\":5,\"sinLimite\":true}").andExpect(status().isOk());

        List<String> esperadas = new ArrayList<>();
        for (String[] caso : casos) {
            esperadas.add(caso[1]);
        }
        esperadas.add("sinLimite=false");
        assertThat(API.queriesMaquinas()).containsExactlyElementsOf(esperadas);
    }

    @Test
    void maquinaIdNoEnteroEsErrorComoEnPhotinoSinLlamarALaApi() throws Exception {
        MockHttpSession sesion = login("operador1");
        for (String id : List.of("1.5", "99999999999")) {
            accion(sesion, "{\"action\":\"" + ACCION + "\",\"data\":{\"maquinaId\":" + id + "}}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Parámetro maquinaId inválido."));
        }
        assertThat(API.queriesMaquinas()).isEmpty();
    }

    // --------------------------------------------------------------- roles y empresa

    @Test
    void rolPermitidoYNoPermitido() throws Exception {
        accion(login("operador1"), "{\"action\":\"" + ACCION + "\",\"data\":{}}").andExpect(status().isOk());
        accion(login("admin1"), "{\"action\":\"" + ACCION + "\",\"data\":{}}").andExpect(status().isOk());
        accion(login("consulta1"), "{\"action\":\"" + ACCION + "\",\"data\":{}}").andExpect(status().isForbidden());
        assertThat(API.queriesMaquinas()).hasSize(2);
    }

    @Test
    void empresaCorrectaEIncorrecta() {
        SessionUser innpack = usuario("INNPACK", "operador");
        SessionUser faret = usuario("FARET", "operador");
        assertThat(policy.evaluar(ACCION, innpack)).isInstanceOf(ActionPolicy.Decision.Permitida.class);
        assertThat(policy.evaluar(ACCION, faret)).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
    }

    @Test
    void accionesDesconocidasOSimilaresSeRechazan() throws Exception {
        MockHttpSession sesion = login("admin1");
        for (String accion : List.of("maquinasSeguimiento.obtenerDetalle", "maquinasSeguimiento.eliminar",
                "maquinasSeguimiento.obtenerresumen", "maquinasSeguimiento", "faret.maquinas.resumen")) {
            accion(sesion, "{\"action\":\"" + accion + "\",\"data\":{}}")
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(400, 403));
        }
        assertThat(API.queriesMaquinas()).isEmpty();
    }

    // ------------------------------------------------------------ identidad manipulada

    @Test
    void identidadManipuladaSinEfecto() throws Exception {
        MockHttpSession sesion = login("operador1");
        accion(sesion, "{\"action\":\"" + ACCION + "\",\"usuarioId\":20,\"rol\":\"admin\",\"empresa\":\"FARET\","
                        + "\"data\":{\"maquinaId\":5,\"usuarioId\":20,\"empresa\":\"FARET\",\"token\":\"robado\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usuarioDelToken").value(10));
        assertThat(API.queriesMaquinas()).containsExactly("sinLimite=false&maquinaId=5");
        assertThat(API.authorizationRecibidos()).allMatch(a -> a.endsWith(FakeInnpackApi.firmaDeToken(10)));
    }

    // ------------------------------------------------------------ concurrencia A/B

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
                    String body = accion(esA ? a : b, "{\"action\":\"" + ACCION + "\",\"data\":{\"maquinaId\":5}}")
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                    return new int[] {esA ? 10 : 20, mapper.readTree(body).at("/data/usuarioDelToken").asInt()};
                });
            }
            for (Future<int[]> f : pool.invokeAll(tareas)) {
                assertThat(f.get()[1]).isEqualTo(f.get()[0]);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // --------------------------------------------------------------------- 401 y CSRF

    @Test
    void unauthorizedUpstreamInvalidaSoloEsaSesion() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        API.revocarTokens(10);
        accion(a, "{\"action\":\"" + ACCION + "\",\"data\":{}}").andExpect(status().isUnauthorized());
        assertThat(a.isInvalid()).isTrue();
        accion(b, "{\"action\":\"" + ACCION + "\",\"data\":{}}").andExpect(status().isOk());
    }

    @Test
    void csrfObligatorio() throws Exception {
        MockHttpSession sesion = login("operador1");
        mockMvc.perform(post("/api/v1/bridge").session(sesion).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + ACCION + "\",\"data\":{}}"))
                .andExpect(status().isForbidden());
        assertThat(API.queriesMaquinas()).isEmpty();
    }

    // ------------------------------------------------------------------------- helpers

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.2.0." + IP.getAndIncrement());
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

    private static SessionUser usuario(String empresa, String rol) {
        return new SessionUser(1, "u", "U", rol, empresa, "t", Instant.now(), Instant.now().plusSeconds(60));
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-2a");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
