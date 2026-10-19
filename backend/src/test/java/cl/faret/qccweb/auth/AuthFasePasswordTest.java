package cl.faret.qccweb.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import java.util.List;
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
 * Política de contraseñas de Photino 1.8.23 en la web: el login de ambas empresas envía soportaCambioPassword y devuelve
 * debeCambiarPassword / motivo / días / vencimiento; con el cambio pendiente el bridge solo atiende permisos.mios;
 * POST /api/v1/auth/cambiar-password (auth.cambiarPassword y faret.cambiarPassword) cambia la clave con el token de la
 * sesión y reemplaza el token por el nuevo de la API. APIs SIMULADAS (FakeInnpackApi / FakeFaretApi).
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.auth.cambio-password-por-minuto=5"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AuthFasePasswordTest {

    private static final FakeInnpackApi INNPACK = new FakeInnpackApi(Clock.systemUTC());
    private static final FakeFaretApi FARET = new FakeFaretApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClavePolitica#2026";
    private static final String NUEVA = "OtraClave#2027";
    private static final String CAMBIAR = "/api/v1/auth/cambiar-password";
    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String BLOQUEADO = "Debes cambiar tu contraseña para continuar.";

    static {
        INNPACK.agregar(new FakeInnpackApi.Usuario(10, "normal", PASS, "Usuario Normal", "admin", true));
        INNPACK.agregar(new FakeInnpackApi.Usuario(11, "temporal", PASS, "Usuario Temporal", "admin", true));
        INNPACK.agregar(new FakeInnpackApi.Usuario(12, "porvencer", PASS, "Usuario Por Vencer", "admin", true));
        INNPACK.agregar(new FakeInnpackApi.Usuario(13, "limite", PASS, "Usuario Limite", "admin", true));
        INNPACK.agregar(new FakeInnpackApi.Usuario(14, "caido", PASS, "Usuario Caido", "admin", true));
        FARET.agregar(new FakeFaretApi.Usuario(20, "ana", "ana@faret.cl", PASS, "Ana Calidad", List.of("CALIDAD")));
        FARET.agregar(new FakeFaretApi.Usuario(21, "vencida", "vencida@faret.cl", PASS, "Clave Vencida", List.of("ADMIN")));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.innpack-api-base-url", INNPACK::baseUrl);
        registry.add("qcc.web.auth.faret-api-base-url", FARET::baseUrl);
        registry.add("qcc.web.www-dir", WWW::toString);
        registry.add("qcc.web.manifest-file", () -> WWW.resolve("no-existe.json").toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reiniciar() {
        INNPACK.restablecerPolitica();
        INNPACK.reiniciarDashboard();
        FARET.restablecerPolitica();
        FARET.limpiarLecturas();
    }

    @AfterAll
    static void cerrar() {
        INNPACK.close();
        FARET.close();
    }

    // ------------------------------------------------------------------------------- login

    @Test
    void loginInnpackEnviaSoportaCambioPasswordYSinPoliticaDevuelveValoresNeutros() throws Exception {
        MvcResult res = loginInnpack("normal", PASS).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.DebeCambiarPassword").value(false))
                .andExpect(jsonPath("$.data.MotivoCambioPassword").doesNotExist())
                .andExpect(jsonPath("$.data.DiasParaVencer").doesNotExist())
                .andReturn();
        assertThat(INNPACK.cuerposLogin()).hasSize(1);
        assertThat(INNPACK.cuerposLogin().get(0)).isEqualTo("{\"codigoUsuario\":\"normal\",\"password\":\"" + PASS + "\",\"soportaCambioPassword\":true}");
        assertThat(res.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("token", "Token");
    }

    @Test
    void loginInnpackConPoliticaLaReenviaAlNavegadorYMarcaLaSesion() throws Exception {
        INNPACK.politicaLogin("temporal", ",\"debeCambiarPassword\":true,\"motivoCambioPassword\":\"Tu contraseña es temporal.\","
                + "\"diasParaVencer\":null,\"passwordVenceEl\":null");
        loginInnpack("temporal", PASS).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.DebeCambiarPassword").value(true))
                .andExpect(jsonPath("$.data.MotivoCambioPassword").value("Tu contraseña es temporal."));
        INNPACK.politicaLogin("porvencer", ",\"debeCambiarPassword\":false,\"diasParaVencer\":5,\"passwordVenceEl\":\"2026-10-24T00:00:00\"");
        loginInnpack("porvencer", PASS).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.DebeCambiarPassword").value(false))
                .andExpect(jsonPath("$.data.DiasParaVencer").value(5))
                .andExpect(jsonPath("$.data.PasswordVenceEl").value("2026-10-24T00:00:00"));
    }

    @Test
    void loginFaretEnviaSoportaCambioPasswordYDevuelveLaPoliticaEnCamelCase() throws Exception {
        FARET.politicaLogin(21, ",\"debeCambiarPassword\":true,\"motivoCambioPassword\":\"La contraseña venció.\",\"diasParaVencer\":0");
        int antes = FARET.cuerposLogin().size();
        loginFaret("vencida", PASS).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.debeCambiarPassword").value(true))
                .andExpect(jsonPath("$.data.motivoCambioPassword").value("La contraseña venció."))
                .andExpect(jsonPath("$.data.diasParaVencer").value(0));
        assertThat(FARET.cuerposLogin().get(antes))
                .isEqualTo("{\"identificador\":\"vencida\",\"password\":\"" + PASS + "\",\"soportaCambioPassword\":true}");
        loginFaret("ana", PASS).andExpect(jsonPath("$.data.debeCambiarPassword").value(false));
    }

    @Test
    void elMotivoDeLaApiSeLimpiaDeControlesYMarcado() throws Exception {
        INNPACK.politicaLogin("temporal", ",\"debeCambiarPassword\":true,\"motivoCambioPassword\":\"<img src=x onerror=1>\\nHola\"");
        MvcResult res = loginInnpack("temporal", PASS).andExpect(status().isOk()).andReturn();
        String motivo = json(res).get("data").get("MotivoCambioPassword").asString();
        assertThat(motivo).doesNotContain("<", ">", "\n");
    }

    // ------------------------------------------------------------------------------- cambio forzado INNPACK

    @Test
    void conElCambioPendienteElBridgeSoloAtiendePermisosMiosYTrasCambiarLaClaveSeReanuda(CapturedOutput salida) throws Exception {
        INNPACK.politicaLogin("temporal", ",\"debeCambiarPassword\":true,\"motivoCambioPassword\":\"Tu contraseña es temporal.\"");
        MockHttpSession s = sesionInnpack("temporal");
        String tokenViejo = bearerDeUltimoLogin(s);
        accion(s, "{\"action\":\"inicio.getDashboard\",\"_modulo\":\"inicio\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BLOQUEADO));
        assertThat(INNPACK.authorizationRecibidos()).isEmpty();
        accion(s, "{\"action\":\"permisos.mios\",\"_modulo\":\"cambiar-password\"}").andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));

        // Photino manda PasswordActual/PasswordNueva dentro de data; el shim las pasa como passwordActual/passwordNueva.
        MvcResult res = cambiar(s, PASS, NUEVA).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.Success").value(true))
                .andExpect(jsonPath("$.data.Message").value("Contraseña actualizada correctamente"))
                .andExpect(jsonPath("$.data.DebeCambiarPassword").value(false))
                .andExpect(jsonPath("$.data.DiasParaVencer").value(60))
                .andReturn();
        assertThat(res.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("token", "Token", NUEVA, PASS);
        // La API recibió el token VIEJO y las dos claves exactas.
        assertThat(INNPACK.cambiosRecibidos()).hasSize(1);
        assertThat(INNPACK.cambiosRecibidos().get(0)).isEqualTo(tokenViejo + "|{\"passwordActual\":\"" + PASS + "\",\"passwordNueva\":\"" + NUEVA + "\"}");

        // Ya no está bloqueado y las llamadas siguientes usan el token NUEVO (otro "gen"), no el viejo.
        accion(s, "{\"action\":\"inicio.getDashboard\",\"_modulo\":\"inicio\"}").andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        List<String> auth = INNPACK.authorizationRecibidos();
        assertThat(auth).hasSize(1);
        assertThat(auth.get(0)).startsWith("Bearer ").isNotEqualTo(tokenViejo);
        // La clave nueva sirve para entrar y la vieja ya no.
        loginInnpack("temporal", NUEVA).andExpect(status().isOk());
        loginInnpack("temporal", PASS).andExpect(status().isUnauthorized());

        String log = salida.getAll();
        assertThat(log).contains("evento=CAMBIO_PASSWORD usuario=temporal empresa=INNPACK").contains("resultado=OK");
        assertThat(log).containsPattern("evento=ACCION_DENEGADA usuario=temporal empresa=INNPACK accion=inicio\\.getDashboard motivo=CAMBIO_PASSWORD_PENDIENTE");
        assertThat(log.lines().filter(l -> l.contains("evento=")).toList()).noneMatch(l -> l.contains(NUEVA) || l.contains(PASS));
    }

    @Test
    void elCambioRotaElIdDeSesionYElTokenCsrf() throws Exception {
        MockHttpSession s = sesionInnpack("normal");
        String antes = s.getId();
        MvcResult res = cambiar(s, PASS, NUEVA + "x").andExpect(status().isOk()).andReturn();
        assertThat(s.getId()).isNotEqualTo(antes);
        assertThat(res.getResponse().getCookie("XSRF-TOKEN")).isNotNull();
        // La sesión sigue siendo válida con el id nuevo.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/session").session(s))
                .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data.CodigoUsuario").value("normal"));
    }

    @Test
    void unaClaveActualIncorrectaSeRechazaConElMensajeDeLaApiYElCambioSigueSiendoObligatorio() throws Exception {
        INNPACK.politicaLogin("temporal", ",\"debeCambiarPassword\":true");
        MockHttpSession s = sesionInnpack("temporal");
        cambiar(s, "equivocada", NUEVA).andExpect(status().isBadRequest()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("La contraseña actual es incorrecta."));
        cambiar(s, PASS, PASS).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La contraseña nueva debe ser distinta de la actual."));
        cambiar(s, PASS, "corta").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La contraseña nueva debe tener al menos 6 caracteres."));
        accion(s, "{\"action\":\"inicio.getDashboard\",\"_modulo\":\"inicio\"}").andExpect(status().isForbidden());
        loginInnpack("temporal", PASS).andExpect(status().isOk());
    }

    @Test
    void unaApiCaidaDejaElResultadoComoDesconocidoSinTocarLaSesion(CapturedOutput salida) throws Exception {
        MockHttpSession s = sesionInnpack("caido");
        INNPACK.estadoCambioForzado(500);
        cambiar(s, PASS, NUEVA).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(AuthController.MENSAJE_CAMBIO_NO_DISPONIBLE));
        INNPACK.estadoCambioForzado(0);
        // La sesión sigue operando con su token.
        accion(s, "{\"action\":\"inicio.getDashboard\",\"_modulo\":\"inicio\"}").andExpect(status().isOk());
        assertThat(salida.getAll()).contains("evento=CAMBIO_PASSWORD usuario=caido empresa=INNPACK").contains("resultado=ERROR");
    }

    @Test
    void formaDeLaPeticionSesionYCsrf() throws Exception {
        MockHttpSession s = sesionInnpack("normal");
        for (String cuerpo : List.of("{}", "{\"passwordActual\":\"x\"}", "{\"passwordNueva\":\"x\"}", "{\"passwordActual\":\"\",\"passwordNueva\":\"\"}",
                "{\"passwordActual\":\"" + "a".repeat(300) + "\",\"passwordNueva\":\"x\"}")) {
            mockMvc.perform(post(CAMBIAR).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(AuthController.MENSAJE_CAMBIO_REQUERIDOS));
        }
        // Los nombres PascalCase de Photino también se aceptan.
        mockMvc.perform(post(CAMBIAR).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"PasswordActual\":\"" + PASS + "\",\"PasswordNueva\":\"" + NUEVA + "2\"}")).andExpect(status().isOk());
        // Sin CSRF → 403; sin sesión → 401.
        mockMvc.perform(post(CAMBIAR).session(s).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mockMvc.perform(post(CAMBIAR).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"passwordActual\":\"a\",\"passwordNueva\":\"b\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void elLimiteFrenaElAdivinadoDeLaClaveActualSinLlamarALaApi(CapturedOutput salida) throws Exception {
        MockHttpSession s = sesionInnpack("limite");
        for (int i = 0; i < 5; i++) {
            cambiar(s, "equivocada" + i, NUEVA).andExpect(status().isBadRequest());
        }
        int llamadas = INNPACK.cambiosRecibidos().size();
        cambiar(s, "equivocada", NUEVA).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(AuthController.MENSAJE_CAMBIO_LIMITE));
        assertThat(INNPACK.cambiosRecibidos()).hasSize(llamadas);
        assertThat(salida.getAll()).contains("evento=CAMBIO_PASSWORD usuario=limite empresa=INNPACK").contains("resultado=LIMITE");
    }

    // ------------------------------------------------------------------------------- FARET

    @Test
    void cambioForzadoFaretUsaLaApiFaretYAplicaLasMismasReglas() throws Exception {
        FARET.politicaLogin(21, ",\"debeCambiarPassword\":true,\"motivoCambioPassword\":\"La contraseña venció.\"");
        MockHttpSession s = sesionFaret("vencida");
        accion(s, "{\"action\":\"faret.talleresExternos.resumen\",\"_modulo\":\"faret\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BLOQUEADO));
        assertThat(FARET.lecturas()).isEmpty();
        accion(s, "{\"action\":\"permisos.mios\",\"_modulo\":\"cambiar-password\"}").andExpect(status().isOk());

        cambiar(s, "equivocada", "NuevaFaret#2027").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La contraseña actual es incorrecta."));
        cambiar(s, PASS, "corta12").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La contraseña nueva debe tener al menos 8 caracteres."));
        MvcResult res = cambiar(s, PASS, "NuevaFaret#2027").andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.username").value("Clave Vencida"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.debeCambiarPassword").value(false))
                .andExpect(jsonPath("$.data.diasParaVencer").value(60))
                .andReturn();
        assertThat(res.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("token", "NuevaFaret", PASS);
        assertThat(FARET.cambiosRecibidos()).hasSize(3);
        assertThat(FARET.cambiosRecibidos().get(2)).endsWith("|{\"passwordActual\":\"" + PASS + "\",\"passwordNueva\":\"NuevaFaret#2027\"}");

        accion(s, "{\"action\":\"faret.talleresExternos.resumen\",\"_modulo\":\"faret\"}").andExpect(status().isOk());
        assertThat(FARET.lecturas()).hasSize(1);
        assertThat(FARET.lecturasDeUsuario()).containsExactly(21);
        loginFaret("vencida", "NuevaFaret#2027").andExpect(status().isOk());
        loginFaret("vencida", PASS).andExpect(status().isUnauthorized());
    }

    @Test
    void cambioFaretConLaApiCaidaYSinNadaQueInvalide() throws Exception {
        MockHttpSession s = sesionFaret("ana");
        FARET.estadoCambioForzado(500);
        cambiar(s, PASS, "NuevaFaret#2028").andExpect(status().isServiceUnavailable());
        FARET.estadoCambioForzado(0);
        accion(s, "{\"action\":\"faret.talleresExternos.resumen\",\"_modulo\":\"faret\"}").andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------- helpers

    private ResultActions cambiar(MockHttpSession sesion, String actual, String nueva) throws Exception {
        return mockMvc.perform(post(CAMBIAR).session(sesion).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"passwordActual\":\"" + actual + "\",\"passwordNueva\":\"" + nueva + "\"}"));
    }

    private ResultActions accion(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private ResultActions loginInnpack(String usuario, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").with(csrf()).with(ip()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + password + "\"}"));
    }

    private ResultActions loginFaret(String identificador, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/faret/login").with(csrf()).with(ip()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"identificador\":\"" + identificador + "\",\"password\":\"" + password + "\"}"));
    }

    private MockHttpSession sesionInnpack(String usuario) throws Exception {
        MvcResult res = loginInnpack(usuario, PASS).andExpect(status().isOk()).andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private MockHttpSession sesionFaret(String identificador) throws Exception {
        MvcResult res = loginFaret(identificador, PASS).andExpect(status().isOk()).andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    /** "Bearer <token>" que la API INNPACK emitió en el último login (el cambio de clave debe enviarlo tal cual). */
    private String bearerDeUltimoLogin(MockHttpSession sesion) {
        Object contexto = sesion.getAttribute("SPRING_SECURITY_CONTEXT");
        org.springframework.security.core.context.SecurityContext sc = (org.springframework.security.core.context.SecurityContext) contexto;
        SessionUser u = (SessionUser) sc.getAuthentication().getPrincipal();
        return "Bearer " + u.upstreamToken();
    }

    private JsonNode json(MvcResult res) throws Exception {
        return mapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor ip() {
        return r -> {
            r.setRemoteAddr("10.87.0." + IP.getAndIncrement());
            return r;
        };
    }

    private static Path crearWww() {
        try {
            Path dir = Files.createTempDirectory("qcc-web-fixture-pwd");
            Files.writeString(dir.resolve("index.html"), "<!doctype html><title>fixture</title>");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
