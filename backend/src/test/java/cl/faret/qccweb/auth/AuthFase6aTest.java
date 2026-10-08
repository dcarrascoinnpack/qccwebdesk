package cl.faret.qccweb.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * Fase 6a — sesión FARET en la web: faret.login → POST /api/v1/auth/faret/login contra QualityControlFaret.Api
 * (SIMULADA: FakeFaretApi), mis-permisos obligatorio como Photino (MessageRouter.CargarPermisosFaretAsync), sesión con
 * empresa FARET y primer rol de la lista, permisos.mios con los módulos FARET, aislamiento respecto de INNPACK.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.auth.innpack-api-base-url=http://127.0.0.1:9"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AuthFase6aTest {

    private static final FakeFaretApi API = new FakeFaretApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveFaret#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final List<String> MODULOS_FARET = List.of("faret", "faret-inspecciones", "faret-inspecciones-pallet",
            "faret-producto-terminado", "faret-certificados-liberacion", "faret-despachos-diarios", "faret-nc", "faret-nc-internas",
            "faret-control-documental", "faret-talleres-externos", "faret-importacion", "faret-maquinas", "faret-data",
            "faret-trazabilidad", "faret-formularios", "faret-laboratorio", "faret-recepcion-calidad", "faret-usuarios");

    static {
        API.agregar(new FakeFaretApi.Usuario(10, "ana", "ana@faret.cl", PASS, "Ana Calidad", List.of("CALIDAD")));
        API.agregar(new FakeFaretApi.Usuario(20, "tito", "tito@faret.cl", PASS, "Tito TI", List.of("ADMIN_TI", "CALIDAD")));
        API.agregar(new FakeFaretApi.Usuario(30, "clara", null, PASS, "Clara Consulta", List.of("CONSULTA")));
        API.agregar(new FakeFaretApi.Usuario(40, "sinrol", null, PASS, "Sin Rol", List.of()));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.faret-api-base-url", API::baseUrl);
        registry.add("qcc.web.www-dir", WWW::toString);
        registry.add("qcc.web.manifest-file", () -> WWW.resolve("no-existe.json").toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reiniciar() {
        API.caida(false);
        API.misPermisosRestablecer();
    }

    @AfterAll
    static void cerrar() {
        API.close();
    }

    // ---------------------------------------------------------------- login

    @Test
    void loginFaretCorrectoRespondeComoPhotinoYCreaSesionFaretSinExponerToken(CapturedOutput salida) throws Exception {
        int antes = API.cuerposLogin().size();
        MvcResult res = login("ana", PASS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.username").value("Ana Calidad"))
                .andExpect(jsonPath("$.data.role").value("CALIDAD"))
                .andReturn();
        String cuerpo = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(cuerpo).doesNotContain(FakeFaretApi.firmaDeToken(10), "token", "Token");
        // Photino manda {identificador, password} a api/Auth/login y luego mis-permisos con el token del usuario.
        assertThat(API.cuerposLogin().get(antes)).isEqualTo("{\"identificador\":\"ana\",\"password\":\"" + PASS + "\"}");
        assertThat(API.misPermisosConsultados()).contains(10);

        MockHttpSession sesion = (MockHttpSession) res.getRequest().getSession(false);
        assertThat(sesion).isNotNull();
        mockMvc.perform(get("/api/v1/auth/session").session(sesion))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.Empresa").value("FARET"))
                .andExpect(jsonPath("$.data.CodigoUsuario").value("ana"))
                .andExpect(jsonPath("$.data.NombreCompleto").value("Ana Calidad"))
                .andExpect(jsonPath("$.data.Rol").value("CALIDAD"))
                .andExpect(jsonPath("$.data.Id").value(10));
        assertThat(salida.getAll()).contains("evento=LOGIN_OK usuario=ana empresa=FARET")
                .doesNotContain(FakeFaretApi.firmaDeToken(10), PASS);
    }

    @Test
    void loginPorCorreoYPrimerRolDeLaListaComoPhotino() throws Exception {
        login("ana@faret.cl", PASS).andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("Ana Calidad"));
        MvcResult res = login("tito", PASS).andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("ADMIN_TI")).andReturn();
        MockHttpSession sesion = (MockHttpSession) res.getRequest().getSession(false);
        mockMvc.perform(get("/api/v1/auth/session").session(sesion)).andExpect(jsonPath("$.data.Rol").value("ADMIN_TI"));
        // Sin roles: Photino deja role = "" (la web entra igual; sin rol no hay módulo con acceso salvo Inicio en VER).
        login("sinrol", PASS).andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value(""));
    }

    @Test
    void credencialesIncorrectasRespondenMensajeGenericoSinSesion() throws Exception {
        MvcResult res = login("ana", "otra-clave")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(AuthController.MENSAJE_CREDENCIALES))
                .andReturn();
        assertThat(res.getRequest().getSession(false)).isNull();
        assertThat(res.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("Credenciales inválidas");
        login("nadie", PASS).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value(AuthController.MENSAJE_CREDENCIALES));
    }

    @Test
    void camposRequeridosYLargosSeValidanAntesDeLlamarALaApi() throws Exception {
        int llamadas = API.llamadas();
        for (String cuerpo : List.of("{}", "{\"identificador\":\"\",\"password\":\"x\"}", "{\"identificador\":\"ana\"}",
                "{\"identificador\":\"ana\",\"password\":\"\"}", "{\"identificador\":\"   \",\"password\":\"x\"}",
                "{\"identificador\":\"" + "a".repeat(101) + "\",\"password\":\"x\"}",
                "{\"identificador\":\"ana\",\"password\":\"" + "p".repeat(257) + "\"}")) {
            mockMvc.perform(post("/api/v1/auth/faret/login").with(csrf()).with(ip()).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(AuthController.MENSAJE_REQUERIDOS));
        }
        mockMvc.perform(post("/api/v1/auth/faret/login").with(csrf()).with(ip())).andExpect(status().isBadRequest());
        assertThat(API.llamadas()).isEqualTo(llamadas);
    }

    @Test
    void sinMisPermisosNoSeEntraComoPhotino(CapturedOutput salida) throws Exception {
        API.misPermisosCaido(30);
        MvcResult res = login("clara", PASS)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(AuthController.MENSAJE_PERMISOS))
                .andReturn();
        MockHttpSession sesion = (MockHttpSession) res.getRequest().getSession(false);
        if (sesion != null) {
            mockMvc.perform(get("/api/v1/auth/session").session(sesion)).andExpect(jsonPath("$.ok").value(false));
        }
        assertThat(salida.getAll()).contains("evento=LOGIN_ERROR_UPSTREAM usuario=clara").doesNotContain("evento=LOGIN_OK usuario=clara");
    }

    @Test
    void apiFaretCaidaRespondeNoDisponibleSinContarComoFallo() throws Exception {
        API.caida(true);
        login("ana", PASS).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(AuthController.MENSAJE_NO_DISPONIBLE));
        API.caida(false);
        login("ana", PASS).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- permisos y aislamiento

    @Test
    void permisosMiosDeUnaSesionFaretSonLosModulosFaretConLaReglaPorRolDePhotino() throws Exception {
        Map<String, String> consulta = mios(sesionDe("clara"));
        assertThat(consulta.keySet()).containsExactlyElementsOf(MODULOS_FARET);
        for (String m : List.of("faret", "faret-talleres-externos", "faret-nc", "faret-nc-internas", "faret-despachos-diarios")) {
            assertThat(consulta.get(m)).as(m).isEqualTo("EDITAR");
        }
        for (String m : List.of("faret-inspecciones", "faret-formularios", "faret-data", "faret-importacion", "faret-usuarios")) {
            assertThat(consulta.get(m)).as(m).isEqualTo("SIN_ACCESO");
        }

        Map<String, String> calidad = mios(sesionDe("ana"));
        assertThat(calidad.get("faret-inspecciones")).isEqualTo("EDITAR");
        assertThat(calidad.get("faret-data")).isEqualTo("SIN_ACCESO");
        assertThat(calidad.get("faret-importacion")).isEqualTo("SIN_ACCESO");
        assertThat(calidad.get("faret-usuarios")).isEqualTo("SIN_ACCESO");

        Map<String, String> adminTi = mios(sesionDe("tito"));
        assertThat(adminTi.values()).containsOnly("EDITAR");

        // Permiso personalizado leído de mis-permisos FARET al iniciar sesión.
        API.permisos(10, "[{\"modulo\":\"faret-nc\",\"nivel\":\"VER\"},{\"modulo\":\"inicio\",\"nivel\":\"EDITAR\"}]");
        Map<String, String> personalizado = mios(sesionDe("ana"));
        assertThat(personalizado.get("faret-nc")).isEqualTo("VER");
        assertThat(personalizado).doesNotContainKey("inicio");
        API.permisos(10, "[]");
    }

    @Test
    void unaSesionFaretNoAlcanzaAccionesNiModulosInnpack() throws Exception {
        MockHttpSession tito = sesionDe("tito");
        // ActionPolicy rechaza por empresa antes de llegar a PermisosModulo (las reglas INNPACK no admiten FARET).
        accion(tito, "{\"action\":\"inicio.getDashboard\",\"data\":{},\"_modulo\":\"inicio\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Acción no disponible en la versión web."));
        accion(tito, "{\"action\":\"formularios.list\",\"data\":{\"tipo\":\"inspeccionesVehiculares\"},\"_modulo\":\"faret-formularios\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.ok").value(false));
        accion(tito, "{\"action\":\"usuarios.list\",\"data\":{},\"_modulo\":\"usuarios\"}")
                .andExpect(status().isForbidden());
        // Las acciones FARET todavía no están habilitadas (siguientes fases): denegadas, no "sin acceso".
        accion(tito, "{\"action\":\"faret.data.list\",\"data\":{},\"_modulo\":\"faret\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Acción no disponible en la versión web."));
    }

    @Test
    void logoutInvalidaLaSesionFaret(CapturedOutput salida) throws Exception {
        MockHttpSession sesion = sesionDe("ana");
        mockMvc.perform(post("/api/v1/auth/logout").session(sesion).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertThat(sesion.isInvalid()).isTrue();
        assertThat(salida.getAll()).contains("evento=LOGOUT usuario=ana");
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions login(String identificador, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/faret/login").with(csrf()).with(ip())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identificador\":\"" + identificador + "\",\"password\":\"" + password + "\"}"));
    }

    private MockHttpSession sesionDe(String identificador) throws Exception {
        MvcResult res = login(identificador, PASS).andExpect(status().isOk()).andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private ResultActions accion(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private Map<String, String> mios(MockHttpSession sesion) throws Exception {
        MvcResult res = accion(sesion, "{\"action\":\"permisos.mios\",\"_modulo\":\"faret-login\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true)).andReturn();
        JsonNode data = mapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
        Map<String, String> niveles = new LinkedHashMap<>();
        data.properties().forEach(e -> niveles.put(e.getKey(), e.getValue().asString()));
        return niveles;
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor ip() {
        return r -> {
            r.setRemoteAddr("10.66.0." + IP.getAndIncrement());
            return r;
        };
    }

    private static Path crearWww() {
        try {
            Path dir = Files.createTempDirectory("qcc-web-fixture-6a");
            Files.writeString(dir.resolve("index.html"), "<!doctype html><title>fixture</title>");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
