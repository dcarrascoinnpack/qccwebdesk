package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeFaretApi;
import cl.faret.qccweb.auth.FakeFaretSinAuthApi;
import cl.faret.qccweb.auth.FakeInnpackApi;
import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.handlers.FaretBridgeHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
 * Fase 6c — lecturas FARET restantes (Photino 1.8.15, 9e1b556, FaretHandler.cs), solo GET, payload PLANO. A: catálogos
 * (areas, operadores, maquinas) y catálogos PNC (QualityControlFaret.Api con Bearer). B: no conformidades (MejoraContinua, sin Authorization).
 * C: talleres externos / registros / importación / data.resumen (Bearer). D: Calidad (sin Authorization). TODAS las APIs son
 * fakes en localhost.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFase6cTest {

    private static final FakeFaretApi QC = new FakeFaretApi(Clock.systemUTC());
    private static final FakeInnpackApi INNPACK = new FakeInnpackApi(Clock.systemUTC());
    private static final FakeFaretSinAuthApi MC = new FakeFaretSinAuthApi("/mejora-continua");
    private static final FakeFaretSinAuthApi CALIDAD = new FakeFaretSinAuthApi("/calidad/api");
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveFaret6c#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        QC.agregar(new FakeFaretApi.Usuario(10, "admin", "admin@faret.cl", PASS, "Admin Faret", List.of("ADMIN")));
        QC.agregar(new FakeFaretApi.Usuario(20, "ana", "ana@faret.cl", PASS, "Ana Calidad", List.of("CALIDAD")));
        QC.agregar(new FakeFaretApi.Usuario(30, "clara", null, PASS, "Clara Consulta", List.of("CONSULTA")));
        INNPACK.agregar(new FakeInnpackApi.Usuario(40, "operador1", PASS, "Operador Uno", "operador", true));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.faret-api-base-url", QC::baseUrl);
        registry.add("qcc.web.auth.innpack-api-base-url", INNPACK::baseUrl);
        registry.add("qcc.web.faret.mejora-continua-base-url", MC::baseUrl);
        registry.add("qcc.web.faret.calidad-base-url", CALIDAD::baseUrl);
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
        QC.limpiarLecturas();
        MC.limpiar();
        CALIDAD.limpiar();
    }

    @AfterAll
    static void cerrar() {
        QC.close();
        INNPACK.close();
        MC.close();
        CALIDAD.close();
    }

    // ----------------------------------------------------------------------------- A: catálogos

    private static final List<String> CATALOGOS_SIMPLES = List.of("areas");
    private static final List<String[]> PNC = List.of(
            new String[] {"clientes", "clientes"}, new String[] {"categoriasDefecto", "categorias-defecto"},
            new String[] {"tiposFalla", "tipos-falla"}, new String[] {"supervisores", "supervisores"},
            new String[] {"revisores", "revisores"}, new String[] {"familiasProducto", "familias-producto"},
            new String[] {"niveles", "niveles"}, new String[] {"impactos", "impactos"});

    @Test
    void catalogoAreasVaASuRutaConElBearerDeLaSesionYDesenvuelveElApiResponse() throws Exception {
        MockHttpSession sesion = login("ana");
        for (String c : CATALOGOS_SIMPLES) {
            // El payload (incluido un area/ruta/empresa del navegador) no llega a la URL.
            JsonNode json = json(accion(sesion, "{\"action\":\"faret.catalogos." + c + "\",\"_modulo\":\"faret-nc\",\"areaId\":5,\"ruta\":\"/x\",\"empresa\":\"INNPACK\"}")
                    .andExpect(status().isOk()).andReturn());
            assertThat(json.get("ok").asBoolean()).isTrue();
            assertThat(json.get("data").toString()).isEqualTo("{\"ruta\":\"/api/catalogos/" + c + "\",\"usuario\":20}");
        }
        assertThat(QC.lecturas()).containsExactly("GET /api/catalogos/areas");
        assertThat(QC.lecturasDeUsuario()).containsExactly(20);
        assertThat(MC.peticiones()).isEmpty();
        assertThat(CALIDAD.peticiones()).isEmpty();
    }

    @Test
    void operadoresYMaquinasLlevanAreaIdSoloSiEsEnteroPositivo() throws Exception {
        MockHttpSession sesion = login("admin");
        for (String c : List.of("operadores", "maquinas")) {
            for (String extra : List.of("\"areaId\":7", "\"areaId\":\" 8 \"", "\"areaId\":0", "\"areaId\":-3", "\"areaId\":\"abc\"", "\"areaId\":1.5",
                    "\"areaId\":null", "\"areaId\":99999999999", "\"x\":1")) {
                accion(sesion, "{\"action\":\"faret.catalogos." + c + "\",\"_modulo\":\"faret-nc\"," + extra + "}").andExpect(jsonPath("$.ok").value(true));
            }
        }
        List<String> esperadas = new java.util.ArrayList<>();
        for (String c : List.of("operadores", "maquinas")) {
            String ruta = "GET /api/catalogos/" + c;
            esperadas.add(ruta + "?areaId=7");
            esperadas.add(ruta + "?areaId=8");
            for (int i = 0; i < 7; i++) {
                esperadas.add(ruta);
            }
        }
        assertThat(QC.lecturas()).containsExactlyElementsOf(esperadas);
        // bool / objeto / array en areaId (regla 2e): rechazado sin llamar a la API.
        QC.limpiarLecturas();
        for (String valor : List.of("true", "false", "{\"a\":1}", "[1]", "[]")) {
            accion(sesion, "{\"action\":\"faret.catalogos.operadores\",\"_modulo\":\"faret-nc\",\"areaId\":" + valor + "}")
                    .andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
        }
        assertThat(QC.lecturas()).isEmpty();
    }

    @Test
    void losOchoCatalogosPncVanAlSegmentoGuionadoDeLaApi() throws Exception {
        MockHttpSession sesion = login("clara");
        for (String[] c : PNC) {
            JsonNode json = json(accion(sesion, "{\"action\":\"faret.pncCatalogos." + c[0] + ".list\",\"_modulo\":\"faret-nc\",\"id\":3,\"nombre\":\"x\"}")
                    .andExpect(status().isOk()).andReturn());
            assertThat(json.get("data").toString()).isEqualTo("{\"ruta\":\"/api/pnc-catalogos/" + c[1] + "\",\"usuario\":30}");
        }
        assertThat(QC.lecturas()).containsExactly(PNC.stream().map(c -> "GET /api/pnc-catalogos/" + c[1]).toArray(String[]::new));
        // Acciones que el frontend de Photino 9e1b556 ya no usa: no se habilitan (el contract check las marcaría SOLO_WEB).
        for (String a : List.of("faret.catalogos.inspectores", "faret.catalogos.defectos", "faret.health")) {
            accion(sesion, "{\"action\":\"" + a + "\",\"_modulo\":\"faret-nc\"}").andExpect(status().isForbidden());
        }
        // crear / desactivar siguen denegadas (son escrituras, fuera de esta fase).
        for (String a : List.of("faret.pncCatalogos.clientes.crear", "faret.pncCatalogos.clientes.desactivar", "faret.catalogos.areas.crear",
                "faret.catalogos.operadores.crear", "faret.catalogos.maquinas.crear")) {
            accion(sesion, "{\"action\":\"" + a + "\",\"_modulo\":\"faret-nc\"}").andExpect(status().isForbidden());
        }
        assertThat(QC.lecturas()).hasSize(PNC.size());
    }

    @Test
    void catalogosErroresDeLaApiYCuerposNoReconocidos() throws Exception {
        MockHttpSession sesion = login("admin");
        QC.respuestaLectura("/api/catalogos/areas", 403, "{\"success\":false,\"message\":\"Sin permiso para ver áreas\",\"data\":null}");
        accion(sesion, "{\"action\":\"faret.catalogos.areas\",\"_modulo\":\"faret-nc\"}").andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Sin permiso para ver áreas"));
        QC.respuestaLectura("/api/catalogos/maquinas", 500, "<html>boom</html>");
        MvcResult r = accion(sesion, "{\"action\":\"faret.catalogos.maquinas\",\"_modulo\":\"faret-nc\"}").andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret")).andReturn();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("boom");
        QC.respuestaLectura("/api/pnc-catalogos/niveles", 200, "{\"success\":true,\"data\":[{\"id\":1,\"nombre\":\"Alto\"}],\"errors\":null}");
        accion(sesion, "{\"action\":\"faret.pncCatalogos.niveles.list\",\"_modulo\":\"faret-nc\"}").andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data[0].nombre").value("Alto"));
    }

    @Test
    void faseARolesEmpresaYModulo() throws Exception {
        List<String> acciones = new java.util.ArrayList<>();
        CATALOGOS_SIMPLES.forEach(c -> acciones.add("faret.catalogos." + c));
        acciones.add("faret.catalogos.operadores");
        acciones.add("faret.catalogos.maquinas");
        PNC.forEach(c -> acciones.add("faret.pncCatalogos." + c[0] + ".list"));
        assertThat(acciones).hasSize(11);
        for (String a : acciones) {
            for (String rol : List.of("ADMIN", "ADMIN_TI", "CALIDAD", "INSPECTOR", "CONSULTA")) {
                assertThat(policy.evaluar(a, usuario("FARET", rol))).as(a + " " + rol).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            }
            assertThat(policy.evaluar(a, usuario("FARET", "operador"))).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
            assertThat(policy.evaluar(a, usuario("INNPACK", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
            assertThat(PermisosModulo.esLectura(a)).as(a).isTrue();
        }
        // Permisos por módulo de Photino: CALIDAD no tiene faret-data; sin módulo declarado tampoco.
        accion(login("ana"), "{\"action\":\"faret.catalogos.areas\",\"_modulo\":\"faret-data\"}").andExpect(status().isForbidden());
        accion(login("ana"), "{\"action\":\"faret.catalogos.areas\"}").andExpect(status().isForbidden());
        accion(login("clara"), "{\"action\":\"faret.catalogos.areas\",\"_modulo\":\"faret-nc\"}").andExpect(status().isOk());
        assertThat(QC.lecturas()).containsExactly("GET /api/catalogos/areas");
        QC.limpiarLecturas();
        accion(loginInnpack("operador1"), "{\"action\":\"faret.catalogos.areas\",\"_modulo\":\"faret-nc\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(QC.lecturas()).isEmpty();
    }

    // ----------------------------------------------------------------------------- helpers

    private static SessionUser usuario(String empresa, String rol) {
        return new SessionUser(1, "u", "U", rol, empresa, "t", Instant.now(), Instant.now().plusSeconds(60));
    }

    private MockHttpSession login(String identificador) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/faret/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.78.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identificador\":\"" + identificador + "\",\"password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private MockHttpSession loginInnpack(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.78.1." + IP.getAndIncrement());
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

    private static Path crearWww() {
        try {
            Path dir = Files.createTempDirectory("qcc-web-fixture-6c");
            Files.writeString(dir.resolve("index.html"), "<!doctype html><title>fixture</title>");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
