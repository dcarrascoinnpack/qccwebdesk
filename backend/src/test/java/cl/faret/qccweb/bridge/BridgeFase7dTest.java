package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * Photino 1.8.23 — formularios.eliminar: borrado FISICO e irreversible, solo ADMIN_TI (PermisosService) y sesion INNPACK.
 * Lista cerrada de claves, tipo del mapa cerrado, id entero positivo antes de la ruta, auditoria con el recurso. API SIMULADA.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase7dTest {

    private static final String MSG_ADMIN_TI = "Acceso no autorizado: solo ADMIN_TI puede eliminar formularios.";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveEliminar#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(20, "admin1", PASS, "Admin Uno", "admin", true));
        API.agregar(new FakeInnpackApi.Usuario(40, "ti1", PASS, "TI Uno", "admin_ti", true));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.innpack-api-base-url", API::baseUrl);
        registry.add("qcc.web.www-dir", WWW::toString);
        registry.add("qcc.web.manifest-file", () -> WWW.resolve("no-existe.json").toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reiniciar() {
        API.reiniciarDashboard();
        API.limpiarFormularios();
    }

    @AfterAll
    static void cerrar() {
        API.close();
    }

    private static String eliminar(String data) {
        return "{\"action\":\"formularios.eliminar\",\"_modulo\":\"formularios\",\"data\":" + data + "}";
    }

    @Test
    void adminTiEliminaCadaTipoYQuedaAuditado(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("ti1");
        accion(s, eliminar("{\"tipo\":\"inspeccionesVehiculares\",\"id\":15}")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        accion(s, eliminar("{\"tipo\":\"revisionCamionJornada\",\"id\":\"7\"}")).andExpect(jsonPath("$.ok").value(true));
        accion(s, eliminar("{\"tipo\":\"checklistBodegaCajas\",\"id\":3}")).andExpect(jsonPath("$.ok").value(true));
        accion(s, eliminar("{\"tipo\":\"revisionBodegaOficinas\",\"id\":4}")).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.peticionesFormularios()).containsExactly(
                "DELETE /api/formularios/inspecciones-vehiculares/15", "DELETE /api/formularios/revision-camion-jornada/7",
                "DELETE /api/formularios/checklist-bodega-cajas/3", "DELETE /api/formularios/revision-bodega-oficinas/4");
        assertThat(salida.getAll()).contains("formularios:inspecciones-vehiculares:15:eliminar");
    }

    @Test
    void errorDeLaApiSePropagaSinReintento() throws Exception {
        MockHttpSession s = login("ti1");
        accion(s, eliminar("{\"tipo\":\"inspeccionesVehiculares\",\"id\":404}")).andExpect(jsonPath("$.error").value("El registro no existe"));
        assertThat(API.peticionesFormularios()).hasSize(1);
    }

    @Test
    void datosInvalidosSeRechazanAntesDeLlamarALaApi() throws Exception {
        MockHttpSession s = login("ti1");
        for (String malo : new String[] {"{\"tipo\":\"otro\",\"id\":1}", "{\"id\":1}", "{\"tipo\":\"../x\",\"id\":1}"}) {
            accion(s, eliminar(malo)).andExpect(jsonPath("$.error").value("Formulario no soportado"));
        }
        for (String id : new String[] {"0", "-3", "\"abc\"", "1.5", "true", "null", "[1]", "{}", "99999999999"}) {
            accion(s, eliminar("{\"tipo\":\"inspeccionesVehiculares\",\"id\":" + id + "}")).andExpect(jsonPath("$.error").value("ID de registro inválido"));
        }
        accion(s, eliminar("{\"tipo\":\"inspeccionesVehiculares\"}")).andExpect(jsonPath("$.error").value("ID de registro inválido"));
        accion(s, eliminar("{\"tipo\":\"inspeccionesVehiculares\",\"id\":1,\"empresa\":\"FARET\"}")).andExpect(jsonPath("$.ok").value(false));
        assertThat(API.peticionesFormularios()).isEmpty();
    }

    @Test
    void soloAdminTi() throws Exception {
        for (String usuario : new String[] {"admin1", "operador1"}) {
            MockHttpSession s = login(usuario);
            accion(s, eliminar("{\"tipo\":\"inspeccionesVehiculares\",\"id\":15}")).andExpect(status().isForbidden());
        }
        assertThat(API.peticionesFormularios()).isEmpty();
        // Mismo mensaje de Photino a nivel de PermisosModulo.
        assertThat(PermisosModulo.validar("formularios.eliminar", "formularios",
                new cl.faret.qccweb.auth.SessionUser(1, "x", "X", "admin", "INNPACK", "t", java.time.Instant.now(),
                        java.time.Instant.now().plusSeconds(60), java.util.Map.of(), false)))
                .isEqualTo(MSG_ADMIN_TI);
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.57.0." + IP.getAndIncrement());
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
            Path dir = Files.createTempDirectory("qcc-web-fixture-7d");
            Files.writeString(dir.resolve("index.html"), "<!doctype html><title>fixture</title>");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
