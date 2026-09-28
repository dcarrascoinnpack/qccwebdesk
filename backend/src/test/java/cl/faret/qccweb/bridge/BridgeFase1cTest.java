package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import cl.faret.qccweb.auth.FakeInnpackApi.ModoDashboard;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
 * Fase 1c — bridge seguro + inicio.getDashboard. API INNPACK SIMULADA (sin credenciales reales).
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase1cTest {

    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveBridge#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(20, "admin1", PASS, "Admin Uno", "admin", true));
        // Rol que existe en la BD pero que la ActionPolicy no conoce: debe quedar denegado.
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

    // ------------------------------------------------------------------- acción permitida

    @Test
    void accionPermitidaRespondeComoPhotino() throws Exception {
        MockHttpSession sesion = login("operador1");
        MvcResult res = bridge(sesion, "{\"action\":\"inicio.getDashboard\"}")
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = mapper.readTree(res.getResponse().getContentAsString());
        // Forma exacta de MessageRouter.NormalizeResponse: { ok, success, data, error }, en ese orden.
        assertThat(nombresDeCampos(json)).containsExactly("ok", "success", "data", "error");
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("error").isNull()).isTrue();
        // data pasa tal cual desde la API (passthrough, igual que HomeHandler.Forward).
        assertThat(json.at("/data/kpis/controlesHoy").asInt()).isEqualTo(42);
        assertThat(json.at("/data/kpis/mermaHoy").asDouble()).isEqualTo(12.5);
        assertThat(json.at("/data/usuarioDelToken").asInt()).isEqualTo(10);
        assertThat(res.getResponse().getContentAsString()).doesNotContain(FakeInnpackApi.firmaDeToken(10));
    }

    @Test
    void errorDeNegocioDeLaApiSeMuestraComoEnPhotino() throws Exception {
        MockHttpSession sesion = login("operador1");
        API.modoDashboard(ModoDashboard.ERROR_NEGOCIO);
        bridge(sesion, "{\"action\":\"inicio.getDashboard\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error").value("No se pudo calcular el dashboard"));
    }

    @Test
    void error500DeLaApiNoFiltraDetallesInternos() throws Exception {
        MockHttpSession sesion = login("operador1");
        API.modoDashboard(ModoDashboard.ERROR_500);
        MvcResult res = bridge(sesion, "{\"action\":\"inicio.getDashboard\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Error al comunicarse con la API Innpack"))
                .andReturn();
        assertThat(res.getResponse().getContentAsString()).doesNotContain("SqlException", "calidad_db");
    }

    // --------------------------------------------------------------- deny-by-default

    @Test
    void accionDesconocidaORegistradaEnPhotinoPeroNoHabilitadaSeRechaza() throws Exception {
        MockHttpSession sesion = login("admin1");
        int antes = API.llamadasDashboard();
        for (String accion : List.of("usuarios.create", "inicio.frecuencias.actualizar", "excel.guardar",
                "noConformidades.eliminar", "accion.inventada", "Inicio.getDashboard", "inicio.getdashboard")) {
            bridge(sesion, "{\"action\":\"" + accion + "\"}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        }
        assertThat(API.llamadasDashboard()).isEqualTo(antes);
    }

    @Test
    void payloadMalformadoSeRechaza() throws Exception {
        MockHttpSession sesion = login("operador1");
        for (String cuerpo : List.of("{}", "[]", "\"inicio.getDashboard\"", "{\"action\":123}",
                "{\"action\":\"../inicio.getDashboard\"}", "{\"action\":\"inicio\"}", "{\"action\":null}")) {
            bridge(sesion, cuerpo).andExpect(status().isBadRequest()).andExpect(jsonPath("$.ok").value(false));
        }
    }

    @Test
    void accionConocidaPeroRolNoAutorizadoSeRechaza() throws Exception {
        MockHttpSession sesion = login("consulta1");
        int antes = API.llamadasDashboard();
        bridge(sesion, "{\"action\":\"inicio.getDashboard\"}").andExpect(status().isForbidden());
        assertThat(API.llamadasDashboard()).isEqualTo(antes);
    }

    @Test
    void sinSesionSeRechazaConContrato401() throws Exception {
        mockMvc.perform(post("/api/v1/bridge").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"inicio.getDashboard\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void soloAccionesHabilitadasHastaFase3n() {
        assertThat(policy.accionesRegistradas()).containsExactlyInAnyOrder(
                "inicio.getDashboard", "maquinasSeguimiento.obtenerResumen",
                "dashboard.obtenerFiltros", "dashboard.obtenerResumen",
                "registrosProduccion.obtenerFiltros", "registrosProduccion.obtenerResumen",
                "registrosControl.obtenerRegistros",
                "productoTerminado.filtros", "productoTerminado.resumen", "productoTerminado.list",
                "productoTerminado.detalle", "productoTerminado.exportarDetalle",
                "certificadosLiberacion.buscar", "certificadosLiberacion.calidadPdf.descargar",
                "controlDocumental.list", "controlDocumental.get", "controlDocumental.adjunto.abrir",
                "noConformidades.list", "noConformidades.resumen", "noConformidades.filtrosOpciones",
                "noConformidades.get", "noConformidades.seguimiento.list", "noConformidades.analisis.get",
                "noConformidades.acciones.list", "noConformidades.adjuntos.list", "noConformidades.adjuntos.abrir",
                "noConformidades.catalogos.clientes.list", "noConformidades.catalogos.categoriasDefecto.list",
                "noConformidades.catalogos.tiposFalla.list", "noConformidades.catalogos.supervisores.list",
                "noConformidades.catalogos.revisores.list", "noConformidades.catalogos.areas.list",
                "noConformidades.catalogos.familiasProducto.list", "noConformidades.catalogos.niveles.list",
                "noConformidades.catalogos.impactos.list",
                "recepcion.list", "recepcion.detalle", "recepcion.foto.abrir",
                "usuarios.list",
                "muestraLab.list", "muestraLab.detalle", "muestraLab.catalogos", "muestraLab.indicadores",
                "muestraLab.metodo.list", "muestraLab.especificacion.list", "muestraLab.bobinaHistorial",
                "muestraLab.registroProduccion.list", "muestraLab.adjunto.abrir",
                "talleresExternos.list", "talleresExternos.catalogos", "talleresExternos.historialLiberaciones",
                "noConformidades.seguimiento.crear", "noConformidades.acciones.crear", "noConformidades.analisis.guardar",
                "noConformidades.catalogos.clientes.crear", "noConformidades.catalogos.categoriasDefecto.crear",
                "noConformidades.catalogos.tiposFalla.crear", "noConformidades.catalogos.supervisores.crear",
                "noConformidades.catalogos.revisores.crear", "noConformidades.catalogos.areas.crear",
                "noConformidades.catalogos.familiasProducto.crear", "noConformidades.catalogos.impactos.crear",
                "noConformidades.catalogos.niveles.crear", "noConformidades.create", "noConformidades.adjuntos.subir",
                "noConformidades.update", "noConformidades.gestion.actualizar", "noConformidades.cerrar",
                "noConformidades.acciones.actualizar");
    }

    // ------------------------------------------------------ manipulación desde DevTools

    @Test
    void identidadEnviadaPorElNavegadorNoTieneEfecto() throws Exception {
        MockHttpSession sesion = login("operador1");
        String manipulado = "{\"action\":\"inicio.getDashboard\",\"usuarioId\":20,\"rol\":\"admin\","
                + "\"empresa\":\"FARET\",\"creadoPor\":\"Admin Uno\",\"rolUsuario\":\"admin_ti\","
                + "\"data\":{\"usuarioId\":20,\"userId\":20,\"rol\":\"admin\",\"empresa\":\"FARET\",\"token\":\"x\"}}";
        mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                        .header("X-Rol", "admin").header("X-Empresa", "FARET").header("Authorization", "Bearer robado")
                        .contentType(MediaType.APPLICATION_JSON).content(manipulado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usuarioDelToken").value(10));
        assertThat(API.authorizationRecibidos()).allMatch(a -> a.endsWith(FakeInnpackApi.firmaDeToken(10)));

        // Un rol no autorizado sigue denegado aunque el payload diga "admin".
        MockHttpSession consulta = login("consulta1");
        bridge(consulta, manipulado).andExpect(status().isForbidden());
    }

    // --------------------------------------------------------------- aislamiento A/B

    @Test
    void sesionesSimultaneasUsanCadaUnaSuPropioJwt() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<int[]>> tareas = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                boolean esA = i % 2 == 0;
                tareas.add(() -> {
                    String body = bridge(esA ? a : b, "{\"action\":\"inicio.getDashboard\"}")
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                    return new int[] {esA ? 10 : 20, mapper.readTree(body).at("/data/usuarioDelToken").asInt()};
                });
            }
            for (Future<int[]> f : pool.invokeAll(tareas)) {
                int[] esperadoYRecibido = f.get();
                assertThat(esperadoYRecibido[1]).isEqualTo(esperadoYRecibido[0]);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(API.authorizationRecibidos()).hasSize(40)
                .allMatch(h -> h.endsWith(FakeInnpackApi.firmaDeToken(10)) || h.endsWith(FakeInnpackApi.firmaDeToken(20)));
    }

    // ------------------------------------------------------------------- 401 upstream

    @Test
    void unauthorizedUpstreamInvalidaSoloEsaSesion() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        API.revocarTokens(10);

        bridge(a, "{\"action\":\"inicio.getDashboard\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_SESION_EXPIRADA));
        assertThat(a.isInvalid()).isTrue();

        bridge(b, "{\"action\":\"inicio.getDashboard\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usuarioDelToken").value(20));
        mockMvc.perform(get("/api/v1/auth/session").session(b))
                .andExpect(jsonPath("$.data.CodigoUsuario").value("admin1"));
    }

    // -------------------------------------------------------------------------- CSRF

    @Test
    void bridgeSinCsrfSeRechazaAunConSesion() throws Exception {
        MockHttpSession sesion = login("operador1");
        int antes = API.llamadasDashboard();
        mockMvc.perform(post("/api/v1/bridge").session(sesion).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"inicio.getDashboard\"}"))
                .andExpect(status().isForbidden());
        assertThat(API.llamadasDashboard()).isEqualTo(antes);
    }

    // ------------------------------------------------------------------ logs / secretos

    @Test
    void niJwtNiContrasenaEnLogsNiRespuestas(CapturedOutput salida) throws Exception {
        MockHttpSession sesion = login("operador1");
        String body = bridge(sesion, "{\"action\":\"inicio.getDashboard\"}").andReturn().getResponse().getContentAsString();
        bridge(sesion, "{\"action\":\"usuarios.create\"}");

        assertThat(body).doesNotContain(FakeInnpackApi.firmaDeToken(10), "eyJ");
        assertThat(salida.getAll())
                .contains("evento=ACCION ", "accion=inicio.getDashboard", "evento=ACCION_DENEGADA", "motivo=ACCION_NO_REGISTRADA")
                .doesNotContain(FakeInnpackApi.firmaDeToken(10), PASS);
    }

    // ------------------------------------------------------------------------ helpers

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.1.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private ResultActions bridge(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private static List<String> nombresDeCampos(JsonNode json) {
        List<String> nombres = new ArrayList<>();
        json.propertyNames().forEach(nombres::add);
        return nombres;
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-bridge");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
