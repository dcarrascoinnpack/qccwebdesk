package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import cl.faret.qccweb.auth.FakeInnpackApi.SeguimientoRecibido;
import cl.faret.qccweb.auth.MutableClock;
import cl.faret.qccweb.auth.SessionUser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Fase 3a — PRIMERA ESCRITURA: noConformidades.seguimiento.crear (vertical slice del patrón de
 * escrituras). Browser → sesión → ActionPolicy (rol) → validación → identidad de sesión → API →
 * auditoría → respuesta. API SIMULADA: se verifica el cuerpo EXACTO que recibe upstream.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=100"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3aTest {

    private static final String CREAR = "noConformidades.seguimiento.crear";
    private static final String BASE = "/api/no-conformidades";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveSeguimiento#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(20, "admin1", PASS, "Admin Uno", "admin", true));
        API.agregar(new FakeInnpackApi.Usuario(25, "adminti1", PASS, "Admin TI", "admin_ti", true));
        API.agregar(new FakeInnpackApi.Usuario(30, "consulta1", PASS, "Consulta Uno", "consulta", true));
        API.agregar(new FakeInnpackApi.Usuario(40, "rafaga1", PASS, "Ráfaga Uno", "operador", true));
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

    // ------------------------------------------------------------------ flujo exitoso

    @Test
    void escrituraExitosaConCuerpoExactoYAutorDeSesion() throws Exception {
        JsonNode json = json(crear(login("operador1"), "{\"action\":\"" + CREAR + "\",\"id\":501,\"comentario\":\"  Se revisó el lote ñ  \"}")
                .andExpect(status().isOk()).andReturn());
        List<String> campos = new ArrayList<>();
        json.propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("ok", "success", "data", "error");
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.at("/data/id").asInt()).isEqualTo(501);
        // Primero se confirma que la NC existe, después se escribe. Nada más.
        assertThat(API.peticionesNoConformidades()).containsExactly("GET " + BASE + "/501", "POST " + BASE + "/501/seguimiento");
        SeguimientoRecibido r = unico();
        assertThat(r.ncId()).isEqualTo(501);
        assertThat(r.sub()).isEqualTo(10);
        assertThat(r.contentType()).startsWith("application/json");
        assertThat(mapper.readTree(r.cuerpo())).isEqualTo(mapper.readTree("{\"comentario\":\"Se revisó el lote ñ\",\"autor\":\"Operador Uno\"}"));
    }

    @Test
    void autorFalsificadoPorElNavegadorNuncaLlegaUpstream() throws Exception {
        // El navegador intenta hacerse pasar por el admin por TODAS las vías conocidas (raíz y data).
        String falsificado = "{\"action\":\"" + CREAR + "\",\"id\":501,\"comentario\":\"ok\","
                + "\"autor\":\"Admin Uno\",\"usuario\":\"admin1\",\"usuarioId\":20,\"creadoPor\":\"Admin Uno\","
                + "\"registradoPor\":\"Admin Uno\",\"nombreUsuario\":\"Admin Uno\",\"rol\":\"admin\",\"empresa\":\"FARET\","
                + "\"token\":\"eyJfalso\",\"data\":{\"autor\":\"Admin Uno\",\"usuarioId\":20,\"comentario\":\"otro\"}}";
        crear(login("operador1"), falsificado).andExpect(jsonPath("$.ok").value(true));
        SeguimientoRecibido r = unico();
        JsonNode cuerpo = mapper.readTree(r.cuerpo());
        List<String> claves = new ArrayList<>();
        cuerpo.propertyNames().forEach(claves::add);
        assertThat(claves).containsExactly("comentario", "autor");
        assertThat(cuerpo.get("autor").asString()).isEqualTo("Operador Uno");
        assertThat(cuerpo.get("comentario").asString()).isEqualTo("ok");
        assertThat(r.sub()).isEqualTo(10);
        assertThat(r.cuerpo()).doesNotContain("Admin", "admin1", "FARET", "eyJ", "20");
        assertThat(API.authorizationRecibidos()).allMatch(a -> a.endsWith(FakeInnpackApi.firmaDeToken(10)));
    }

    // ---------------------------------------------------------------- autorización

    @Test
    void rolesDeLaMatrizYRolNoPermitidoSinTocarUpstream() throws Exception {
        crear(login("consulta1"), "{\"action\":\"" + CREAR + "\",\"id\":501,\"comentario\":\"x\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(API.peticionesNoConformidades()).isEmpty();
        crear(login("admin1"), "{\"action\":\"" + CREAR + "\",\"id\":501,\"comentario\":\"por admin\"}").andExpect(jsonPath("$.ok").value(true));
        crear(login("adminti1"), "{\"action\":\"" + CREAR + "\",\"id\":501,\"comentario\":\"por admin ti\"}").andExpect(jsonPath("$.ok").value(true));
        assertThat(API.seguimientosRecibidos()).extracting(s -> mapper.readTree(s.cuerpo()).get("autor").asString())
                .containsExactly("Admin Uno", "Admin TI");
    }

    @Test
    void politicaDeclaradaEmpresaYEscritura() {
        assertThat(policy.evaluar(CREAR, usuario("INNPACK", "operador"))).isInstanceOf(ActionPolicy.Decision.Permitida.class);
        assertThat(policy.evaluar(CREAR, usuario("INNPACK", "ADMIN_TI"))).isInstanceOf(ActionPolicy.Decision.Permitida.class);
        assertThat(policy.evaluar(CREAR, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        assertThat(policy.evaluar(CREAR, usuario("INNPACK", "consulta"))).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
        Map<String, Object> descrita = policy.describir().stream().filter(d -> d.get("accion").equals(CREAR)).findFirst().orElseThrow();
        assertThat(descrita.get("escritura")).isEqualTo(true);
        assertThat(descrita.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
        assertThat(descrita.get("identidad")).isEqualTo(Map.of("autor", IdentityOverride.Fuente.NOMBRE_COMPLETO));
        // Solo las escrituras aprobadas una a una (3a seguimiento.crear, 3b acciones.crear) están habilitadas.
        assertThat(policy.describir().stream().filter(d -> Boolean.TRUE.equals(d.get("escritura"))).map(d -> d.get("accion")))
                .containsExactlyInAnyOrder(CREAR, "noConformidades.acciones.crear", "noConformidades.analisis.guardar",
                        "noConformidades.catalogos.clientes.crear", "noConformidades.catalogos.categoriasDefecto.crear",
                        "noConformidades.catalogos.tiposFalla.crear", "noConformidades.catalogos.supervisores.crear",
                        "noConformidades.catalogos.revisores.crear", "noConformidades.catalogos.areas.crear",
                        "noConformidades.catalogos.familiasProducto.crear", "noConformidades.catalogos.impactos.crear");
        for (String otra : List.of("noConformidades.create", "noConformidades.cerrar", "noConformidades.eliminar", "noConformidades.update",
                "noConformidades.acciones.actualizar", "noConformidades.gestion.actualizar", "noConformidades.adjuntos.subir")) {
            assertThat(policy.accionesRegistradas()).doesNotContain(otra);
        }
    }

    // ---------------------------------------------------------------- validaciones

    @Test
    void ncInexistenteNoEscribe() throws Exception {
        crear(login("operador1"), "{\"action\":\"" + CREAR + "\",\"id\":404,\"comentario\":\"x\"}")
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("No conformidad no encontrada"));
        assertThat(API.peticionesNoConformidades()).containsExactly("GET " + BASE + "/404");
        assertThat(API.seguimientosRecibidos()).isEmpty();
    }

    @Test
    void comentarioVacioIdInvalidoYTiposInvalidosSinLlamarUpstream() throws Exception {
        MockHttpSession s = login("operador1");
        String[][] casos = {
            {",\"id\":501", "Falta el comentario de seguimiento"},
            {",\"id\":501,\"comentario\":\"\"", "Falta el comentario de seguimiento"},
            {",\"id\":501,\"comentario\":\"   \\n\\t \"", "Falta el comentario de seguimiento"},
            {",\"id\":501,\"comentario\":null", "Falta el comentario de seguimiento"},
            {",\"id\":501,\"comentario\":123", "Parámetro inválido."},
            {",\"id\":501,\"comentario\":true", "Parámetro inválido."},
            {",\"id\":501,\"comentario\":{\"a\":1}", "Parámetro inválido."},
            {",\"id\":501,\"comentario\":[\"x\"]", "Parámetro inválido."},
            {",\"comentario\":\"x\"", "Falta el id de la no conformidad"},
            {",\"id\":0,\"comentario\":\"x\"", "Falta el id de la no conformidad"},
            {",\"id\":-3,\"comentario\":\"x\"", "Falta el id de la no conformidad"},
            {",\"id\":\"abc\",\"comentario\":\"x\"", "Falta el id de la no conformidad"},
            {",\"data\":{\"id\":501,\"comentario\":\"x\"}", "Falta el id de la no conformidad"},
        };
        for (String[] c : casos) {
            crear(s, "{\"action\":\"" + CREAR + "\"" + c[0] + "}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(c[1]));
        }
        assertThat(API.peticionesNoConformidades()).isEmpty();
    }

    @Test
    void longitudMaximaEnCaracteresNoEnBytes() throws Exception {
        MockHttpSession s = login("operador1");
        String justo = "😀".repeat(1000) + "ñ".repeat(1000); // 2000 caracteres (6000 bytes UTF-8)
        crear(s, cuerpo(501, justo)).andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(unico().cuerpo()).get("comentario").asString()).isEqualTo(justo);
        crear(s, cuerpo(501, justo + "x"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("El comentario supera el máximo de 2000 caracteres."));
        assertThat(API.seguimientosRecibidos()).hasSize(1);
    }

    @Test
    void utf8YTextoNormalConSignosPasanIntactos() throws Exception {
        MockHttpSession s = login("operador1");
        String texto = "Revisión ñandú — «ok» ✓ 😀\nLínea 2: a < b, 5<6, x -> y, <3, 3 > 2, R&D, \"comillas\" y 'apóstrofo'\tfin";
        crear(s, cuerpo(501, texto)).andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(unico().cuerpo()).get("comentario").asString()).isEqualTo(texto);
    }

    @Test
    void htmlYScriptSeRechazanSinLlamarUpstream() throws Exception {
        MockHttpSession s = login("operador1");
        for (String malo : List.of("<script>alert(1)</script>", "hola <img src=x onerror=alert(1)>", "</div><b>x", "<!-- x -->",
                "<?xml version=\"1.0\"?>", "texto <b>negrita</b>", "<svg/onload=alert(1)>", "<iframe src=javascript:alert(1)>",
                "<IMG SRC=x ONERROR=alert(1)>")) {
            crear(s, cuerpo(501, malo))
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("El comentario no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\")."));
        }
        for (String control : List.of("a\u0000b", "a\u001bb", "a\u007fb")) {
            crear(s, cuerpo(501, control)).andExpect(jsonPath("$.error").value("El comentario contiene caracteres no permitidos."));
        }
        assertThat(API.peticionesNoConformidades()).isEmpty();
        // Entidades escritas como texto no son marcado: innerHTML las muestra como texto, no ejecuta nada.
        crear(s, cuerpo(501, "&lt;img src=x onerror=alert(1)&gt;")).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void lecturaDelSeguimientoEscapaMarcadoGuardadoDesdePhotino() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode res = json(mockMvc.perform(post("/api/v1/bridge").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"noConformidades.seguimiento.list\",\"id\":901}")).andReturn());
        assertThat(res.at("/data/0/comentario").asString()).isEqualTo("&lt;img src=x onerror=alert(1)&gt; &amp; &#39;x&#39; &quot;y&quot; ñ");
        assertThat(res.at("/data/0/autor").asString()).isEqualTo("&lt;b&gt;Mallory&lt;/b&gt;");
        assertThat(res.at("/data/0/creadoEn").asString()).isEqualTo("2026-09-20T10:00:00");
        // Texto normal (con acentos) queda igual.
        JsonNode normal = json(mockMvc.perform(post("/api/v1/bridge").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"noConformidades.seguimiento.list\",\"id\":501}")).andReturn());
        assertThat(normal.at("/data/0/comentario").asString()).isEqualTo("Revisión ñ");
    }

    // ------------------------------------------------------------ errores y sesiones

    @Test
    void errorDeNegocioYErrorInternoDeLaApi() throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, cuerpo(501, "ERROR_API"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("No se pudo registrar el seguimiento"));
        crear(s, cuerpo(777, "x"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Error al comunicarse con la API Innpack"));
        // Sin reintentos: exactamente un POST por intento.
        assertThat(API.seguimientosRecibidos()).hasSize(2);
    }

    @Test
    void unauthorizedUpstreamInvalidaSoloEsaSesion() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        API.revocarTokens(10);
        crear(a, cuerpo(501, "x")).andExpect(status().isUnauthorized());
        assertThat(a.isInvalid()).isTrue();
        assertThat(API.seguimientosRecibidos()).isEmpty();
        crear(b, cuerpo(501, "sigo")).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void csrfObligatorio() throws Exception {
        MockHttpSession s = login("operador1");
        mockMvc.perform(post("/api/v1/bridge").session(s).contentType(MediaType.APPLICATION_JSON).content(cuerpo(501, "x")))
                .andExpect(status().isForbidden());
        assertThat(API.peticionesNoConformidades()).isEmpty();
    }

    @Test
    void dosUsuariosConcurrentesCadaComentarioConSuAutor() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tareas = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                boolean esA = i % 2 == 0;
                int n = i;
                tareas.add(() -> json(crear(esA ? a : b, cuerpo(501, "c" + n)).andExpect(status().isOk()).andReturn()).get("ok").asBoolean());
            }
            for (Future<Boolean> f : pool.invokeAll(tareas)) {
                assertThat(f.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        List<SeguimientoRecibido> recibidos = API.seguimientosRecibidos();
        assertThat(recibidos).hasSize(20);
        for (SeguimientoRecibido r : recibidos) {
            String autor = mapper.readTree(r.cuerpo()).get("autor").asString();
            assertThat(autor).isEqualTo(r.sub() == 10 ? "Operador Uno" : "Admin Uno");
        }
    }

    @Test
    void limiteDeEscriturasPorUsuarioAntesDeTocarUpstream(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("rafaga1");
        // 100 escrituras inválidas (no llegan a la API) consumen el cupo de la ventana.
        for (int i = 0; i < 100; i++) {
            crear(s, cuerpo(501, "")).andExpect(status().isOk());
        }
        crear(s, cuerpo(501, "x"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_LIMITE_ESCRITURAS));
        assertThat(API.peticionesNoConformidades()).isEmpty();
        assertThat(salida.getAll()).contains("evento=ACCION_DENEGADA usuario=rafaga1 empresa=INNPACK accion=" + CREAR + " motivo=LIMITE_ESCRITURAS");
        // Las lecturas no comparten ese límite.
        mockMvc.perform(post("/api/v1/bridge").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"noConformidades.get\",\"id\":501}")).andExpect(status().isOk());
    }

    @Test
    void limitadorDeVentanaDeslizante() {
        MutableClock reloj = new MutableClock(Instant.parse("2026-09-25T12:00:00Z"));
        EscrituraRateLimiter limite = new EscrituraRateLimiter(3, Duration.ofMinutes(1), reloj);
        assertThat(limite.permitir(1)).isTrue();
        assertThat(limite.permitir(1)).isTrue();
        assertThat(limite.permitir(1)).isTrue();
        assertThat(limite.permitir(1)).isFalse();
        assertThat(limite.permitir(2)).isTrue(); // otro usuario, cupo propio
        reloj.avanzar(Duration.ofSeconds(61));
        assertThat(limite.permitir(1)).isTrue();
    }

    // ------------------------------------------------------------------ auditoría

    @Test
    void auditoriaConUsuarioRealRecursoYSinElComentario(CapturedOutput salida) throws Exception {
        String secreto = "Dato sensible del cliente 12.345.678-9";
        crear(login("operador1"), "{\"action\":\"" + CREAR + "\",\"id\":501,\"comentario\":\"" + secreto + "\",\"autor\":\"Admin Uno\"}")
                .andExpect(jsonPath("$.ok").value(true));
        crear(login("operador1"), cuerpo(404, "x")).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=" + CREAR.replace(".", "\\.")
                + " recurso=nc:501 resultado=OK ms=\\d+");
        assertThat(log).contains("recurso=nc:404 resultado=ERROR");
        assertThat(log).doesNotContain(secreto, "Admin Uno", FakeInnpackApi.firmaDeToken(10), PASS);
    }

    // ------------------------------------------------------------------ helpers

    private SeguimientoRecibido unico() {
        List<SeguimientoRecibido> r = API.seguimientosRecibidos();
        assertThat(r).hasSize(1);
        return r.get(0);
    }

    private String cuerpo(int id, String comentario) throws Exception {
        return "{\"action\":\"" + CREAR + "\",\"id\":" + id + ",\"comentario\":" + mapper.writeValueAsString(comentario) + "}";
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.14.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private ResultActions crear(MockHttpSession sesion, String cuerpo) throws Exception {
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
            Path www = Files.createTempDirectory("qcc-web-fixture-3a");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
