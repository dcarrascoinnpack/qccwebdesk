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
 * handler `catalogoCrear`). Cada catálogo habilitado lo hereda con su propia clase (3d clientes, 3e categoriasDefecto, 3f tiposFalla/supervisores/revisores, 3g areas, 3h familiasProducto/impactos, 3i niveles).
 * Foco: identidad (creadoPor → siempre la sesión), `nombre` con el contrato real de la API (trim/colapso, ≤ largo
 * de la columna en UTF-16 — 150 por defecto, 50/20 según catálogo —, sin controles ni HTML), duplicados resueltos por
 * la API, refresco inmediato. Los textos de prueba caben en el largo más corto (20). Roles por catálogo: los
 * escritores A/B salen de los roles permitidos (niveles: solo admin_ti → adminti1/adminti2) y el resto debe recibir 403.
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
            "noConformidades.catalogos.revisores.crear", "noConformidades.catalogos.areas.crear",
            "noConformidades.catalogos.familiasProducto.crear", "noConformidades.catalogos.impactos.crear",
            "noConformidades.catalogos.niveles.crear");
    /** Roles de escritura operativa (orden de ActionPolicy.describir). */
    static final List<String> ROLES_OPERATIVOS = List.of("admin", "admin_ti", "operador");
    private static final String MSG_HTML = "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").";
    private static final String MSG_CARACTERES = "El texto contiene caracteres no permitidos.";
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
        API.agregar(new FakeInnpackApi.Usuario(26, "adminti2", PASS, "Admin TI Dos", "admin_ti", true));
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
    private final int limite;
    private final String MSG_LARGO;
    private final List<String> roles;
    /** Usuarios INNPACK con rol de escritura operativa (sin el segundo admin_ti). */
    private static final List<Escritor> ESCRITORES = List.of(new Escritor("operador1", "Operador Uno", 10, "operador"),
            new Escritor("admin1", "Admin Uno", 20, "admin"), new Escritor("adminti1", "Admin TI", 25, "admin_ti"));
    private static final Escritor ADMINTI2 = new Escritor("adminti2", "Admin TI Dos", 26, "admin_ti");
    private final List<Escritor> permitidos;
    private final List<Escritor> noPermitidos;
    /** Escritor principal y segunda sesión (otro usuario con permiso). */
    private final Escritor A;
    private final Escritor B;

    private record Escritor(String codigo, String nombre, int sub, String rol) { }

    protected CatalogoCrearBase(String catalogo) {
        this(catalogo, 150);
    }

    /** limite = largo de la columna `nombre` del catálogo en la API (par, para el caso de emojis). */
    protected CatalogoCrearBase(String catalogo, int limite) {
        this(catalogo, limite, ROLES_OPERATIVOS);
    }

    /** roles = roles con `crear` en la web, ordenados (niveles: solo admin_ti, más estricto que Photino). */
    protected CatalogoCrearBase(String catalogo, int limite, List<String> roles) {
        this.catalogo = catalogo;
        this.limite = limite;
        this.roles = roles;
        this.permitidos = ESCRITORES.stream().filter(e -> roles.contains(e.rol())).toList();
        this.noPermitidos = ESCRITORES.stream().filter(e -> !roles.contains(e.rol())).toList();
        this.A = permitidos.get(0);
        this.B = permitidos.size() > 1 ? permitidos.get(1) : ADMINTI2;
        this.MSG_LARGO = "El valor no puede superar los " + limite + " caracteres.";
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
        MockHttpSession s = login(A.codigo());
        JsonNode json = json(crear(s, payload(Map.of())).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.at("/data/id").asInt()).isEqualTo(901);
        assertThat(json.at("/data/nombre").asString()).isEqualTo("Viña Ñandú Ltda.");
        assertThat(API.peticionesNoConformidades()).containsExactly(POST_CATALOGO);
        CatalogoRecibido r = unica();
        assertThat(r.sub()).isEqualTo(A.sub());
        assertThat(r.contentType()).startsWith("application/json");
        assertThat(mapper.readTree(r.cuerpo())).isEqualTo(mapper.readTree("{\"nombre\":\"Viña Ñandú Ltda.\",\"creadoPor\":\"" + A.nombre() + "\"}"));
        // Refresco: el combo agrega el ítem devuelto a su caché; una relectura (otra pestaña/usuario) también lo trae.
        JsonNode lista = json(listar(login("admin1")).andReturn());
        assertThat(lista.at("/data/1/id").asInt()).isEqualTo(901);
        assertThat(lista.at("/data/1/nombre").asString()).isEqualTo("Viña Ñandú Ltda.");
        assertThat(API.valoresCatalogo(catalogo).get(1).get("creadoPor").asString()).isEqualTo(A.nombre());
    }

    // ------------------------------------------------------------------ identidad

    @Test
    void creadoPorFalsificadoOAusenteSiempreEsLaSesion() throws Exception {
        int i = 0;
        for (Escritor e : permitidos) {
            crear(login(e.codigo()), payload(Map.of("nombre", "Cliente " + i++, "creadoPor", "Otro Usuario"))).andExpect(jsonPath("$.ok").value(true));
        }
        crear(login(A.codigo()), payload(Map.of("nombre", "Cliente sin autor", "creadoPor", Borrar.CAMPO))).andExpect(jsonPath("$.ok").value(true));
        crear(login(A.codigo()), payload(Map.of("nombre", "Cliente autor raro", "creadoPor", Map.of("id", 20)))).andExpect(jsonPath("$.ok").value(true));
        List<CatalogoRecibido> r = API.catalogosRecibidos();
        int n = permitidos.size();
        assertThat(r).hasSize(n + 2);
        for (int k = 0; k < n; k++) {
            assertThat(mapper.readTree(r.get(k).cuerpo()).get("creadoPor").asString()).isEqualTo(permitidos.get(k).nombre());
        }
        assertThat(mapper.readTree(r.get(n).cuerpo()).get("creadoPor").asString()).isEqualTo(A.nombre());
        assertThat(mapper.readTree(r.get(n + 1).cuerpo()).get("creadoPor").asString()).isEqualTo(A.nombre());
        for (CatalogoRecibido x : r) {
            assertThat(mapper.readTree(x.cuerpo()).propertyNames()).containsExactlyInAnyOrder("nombre", "creadoPor");
        }
    }

    @Test
    void camposInesperadosSeRechazanSinLlamarUpstream() throws Exception {
        MockHttpSession s = login(A.codigo());
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
        MockHttpSession s = login(A.codigo());
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
        MockHttpSession s = login(A.codigo());
        String justo = "Ñ".repeat(limite - 1) + "a";
        String emojis = "😀".repeat(limite / 2);                // `limite` unidades UTF-16 (limite/2 code points)
        crear(s, payload(Map.of("nombre", "x".repeat(limite + 1)))).andExpect(jsonPath("$.error").value(MSG_LARGO));
        crear(s, payload(Map.of("nombre", emojis + "a"))).andExpect(jsonPath("$.error").value(MSG_LARGO));
        assertThat(API.peticionesNoConformidades()).isEmpty();
        crear(s, payload(Map.of("nombre", justo))).andExpect(jsonPath("$.ok").value(true));
        crear(s, payload(Map.of("nombre", emojis))).andExpect(jsonPath("$.ok").value(true));
        // `limite` tras colapsar espacios (el largo se mide sobre el valor normalizado, como la API).
        String b = "b".repeat(limite / 2);
        String c = "c".repeat(limite / 2 - 1);
        crear(s, payload(Map.of("nombre", "   " + b + "      " + c + "   "))).andExpect(jsonPath("$.ok").value(true));
        List<CatalogoRecibido> r = API.catalogosRecibidos();
        assertThat(r).hasSize(3);
        assertThat(mapper.readTree(r.get(0).cuerpo()).get("nombre").asString()).isEqualTo(justo);
        assertThat(mapper.readTree(r.get(1).cuerpo()).get("nombre").asString()).isEqualTo(emojis);
        assertThat(mapper.readTree(r.get(2).cuerpo()).get("nombre").asString()).isEqualTo(b + " " + c);
    }

    @Test
    void espaciosExtremosEInternosSeNormalizanComoLaApi() throws Exception {
        MockHttpSession s = login(A.codigo());
        crear(s, payload(Map.of("nombre", "  Viña   del\u00A0 Mar  "))).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.nombre").value("Viña del Mar"));
        assertThat(mapper.readTree(unica().cuerpo()).get("nombre").asString()).isEqualTo("Viña del Mar");
    }

    @Test
    void utf8SeConservaExacto() throws Exception {
        MockHttpSession s = login(A.codigo());
        String utf8 = limite >= 62 ? "Industrias «Ñuñoa» — R&D 5<6 a < b ✓ 😀 &lt;b&gt; O'Higgins \"Sur\""
                : "«Ñ» R&D 5<6 ✓😀&lt;\"";                   // 20 unidades UTF-16
        crear(s, payload(Map.of("nombre", utf8))).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data.nombre").value(utf8));
        assertThat(mapper.readTree(unica().cuerpo()).get("nombre").asString()).isEqualTo(utf8);
    }

    @Test
    void htmlYScriptSeRechazanSinLlamarUpstream() throws Exception {
        MockHttpSession s = login(A.codigo());
        // Variantes cortas (≤ 20) siempre; las largas solo si caben en el límite (si no, las corta antes el largo).
        List<String> malos = new ArrayList<>(List.of("<script>x</script>", "A <img src=x>", "</td><b>", "<!--x-->",
                "<svg/onload=1>", "<?xml?>", "x<a href=j:a>y"));
        for (String largo : List.of("<script>alert(1)</script>", "ACME <img src=x onerror=alert(1)>", "x<a href=javascript:alert(1)>y")) {
            if (largo.length() <= limite) {
                malos.add(largo);
            }
        }
        for (String malo : malos) {
            crear(s, payload(Map.of("nombre", malo))).andExpect(jsonPath("$.error").value(MSG_HTML));
        }
        assertThat(API.peticionesNoConformidades()).isEmpty();
    }

    @Test
    void caracteresDeControlYSustitutosSueltosSeRechazan() throws Exception {
        MockHttpSession s = login(A.codigo());
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
        MockHttpSession s = login(A.codigo());
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
        List<String> sinPermiso = new ArrayList<>(List.of("consulta1"));
        noPermitidos.forEach(e -> sinPermiso.add(e.codigo()));
        for (String u : sinPermiso) {
            crear(login(u), payload(Map.of())).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        }
        assertThat(API.peticionesNoConformidades()).isEmpty();
        for (Escritor e : permitidos) {
            crear(login(e.codigo()), payload(Map.of("nombre", "Cliente de " + e.codigo()))).andExpect(jsonPath("$.ok").value(true));
        }
        assertThat(policy.evaluar(CREAR, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        for (String rol : List.of("consulta", "operador", "admin", "admin_ti")) {
            if (!roles.contains(rol)) {
                assertThat(policy.evaluar(CREAR, usuario("INNPACK", rol))).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
            }
        }
        Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(CREAR)).findFirst().orElseThrow();
        assertThat(d.get("escritura")).isEqualTo(true);
        assertThat(d.get("roles")).isEqualTo(roles);
        assertThat(d.get("identidad")).isEqualTo(Map.of("creadoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO));
        // Ninguna otra escritura de catálogo: ni crear en catálogos no habilitados, ni editar/desactivar/eliminar.
        for (String cat : cl.faret.qccweb.bridge.handlers.NoConformidadesBridgeHandler.CATALOGOS) {
            for (String op : List.of("crear", "desactivar", "editar", "actualizar", "eliminar")) {
                String a = "noConformidades.catalogos." + cat + "." + op;
                if (!HABILITADAS.contains(a)) {
                    assertThat(policy.accionesRegistradas()).doesNotContain(a);
                }
            }
        }
        MockHttpSession s = login(A.codigo());
        crear(s, "{\"action\":\"noConformidades.catalogos.inexistente.crear\",\"nombre\":\"X\"}").andExpect(status().isForbidden());
        crear(s, "{\"action\":\"noConformidades.catalogos." + catalogo + ".desactivar\",\"id\":1}").andExpect(status().isForbidden());
        assertThat(API.catalogosRecibidos()).hasSize(permitidos.size());
    }

    // ------------------------------------------------------------ errores y sesiones

    @Test
    void errorDeNegocioYErrorInternoSinReintento() throws Exception {
        MockHttpSession s = login(A.codigo());
        crear(s, payload(Map.of("nombre", "ERROR_API"))).andExpect(jsonPath("$.error").value("No se pudo crear el valor"));
        crear(s, payload(Map.of("nombre", "ERROR_500"))).andExpect(jsonPath("$.error").value("Error al comunicarse con la API Innpack"));
        assertThat(API.catalogosRecibidos()).hasSize(2);
    }

    @Test
    void unauthorizedUpstreamYCsrf() throws Exception {
        MockHttpSession a = login(A.codigo());
        MockHttpSession b = login(B.codigo());
        mockMvc.perform(post("/api/v1/bridge").session(b).contentType(MediaType.APPLICATION_JSON).content(payload(Map.of())))
                .andExpect(status().isForbidden());
        assertThat(API.peticionesNoConformidades()).isEmpty();
        API.revocarTokens(A.sub());
        crear(a, payload(Map.of())).andExpect(status().isUnauthorized());
        assertThat(a.isInvalid()).isTrue();
        crear(b, payload(Map.of())).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.catalogosRecibidos()).hasSize(1); // el 401 no llegó a crear nada
        assertThat(API.catalogosRecibidos().get(0).sub()).isEqualTo(B.sub());
    }

    @Test
    void dosSesionesEnParaleloCadaValorConSuCreador() throws Exception {
        MockHttpSession a = login(A.codigo());
        MockHttpSession b = login(B.codigo());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tareas = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                boolean esA = i % 2 == 0;
                String nombre = "Cliente paralelo " + i;
                tareas.add(() -> json(crear(esA ? a : b, payload(Map.of("nombre", nombre, "creadoPor", esA ? B.nombre() : A.nombre())))
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
            assertThat(c.get("creadoPor").asString()).isEqualTo(x.sub() == A.sub() ? A.nombre() : B.nombre());
            int n = Integer.parseInt(c.get("nombre").asString().substring("Cliente paralelo ".length()));
            assertThat(x.sub()).isEqualTo(n % 2 == 0 ? A.sub() : B.sub());
        }
        assertThat(json(listar(a).andReturn()).get("data").size()).isEqualTo(21);
    }

    // ------------------------------------------------------------------ auditoría

    @Test
    void auditoriaConCatalogoEIdCreadoSinElNombre(CapturedOutput salida) throws Exception {
        MockHttpSession s = login(A.codigo());
        crear(s, payload(Map.of("nombre", "Sens 12.345.678-9"))).andExpect(jsonPath("$.ok").value(true));
        crear(s, payload(Map.of("nombre", "ERROR_API"))).andExpect(jsonPath("$.ok").value(false));
        crear(s, payload(Map.of("nombre", "<b>x</b>"))).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=" + A.codigo() + " empresa=INNPACK accion=noConformidades\\.catalogos\\." + catalogo + "\\.crear "
                + "recurso=catalogo:" + catalogo + ":901 resultado=OK ms=\\d+");
        assertThat(log).contains("recurso=catalogo:" + catalogo + " resultado=ERROR");
        assertThat(log).doesNotContain("Sens 12.345.678-9", "12.345.678-9", "<b>x</b>", FakeInnpackApi.firmaDeToken(A.sub()), PASS);
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
