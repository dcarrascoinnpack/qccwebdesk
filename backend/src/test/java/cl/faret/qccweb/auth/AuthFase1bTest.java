package cl.faret.qccweb.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Fase 1b — autenticación INNPACK delegada. Usa una API INNPACK SIMULADA (FakeInnpackApi):
 * ninguna credencial real, ninguna llamada a producción.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.auth.rate-limit.max-intentos-por-ip=8",
    "qcc.web.auth.rate-limit.fallos-antes-de-bloqueo=3",
    "qcc.web.auth.rate-limit.bloqueo-base=30s",
    "qcc.web.auth.rate-limit.bloqueo-maximo=2m"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AuthFase1bTest {

    private static final Instant T0 = Instant.parse("2026-09-23T12:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(T0);
    private static final FakeInnpackApi API = new FakeInnpackApi(CLOCK);
    private static final Path WWW = crearWww();

    private static final String PASS_OPERADOR = "ClaveOperador#2026";
    private static final String PASS_ADMIN = "ClaveAdmin#2026";

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS_OPERADOR, "Operador Uno", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(20, "admin1", PASS_ADMIN, "Admin Uno", "admin", true));
        API.agregar(new FakeInnpackApi.Usuario(30, "inactivo1", "ClaveInactivo#1", "Inactivo", "operador", false));
    }

    @TestConfiguration
    static class RelojDePrueba {
        @Bean
        @Primary
        Clock relojDePrueba() {
            return CLOCK;
        }
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.innpack-api-base-url", API::baseUrl);
        registry.add("qcc.web.www-dir", WWW::toString);
        registry.add("qcc.web.manifest-file", () -> WWW.resolve("no-existe.json").toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void reiniciar() {
        CLOCK.fijar(T0);
        API.caida(false);
        API.expiracionToken(() -> CLOCK.instant().plus(Duration.ofHours(8)));
    }

    @AfterAll
    static void cerrar() {
        API.close();
    }

    // ---------------------------------------------------------------- login correcto / incorrecto

    @Test
    void loginCorrectoCreaSesionServerSideSinExponerToken() throws Exception {
        MvcResult res = login("10.0.1.1", "operador1", PASS_OPERADOR, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.CodigoUsuario").value("operador1"))
                .andExpect(jsonPath("$.data.Rol").value("operador"))
                .andReturn();
        assertThat(res.getResponse().getContentAsString()).doesNotContain(FakeInnpackApi.firmaDeToken(10), "token");
        assertThat(API.ultimoEncuadre()).as("cuerpo con largo explícito, sin chunked").matches("\\d+\\|null");

        MockHttpSession sesion = sesionDe(res);
        mockMvc.perform(get("/api/v1/auth/session").session(sesion))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.Id").value(10))
                .andExpect(jsonPath("$.data.NombreCompleto").value("Operador Uno"))
                .andExpect(jsonPath("$.data.Empresa").value("INNPACK"))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .doesNotContain(FakeInnpackApi.firmaDeToken(10)));
    }

    @Test
    void loginIncorrectoNoCreaSesion() throws Exception {
        MvcResult res = login("10.0.1.2", "operador1", "equivocada", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(AuthController.MENSAJE_CREDENCIALES))
                .andReturn();
        assertThat(res.getRequest().getSession(false)).isNull();
    }

    @Test
    void camposVaciosNoLleganALaApi() throws Exception {
        int antes = API.llamadas();
        login("10.0.1.3", "", "x", null).andExpect(status().isBadRequest());
        login("10.0.1.3", "operador1", "", null).andExpect(status().isBadRequest());
        assertThat(API.llamadas()).isEqualTo(antes);
    }

    @Test
    void apiCaidaResponde503SinContarComoFallo() throws Exception {
        API.caida(true);
        for (int i = 0; i < 4; i++) {
            login("10.0.1.4", "operador1", PASS_OPERADOR, null)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value(AuthController.MENSAJE_NO_DISPONIBLE));
        }
        API.caida(false);
        login("10.0.1.4", "operador1", PASS_OPERADOR, null).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ enumeración de usuarios

    @Test
    void respuestaNoRevelaSiElUsuarioExiste() throws Exception {
        String noExiste = login("10.0.2.1", "no_existe_xyz", "cualquiera", null)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String claveMala = login("10.0.2.2", "operador1", "cualquiera", null)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String inactivo = login("10.0.2.3", "inactivo1", "ClaveInactivo#1", null)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        assertThat(noExiste).isEqualTo(claveMala).isEqualTo(inactivo);
        assertThat(noExiste).doesNotContain("no existe", "desactivado", "Contraseña incorrecta");
    }

    // ---------------------------------------------------------------------------------- CSRF

    @Test
    void loginYLogoutExigenTokenCsrf() throws Exception {
        int antes = API.llamadas();
        mockMvc.perform(post("/api/v1/auth/login").with(ip("10.0.3.1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo("operador1", PASS_OPERADOR)))
                .andExpect(status().isForbidden());
        assertThat(API.llamadas()).as("sin CSRF no se llama a la API").isEqualTo(antes);

        MockHttpSession sesion = sesionDe(login("10.0.3.1", "operador1", PASS_OPERADOR, null).andReturn());
        mockMvc.perform(post("/api/v1/auth/logout").session(sesion)).andExpect(status().isForbidden());
        assertThat(sesion.isInvalid()).isFalse();
    }

    // ----------------------------------------------------------------------- session fixation

    @Test
    void loginInvalidaLaSesionPreviaYEmiteUnaNueva() throws Exception {
        MockHttpSession previa = new MockHttpSession(null, "sesion-fijada-por-atacante");
        MvcResult res = login("10.0.4.1", "operador1", PASS_OPERADOR, previa).andExpect(status().isOk()).andReturn();

        MockHttpSession nueva = sesionDe(res);
        assertThat(previa.isInvalid()).isTrue();
        assertThat(nueva.getId()).isNotEqualTo("sesion-fijada-por-atacante");
        mockMvc.perform(get("/api/v1/auth/session").session(nueva)).andExpect(jsonPath("$.ok").value(true));
    }

    // "Petición anónima no crea sesión" se prueba contra el servidor real
    // (AuthFase1bServidorRealTest): el harness de MockMvc crea una MockHttpSession propia.

    // ----------------------------------------------------------------- rate limiting / backoff

    @Test
    void backoffPorUsuarioEIp() throws Exception {
        String ip = "10.0.5.1";
        for (int i = 0; i < 3; i++) {
            login(ip, "operador1", "mala-" + i, null).andExpect(status().isUnauthorized());
        }
        int antes = API.llamadas();
        // Bloqueado: ni siquiera con la contraseña correcta, y sin llamar a la API.
        login(ip, "operador1", PASS_OPERADOR, null)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value(AuthController.MENSAJE_BLOQUEO));
        assertThat(API.llamadas()).isEqualTo(antes);

        // El mismo usuario desde otra IP no queda bloqueado por el backoff de esta IP.
        login("10.0.5.2", "operador1", PASS_OPERADOR, null).andExpect(status().isOk());

        // Pasado el bloqueo (30s), vuelve a funcionar y el éxito reinicia el contador.
        CLOCK.avanzar(Duration.ofSeconds(31));
        login(ip, "operador1", PASS_OPERADOR, null).andExpect(status().isOk());
        login(ip, "operador1", "mala-x", null).andExpect(status().isUnauthorized());
        login(ip, "operador1", PASS_OPERADOR, null).andExpect(status().isOk());
    }

    @Test
    void backoffCreceExponencialmente() throws Exception {
        String ip = "10.0.5.3";
        for (int i = 0; i < 3; i++) {
            login(ip, "admin1", "mala", null).andExpect(status().isUnauthorized());
        }
        CLOCK.avanzar(Duration.ofSeconds(31));                     // 1er bloqueo: 30s
        login(ip, "admin1", "mala", null).andExpect(status().isUnauthorized());
        CLOCK.avanzar(Duration.ofSeconds(31));                     // 2º bloqueo: 60s → aún bloqueado
        login(ip, "admin1", PASS_ADMIN, null).andExpect(status().isTooManyRequests());
        CLOCK.avanzar(Duration.ofSeconds(30));
        login(ip, "admin1", PASS_ADMIN, null).andExpect(status().isOk());
    }

    @Test
    void limitePorIpAunqueCambieElUsuario() throws Exception {
        String ip = "10.0.6.1";
        for (int i = 0; i < 8; i++) {
            login(ip, "usuario_inventado_" + i, "x", null).andExpect(status().isUnauthorized());
        }
        login(ip, "otro_usuario", "x", null).andExpect(status().isTooManyRequests());
        login("10.0.6.2", "operador1", PASS_OPERADOR, null).andExpect(status().isOk());

        CLOCK.avanzar(Duration.ofMinutes(5).plusSeconds(1));
        login(ip, "operador1", PASS_OPERADOR, null).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------- logout

    @Test
    void logoutInvalidaLaSesionEnElServidor() throws Exception {
        MockHttpSession sesion = sesionDe(login("10.0.7.1", "operador1", PASS_OPERADOR, null).andReturn());
        mockMvc.perform(post("/api/v1/auth/logout").session(sesion).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        assertThat(sesion.isInvalid()).isTrue();

        // Reusar la sesión vieja no da acceso.
        mockMvc.perform(get("/api/v1/auth/session").session(nuevaConMismoId(sesion)))
                .andExpect(jsonPath("$.ok").value(false));
        mockMvc.perform(post("/api/v1/bridge").session(nuevaConMismoId(sesion)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------------- expiración

    @Test
    void sesionExpiraConElJwtUpstream() throws Exception {
        API.expiracionToken(() -> CLOCK.instant().plus(Duration.ofMinutes(10)));
        MockHttpSession sesion = sesionDe(login("10.0.8.1", "operador1", PASS_OPERADOR, null).andReturn());

        CLOCK.avanzar(Duration.ofMinutes(9));
        mockMvc.perform(get("/api/v1/auth/session").session(sesion)).andExpect(jsonPath("$.ok").value(true));

        CLOCK.avanzar(Duration.ofMinutes(2));
        mockMvc.perform(get("/api/v1/auth/session").session(sesion)).andExpect(jsonPath("$.ok").value(false));
        assertThat(sesion.isInvalid()).isTrue();
    }

    @Test
    void sesionTieneTopeAbsolutoAunqueElJwtDureMas() throws Exception {
        API.expiracionToken(() -> CLOCK.instant().plus(Duration.ofDays(30)));
        MockHttpSession sesion = sesionDe(login("10.0.8.2", "operador1", PASS_OPERADOR, null).andReturn());

        CLOCK.avanzar(Duration.ofHours(8).plusSeconds(1));
        mockMvc.perform(get("/api/v1/auth/session").session(sesion)).andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void sesionTieneTimeoutDeInactividadConfigurado() throws Exception {
        MockHttpSession sesion = sesionDe(login("10.0.8.3", "operador1", PASS_OPERADOR, null).andReturn());
        assertThat(sesion.getMaxInactiveInterval()).isEqualTo(30 * 60);
    }

    // ---------------------------------------------------------------- aislamiento de sesiones

    @Test
    void dosUsuariosSimultaneosQuedanAislados() throws Exception {
        MockHttpSession a = sesionDe(login("10.0.9.1", "operador1", PASS_OPERADOR, null).andReturn());
        MockHttpSession b = sesionDe(login("10.0.9.2", "admin1", PASS_ADMIN, null).andReturn());
        assertThat(a.getId()).isNotEqualTo(b.getId());

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/v1/auth/session").session(a))
                    .andExpect(jsonPath("$.data.CodigoUsuario").value("operador1"))
                    .andExpect(jsonPath("$.data.Rol").value("operador"));
            mockMvc.perform(get("/api/v1/auth/session").session(b))
                    .andExpect(jsonPath("$.data.CodigoUsuario").value("admin1"))
                    .andExpect(jsonPath("$.data.Rol").value("admin"));
        }
        SessionUser usuarioA = usuarioEn(a);
        SessionUser usuarioB = usuarioEn(b);
        assertThat(usuarioA.upstreamToken()).contains(FakeInnpackApi.firmaDeToken(10));
        assertThat(usuarioB.upstreamToken()).contains(FakeInnpackApi.firmaDeToken(20));

        mockMvc.perform(post("/api/v1/auth/logout").session(a).with(csrf())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/session").session(b))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.CodigoUsuario").value("admin1"));
    }

    // ------------------------------------------------ manipulación de identidad desde el cliente

    @Test
    void camposDeIdentidadEnviadosPorElClienteSeIgnoran() throws Exception {
        String cuerpoManipulado = "{\"codigoUsuario\":\"operador1\",\"password\":\"" + PASS_OPERADOR + "\","
                + "\"rol\":\"admin\",\"Rol\":\"admin_ti\",\"empresa\":\"FARET\",\"userId\":20,\"Id\":20,"
                + "\"NombreCompleto\":\"Admin Falso\"}";
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(ip("10.0.10.1")).with(csrf())
                        .header("X-Rol", "admin").header("X-Empresa", "FARET")
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpoManipulado))
                .andExpect(status().isOk()).andReturn();

        mockMvc.perform(get("/api/v1/auth/session").session(sesionDe(res))
                        .header("X-Rol", "admin").param("rol", "admin"))
                .andExpect(jsonPath("$.data.Id").value(10))
                .andExpect(jsonPath("$.data.Rol").value("operador"))
                .andExpect(jsonPath("$.data.Empresa").value("INNPACK"))
                .andExpect(jsonPath("$.data.NombreCompleto").value("Operador Uno"));
    }

    @Test
    void sinSesionNoHayAccesoAunqueSeEnvieIdentidad() throws Exception {
        mockMvc.perform(post("/api/v1/bridge").with(csrf())
                        .header("X-Rol", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"usuarios.list\",\"rolUsuario\":\"admin\",\"empresa\":\"INNPACK\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --------------------------------------------------------------------------------- logs

    @Test
    void niContrasenaNiTokenAparecenEnLogs(CapturedOutput salida) throws Exception {
        login("10.0.11.1", "operador1", "ClaveErronea#LOG-777", null).andExpect(status().isUnauthorized());
        login("10.0.11.1", "operador1", PASS_OPERADOR, null).andExpect(status().isOk());

        assertThat(salida.getAll())
                .contains("evento=LOGIN_FALLIDO", "evento=LOGIN_OK")
                .doesNotContain("ClaveErronea#LOG-777", PASS_OPERADOR, FakeInnpackApi.firmaDeToken(10));
    }

    @Test
    void valoresDelClienteNoPuedenInyectarLineasEnLaAuditoria(CapturedOutput salida) throws Exception {
        login("10.0.11.2", "falso\nevento=LOGIN_OK usuario=admin1", "x", null).andExpect(status().isUnauthorized());
        assertThat(salida.getAll()).doesNotContain("\nevento=LOGIN_OK usuario=admin1");
    }

    // ------------------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions login(
            String ipCliente, String usuario, String password, MockHttpSession sesion) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/v1/auth/login").with(ip(ipCliente)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo(usuario, password));
        if (sesion != null) {
            req.session(sesion);
        }
        return mockMvc.perform(req);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor ip(String ip) {
        return r -> {
            r.setRemoteAddr(ip);
            return r;
        };
    }

    /** Mismo formato que envía Photino (auth.controller.js): claves PascalCase. */
    private static String cuerpo(String usuario, String password) {
        return "{\"CodigoUsuario\":\"" + usuario.replace("\n", "\\n") + "\",\"Password\":\"" + password + "\"}";
    }

    private static MockHttpSession sesionDe(MvcResult res) {
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    /** Simula que el navegador reenvía una cookie con el id de una sesión ya invalidada. */
    private static MockHttpSession nuevaConMismoId(MockHttpSession vieja) {
        return new MockHttpSession(null, vieja.getId());
    }

    private static SessionUser usuarioEn(MockHttpSession sesion) {
        org.springframework.security.core.context.SecurityContext ctx =
                (org.springframework.security.core.context.SecurityContext) sesion.getAttribute("SPRING_SECURITY_CONTEXT");
        return (SessionUser) ctx.getAuthentication().getPrincipal();
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-auth");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
