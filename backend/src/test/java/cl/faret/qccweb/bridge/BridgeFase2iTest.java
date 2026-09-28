package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import cl.faret.qccweb.auth.FakeInnpackApi.ModoDashboard;
import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.handlers.NoConformidadesBridgeHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/** Fase 2i — No Conformidades, solo lectura (payload PLANO, 18 acciones). API SIMULADA. */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFase2iTest {

    private static final String LIST = "noConformidades.list";
    private static final String RESUMEN = "noConformidades.resumen";
    private static final String OPCIONES = "noConformidades.filtrosOpciones";
    private static final String GET = "noConformidades.get";
    private static final String ABRIR = "noConformidades.adjuntos.abrir";
    private static final String ADJUNTOS = "noConformidades.adjuntos.list";
    private static final List<String> LECTURAS_POR_ID = List.of(
            GET, "noConformidades.seguimiento.list", "noConformidades.analisis.get", "noConformidades.acciones.list", ADJUNTOS);
    private static final List<String> ESCRITURAS = escrituras();
    private static final String BASE = "GET /api/no-conformidades";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveNoConformidad#2026";
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
    }

    @AfterAll
    static void cerrar() {
        API.close();
    }

    // ------------------------------------------------------------------ list / resumen

    @Test
    void listYResumenConLosFiltrosVaciosDeLaPantalla() throws Exception {
        MockHttpSession sesion = login("operador1");
        String filtros = "\"cliente\":\"\",\"tipoPnc\":\"\",\"nivel\":\"\",\"estadoGestion\":\"\",\"area\":\"\",\"fechaDesde\":\"\",\"fechaHasta\":\"\"";
        // Payload plano exacto de _loadLista (página + resumen + "traer todo" de indicadores).
        JsonNode lista = json(accion(sesion, "{\"action\":\"" + LIST + "\",\"page\":1,\"pageSize\":50," + filtros + "}")
                .andExpect(status().isOk()).andReturn());
        List<String> campos = new ArrayList<>();
        lista.propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("ok", "success", "data", "error");
        assertThat(lista.get("data")).isEqualTo(mapper.readTree(FakeInnpackApi.dataNoConformidadesList(10, "page=1&pageSize=50")));
        assertThat(lista.at("/data/items")).hasSize(3);
        assertThat(lista.at("/data/items/0/cliente").asString()).isEqualTo("Viña Ñandú «0»");
        assertThat(lista.at("/data/items/0/categoriaDefecto").asString()).isEqualTo("=SUMA(1;2)");
        JsonNode resumen = json(accion(sesion, "{\"action\":\"" + RESUMEN + "\"," + filtros + "}").andReturn());
        assertThat(resumen.at("/data/total").asInt()).isEqualTo(42);
        assertThat(resumen.at("/data/criticas").asInt()).isEqualTo(5);
        accion(sesion, "{\"action\":\"" + LIST + "\",\"page\":1,\"pageSize\":999999," + filtros + "}").andExpect(jsonPath("$.ok").value(true));
        accion(sesion, "{\"action\":\"" + LIST + "\"}").andExpect(jsonPath("$.ok").value(true));
        assertThat(API.peticionesNoConformidades()).containsExactly(
                BASE + "?page=1&pageSize=50", BASE + "/resumen", BASE + "?page=1&pageSize=999999", BASE + "?page=1&pageSize=50");
    }

    /** Mismas reglas que InnpackNoConformidadesApiService.ListAsync/ResumenAsync + TryGetInt/TryGetString. */
    @Test
    void mapeoDeFiltrosOrdenEscapadoYUtf8IgualQuePhotino() throws Exception {
        MockHttpSession sesion = login("admin1");
        String[][] casos = {
            {"{\"page\":3,\"pageSize\":\"25\"}", "?page=3&pageSize=25", "/resumen"},
            {"{\"page\":0,\"pageSize\":-5}", "?page=1&pageSize=50", "/resumen"},
            {"{\"page\":\"x\",\"pageSize\":2.5}", "?page=1&pageSize=50", "/resumen"},
            {"{\"cliente\":\"Viña & Cía / 100%\"}", "?page=1&pageSize=50&cliente=Vi%C3%B1a%20%26%20C%C3%ADa%20%2F%20100%25",
                "/resumen?cliente=Vi%C3%B1a%20%26%20C%C3%ADa%20%2F%20100%25"},
            {"{\"fechaHasta\":\"2026-09-30\",\"fechaDesde\":\"2026-09-01\",\"area\":\"Impresión\",\"estadoGestion\":\"EN_GESTION\","
                + "\"nivel\":\"Crítico\",\"tipoPnc\":\"Reclamo\",\"cliente\":\"ñ\"}",
                "?page=1&pageSize=50&cliente=%C3%B1&tipoPnc=Reclamo&nivel=Cr%C3%ADtico&estadoGestion=EN_GESTION&area=Impresi%C3%B3n"
                    + "&fechaDesde=2026-09-01&fechaHasta=2026-09-30",
                "/resumen?cliente=%C3%B1&tipoPnc=Reclamo&nivel=Cr%C3%ADtico&estadoGestion=EN_GESTION&area=Impresi%C3%B3n"
                    + "&fechaDesde=2026-09-01&fechaHasta=2026-09-30"},
            {"{\"cliente\":\"a&page=9\"}", "?page=1&pageSize=50&cliente=a%26page%3D9", "/resumen?cliente=a%26page%3D9"},
            {"{\"cliente\":\"   \",\"area\":null,\"nivel\":\"\"}", "?page=1&pageSize=50", "/resumen"},
            {"{\"cliente\":4101,\"area\":1.5}", "?page=1&pageSize=50&cliente=4101&area=1.5", "/resumen?cliente=4101&area=1.5"},
        };
        List<String> esperadas = new ArrayList<>();
        for (String[] caso : casos) {
            String resto = caso[0].substring(1);
            accion(sesion, "{\"action\":\"" + LIST + "\"," + resto).andExpect(jsonPath("$.ok").value(true));
            accion(sesion, "{\"action\":\"" + RESUMEN + "\"," + resto).andExpect(jsonPath("$.ok").value(true));
            esperadas.add(BASE + caso[1]);
            esperadas.add(BASE + caso[2]);
        }
        // "data" NO se lee en este módulo (payload plano).
        accion(sesion, "{\"action\":\"" + LIST + "\",\"data\":{\"cliente\":\"ignorado\",\"page\":7}}").andExpect(status().isOk());
        esperadas.add(BASE + "?page=1&pageSize=50");
        assertThat(API.peticionesNoConformidades()).containsExactlyElementsOf(esperadas);
    }

    @Test
    void tiposInvalidosSeRechazanSinLlamarALaApiYEmpresaNoSeInyecta() throws Exception {
        MockHttpSession sesion = login("operador1");
        for (String a : List.of(LIST, RESUMEN)) {
            for (String data : List.of("\"cliente\":true", "\"area\":{\"x\":1}", "\"fechaDesde\":[\"a\"]", "\"estadoGestion\":false")) {
                accion(sesion, "{\"action\":\"" + a + "\"," + data + "}")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.ok").value(false))
                        .andExpect(jsonPath("$.error").value("Parámetro de filtro inválido."));
            }
        }
        assertThat(API.peticionesNoConformidades()).isEmpty();
        accion(sesion, "{\"action\":\"" + LIST + "\",\"empresa\":\"FARET\"}").andExpect(status().isOk());
        assertThat(API.peticionesNoConformidades()).containsExactly(BASE + "?page=1&pageSize=50");
    }

    @Test
    void datasetVacioYGrandeYErrorDeLaApi() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode vacio = json(accion(sesion, "{\"action\":\"" + LIST + "\",\"cliente\":\"VACIO\"}").andReturn());
        assertThat(vacio.at("/data/items")).isEmpty();
        assertThat(vacio.at("/data/total").asInt()).isZero();
        JsonNode grande = json(accion(sesion, "{\"action\":\"" + LIST + "\",\"cliente\":\"GRANDE\",\"pageSize\":999999}").andReturn());
        assertThat(grande.at("/data/items")).hasSize(300);
        API.modoDashboard(ModoDashboard.ERROR_NEGOCIO);
        for (String a : List.of(LIST, RESUMEN, OPCIONES)) {
            accion(sesion, "{\"action\":\"" + a + "\"}")
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Filtro de fecha inválido"));
        }
    }

    // --------------------------------------------------- filtrosOpciones / catálogos

    @Test
    void filtrosOpcionesYLosNueveCatalogos() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode opciones = json(accion(sesion, "{\"action\":\"" + OPCIONES + "\",\"cliente\":\"ignorado\"}").andReturn());
        assertThat(opciones.at("/data/clientes/0").asString()).isEqualTo("Viña Ñandú");
        List<String> esperadas = new ArrayList<>(List.of(BASE + "/filtros-opciones"));
        for (String catalogo : NoConformidadesBridgeHandler.CATALOGOS) {
            JsonNode res = json(accion(sesion, "{\"action\":\"noConformidades.catalogos." + catalogo + ".list\",\"catalogo\":\"../x\"}")
                    .andExpect(status().isOk()).andReturn());
            assertThat(res.get("ok").asBoolean()).isTrue();
            assertThat(res.at("/data/0/nombre").asString()).isEqualTo(catalogo + " ñ");
            esperadas.add("GET /api/nc-catalogos/" + catalogo);
        }
        assertThat(API.peticionesNoConformidades()).containsExactlyElementsOf(esperadas);
        // Un catálogo que no existe en Photino no es una acción registrada.
        accion(sesion, "{\"action\":\"noConformidades.catalogos.usuarios.list\"}").andExpect(status().isForbidden());
        assertThat(API.peticionesNoConformidades()).hasSize(esperadas.size());
    }

    // ------------------------------------------------------ get y sub-recursos por id

    @Test
    void lecturasPorIdConIdValidoInvalidoYNoEncontrado() throws Exception {
        MockHttpSession sesion = login("operador1");
        String[] sufijos = {"", "/seguimiento", "/analisis", "/acciones", "/adjuntos"};
        List<String> esperadas = new ArrayList<>();
        for (int i = 0; i < LECTURAS_POR_ID.size(); i++) {
            String a = LECTURAS_POR_ID.get(i);
            accion(sesion, "{\"action\":\"" + a + "\",\"id\":501}").andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data").exists());
            accion(sesion, "{\"action\":\"" + a + "\",\"id\":\" 502 \"}").andExpect(jsonPath("$.ok").value(true));
            for (String data : List.of("", ",\"id\":\"abc\"", ",\"id\":true", ",\"id\":2.5", ",\"id\":null", ",\"data\":{\"id\":501}")) {
                accion(sesion, "{\"action\":\"" + a + "\"" + data + "}")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.ok").value(false))
                        .andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
            }
            accion(sesion, "{\"action\":\"" + a + "\",\"id\":404}")
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("No conformidad no encontrada"));
            esperadas.addAll(List.of(BASE + "/501" + sufijos[i], BASE + "/502" + sufijos[i], BASE + "/404" + sufijos[i]));
        }
        assertThat(API.peticionesNoConformidades()).containsExactlyElementsOf(esperadas);
        JsonNode get = json(accion(sesion, "{\"action\":\"" + GET + "\",\"id\":501}").andReturn());
        assertThat(get.at("/data/descripcion").asString()).isEqualTo("=HYPERLINK(1)");
    }

    @Test
    void adjuntosListSaneaNombreArchivo() throws Exception {
        MockHttpSession sesion = login("operador1");
        JsonNode normal = json(accion(sesion, "{\"action\":\"" + ADJUNTOS + "\",\"id\":501}").andReturn());
        assertThat(normal.at("/data/0/nombreArchivo").asString()).isEqualTo("causa raíz ñ.pdf");
        assertThat(normal.at("/data/0/tipo").asString()).isEqualTo("CAUSA_RAIZ_PDF");
        assertThat(normal.at("/data/1/nombreArchivo").asString()).isEqualTo("adjunto_2");
        assertThat(normal.at("/data/2").has("nombreArchivo")).isFalse();
        JsonNode malicioso = json(accion(sesion, "{\"action\":\"" + ADJUNTOS + "\",\"id\":900}").andReturn());
        assertThat(malicioso.at("/data/0/nombreArchivo").asString()).isEqualTo("_img src=x onerror=alert(1)_.pdf");
        assertThat(malicioso.toString()).doesNotContain("<img");
    }

    // ------------------------------------------------------------------ adjuntos.abrir

    @Test
    void adjuntoPdfPngYJpegSeEntreganSinTocarElDisco() throws Exception {
        MockHttpSession sesion = login("operador1");
        long tmpAntes = archivosEnTemp();
        JsonNode pdf = json(accion(sesion, "{\"action\":\"" + ABRIR + "\",\"id\":501,\"adjuntoId\":1}").andExpect(status().isOk()).andReturn());
        assertThat(pdf.get("ok").asBoolean()).isTrue();
        List<String> campos = new ArrayList<>();
        pdf.get("data").propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("id", "nombreArchivo", "tipoMime", "contenidoBase64");
        assertThat(pdf.at("/data/id").asInt()).isEqualTo(1);
        assertThat(pdf.at("/data/nombreArchivo").asString()).isEqualTo("causa raíz ñ.pdf");
        assertThat(pdf.at("/data/tipoMime").asString()).isEqualTo("application/pdf");
        assertThat(Base64.getDecoder().decode(pdf.at("/data/contenidoBase64").asString())).isEqualTo(FakeInnpackApi.pdfMinimo(1));

        JsonNode png = json(accion(sesion, "{\"action\":\"" + ABRIR + "\",\"id\":\"501\",\"adjuntoId\":\"2\"}").andReturn());
        assertThat(png.at("/data/tipoMime").asString()).isEqualTo("image/png");
        JsonNode jpeg = json(accion(sesion, "{\"action\":\"" + ABRIR + "\",\"id\":501,\"adjuntoId\":3}").andReturn());
        assertThat(jpeg.at("/data/tipoMime").asString()).isEqualTo("image/jpeg");

        JsonNode malicioso = json(accion(sesion, "{\"action\":\"" + ABRIR + "\",\"id\":501,\"adjuntoId\":900}").andReturn());
        assertThat(malicioso.at("/data/nombreArchivo").asString()).isEqualTo("_img src=x onerror=alert(1)__.png");

        assertThat(API.peticionesNoConformidades()).containsExactly(
                BASE + "/501/adjuntos/1", BASE + "/501/adjuntos/2", BASE + "/501/adjuntos/3", BASE + "/501/adjuntos/900");
        assertThat(archivosEnTemp()).isEqualTo(tmpAntes);
        assertThat(pdf.toString()).doesNotContain("path", "Temp", FakeInnpackApi.firmaDeToken(10));
    }

    @Test
    void adjuntoFueraDeListaFalsoVacioCorruptoOExcesivoSeRechaza() throws Exception {
        MockHttpSession sesion = login("operador1");
        String[][] casos = {
            {"404", "Adjunto no encontrado"},
            {"4", "El adjunto no es válido."},
            {"5", "El adjunto no es válido."},
            {"6", "El adjunto no es válido."},
            {"600", "El adjunto no trae contenido"},
            {"800", "El adjunto no es válido."},
            {"700", "El adjunto excede el tamaño máximo permitido."},
        };
        for (String[] caso : casos) {
            accion(sesion, "{\"action\":\"" + ABRIR + "\",\"id\":501,\"adjuntoId\":" + caso[0] + "}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.data").value((Object) null))
                    .andExpect(jsonPath("$.error").value(caso[1]));
        }
        assertThat(API.peticionesNoConformidades()).hasSize(casos.length);
        for (String data : List.of(",\"adjuntoId\":1", ",\"id\":\"abc\",\"adjuntoId\":1", ",\"data\":{\"id\":501,\"adjuntoId\":1}")) {
            accion(sesion, "{\"action\":\"" + ABRIR + "\"" + data + "}")
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
        }
        for (String data : List.of(",\"id\":501", ",\"id\":501,\"adjuntoId\":true", ",\"id\":501,\"adjuntoId\":\"x\"")) {
            accion(sesion, "{\"action\":\"" + ABRIR + "\"" + data + "}")
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Falta el id del adjunto"));
        }
        assertThat(API.peticionesNoConformidades()).hasSize(casos.length);
    }

    // ------------------------------------------------------------- escrituras / roles / empresa

    @Test
    void escriturasDelModuloSiguenBloqueadas() throws Exception {
        MockHttpSession admin = login("admin1");
        assertThat(ESCRITURAS).hasSize(20); // 3a-3c + catalogos clientes (3d), categoriasDefecto (3e), tiposFalla/supervisores/revisores (3f) y areas (3g) ya habilitadas
        for (String escritura : ESCRITURAS) {
            accion(admin, "{\"action\":\"" + escritura + "\",\"id\":501,\"adjuntoId\":1,\"accionId\":3,\"nombre\":\"x\"}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
            assertThat(policy.accionesRegistradas()).doesNotContain(escritura);
        }
        assertThat(API.peticionesNoConformidades()).isEmpty();
    }

    @Test
    void rolPermitidoYNoPermitido() throws Exception {
        for (String a : List.of(LIST, RESUMEN, OPCIONES, GET, ABRIR, "noConformidades.catalogos.clientes.list")) {
            accion(login("consulta1"), "{\"action\":\"" + a + "\",\"id\":501,\"adjuntoId\":1}").andExpect(status().isForbidden());
        }
        assertThat(API.peticionesNoConformidades()).isEmpty();
        accion(login("operador1"), "{\"action\":\"" + LIST + "\"}").andExpect(status().isOk());
        accion(login("admin1"), "{\"action\":\"" + GET + "\",\"id\":501}").andExpect(status().isOk());
        assertThat(API.peticionesNoConformidades()).hasSize(2);
    }

    @Test
    void empresaDeSesionCorrectaEIncorrecta() {
        List<String> lecturas = policy.accionesRegistradas().stream()
                .filter(a -> a.startsWith("noConformidades.") && !a.equals("noConformidades.seguimiento.crear")
                        && !a.equals("noConformidades.acciones.crear") && !a.equals("noConformidades.analisis.guardar")
                        && !a.equals("noConformidades.catalogos.clientes.crear")
                        && !a.equals("noConformidades.catalogos.categoriasDefecto.crear")
                        && !a.equals("noConformidades.catalogos.tiposFalla.crear")
                        && !a.equals("noConformidades.catalogos.supervisores.crear")
                        && !a.equals("noConformidades.catalogos.revisores.crear")
                        && !a.equals("noConformidades.catalogos.areas.crear")).toList();
        assertThat(lecturas).hasSize(18);
        for (String a : lecturas) {
            assertThat(policy.evaluar(a, usuario("INNPACK", "operador"))).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            assertThat(policy.evaluar(a, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        }
    }

    // --------------------------------------------------------- identidad y aislamiento

    @Test
    void identidadManipuladaSinEfecto() throws Exception {
        MockHttpSession sesion = login("operador1");
        accion(sesion, "{\"action\":\"" + GET + "\",\"id\":501,\"usuarioId\":20,\"rol\":\"admin\",\"empresa\":\"FARET\",\"actualizadoPor\":\"Admin Uno\",\"token\":\"x\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usuarioDelToken").value(10));
        assertThat(API.peticionesNoConformidades()).containsExactly(BASE + "/501");
        assertThat(API.authorizationRecibidos()).allMatch(a -> a.endsWith(FakeInnpackApi.firmaDeToken(10)));
    }

    @Test
    void sesionesConcurrentesAisladas() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<int[]>> tareas = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                boolean esA = i % 2 == 0;
                tareas.add(() -> {
                    JsonNode json = json(accion(esA ? a : b, "{\"action\":\"" + RESUMEN + "\"}").andExpect(status().isOk()).andReturn());
                    return new int[] {esA ? 10 : 20, json.at("/data/usuarioDelToken").asInt()};
                });
            }
            for (Future<int[]> f : pool.invokeAll(tareas)) {
                assertThat(f.get()[1]).isEqualTo(f.get()[0]);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unauthorizedUpstreamInvalidaSoloEsaSesion() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        API.revocarTokens(10);
        accion(a, "{\"action\":\"" + ABRIR + "\",\"id\":501,\"adjuntoId\":1}").andExpect(status().isUnauthorized());
        assertThat(a.isInvalid()).isTrue();
        accion(b, "{\"action\":\"" + LIST + "\"}").andExpect(status().isOk());
    }

    @Test
    void csrfObligatorio() throws Exception {
        MockHttpSession sesion = login("operador1");
        mockMvc.perform(post("/api/v1/bridge").session(sesion).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + LIST + "\"}"))
                .andExpect(status().isForbidden());
        assertThat(API.peticionesNoConformidades()).isEmpty();
    }

    // ------------------------------------------------------------------------- helpers

    private static List<String> escrituras() {
        List<String> e = new ArrayList<>(List.of("noConformidades.create", "noConformidades.update", "noConformidades.eliminar",
                "noConformidades.gestion.actualizar", "noConformidades.cerrar",
                "noConformidades.acciones.actualizar",
                "noConformidades.adjuntos.subir", "noConformidades.adjuntos.eliminar"));
        for (String catalogo : NoConformidadesBridgeHandler.CATALOGOS) {
            if (!List.of("clientes", "categoriasDefecto", "tiposFalla", "supervisores", "revisores", "areas").contains(catalogo)) { // habilitadas en 3d-3g
                e.add("noConformidades.catalogos." + catalogo + ".crear");
            }
            e.add("noConformidades.catalogos." + catalogo + ".desactivar");
        }
        return List.copyOf(e);
    }

    private static long archivosEnTemp() throws IOException {
        try (var s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".pdf") || n.endsWith(".png") || n.endsWith(".jpg");
            }).count();
        }
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.9.0." + IP.getAndIncrement());
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

    private static SessionUser usuario(String empresa, String rol) {
        return new SessionUser(1, "u", "U", rol, empresa, "t", Instant.now(), Instant.now().plusSeconds(60));
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-2i");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
