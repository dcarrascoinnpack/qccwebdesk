package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import cl.faret.qccweb.auth.FakeInnpackApi.CatalogoRecibido;
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
 * Contrato común de noConformidades.catalogos.{catalogo}.crear (acción dinámica del combo de catálogos; mismo
 * handler `catalogoCrear`). Cada catálogo habilitado lo hereda con su propia clase (3d clientes, 3e categoriasDefecto, 3f tiposFalla/supervisores/revisores).
 * Foco: identidad (creadoPor → siempre la sesión), `nombre` con el contrato real de la API (trim/colapso, ≤ 150
 * UTF-16, sin controles ni HTML), duplicados resueltos por la API, refresco inmediato.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
abstract class CatalogoCrearBase {

    /** Escrituras de catálogo habilitadas en la web (todo lo demás debe seguir denegado). */
    static final java.util.Set<String> HABILITADAS = java.util.Set.of(
            "noConformidades.catalogos.clientes.crear", "noConformidades.catalogos.categoriasDefecto.crear",
            "noConformidades.catalogos.tiposFalla.crear", "noConformidades.catalogos.supervisores.crear",
            "noConformidades.catalogos.revisores.crear");
    private static final String MSG_HTML = "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").";
    private static final String MSG_CARACTERES = "El texto contiene caracteres no permitidos.";
    private static final String MSG_LARGO = "El valor no puede superar los 150 caracteres.";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveCatalogos#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        // Compartida por todas las subclases (mismo contexto Spring); se cierra al terminar la JVM de tests.
        Runtime.getRuntime().addShutdownHook(new Thread(API::close));
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

    private final String catalogo;
    private final String CREAR;
    private final String LISTAR;
    private final String POST_CATALOGO;

    protected CatalogoCrearBase(String catalogo) {
        this.catalogo = catalogo;
        this.CREAR = "noConformidades.catalogos." + catalogo + ".crear";
        this.LISTAR = "noConformidades.catalogos." + catalogo + ".list";
        this.POST_CATALOGO = "POST /api/nc-catalogos/" + catalogo;
    }

    /** Payload exacto de _catalogoCrear de Photino ({action, nombre, creadoPor}), con overrides opcionales. */
    private String payload(Map<String, Object> cambios) {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", CREAR);
        p.put("nombre", "Viña Ñandú Ltda.");
        p.put("creadoPor", "Operador Uno");
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

    // ------------------------------------------------------- flujo exitoso + refresco

    @Test
    void creacionCorrectaConCuerpoExactoYApareceEnElCatalogoInmediatamente() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(crear(s, payload(Map.of())).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.at("/data/id").asInt()).isEqualTo(901);
        assertThat(json.at("/data/nombre").asString()).isEqualTo("Viña Ñandú Ltda.");
        assertThat(API.peticionesNoConformidades()).containsExactly(POST_CATALOGO);
        CatalogoRecibido r = unica();
        assertThat(r.sub()).isEqualTo(10);
        assertThat(r.contentType()).startsWith("application/json");
        assertThat(mapper.readTree(r.cuerpo())).isEqualTo(mapper.readTree("{\"nombre\":\"Viña Ñandú Ltda.\",\"creadoPor\":\"Operador Uno\"}"));
        // Refresco: el combo agrega el ítem devuelto a su caché; una relectura (otra pestaña/usuario) también lo trae.
        JsonNode lista = json(listar(login("admin1")).andReturn());
        assertThat(lista.at("/data/1/id").asInt()).isEqualTo(901);
        assertThat(lista.at("/data/1/nombre").asString()).isEqualTo("Viña Ñandú Ltda.");
        assertThat(API.valoresCatalogo(catalogo).get(1).get("creadoPor").asString()).isEqualTo("Operador Uno");
    }

    // ------------------------------------------------------------------ identidad

    @Test
    void creadoPorFalsificadoOAusenteSiempreEsLaSesion() throws Exception {
        String[][] casos = {{"operador1", "Operador Uno"}, {"admin1", "Admin Uno"}, {"adminti1", "Admin TI"}};
        int i = 0;
        for (String[] c : casos) {
            crear(login(c[0]), payload(Map.of("nombre", "Cliente " + i++, "creadoPor", "Otro Usuario"))).andExpect(jsonPath("$.ok").value(true));
        }
        crear(login("operador1"), payload(Map.of("nombre", "Cliente sin autor", "creadoPor", Borrar.CAMPO))).andExpect(jsonPath("$.ok").value(true));
        crear(login("operador1"), payload(Map.of("nombre", "Cliente autor raro", "creadoPor", Map.of("id", 20)))).andExpect(jsonPath("$.ok").value(true));
        List<CatalogoRecibido> r = API.catalogosRecibidos();
        assertThat(r).hasSize(5);
        for (int k = 0; k < 3; k++) {
            assertThat(mapper.readTree(r.get(k).cuerpo()).get("creadoPor").asString()).isEqualTo(casos[k][1]);
        }
        assertThat(mapper.readTree(r.get(3).cuerpo()).get("creadoPor").asString()).isEqualTo("Operador Uno");
        assertThat(mapper.readTree(r.get(4).cuerpo()).get("creadoPor").asString()).isEqualTo("Operador Uno");
        for (CatalogoRecibido x : r) {
            assertThat(mapper.readTree(x.cuerpo()).propertyNames()).containsExactlyInAnyOrder("nombre", "creadoPor");
        }
    }

    @Test
    void camposInesperadosSeRechazanSinLlamarUpstream() throws Exception {
        MockHttpSession s = login("operador1");
        for (String extra : List.of("autor", "usuario", "usuarioId", "empresa", "rol", "id", "activo", "catalogo", "tabla", "data", "creadoEn")) {
            crear(s, payload(Map.of(extra, "Admin Uno")))
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Campo no permitido: " + extra));
        }
        crear(s, payload(Map.of("<script>alert(1)</script>", 1))).andExpect(jsonPath("$.error").value("Campo no permitido: ?"));
        for (Object malo : new Object[] {123, true, List.of("x"), Map.of("nombre", "x")}) {
            crear(s, payload(Map.of("nombre", malo))).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        }
        assertThat(API.peticionesNoConformidades()).isEmpty();
    }

    // ---------------------------------------------------------------- nombre

    @Test
    void nombreVacioOAusente() throws Exception {
        MockHttpSession s = login("operador1");
        for (Object v : new Object[] {"", "   ", "\u00A0 \u2003", null}) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("nombre", v);
            crear(s, payload(m)).andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value("Falta el nombre"));
        }
        crear(s, payload(Map.of("nombre", Borrar.CAMPO))).andExpect(jsonPath("$.error").value("Falta el nombre"));
        assertThat(API.peticionesNoConformidades()).isEmpty();
    }

    @Test
    void maximoPermitidoYExcesoEnUnidadesUtf16ComoLaApi() throws Exception {
        MockHttpSession s = login("operador1");
        String justo = "Ñ".repeat(149) + "a";
        String emojis = "😀".repeat(75);                        // 150 unidades UTF-16 (75 code points)
        crear(s, payload(Map.of("nombre", "x".repeat(151)))).andExpect(jsonPath("$.error").value(MSG_LARGO));
        crear(s, payload(Map.of("nombre", emojis + "a"))).andExpect(jsonPath("$.error").value(MSG_LARGO));
        assertThat(API.peticionesNoConformidades()).isEmpty();
        crear(s, payload(Map.of("nombre", justo))).andExpect(jsonPath("$.ok").value(true));
        crear(s, payload(Map.of("nombre", emojis))).andExpect(jsonPath("$.ok").value(true));
        // 150 tras colapsar espacios (el largo se mide sobre el valor normalizado, como la API).
        crear(s, payload(Map.of("nombre", "   " + "b".repeat(75) + "      " + "c".repeat(74) + "   "))).andExpect(jsonPath("$.ok").value(true));
        List<CatalogoRecibido> r = API.catalogosRecibidos();
        assertThat(r).hasSize(3);
        assertThat(mapper.readTree(r.get(0).cuerpo()).get("nombre").asString()).isEqualTo(justo);
        assertThat(mapper.readTree(r.get(1).cuerpo()).get("nombre").asString()).isEqualTo(emojis);
        assertThat(mapper.readTree(r.get(2).cuerpo()).get("nombre").asString()).isEqualTo("b".repeat(75) + " " + "c".repeat(74));
    }

    @Test
    void espaciosExtremosEInternosSeNormalizanComoLaApi() throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, payload(Map.of("nombre", "  Viña   del\u00A0 Mar  "))).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.nombre").value("Viña del Mar"));
        assertThat(mapper.readTree(unica().cuerpo()).get("nombre").asString()).isEqualTo("Viña del Mar");
    }

    @Test
    void utf8SeConservaExacto() throws Exception {
        MockHttpSession s = login("operador1");
        String utf8 = "Industrias «Ñuñoa» — R&D 5<6 a < b ✓ 😀 &lt;b&gt; O'Higgins \"Sur\"";
        crear(s, payload(Map.of("nombre", utf8))).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data.nombre").value(utf8));
        assertThat(mapper.readTree(unica().cuerpo()).get("nombre").asString()).isEqualTo(utf8);
    }

    @Test
    void htmlYScriptSeRechazanSinLlamarUpstream() throws Exception {
        MockHttpSession s = login("operador1");
        for (String malo : List.of("<script>alert(1)</script>", "ACME <img src=x onerror=alert(1)>", "</td><b>", "<!--x-->",
                "<svg/onload=1>", "<?xml?>", "x<a href=javascript:alert(1)>y")) {
            crear(s, payload(Map.of("nombre", malo))).andExpect(jsonPath("$.error").value(MSG_HTML));
        }
        assertThat(API.peticionesNoConformidades()).isEmpty();
    }

    @Test
    void caracteresDeControlYSustitutosSueltosSeRechazan() throws Exception {
        MockHttpSession s = login("operador1");
        for (String malo : List.of("ACME\nSur", "ACME\rSur", "ACME\u0000", "\u0007ACME", "ACME\u007F", "ACME\u001B[31m", "\nACME")) {
            crear(s, payload(Map.of("nombre", malo))).andExpect(jsonPath("$.error").value(MSG_CARACTERES));
        }
        crear(s, "{\"action\":\"" + CREAR + "\",\"nombre\":\"ACME \\ud800 Sur\"}").andExpect(jsonPath("$.error").value(MSG_CARACTERES));
        crear(s, "{\"action\":\"" + CREAR + "\",\"nombre\":\"ACME \\udc00\"}").andExpect(jsonPath("$.error").value(MSG_CARACTERES));
        assertThat(API.peticionesNoConformidades()).isEmpty();
        // Tab (mismo criterio de una línea que 3b): la API lo colapsa a un espacio; el gateway envía lo mismo.
        crear(s, payload(Map.of("nombre", "\tACME\tSur "))).andExpect(jsonPath("$.data.nombre").value("ACME Sur"));
        assertThat(mapper.readTree(unica().cuerpo()).get("nombre").asString()).isEqualTo("ACME Sur");
    }

    @Test
    void duplicadosLosResuelveLaApiSinPoliticaPropiaDelGateway() throws Exception {
        MockHttpSession s = login("operador1");
        int id = json(crear(s, payload(Map.of("nombre", "Cliente Nuevo"))).andReturn()).at("/data/id").asInt();
        // Mismo nombre, otra capitalización y espacios extra: el gateway los ENVÍA; la API devuelve el existente.
        for (String dup : List.of("Cliente Nuevo", "cliente nuevo", "  CLIENTE   NUEVO ")) {
            crear(s, payload(Map.of("nombre", dup))).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data.id").value(id))
                    .andExpect(jsonPath("$.data.nombre").value("Cliente Nuevo"));
        }
        // Inactivo: la API lo reactiva y devuelve su id (comportamiento real documentado, no del gateway).
        crear(s, payload(Map.of("nombre", "Cliente Inactivo"))).andExpect(jsonPath("$.data.id").value(2));
        assertThat(API.catalogosRecibidos()).hasSize(5);
        JsonNode lista = json(listar(s).andReturn());
        assertThat(lista.get("data").size()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- autorización

    @Test
    void rolesYPolitica() throws Exception {
        crear(login("consulta1"), payload(Map.of())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(API.peticionesNoConformidades()).isEmpty();
        for (String u : List.of("operador1", "admin1", "adminti1")) {
            crear(login(u), payload(Map.of("nombre", "Cliente de " + u))).andExpect(jsonPath("$.ok").value(true));
        }
        assertThat(policy.evaluar(CREAR, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        assertThat(policy.evaluar(CREAR, usuario("INNPACK", "consulta"))).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
        Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(CREAR)).findFirst().orElseThrow();
        assertThat(d.get("escritura")).isEqualTo(true);
        assertThat(d.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
        assertThat(d.get("identidad")).isEqualTo(Map.of("creadoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO));
        // Ninguna otra escritura de catálogo: ni crear en otros catálogos, ni editar/desactivar/eliminar.
        for (String cat : cl.faret.qccweb.bridge.handlers.NoConformidadesBridgeHandler.CATALOGOS) {
            for (String op : List.of("crear", "desactivar", "editar", "actualizar", "eliminar")) {
                String a = "noConformidades.catalogos." + cat + "." + op;
                if (!HABILITADAS.contains(a)) {
                    assertThat(policy.accionesRegistradas()).doesNotContain(a);
                }
            }
        }
        MockHttpSession s = login("admin1");
        crear(s, "{\"action\":\"noConformidades.catalogos.areas.crear\",\"nombre\":\"X\"}").andExpect(status().isForbidden());
        crear(s, "{\"action\":\"noConformidades.catalogos." + catalogo + ".desactivar\",\"id\":1}").andExpect(status().isForbidden());
        assertThat(API.catalogosRecibidos()).hasSize(3);
    }

    // ------------------------------------------------------------ errores y sesiones

    @Test
    void errorDeNegocioYErrorInternoSinReintento() throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, payload(Map.of("nombre", "ERROR_API"))).andExpect(jsonPath("$.error").value("No se pudo crear el valor"));
        crear(s, payload(Map.of("nombre", "ERROR_500"))).andExpect(jsonPath("$.error").value("Error al comunicarse con la API Innpack"));
        assertThat(API.catalogosRecibidos()).hasSize(2);
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
        assertThat(API.catalogosRecibidos()).hasSize(1); // el 401 no llegó a crear nada
        assertThat(API.catalogosRecibidos().get(0).sub()).isEqualTo(20);
    }

    @Test
    void dosSesionesEnParaleloCadaValorConSuCreador() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tareas = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                boolean esA = i % 2 == 0;
                String nombre = "Cliente paralelo " + i;
                tareas.add(() -> json(crear(esA ? a : b, payload(Map.of("nombre", nombre, "creadoPor", esA ? "Admin Uno" : "Operador Uno")))
                        .andExpect(status().isOk()).andReturn()).get("ok").asBoolean());
            }
            for (Future<Boolean> f : pool.invokeAll(tareas)) {
                assertThat(f.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        List<CatalogoRecibido> r = API.catalogosRecibidos();
        assertThat(r).hasSize(20);
        for (CatalogoRecibido x : r) {
            JsonNode c = mapper.readTree(x.cuerpo());
            assertThat(c.get("creadoPor").asString()).isEqualTo(x.sub() == 10 ? "Operador Uno" : "Admin Uno");
            int n = Integer.parseInt(c.get("nombre").asString().substring("Cliente paralelo ".length()));
            assertThat(x.sub()).isEqualTo(n % 2 == 0 ? 10 : 20);
        }
        assertThat(json(listar(a).andReturn()).get("data").size()).isEqualTo(21);
    }

    // ------------------------------------------------------------------ auditoría

    @Test
    void auditoriaConCatalogoEIdCreadoSinElNombre(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, payload(Map.of("nombre", "Cliente Sensible 12.345.678-9"))).andExpect(jsonPath("$.ok").value(true));
        crear(s, payload(Map.of("nombre", "ERROR_API"))).andExpect(jsonPath("$.ok").value(false));
        crear(s, payload(Map.of("nombre", "<b>x</b>"))).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=noConformidades\\.catalogos\\." + catalogo + "\\.crear "
                + "recurso=catalogo:" + catalogo + ":901 resultado=OK ms=\\d+");
        assertThat(log).contains("recurso=catalogo:" + catalogo + " resultado=ERROR");
        assertThat(log).doesNotContain("Cliente Sensible", "12.345.678-9", "<b>x</b>", FakeInnpackApi.firmaDeToken(10), PASS);
    }

    // ------------------------------------------------------------------ helpers

    private CatalogoRecibido unica() {
        List<CatalogoRecibido> r = API.catalogosRecibidos();
        assertThat(r).hasSize(1);
        assertThat(r.get(0).catalogo()).isEqualTo(catalogo);
        return r.get(0);
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.16.0." + IP.getAndIncrement());
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

    private ResultActions listar(MockHttpSession sesion) throws Exception {
        return crear(sesion, "{\"action\":\"" + LISTAR + "\"}").andExpect(jsonPath("$.ok").value(true));
    }

    private JsonNode json(MvcResult res) throws Exception {
        return mapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static SessionUser usuario(String empresa, String rol) {
        return new SessionUser(1, "u", "U", rol, empresa, "t", Instant.now(), Instant.now().plusSeconds(60));
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-catalogos");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
