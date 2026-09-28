package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import cl.faret.qccweb.auth.FakeInnpackApi.SeguimientoRecibido;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Fase 3b — SEGUNDA ESCRITURA: noConformidades.acciones.crear. Foco: IDENTIDAD (creadoPor → siempre la
 * sesión) vs DATOS DE NEGOCIO (responsable, descripción, fecha, prioridad, análisis → validados y
 * conservados), lista blanca estricta. API SIMULADA: se verifica el cuerpo EXACTO que recibe upstream.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3bTest {

    private static final String CREAR = "noConformidades.acciones.crear";
    private static final String BASE = "/api/no-conformidades";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveAcciones#2026";
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

    /** Payload exacto de _agregarAccion de Photino, con overrides opcionales. */
    private String payload(Map<String, Object> cambios) {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", CREAR);
        p.put("id", 501);
        p.put("analisisId", 7);
        p.put("creadoPor", "Operador Uno");
        p.put("descripcion", "Cambiar rodillo de la impresora 2");
        p.put("responsable", "Juan Pérez (Mantención)");
        p.put("fechaLimite", "2026-10-15");
        p.put("prioridad", "ALTA");
        cambios.forEach((k, v) -> {
            if (v == Borrar.CAMPO) {
                p.remove(k);
            } else {
                p.set(k, mapper.valueToTree(v));
            }
        });
        return p.toString();
    }

    private enum Borrar { CAMPO }

    // ------------------------------------------------------------------ flujo exitoso

    @Test
    void escrituraExitosaConCuerpoExacto() throws Exception {
        JsonNode json = json(crear(login("operador1"), payload(Map.of())).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(API.peticionesNoConformidades()).containsExactly(
                "GET " + BASE + "/501", "GET " + BASE + "/501/analisis", "POST " + BASE + "/501/acciones");
        SeguimientoRecibido r = unica();
        assertThat(r.sub()).isEqualTo(10);
        assertThat(r.contentType()).startsWith("application/json");
        assertThat(mapper.readTree(r.cuerpo())).isEqualTo(mapper.readTree("{\"analisisId\":7,\"descripcion\":\"Cambiar rodillo de la impresora 2\","
                + "\"responsable\":\"Juan Pérez (Mantención)\",\"fechaLimite\":\"2026-10-15\",\"prioridad\":\"ALTA\",\"creadoPor\":\"Operador Uno\"}"));
    }

    // ------------------------------------------------- identidad vs datos de negocio

    @Test
    void creadoPorFalsificadoSeReemplazaPeroResponsableDeOtraPersonaSeConserva() throws Exception {
        // El operador asigna la acción al admin (dato de negocio legítimo) e intenta firmarla como admin (identidad).
        crear(login("operador1"), payload(Map.of("creadoPor", "Admin Uno", "responsable", "Admin Uno"))).andExpect(jsonPath("$.ok").value(true));
        JsonNode cuerpo = mapper.readTree(unica().cuerpo());
        assertThat(cuerpo.get("creadoPor").asString()).isEqualTo("Operador Uno");   // identidad: SIEMPRE la sesión
        assertThat(cuerpo.get("responsable").asString()).isEqualTo("Admin Uno");    // negocio: SE CONSERVA
    }

    @Test
    void responsableDistintoDelUsuarioSeConservaExactoEnTodosLosRoles() throws Exception {
        String[][] casos = {{"operador1", "Operador Uno"}, {"admin1", "Admin Uno"}, {"adminti1", "Admin TI"}};
        for (String[] c : casos) {
            crear(login(c[0]), payload(Map.of("responsable", "María José Núñez — Calidad"))).andExpect(jsonPath("$.ok").value(true));
        }
        List<SeguimientoRecibido> r = API.accionesRecibidas();
        assertThat(r).hasSize(3);
        for (int i = 0; i < 3; i++) {
            JsonNode cuerpo = mapper.readTree(r.get(i).cuerpo());
            assertThat(cuerpo.get("responsable").asString()).isEqualTo("María José Núñez — Calidad");
            assertThat(cuerpo.get("creadoPor").asString()).isEqualTo(casos[i][1]);
        }
    }

    @Test
    void responsableFueraDeFormatoSeRechazaSinLlamarUpstream() throws Exception {
        MockHttpSession s = login("operador1");
        Object[][] casos = {
            {"", "Falta el responsable"},
            {"   ", "Falta el responsable"},
            {null, "Falta el responsable"},
            {"x".repeat(151), "El responsable supera el máximo de 150 caracteres."},
            {"Juan\nPérez", "El texto contiene caracteres no permitidos."},
            {"Juan\rPérez", "El texto contiene caracteres no permitidos."},
            {"<b>Juan</b>", "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\")."},
            {"<img src=x onerror=alert(1)>", "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\")."},
            {123, "Parámetro inválido."},
            {true, "Parámetro inválido."},
            {Map.of("nombre", "x"), "Parámetro inválido."},
        };
        for (Object[] c : casos) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("responsable", c[0]);
            crear(s, payload(m)).andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value((String) c[1]));
        }
        crear(s, payload(Map.of("responsable", Borrar.CAMPO))).andExpect(jsonPath("$.error").value("Falta el responsable"));
        assertThat(API.peticionesNoConformidades()).isEmpty();
        // 150 exactos (con tildes y emoji) → válido.
        crear(s, payload(Map.of("responsable", "Ñ".repeat(149) + "😀"))).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void camposInesperadosSeRechazanSinLlamarUpstream() throws Exception {
        MockHttpSession s = login("operador1");
        for (String extra : List.of("autor", "usuario", "usuarioId", "empresa", "rol", "estado", "data", "creadoEn", "actualizadoPor", "noConformidadId")) {
            crear(s, payload(Map.of(extra, "Admin Uno")))
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Campo no permitido: " + extra));
        }
        crear(s, payload(Map.of("<script>alert(1)</script>", 1))).andExpect(jsonPath("$.error").value("Campo no permitido: ?"));
        assertThat(API.peticionesNoConformidades()).isEmpty();
    }

    // ---------------------------------------------------------------- validaciones

    @Test
    void descripcionVaciaLimitesHtmlYUtf8() throws Exception {
        MockHttpSession s = login("operador1");
        for (Object d : new Object[] {"", "  ", null}) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("descripcion", d);
            crear(s, payload(m)).andExpect(jsonPath("$.error").value("Falta la descripción de la acción"));
        }
        crear(s, payload(Map.of("descripcion", "x".repeat(501)))).andExpect(jsonPath("$.error").value("La descripción supera el máximo de 500 caracteres."));
        for (String malo : List.of("<script>alert(1)</script>", "ok <img src=x onerror=alert(1)>", "</td><b>", "<!--x-->", "<svg/onload=1>")) {
            crear(s, payload(Map.of("descripcion", malo)))
                    .andExpect(jsonPath("$.error").value("El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\")."));
        }
        crear(s, payload(Map.of("descripcion", "tab\tpermitido pero\nsalto no"))).andExpect(jsonPath("$.error").value("El texto contiene caracteres no permitidos."));
        assertThat(API.peticionesNoConformidades()).isEmpty();
        String utf8 = "Revisar rodillo ñ — «ok» ✓ 😀 a < b, 5<6, x -> y, R&D, &lt;b&gt;";
        String justo = "é".repeat(499) + "😀";
        crear(s, payload(Map.of("descripcion", utf8, "responsable", "José Ñúñez"))).andExpect(jsonPath("$.ok").value(true));
        crear(s, payload(Map.of("descripcion", justo))).andExpect(jsonPath("$.ok").value(true));
        List<SeguimientoRecibido> r = API.accionesRecibidas();
        assertThat(mapper.readTree(r.get(0).cuerpo()).get("descripcion").asString()).isEqualTo(utf8);
        assertThat(mapper.readTree(r.get(0).cuerpo()).get("responsable").asString()).isEqualTo("José Ñúñez");
        assertThat(mapper.readTree(r.get(1).cuerpo()).get("descripcion").asString()).isEqualTo(justo);
    }

    @Test
    void fechaYPrioridadSegunElContratoReal() throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, payload(Map.of("fechaLimite", ""))).andExpect(jsonPath("$.error").value("Falta la fecha límite"));
        crear(s, payload(Map.of("fechaLimite", Borrar.CAMPO))).andExpect(jsonPath("$.error").value("Falta la fecha límite"));
        for (String f : List.of("2026-13-01", "2026-02-30", "15-10-2026", "2026-10-15T00:00", "2026/10/15", "20261015")) {
            crear(s, payload(Map.of("fechaLimite", f))).andExpect(jsonPath("$.error").value("La fecha límite no es válida (formato AAAA-MM-DD)."));
        }
        crear(s, payload(Map.of("fechaLimite", 20261015))).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        for (String p : List.of("URGENTE", "alta", " ALTA", "ALTA;DROP")) {
            crear(s, payload(Map.of("prioridad", p))).andExpect(jsonPath("$.ok").value(p.equals(" ALTA")));
        }
        assertThat(API.accionesRecibidas()).hasSize(1); // " ALTA" se normaliza con strip como el resto de textos
        // prioridad opcional: null, vacía o ausente → null upstream (la vista manda `value || null`).
        crear(s, payload(Map.of("prioridad", ""))).andExpect(jsonPath("$.ok").value(true));
        Map<String, Object> nula = new java.util.HashMap<>();
        nula.put("prioridad", null);
        crear(s, payload(nula)).andExpect(jsonPath("$.ok").value(true));
        crear(s, payload(Map.of("prioridad", Borrar.CAMPO))).andExpect(jsonPath("$.ok").value(true));
        List<SeguimientoRecibido> r = API.accionesRecibidas();
        for (int i = 1; i <= 3; i++) {
            assertThat(mapper.readTree(r.get(i).cuerpo()).get("prioridad").isNull()).isTrue();
        }
        for (String p : List.of("MEDIA", "BAJA")) {
            crear(s, payload(Map.of("prioridad", p))).andExpect(jsonPath("$.ok").value(true));
        }
    }

    @Test
    void analisisIdDebeSerElDeLaNcONulo() throws Exception {
        MockHttpSession s = login("operador1");
        Map<String, Object> sin = new java.util.HashMap<>();
        sin.put("analisisId", null);
        crear(s, payload(sin)).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.peticionesNoConformidades()).containsExactly("GET " + BASE + "/501", "POST " + BASE + "/501/acciones");
        assertThat(mapper.readTree(unica().cuerpo()).get("analisisId").isNull()).isTrue();
        API.reiniciarDashboard();
        crear(s, payload(Map.of("analisisId", 99))).andExpect(jsonPath("$.error").value("El análisis indicado no corresponde a la no conformidad."));
        crear(s, payload(Map.of("id", 503, "analisisId", 7))).andExpect(jsonPath("$.error").value("El análisis indicado no corresponde a la no conformidad."));
        for (Object malo : new Object[] {"7", 0, -1, 2.5, true}) {
            crear(s, payload(Map.of("analisisId", malo))).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        }
        assertThat(API.accionesRecibidas()).isEmpty();
    }

    @Test
    void ncInexistenteEIdInvalido() throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, payload(Map.of("id", 404))).andExpect(jsonPath("$.error").value("No conformidad no encontrada"));
        assertThat(API.peticionesNoConformidades()).containsExactly("GET " + BASE + "/404");
        for (Object malo : new Object[] {0, -3, "abc"}) {
            crear(s, payload(Map.of("id", malo))).andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
        }
        crear(s, payload(Map.of("id", Borrar.CAMPO))).andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
        assertThat(API.accionesRecibidas()).isEmpty();
    }

    // ---------------------------------------------------------------- autorización

    @Test
    void rolesYPolitica() throws Exception {
        crear(login("consulta1"), payload(Map.of())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(API.peticionesNoConformidades()).isEmpty();
        assertThat(policy.evaluar(CREAR, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        assertThat(policy.evaluar(CREAR, usuario("INNPACK", "consulta"))).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
        Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(CREAR)).findFirst().orElseThrow();
        assertThat(d.get("escritura")).isEqualTo(true);
        assertThat(d.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
        assertThat(d.get("identidad")).isEqualTo(Map.of("creadoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO));
        for (String otra : List.of("noConformidades.eliminar", "noConformidades.adjuntos.eliminar")) {
            assertThat(policy.accionesRegistradas()).doesNotContain(otra);
        }
    }

    // ------------------------------------------------------------ errores y sesiones

    @Test
    void errorDeNegocioYErrorInternoSinReintento() throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, payload(Map.of("descripcion", "ERROR_API"))).andExpect(jsonPath("$.error").value("No se pudo registrar la acción"));
        crear(s, payload(Map.of("id", 777))).andExpect(jsonPath("$.error").value("Error al comunicarse con la API Innpack"));
        assertThat(API.accionesRecibidas()).hasSize(2);
    }

    @Test
    void unauthorizedUpstreamYCsrf() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        mockMvc.perform(post("/api/v1/bridge").session(b).contentType(MediaType.APPLICATION_JSON).content(payload(Map.of())))
                .andExpect(status().isForbidden());
        assertThat(API.peticionesNoConformidades()).isEmpty();
        API.revocarTokens(10);
        crear(a, payload(Map.of())).andExpect(status().isUnauthorized());
        assertThat(a.isInvalid()).isTrue();
        crear(b, payload(Map.of())).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.accionesRecibidas()).hasSize(1);
        assertThat(API.accionesRecibidas().get(0).sub()).isEqualTo(20);
    }

    @Test
    void concurrenciaABCadaAccionConSuCreadorYSuResponsable() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tareas = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                boolean esA = i % 2 == 0;
                String resp = "Responsable " + i;
                tareas.add(() -> json(crear(esA ? a : b, payload(Map.of("responsable", resp, "creadoPor", esA ? "Admin Uno" : "Operador Uno")))
                        .andExpect(status().isOk()).andReturn()).get("ok").asBoolean());
            }
            for (Future<Boolean> f : pool.invokeAll(tareas)) {
                assertThat(f.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        List<SeguimientoRecibido> r = API.accionesRecibidas();
        assertThat(r).hasSize(20);
        List<String> responsables = new ArrayList<>();
        for (SeguimientoRecibido x : r) {
            JsonNode c = mapper.readTree(x.cuerpo());
            assertThat(c.get("creadoPor").asString()).isEqualTo(x.sub() == 10 ? "Operador Uno" : "Admin Uno");
            responsables.add(c.get("responsable").asString());
        }
        List<String> esperados = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            esperados.add("Responsable " + i);
        }
        assertThat(responsables).containsExactlyInAnyOrderElementsOf(esperados);
    }

    // ------------------------------------------------------ auditoría y lectura segura

    @Test
    void auditoriaConNcIdDeAccionSiLaApiLoDevuelveYSinPayload(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, payload(Map.of("responsable", "Persona Sensible 12.345.678-9"))).andExpect(jsonPath("$.ok").value(true));
        crear(s, payload(Map.of("descripcion", "CON_ID"))).andExpect(jsonPath("$.ok").value(true));
        crear(s, payload(Map.of("id", 404))).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=noConformidades\\.acciones\\.crear recurso=nc:501 resultado=OK ms=\\d+");
        assertThat(log).contains("recurso=nc:501:accion:77 resultado=OK");
        assertThat(log).contains("recurso=nc:404 resultado=ERROR");
        assertThat(log).doesNotContain("Persona Sensible", "Cambiar rodillo", "2026-10-15", FakeInnpackApi.firmaDeToken(10), PASS);
    }

    @Test
    void lecturaDeAccionesEscapaMarcadoGuardadoDesdePhotino() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode r = json(mockMvc.perform(post("/api/v1/bridge").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"noConformidades.acciones.list\",\"id\":901}")).andReturn());
        assertThat(r.at("/data/0/descripcion").asString()).isEqualTo("&lt;img src=x onerror=alert(1)&gt; &amp; ñ");
        assertThat(r.at("/data/0/responsable").asString()).isEqualTo("&lt;b&gt;Mallory&lt;/b&gt;");
        assertThat(r.at("/data/0/prioridad").asString()).isEqualTo("ALTA");
        assertThat(r.at("/data/0/estado").asString()).isEqualTo("PENDIENTE");
    }

    // ------------------------------------------------------------------ helpers

    private SeguimientoRecibido unica() {
        List<SeguimientoRecibido> r = API.accionesRecibidas();
        assertThat(r).hasSize(1);
        return r.get(0);
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.15.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-3b");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
