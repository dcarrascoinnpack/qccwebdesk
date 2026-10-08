package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import cl.faret.qccweb.auth.FakeInnpackApi.ModoDashboard;
import cl.faret.qccweb.bridge.handlers.FormulariosBridgeHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
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
 * Fase 5a-2 — Formularios (Photino 1.8.15, 9e1b556): list / detalle / abrirPdf, SOLO LECTURA. Mismo contrato que
 * FormulariosHandler.cs + InnpackFormulariosApiService (tipo cerrado → ruta, 6 filtros, detalle sin
 * revisionCamionJornada, URL de PDF solo https hacia solicitudes.faret.cl). API SIMULADA.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFase5aTest {

    private static final String LIST = "formularios.list";
    private static final String DETALLE = "formularios.detalle";
    private static final String ABRIR_PDF = "formularios.abrirPdf";
    private static final String BASE = "GET /api/formularios";
    private static final String PDF_OK = "https://solicitudes.faret.cl/formularios/pdf/insp_15.pdf";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveFormularios#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(20, "admin1", PASS, "Admin Uno", "admin", true));
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
        API.limpiarFormularios();
    }

    @AfterAll
    static void cerrar() {
        API.close();
    }

    // ----------------------------------------------------------------------------- list

    @Test
    void listConLosSeisFiltrosEnBlancoComoLaPantallaNoMandaQuery() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode json = json(accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":{\"tipo\":\"inspeccionesVehiculares\",\"fechaDesde\":\"\","
                + "\"fechaHasta\":\"\",\"patente\":\"\",\"conductor\":\"\",\"responsable\":\"\",\"estado\":\"\"}}")
                .andExpect(status().isOk()).andReturn());
        List<String> campos = new ArrayList<>();
        json.propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("ok", "success", "data", "error");
        assertThat(json.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataFormularios("inspecciones-vehiculares", "")));
        // El controller de Photino escapa en pantalla (esc) y la API no transforma: el gateway reenvía tal cual.
        assertThat(json.at("/data/0/responsable").asString()).isEqualTo("Resp <b>Uno</b>");
        assertThat(json.at("/data/0/pdfUrl").asString()).isEqualTo(PDF_OK);
        assertThat(API.peticionesFormularios()).containsExactly(BASE + "/inspecciones-vehiculares");
    }

    @Test
    void losCuatroTiposDelSelectMapeanASuRutaYNingunOtroValorLlegaALaUrl() throws Exception {
        MockHttpSession sesion = login("admin1");
        String[][] tipos = {
            {"inspeccionesVehiculares", "inspecciones-vehiculares"},
            {"revisionCamionJornada", "revision-camion-jornada"},
            {"checklistBodegaCajas", "checklist-bodega-cajas"},
            {"revisionBodegaOficinas", "revision-bodega-oficinas"},
        };
        for (String[] t : tipos) {
            JsonNode json = json(accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":{\"tipo\":\"" + t[0] + "\"}}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true)).andReturn());
            assertThat(json.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataFormularios(t[1], "")));
        }
        assertThat(API.peticionesFormularios()).containsExactly(
                BASE + "/inspecciones-vehiculares", BASE + "/revision-camion-jornada", BASE + "/checklist-bodega-cajas",
                BASE + "/revision-bodega-oficinas");
        API.limpiarFormularios();
        // Nada del navegador se concatena a la URL sin pasar por el mapa (mismo mensaje que el handler C#).
        for (String tipo : List.of("\"\"", "null", "\"inspecciones-vehiculares\"", "\"InspeccionesVehiculares\"", "\"../usuarios\"",
                "\"inspeccionesVehiculares/1\"", "\" inspeccionesVehiculares\"", "true", "{\"a\":1}", "[\"inspeccionesVehiculares\"]", "7")) {
            accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":{\"tipo\":" + tipo + "}}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(FormulariosBridgeHandler.MENSAJE_TIPO));
        }
        accion(sesion, "{\"action\":\"" + LIST + "\"}").andExpect(jsonPath("$.error").value(FormulariosBridgeHandler.MENSAJE_TIPO));
        accion(sesion, "{\"action\":\"" + LIST + "\",\"tipo\":\"inspeccionesVehiculares\"}")
                .andExpect(jsonPath("$.error").value(FormulariosBridgeHandler.MENSAJE_TIPO));
        assertThat(API.peticionesFormularios()).isEmpty();
    }

    /** Mismas reglas que InnpackFormulariosApiService.ListAsync (orden fijo, trim, EscapeDataString, solo no vacíos). */
    @Test
    void mapeoDeFiltrosOrdenTrimYEscapadoIgualQuePhotino() throws Exception {
        MockHttpSession sesion = login("operador1");
        String prefijo = "{\"action\":\"" + LIST + "\",\"data\":{\"tipo\":\"inspeccionesVehiculares\",";
        String[][] casos = {
            {"\"patente\":\"ABCD12\"", "?patente=ABCD12"},
            {"\"patente\":\"  ABCD12 \"", "?patente=ABCD12"},
            {"\"estado\":\"guardado\"", "?estado=guardado"},
            // combinado desordenado → orden fijo de Photino (CamposFiltro)
            {"\"estado\":\"guardado\",\"responsable\":\"Ana María\",\"conductor\":\"Juan Pérez & Cía / 100%\",\"patente\":\"AB-12\","
                + "\"fechaHasta\":\"2026-10-08\",\"fechaDesde\":\"2026-10-01\"",
                "?fechaDesde=2026-10-01&fechaHasta=2026-10-08&patente=AB-12&conductor=Juan%20P%C3%A9rez%20%26%20C%C3%ADa%20%2F%20100%25"
                    + "&responsable=Ana%20Mar%C3%ADa&estado=guardado"},
            {"\"conductor\":\"a&estado=x\"", "?conductor=a%26estado%3Dx"},
            {"\"patente\":\"a-b_c.d~e\"", "?patente=a-b_c.d~e"},
            // vacíos / blancos / null se omiten; número → texto (GetString de Photino)
            {"\"patente\":\"   \",\"conductor\":null,\"responsable\":\"\"", ""},
            {"\"patente\":1234", "?patente=1234"},
        };
        for (String[] caso : casos) {
            accion(sesion, prefijo + caso[0] + "}}").andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        }
        List<String> esperadas = new ArrayList<>();
        for (String[] caso : casos) {
            esperadas.add(BASE + "/inspecciones-vehiculares" + caso[1]);
        }
        assertThat(API.peticionesFormularios()).containsExactlyElementsOf(esperadas);
    }

    /** Defensas transparentes de la web (nunca alcanzables desde la pantalla de Photino): no llegan a la API. */
    @Test
    void filtrosInvalidosSeRechazanAntesDeLlamarALaApi() throws Exception {
        MockHttpSession sesion = login("operador1");
        String prefijo = "{\"action\":\"" + LIST + "\",\"data\":{\"tipo\":\"inspeccionesVehiculares\",";
        String[][] casos = {
            {"\"patente\":true", FormulariosBridgeHandler.MENSAJE_FILTRO_INVALIDO},
            {"\"conductor\":{\"a\":1}", FormulariosBridgeHandler.MENSAJE_FILTRO_INVALIDO},
            {"\"responsable\":[\"x\"]", FormulariosBridgeHandler.MENSAJE_FILTRO_INVALIDO},
            {"\"fechaDesde\":false", FormulariosBridgeHandler.MENSAJE_FILTRO_INVALIDO},
            {"\"patente\":\"AB\\u0000CD\"", FormulariosBridgeHandler.MENSAJE_FILTRO_INVALIDO},
            {"\"conductor\":\"linea1\\nlinea2\"", FormulariosBridgeHandler.MENSAJE_FILTRO_INVALIDO},
            {"\"responsable\":\"" + "x".repeat(201) + "\"", FormulariosBridgeHandler.MENSAJE_FILTRO_LARGO},
            {"\"fechaDesde\":\"08-10-2026\"", "La fecha fechaDesde no es válida (formato AAAA-MM-DD)."},
            {"\"fechaHasta\":\"2026-13-01\"", "La fecha fechaHasta no es válida (formato AAAA-MM-DD)."},
            {"\"fechaHasta\":\"2026-02-30\"", "La fecha fechaHasta no es válida (formato AAAA-MM-DD)."},
            {"\"estado\":\"borrador\"", FormulariosBridgeHandler.MENSAJE_ESTADO_INVALIDO},
            {"\"estado\":\"GUARDADO\"", FormulariosBridgeHandler.MENSAJE_ESTADO_INVALIDO},
        };
        for (String[] caso : casos) {
            accion(sesion, prefijo + caso[0] + "}}").andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(caso[1]));
        }
        // 200 caracteres exactos sí pasan.
        accion(sesion, prefijo + "\"responsable\":\"" + "y".repeat(200) + "\"}}").andExpect(jsonPath("$.ok").value(true));
        assertThat(API.peticionesFormularios()).containsExactly(BASE + "/inspecciones-vehiculares?responsable=" + "y".repeat(200));
    }

    // ----------------------------------------------------------------------------- detalle

    @Test
    void detallePorTipoEId() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode json = json(accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"tipo\":\"inspeccionesVehiculares\",\"id\":15}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true)).andReturn());
        assertThat(json.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataFormulariosDetalle("inspecciones-vehiculares", 15)));
        assertThat(json.at("/data/1/descripcion").asString()).isEqualTo("Frenos <i>x</i>");
        // id como texto (int.TryParse de Photino) y con espacios; los otros dos tipos con detalle.
        accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"tipo\":\"checklistBodegaCajas\",\"id\":\"3\"}}").andExpect(jsonPath("$.ok").value(true));
        accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"tipo\":\"revisionBodegaOficinas\",\"id\":\" 9 \"}}").andExpect(jsonPath("$.ok").value(true));
        // La API real responde lista vacía (no 404) cuando el registro no existe: se reenvía igual.
        JsonNode vacio = json(accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"tipo\":\"inspeccionesVehiculares\",\"id\":404}}").andReturn());
        assertThat(vacio.get("ok").asBoolean()).isTrue();
        assertThat(vacio.get("data").isArray() && vacio.get("data").isEmpty()).isTrue();
        assertThat(API.peticionesFormularios()).containsExactly(BASE + "/inspecciones-vehiculares/15", BASE + "/checklist-bodega-cajas/3",
                BASE + "/revision-bodega-oficinas/9", BASE + "/inspecciones-vehiculares/404");
    }

    @Test
    void detalleRechazaTipoSinDetalleEIdInvalidoSinLlamarALaApi() throws Exception {
        MockHttpSession sesion = login("admin1");
        accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"tipo\":\"revisionCamionJornada\",\"id\":7}}")
                .andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value(FormulariosBridgeHandler.MENSAJE_SIN_DETALLE));
        accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"tipo\":\"otro\",\"id\":7}}")
                .andExpect(jsonPath("$.error").value(FormulariosBridgeHandler.MENSAJE_TIPO));
        for (String id : List.of("0", "-1", "\"abc\"", "1.5", "\"\"", "null", "true", "\"15/1\"", "99999999999", "\"../otro\"")) {
            accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"tipo\":\"inspeccionesVehiculares\",\"id\":" + id + "}}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(FormulariosBridgeHandler.MENSAJE_ID));
        }
        accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"tipo\":\"inspeccionesVehiculares\"}}")
                .andExpect(jsonPath("$.error").value(FormulariosBridgeHandler.MENSAJE_ID));
        assertThat(API.peticionesFormularios()).isEmpty();
    }

    // ----------------------------------------------------------------------------- abrirPdf

    @Test
    void abrirPdfValidaComoPhotinoYDevuelveLaUrlParaElNavegadorSinTocarLaApi() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode json = json(accion(sesion, "{\"action\":\"" + ABRIR_PDF + "\",\"data\":{\"url\":\"" + PDF_OK + "\"}}")
                .andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.at("/data/abierto").asBoolean()).isTrue();
        assertThat(json.at("/data/url").asString()).isEqualTo(PDF_OK);
        // Host y esquema sin distinguir mayúsculas (Uri de .NET), puerto 443 explícito, query y espacios ya escapados.
        for (String url : List.of("HTTPS://SOLICITUDES.FARET.CL/x.pdf", "https://solicitudes.faret.cl:443/x.pdf",
                "https://solicitudes.faret.cl/formularios/pdf/a%20b.pdf?v=1", "https://solicitudes.faret.cl")) {
            accion(sesion, "{\"action\":\"" + ABRIR_PDF + "\",\"data\":{\"url\":\"" + url + "\"}}")
                    .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data.abierto").value(true));
        }
        assertThat(API.peticionesFormularios()).isEmpty();
    }

    @Test
    void abrirPdfRechazaCualquierUrlFueraDelHostHttpsDeFormularios() throws Exception {
        MockHttpSession sesion = login("operador1");
        List<String> invalidas = List.of(
                "http://solicitudes.faret.cl/x.pdf",
                "https://evil.com/x.pdf",
                "https://solicitudes.faret.cl.evil.com/x.pdf",
                "https://evil.com/?u=https://solicitudes.faret.cl/x.pdf",
                "https://evil.com\\\\@solicitudes.faret.cl/x.pdf",
                "https://user:pw@solicitudes.faret.cl/x.pdf",
                "https://solicitudes.faret.cl:8443/x.pdf",
                "javascript:alert(1)",
                "file:///C:/x.pdf",
                "//solicitudes.faret.cl/x.pdf",
                "solicitudes.faret.cl/x.pdf",
                "/formularios/pdf/x.pdf",
                "https://solicitudes.faret.cl/x y.pdf",
                "https://solicitudes.faret.cl/x.pdf\\n",
                "https://solicitudes.faret.cl/" + "a".repeat(2048),
                "", "   ");
        for (String url : invalidas) {
            accion(sesion, "{\"action\":\"" + ABRIR_PDF + "\",\"data\":{\"url\":\"" + url + "\"}}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(FormulariosBridgeHandler.MENSAJE_URL));
        }
        for (String data : List.of("{}", "{\"url\":null}", "{\"url\":true}", "{\"url\":[\"" + PDF_OK + "\"]}", "{\"url\":{\"a\":1}}")) {
            accion(sesion, "{\"action\":\"" + ABRIR_PDF + "\",\"data\":" + data + "}").andExpect(jsonPath("$.error").value(FormulariosBridgeHandler.MENSAJE_URL));
        }
        accion(sesion, "{\"action\":\"" + ABRIR_PDF + "\",\"url\":\"" + PDF_OK + "\"}").andExpect(jsonPath("$.error").value(FormulariosBridgeHandler.MENSAJE_URL));
        assertThat(API.peticionesFormularios()).isEmpty();
    }

    // ----------------------------------------------------------------------------- errores, roles y alcance

    @Test
    void errorDeLaApiSeReenviaConSuMensaje() throws Exception {
        MockHttpSession sesion = login("operador1");
        API.modoDashboard(ModoDashboard.ERROR_NEGOCIO);
        accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":{\"tipo\":\"inspeccionesVehiculares\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Formularios no está configurado en el servidor"));
        accion(sesion, "{\"action\":\"" + DETALLE + "\",\"data\":{\"tipo\":\"inspeccionesVehiculares\",\"id\":15}}")
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Formularios no está configurado en el servidor"));
    }

    @Test
    void soloLecturasDelModuloYSoloRolesInnpack() throws Exception {
        assertThat(policy.accionesRegistradas()).contains(LIST, DETALLE, ABRIR_PDF).doesNotContain("formularios.eliminar");
        assertThat(PermisosModulo.esLectura(LIST)).isTrue();
        assertThat(PermisosModulo.esLectura(DETALLE)).isTrue();
        assertThat(PermisosModulo.esLectura(ABRIR_PDF)).isTrue();
        // formularios.eliminar existe solo en el WIP de Photino (no en 9e1b556): denegada por ActionPolicy.
        MockHttpSession admin = login("admin1");
        accion(admin, "{\"action\":\"formularios.eliminar\",\"data\":{\"tipo\":\"inspeccionesVehiculares\",\"id\":15}}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        // Rol consulta (no está en ROLES_INNPACK, igual que Certificados).
        for (String a : List.of(LIST, DETALLE, ABRIR_PDF)) {
            accion(login("consulta1"), "{\"action\":\"" + a + "\",\"data\":{\"tipo\":\"inspeccionesVehiculares\",\"id\":15,\"url\":\"" + PDF_OK + "\"}}")
                    .andExpect(status().isForbidden());
        }
        // Lecturas atadas al módulo declarado: desde otro módulo se rechazan (seguridad web de la Fase 3u).
        accion(admin, "{\"action\":\"" + LIST + "\",\"data\":{\"tipo\":\"inspeccionesVehiculares\"},\"_modulo\":\"certificados-liberacion\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Acción no permitida desde este módulo."));
        accion(admin, "{\"action\":\"" + LIST + "\",\"data\":{\"tipo\":\"inspeccionesVehiculares\"},\"_modulo\":\"formularios\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.peticionesFormularios()).containsExactly(BASE + "/inspecciones-vehiculares");
    }

    @Test
    void urlPdfValidaNormalizaYRechazaEnLaReglaPura() {
        assertThat(FormulariosBridgeHandler.urlPdfValida(PDF_OK)).isEqualTo(PDF_OK);
        assertThat(FormulariosBridgeHandler.urlPdfValida("  " + PDF_OK + "  ")).isEqualTo(PDF_OK);
        assertThat(FormulariosBridgeHandler.urlPdfValida("https://solicitudes.faret.cl/pdf/ñ.pdf")).isEqualTo("https://solicitudes.faret.cl/pdf/%C3%B1.pdf");
        assertThat(FormulariosBridgeHandler.urlPdfValida(null)).isNull();
        assertThat(FormulariosBridgeHandler.urlPdfValida("https://solicitudes.faret.cl/x.pdf#frag")).isEqualTo("https://solicitudes.faret.cl/x.pdf#frag");
        assertThat(FormulariosBridgeHandler.urlPdfValida("https:solicitudes.faret.cl/x.pdf")).isNull();
        assertThat(FormulariosBridgeHandler.urlPdfValida("https://[::1]/x.pdf")).isNull();
    }

    // ----------------------------------------------------------------------------- helpers

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.55.0." + IP.getAndIncrement());
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
            Path dir = Files.createTempDirectory("qcc-web-fixture-5a");
            Files.writeString(dir.resolve("index.html"), "<!doctype html><title>fixture</title>");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
