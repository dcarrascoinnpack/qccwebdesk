package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import cl.faret.qccweb.auth.FakeInnpackApi.SeguimientoRecibido;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
 * Fase 3z — recepcion.crear ("Nuevo Lote de Inspección": Bobina desde SAP, PVA / Pliego manual con foto), igual que
 * Photino para el usuario. Seguridad transparente: autor y empresa de sesión, lista blanca, largos del esquema, selects,
 * foto con firma real ≤ 10 MB por la ruta de archivos, doble clic sin duplicar. API SIMULADA.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3zTest {

    private static final String RUTA = "/api/v1/bridge/archivo";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveNuevoLote#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String JPEG = Base64.getEncoder().encodeToString(FakeInnpackApi.jpegMinimo());
    private static final String PNG = Base64.getEncoder().encodeToString(FakeInnpackApi.pngMinimo());

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
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

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reiniciar() {
        API.reiniciarDashboard();
    }

    @AfterAll
    static void cerrar() {
        API.close();
    }

    /** data de crearLote de Photino para una Bobina elegida desde SAP. */
    private ObjectNode bobina() {
        ObjectNode d = mapper.createObjectNode();
        d.put("tipoMateriaPrima", "Bobina");
        d.put("proveedor", "Papeles & Cía");
        d.put("guia", "G-77");
        d.put("itemCode", "1095SC21000090");
        d.put("descripcion", "Kraft 125");
        d.put("anchoDeclarado", 1600);
        d.put("gramajeDeclarado", 125.5);
        d.putArray("bobinas").add("B-001").add("B-002");
        return d;
    }

    /** data de crearLote de Photino para PVA manual (foto opcional). */
    private ObjectNode pva(String foto) {
        ObjectNode d = manual("PVA");
        d.put("pvaNombreAdhesivo", "Adhesivo X");
        d.put("pvaCantidadBins", "3");
        d.put("pvaFechaFabricacionVencimiento", "2026-12-31");
        d.put("pvaCertificadoCalidad", "Pendiente");
        d.put("pvaCondicionGeneral", "Conforme");
        d.put("pvaObservacion", "Línea 1\nLínea 2");
        if (foto == null) {
            d.putNull("pvaFotoBase64");
        } else {
            d.put("pvaFotoBase64", foto);
        }
        return d;
    }

    private ObjectNode pliego(Object total, Object verde, Object azul, Object roja) {
        ObjectNode d = manual("PliegoFaret");
        d.put("pfNp", "40001");
        d.put("pfCliente", "Cliente");
        d.put("pfProducto", "Caja");
        d.set("pfCantidadTotal", mapper.valueToTree(total));
        d.set("pfCantidadVerde", mapper.valueToTree(verde));
        d.set("pfCantidadAzul", mapper.valueToTree(azul));
        d.set("pfCantidadRoja", mapper.valueToTree(roja));
        d.put("pfEstadoCarpeta", "Recibida");
        d.put("pfCondicionVisual", "");
        d.put("pfTipoHallazgo", "");
        d.putNull("pfCantidadAfectada");
        d.put("pfObservacion", "");
        d.put("pfFotoBase64", PNG);
        return d;
    }

    private ObjectNode manual(String tipo) {
        ObjectNode d = mapper.createObjectNode();
        d.put("tipoMateriaPrima", tipo);
        d.put("proveedor", "Proveedor");
        d.put("guia", "G-1");
        d.put("itemCode", "ITM");
        d.put("loteProveedor", "L-9");
        d.put("descripcion", "Desc");
        d.putArray("bobinas");
        return d;
    }

    private String payload(ObjectNode data) {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", "recepcion.crear");
        p.set("data", data);
        return p.toString();
    }

    @Test
    void bobinaDesdeSapConElCuerpoDePhotinoYAutorDeSesion(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, payload(bobina())).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data.id").value(1000));
        SeguimientoRecibido r = API.lotesCreados().get(0);
        assertThat(r.sub()).isEqualTo(10);
        assertThat(mapper.readTree(r.cuerpo())).isEqualTo(mapper.readTree("{\"tipoMateriaPrima\":\"Bobina\",\"empresa\":\"INNPACK\","
                + "\"proveedor\":\"Papeles & Cía\",\"guia\":\"G-77\",\"itemCode\":\"1095SC21000090\",\"descripcion\":\"Kraft 125\",\"loteProveedor\":\"\","
                + "\"anchoDeclarado\":1600,\"gramajeDeclarado\":125.5,\"bobinas\":[\"B-001\",\"B-002\"],\"pvaNombreAdhesivo\":\"\",\"pvaCantidadBins\":null,"
                + "\"pvaFechaFabricacionVencimiento\":\"\",\"pvaCertificadoCalidad\":\"\",\"pvaCondicionGeneral\":\"\",\"pvaObservacion\":\"\","
                + "\"pvaFotoBase64\":\"\",\"pfNp\":\"\",\"pfCliente\":\"\",\"pfProducto\":\"\",\"pfCantidadTotal\":null,\"pfCantidadVerde\":null,"
                + "\"pfCantidadAzul\":null,\"pfCantidadRoja\":null,\"pfEstadoCarpeta\":\"\",\"pfCondicionVisual\":\"\",\"pfTipoHallazgo\":\"\","
                + "\"pfCantidadAfectada\":null,\"pfObservacion\":\"\",\"pfFotoBase64\":\"\",\"usuarioNombre\":\"Operador Uno\"}"));
        assertThat(salida.getAll()).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=recepcion\\.crear "
                + "recurso=recepcion:1000:crear:Bobina resultado=OK ms=\\d+");
        assertThat(salida.getAll()).doesNotContain("Papeles & Cía", "1095SC21000090", FakeInnpackApi.firmaDeToken(10), PASS);
    }

    @Test
    void pvaYPliegoManualesConFotoReal() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, payload(pva(JPEG))).andExpect(jsonPath("$.ok").value(true));
        JsonNode c = mapper.readTree(API.lotesCreados().get(0).cuerpo());
        assertThat(c.get("pvaFotoBase64").asString()).isEqualTo(JPEG);
        assertThat(c.get("pvaCantidadBins").decimalValue()).isEqualByComparingTo("3");
        assertThat(c.get("pvaObservacion").asString()).isEqualTo("Línea 1\nLínea 2");
        assertThat(c.get("pvaFechaFabricacionVencimiento").asString()).isEqualTo("2026-12-31");
        // Sin foto (input vacío → null en Photino → "").
        enviar(s, payload(pva(null))).andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(API.lotesCreados().get(1).cuerpo()).get("pvaFotoBase64").asString()).isEmpty();
        enviar(s, payload(pliego(10, 4, 3, 3))).andExpect(jsonPath("$.ok").value(true));
        // La suma la valida la API (y la vista): su mensaje, como Photino.
        enviar(s, payload(pliego(10, 4, 3, 2))).andExpect(jsonPath("$.error").value("Cantidad verde + azul + roja debe ser igual a la cantidad total"));
        // Negativos y más de 2 decimales: Photino los manda (SQL Server redondea).
        ObjectNode neg = pva(null);
        neg.put("pvaCantidadBins", -1.255);
        enviar(s, payload(neg)).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.lotesCreados()).hasSize(4);
    }

    @Test
    void validacionesTransparentes() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode d;
        d = bobina(); d.put("tipoMateriaPrima", "Otro");
        enviar(s, payload(d)).andExpect(jsonPath("$.error").value("Tipo de materia prima inválido."));
        d = bobina(); d.put("usuarioNombre", "Otro");
        enviar(s, payload(d)).andExpect(jsonPath("$.error").value("Campo no permitido: usuarioNombre"));
        d = bobina(); d.put("empresa", "FARET");
        enviar(s, payload(d)).andExpect(jsonPath("$.error").value("Campo no permitido: empresa"));
        d = bobina(); d.put("proveedor", "P".repeat(151));
        enviar(s, payload(d)).andExpect(jsonPath("$.error").value("El campo proveedor supera el máximo de 150 caracteres."));
        d = bobina(); d.put("descripcion", "<img src=x onerror=alert(1)>");
        enviar(s, payload(d)).andExpect(jsonPath("$.error").value("El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\")."));
        d = bobina(); d.put("guia", "G\n1");
        enviar(s, payload(d)).andExpect(jsonPath("$.error").value("El texto contiene caracteres no permitidos."));
        d = bobina(); d.putArray("bobinas").add("B-1").add("B-1");
        enviar(s, payload(d)).andExpect(jsonPath("$.error").value("La lista de bobinas no es válida."));
        d = bobina(); d.putArray("bobinas").add(5);
        enviar(s, payload(d)).andExpect(jsonPath("$.error").value("La lista de bobinas no es válida."));
        d = pva(null); d.putArray("bobinas").add("B-1");
        enviar(s, payload(d)).andExpect(jsonPath("$.error").value("La lista de bobinas no es válida."));
        d = pva(null); d.put("pvaCertificadoCalidad", "Quizás");
        enviar(s, payload(d)).andExpect(jsonPath("$.error").value("Valor no permitido en pvaCertificadoCalidad."));
        d = pliego(10, 4, 3, 3); d.put("pfTipoHallazgo", "Inventado");
        enviar(s, payload(d)).andExpect(jsonPath("$.error").value("Valor no permitido en pfTipoHallazgo."));
        d = pva(null); d.put("pvaFechaFabricacionVencimiento", "2026-02-30");
        enviar(s, payload(d)).andExpect(jsonPath("$.error").value("La fecha de fabricación/vencimiento no es válida (formato AAAA-MM-DD)."));
        d = bobina(); d.put("anchoDeclarado", 100000000);
        enviar(s, payload(d)).andExpect(jsonPath("$.error").value("Valor numérico fuera de rango en anchoDeclarado."));
        d = bobina(); d.set("gramajeDeclarado", mapper.createObjectNode());
        enviar(s, payload(d)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        // Tipo vacío o Bobina sin bobinas: mensajes de la API, como Photino.
        d = bobina(); d.put("tipoMateriaPrima", "");
        enviar(s, payload(d)).andExpect(jsonPath("$.error").value("Falta el tipo de materia prima"));
        d = bobina(); d.putArray("bobinas");
        enviar(s, payload(d)).andExpect(jsonPath("$.error").value("Debes seleccionar al menos una bobina desde SAP"));
        assertThat(API.lotesCreados()).isEmpty();
    }

    @Test
    void fotoSoloImagenRealYHasta10Mb() throws Exception {
        MockHttpSession s = login("operador1");
        String html = Base64.getEncoder().encodeToString("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        String pdf = Base64.getEncoder().encodeToString("%PDF-1.4 x".getBytes(StandardCharsets.UTF_8));
        for (String mala : new String[] {html, pdf, "%%%no-base64%%%", "AAAA AAAA"}) {
            enviar(s, payload(pva(mala))).andExpect(jsonPath("$.error").value("La fotografía no es una imagen válida."));
        }
        String grande = "/9j/" + "A".repeat((((10 * 1024 * 1024 + 2) / 3) * 4));
        enviar(s, payload(pva(grande))).andExpect(jsonPath("$.error").value("La fotografía excede el tamaño máximo permitido."));
        // HEIC (celulares): Photino lo guarda; la web también (aunque ninguno de los dos pueda mostrarlo).
        byte[] heic = new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0};
        enviar(s, payload(pva(Base64.getEncoder().encodeToString(heic)))).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.lotesCreados()).hasSize(1);
    }

    @Test
    void soloPorLaRutaDeArchivosYRolesComoPhotino() throws Exception {
        MockHttpSession s = login("operador1");
        mockMvc.perform(post("/api/v1/bridge").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload(bobina())))
                .andExpect(status().isForbidden());
        enviar(login("consulta1"), payload(bobina())).andExpect(status().isForbidden());
        assertThat(API.lotesCreados()).isEmpty();
    }

    @Test
    void dobleClicNoDuplicaElLote() throws Exception {
        MockHttpSession s = login("operador1");
        String p = payload(pva(JPEG));
        List<JsonNode> r = simultaneos(() -> json(enviar(s, p).andReturn()), () -> json(enviar(s, p).andReturn()));
        assertThat(r).allMatch(x -> x.get("ok").asBoolean());
        assertThat(r.get(0).at("/data/id").asInt()).isEqualTo(r.get(1).at("/data/id").asInt());
        enviar(s, p).andExpect(jsonPath("$.data.id").value(r.get(0).at("/data/id").asInt()));
        assertThat(API.lotesCreados()).hasSize(1);
        // Otro alta (datos distintos) sí crea otro lote.
        enviar(s, payload(pva(null))).andExpect(jsonPath("$.data.id").value(1001));
        assertThat(API.lotesCreados()).hasSize(2);
    }

    // ------------------------------------------------------------------ helpers

    private List<JsonNode> simultaneos(Callable<JsonNode> uno, Callable<JsonNode> dos) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<JsonNode> f1 = ex.submit(uno);
            Future<JsonNode> f2 = ex.submit(dos);
            return List.of(f1.get(), f2.get());
        } finally {
            ex.shutdownNow();
        }
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.38.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private ResultActions enviar(MockHttpSession sesion, String cuerpo) throws Exception {
        // Como el navegador: la cookie de sesión viaja (el filtro de tamaño no lee cuerpos grandes sin sesión).
        return mockMvc.perform(post(RUTA).session(sesion).with(csrf())
                .with(r -> {
                    r.setRequestedSessionId(sesion.getId());
                    return r;
                })
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private JsonNode json(MvcResult res) throws Exception {
        return mapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-3z");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
