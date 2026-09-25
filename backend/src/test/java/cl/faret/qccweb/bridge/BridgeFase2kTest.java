package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
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

/** Fase 2k — Gestión de Usuarios, solo lectura y solo admin/admin_ti (usuarios.list). API SIMULADA. */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFase2kTest {

    private static final String LIST = "usuarios.list";
    private static final String CUERPO = "{\"action\":\"" + LIST + "\",\"data\":{}}";
    private static final List<String> ESCRITURAS = List.of("usuarios.create", "usuarios.delete", "usuarios.resetPassword");
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveUsuarios#2026";
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

    @Test
    void listReproyectaALosSieteCamposPascalCaseYDescartaLoDemas() throws Exception {
        JsonNode json = json(accion(login("admin1"), CUERPO).andExpect(status().isOk()).andReturn());
        List<String> raiz = new ArrayList<>();
        json.propertyNames().forEach(raiz::add);
        assertThat(raiz).containsExactly("ok", "success", "data", "error");
        assertThat(json.get("data")).hasSize(2);
        List<String> campos = new ArrayList<>();
        json.at("/data/0").propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("Id", "CodigoUsuario", "NombreCompleto", "Rol", "Activo", "CreadoEn", "ActualizadoEn");
        assertThat(json.get("data").get(0)).isEqualTo(mapper.readTree("{\"Id\":10,\"CodigoUsuario\":\"operador1\","
                + "\"NombreCompleto\":\"María José Peña «ñ»\",\"Rol\":\"operador\",\"Activo\":true,\"CreadoEn\":\"2026-07-29T10:15:00\",\"ActualizadoEn\":null}"));
        // Nombres en otra capitalización (case-insensitive como Photino) y campos ausentes → defaults del DTO.
        assertThat(json.get("data").get(1)).isEqualTo(mapper.readTree("{\"Id\":20,\"CodigoUsuario\":\"admin1\","
                + "\"NombreCompleto\":\"<b>Admin</b>\",\"Rol\":null,\"Activo\":false,\"CreadoEn\":null,\"ActualizadoEn\":null}"));
        String texto = json.toString();
        assertThat(texto).doesNotContain("passwordHash", "SECRETO", "$2a$", "token", "eyJ");
        assertThat(API.peticionesUsuarios()).containsExactly("GET /api/usuarios");
    }

    @Test
    void adminTiTambienYElPayloadNoSeUsa() throws Exception {
        MockHttpSession sesion = login("adminti1");
        accion(sesion, CUERPO).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data.length()").value(2));
        accion(sesion, "{\"action\":\"" + LIST + "\"}").andExpect(jsonPath("$.ok").value(true));
        accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":{\"id\":5,\"rol\":\"operador\",\"filtro\":\"x\"}}").andExpect(jsonPath("$.ok").value(true));
        assertThat(API.peticionesUsuarios()).containsExactly("GET /api/usuarios", "GET /api/usuarios", "GET /api/usuarios");
    }

    @Test
    void datasetVacioNuloYFormasInvalidas() throws Exception {
        MockHttpSession sesion = login("admin1");
        API.modoUsuarios("VACIO");
        accion(sesion, CUERPO).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data.length()").value(0));
        API.modoUsuarios("NULO");
        accion(sesion, CUERPO).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data.length()").value(0));
        // Photino (System.Text.Json) no convierte tipos: cualquiera de estos hace fallar la lista.
        for (String modo : List.of("OBJETO", "ITEM_INVALIDO", "ACTIVO_TEXTO", "FECHA_NUMERO", "ID_TEXTO")) {
            API.modoUsuarios(modo);
            accion(sesion, CUERPO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.data").value((Object) null))
                    .andExpect(jsonPath("$.error").value("Error al comunicarse con la API Innpack"));
        }
    }

    @Test
    void operadorYRolDesconocidoNoLleganALaApi() throws Exception {
        for (String usuario : List.of("operador1", "consulta1")) {
            accion(login(usuario), CUERPO)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        }
        assertThat(API.peticionesUsuarios()).isEmpty();
        assertThat(policy.evaluar(LIST, usuario("INNPACK", "operador"))).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
        assertThat(policy.evaluar(LIST, usuario("INNPACK", "ADMIN_TI"))).isInstanceOf(ActionPolicy.Decision.Permitida.class);
        assertThat(policy.evaluar(LIST, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
    }

    @Test
    void rolManipuladoEnElPayloadNoEscala() throws Exception {
        MockHttpSession sesion = login("operador1");
        accion(sesion, "{\"action\":\"" + LIST + "\",\"rol\":\"admin\",\"usuarioId\":20,\"data\":{\"rol\":\"admin\"}}")
                .andExpect(status().isForbidden());
        assertThat(API.peticionesUsuarios()).isEmpty();
    }

    @Test
    void escriturasSiguenBloqueadasInclusoParaAdmin() throws Exception {
        MockHttpSession admin = login("admin1");
        for (String escritura : ESCRITURAS) {
            accion(admin, "{\"action\":\"" + escritura + "\",\"data\":{\"id\":10,\"codigoUsuario\":\"x\",\"password\":\"Clave#123\","
                    + "\"nuevaPassword\":\"Clave#123\",\"rol\":\"admin\",\"nombreCompleto\":\"X\"}}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
            assertThat(policy.accionesRegistradas()).doesNotContain(escritura);
        }
        assertThat(API.peticionesUsuarios()).isEmpty();
    }

    @Test
    void jwtDeLaSesionYConcurrencia() throws Exception {
        MockHttpSession a = login("admin1");
        MockHttpSession b = login("adminti1");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tareas = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                boolean esA = i % 2 == 0;
                tareas.add(() -> json(accion(esA ? a : b, CUERPO).andExpect(status().isOk()).andReturn()).get("ok").asBoolean());
            }
            for (Future<Boolean> f : pool.invokeAll(tareas)) {
                assertThat(f.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(API.authorizationRecibidos()).allMatch(x -> x.endsWith(FakeInnpackApi.firmaDeToken(20)) || x.endsWith(FakeInnpackApi.firmaDeToken(25)));
    }

    @Test
    void unauthorizedUpstreamInvalidaSoloEsaSesion() throws Exception {
        MockHttpSession a = login("admin1");
        MockHttpSession b = login("adminti1");
        API.revocarTokens(20);
        accion(a, CUERPO).andExpect(status().isUnauthorized());
        assertThat(a.isInvalid()).isTrue();
        accion(b, CUERPO).andExpect(status().isOk());
    }

    @Test
    void csrfObligatorio() throws Exception {
        MockHttpSession sesion = login("admin1");
        mockMvc.perform(post("/api/v1/bridge").session(sesion).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isForbidden());
        assertThat(API.peticionesUsuarios()).isEmpty();
    }

    // ------------------------------------------------------------------------- helpers

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.11.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-2k");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
