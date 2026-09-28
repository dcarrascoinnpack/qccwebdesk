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
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fase 3q — recepcion.bobinas.muestrear (primera escritura de Recepción), igual que Photino para el usuario: la API
 * REEMPLAZA la selección del lote y avanza PendienteMuestreo → PendienteLaboratorio. Seguridad transparente: usuario de
 * sesión, lista blanca, lote de la empresa de sesión, detección de cambios concurrentes. API SIMULADA con estado.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3qTest {

    private static final String MUESTREAR = "recepcion.bobinas.muestrear";
    private static final String MSG_FALTA = "Falta el lote o la lista de bobinas muestreadas";
    private static final String MSG_SIN_LEER = "Abre el detalle del lote antes de guardar las bobinas muestreadas.";
    private static final String MSG_CONFLICTO = "La selección de bobinas muestreadas fue modificada por otra persona desde que "
            + "abriste el lote. Vuelve a abrirlo para ver los cambios.";
    private static final String MSG_HTML = "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").";
    private static final String MSG_CARACTERES = "El texto contiene caracteres no permitidos.";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveMuestreo#2026";
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

    /** Payload EXACTO de guardarMuestreadas de Photino: {action, data:{loteId, bobinas[{numeroBobina, seleccionTipo, criterioManual}]}}. */
    private String payload(int loteId, String tipo, String criterio, String... numeros) {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", MUESTREAR);
        ObjectNode data = p.putObject("data");
        data.put("loteId", loteId);
        ArrayNode bobinas = data.putArray("bobinas");
        for (String n : numeros) {
            ObjectNode b = bobinas.addObject();
            b.put("numeroBobina", n);
            b.put("seleccionTipo", tipo);
            if (criterio == null) {
                b.putNull("criterioManual");
            } else {
                b.put("criterioManual", criterio);
            }
        }
        return p.toString();
    }

    // ------------------------------------------------------------------ flujo exitoso y refresco

    @Test
    void muestreoCorrectoConCuerpoExactoEstadoYRefresco() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode antes = abrir(s, 20);
        assertThat(antes.get("estado").asString()).isEqualTo("PendienteMuestreo");
        JsonNode json = json(enviar(s, payload(20, "Aleatoria", null, "B-002", "B-004")).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.at("/data/muestreadas").asInt()).isEqualTo(2);
        assertThat(API.peticionesRecepcion()).containsExactly("GET /api/recepcion-calidad/20?empresa=INNPACK",
                "GET /api/recepcion-calidad/20?empresa=INNPACK", "POST /api/recepcion-calidad/20/bobinas-muestreadas");
        SeguimientoRecibido r = API.muestreosRecibidos().get(0);
        assertThat(r.sub()).isEqualTo(10);
        assertThat(r.contentType()).startsWith("application/json");
        assertThat(mapper.readTree(r.cuerpo())).isEqualTo(mapper.readTree("{\"bobinas\":["
                + "{\"numeroBobina\":\"B-002\",\"seleccionTipo\":\"Aleatoria\",\"criterioManual\":null},"
                + "{\"numeroBobina\":\"B-004\",\"seleccionTipo\":\"Aleatoria\",\"criterioManual\":null}],\"usuario\":\"Operador Uno\"}"));
        // Refresco inmediato (la vista reabre el detalle): nueva selección y estado avanzado.
        JsonNode despues = abrir(s, 20);
        assertThat(despues.get("estado").asString()).isEqualTo("PendienteLaboratorio");
        assertThat(despues.at("/muestreadas/1/numeroBobina").asString()).isEqualTo("B-004");
        // Volver a guardar (re-seleccionar bobinas ya muestreadas) = reemplazo, igual que Photino; el estado no retrocede.
        enviar(s, payload(20, "Manual", "Falta una bobina por humedad", "B-001", "B-002")).andExpect(jsonPath("$.ok").value(true));
        assertThat(abrir(s, 20).get("estado").asString()).isEqualTo("PendienteLaboratorio");
        assertThat(mapper.readTree(API.muestreosRecibidos().get(1).cuerpo()).at("/bobinas/0/criterioManual").asString())
                .isEqualTo("Falta una bobina por humedad");
    }

    @Test
    void enCualquierEstadoSeGuardaComoPhotinoYSoloAvanzaDesdePendienteMuestreo() throws Exception {
        MockHttpSession s = login("admin1");
        abrir(s, 22); // EnAnalisis
        enviar(s, payload(22, "", null, "C-001")).andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(API.muestreosRecibidos().get(0).cuerpo()).at("/bobinas/0/seleccionTipo").asString()).isEqualTo("Manual");
        assertThat(API.estadoLote(22)).isEqualTo("EnAnalisis");
    }

    @Test
    void usuarioSiempreEsLaSesionYRolesComoPhotino() throws Exception {
        String[][] casos = {{"operador1", "Operador Uno"}, {"admin1", "Admin Uno"}, {"adminti1", "Admin TI"}};
        for (String[] c : casos) {
            MockHttpSession s = login(c[0]);
            abrir(s, 20);
            ObjectNode p = (ObjectNode) mapper.readTree(payload(20, "Manual", null, "B-001"));
            p.put("usuario", "Otra Persona");
            ((ObjectNode) p.get("data")).put("usuario", "Otra Persona");
            enviar(s, p.toString()).andExpect(jsonPath("$.ok").value(true));
        }
        for (int k = 0; k < 3; k++) {
            assertThat(mapper.readTree(API.muestreosRecibidos().get(k).cuerpo()).get("usuario").asString()).isEqualTo(casos[k][1]);
        }
        enviar(login("consulta1"), payload(20, "Manual", null, "B-001")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(policy.evaluar(MUESTREAR, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(MUESTREAR)).findFirst().orElseThrow();
        assertThat(d.get("escritura")).isEqualTo(true);
        assertThat(d.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
        assertThat(d.get("identidad")).isEqualTo(Map.of("usuario", IdentityOverride.Fuente.NOMBRE_COMPLETO));
        for (String otra : List.of("recepcion.crear", "recepcion.nc.crear", "recepcion.plan.generar", "recepcion.muestra.crear",
                "recepcion.estado.actualizar", "recepcion.sap.consultar", "recepcion.sap.lotes")) {
            assertThat(policy.accionesRegistradas()).doesNotContain(otra);
        }
    }

    // ------------------------------------------------------------------ validaciones

    @Test
    void seleccionVaciaLoteYBobinasInvalidasOAjenas() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        enviar(s, payload(20, "Manual", null)).andExpect(jsonPath("$.error").value(MSG_FALTA));
        enviar(s, "{\"action\":\"" + MUESTREAR + "\",\"data\":{\"bobinas\":[{\"numeroBobina\":\"B-001\"}]}}").andExpect(jsonPath("$.error").value(MSG_FALTA));
        enviar(s, "{\"action\":\"" + MUESTREAR + "\",\"data\":{\"loteId\":20}}").andExpect(jsonPath("$.error").value(MSG_FALTA));
        enviar(s, "{\"action\":\"" + MUESTREAR + "\",\"data\":{\"loteId\":20,\"bobinas\":\"B-001\"}}").andExpect(jsonPath("$.error").value("Parámetro inválido."));
        enviar(s, "{\"action\":\"" + MUESTREAR + "\",\"data\":{\"loteId\":20,\"bobinas\":[\"B-001\"]}}").andExpect(jsonPath("$.error").value("Parámetro inválido."));
        assertThat(API.muestreosRecibidos()).isEmpty();
        // Bobinas que no son del lote (inexistentes o de OTRO lote): las rechaza la API con su mensaje; nada se reemplaza.
        enviar(s, payload(20, "Manual", null, "B-001", "X-001", "ZZZ")).andExpect(jsonPath("$.error")
                .value("Las siguientes bobinas no pertenecen a este lote: X-001, ZZZ"));
        assertThat(abrir(s, 20).get("muestreadas").size()).isZero();
        // Lote de otra empresa / inexistente: el detalle de la empresa de sesión no lo encuentra → no se puede abrir ni guardar.
        crear(s, "{\"action\":\"recepcion.detalle\",\"data\":{\"id\":99}}").andExpect(jsonPath("$.ok").value(false));
        enviar(s, payload(99, "Manual", null, "B-001")).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        assertThat(API.muestreosRecibidos()).hasSize(1);
    }

    @Test
    void camposTiposTextosYDuplicados() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        ObjectNode extraRaiz = (ObjectNode) mapper.readTree(payload(20, "Manual", null, "B-001"));
        extraRaiz.put("empresa", "FARET");
        enviar(s, extraRaiz.toString()).andExpect(jsonPath("$.error").value("Campo no permitido: empresa"));
        ObjectNode extraData = (ObjectNode) mapper.readTree(payload(20, "Manual", null, "B-001"));
        ((ObjectNode) extraData.get("data")).put("estado", "NoConforme");
        enviar(s, extraData.toString()).andExpect(jsonPath("$.error").value("Campo no permitido: estado"));
        ObjectNode extraBobina = (ObjectNode) mapper.readTree(payload(20, "Manual", null, "B-001"));
        ((ObjectNode) extraBobina.at("/data/bobinas/0")).put("usuario", "x");
        enviar(s, extraBobina.toString()).andExpect(jsonPath("$.error").value("Campo no permitido: usuario"));
        enviar(s, payload(20, "Masiva", null, "B-001")).andExpect(jsonPath("$.error").value("Tipo de selección inválido (Manual o Aleatoria)."));
        enviar(s, payload(20, "Manual", "R".repeat(256), "B-001")).andExpect(jsonPath("$.error").value("El motivo supera el máximo de 255 caracteres."));
        enviar(s, payload(20, "Manual", "<img src=x onerror=alert(1)>", "B-001")).andExpect(jsonPath("$.error").value(MSG_HTML));
        enviar(s, payload(20, "Manual", "línea\nnueva", "B-001")).andExpect(jsonPath("$.error").value(MSG_CARACTERES));
        enviar(s, payload(20, "Manual", null, "N".repeat(101))).andExpect(jsonPath("$.error").value("El número de bobina supera el máximo de 100 caracteres."));
        enviar(s, payload(20, "Manual", null, "B-001", "B-002", "B-001")).andExpect(jsonPath("$.error").value("La bobina B-001 está repetida en la selección."));
        enviar(s, "{\"action\":\"" + MUESTREAR + "\",\"data\":{\"loteId\":20,\"bobinas\":[{\"numeroBobina\":true}]}}").andExpect(jsonPath("$.error").value("Parámetro inválido."));
        assertThat(API.muestreosRecibidos()).isEmpty();
        // UTF-8 en el motivo y loteId como string (GetInt de Photino) se aceptan.
        String utf8 = "Humedad «alta» — Ñuble ✓ 😀 5<6";
        enviar(s, payload(20, "Manual", utf8, "B-003").replace("\"loteId\":20", "\"loteId\":\"20\"")).andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(API.muestreosRecibidos().get(0).cuerpo()).at("/bobinas/0/criterioManual").asString()).isEqualTo(utf8);
    }

    // ------------------------------------------------------------------ concurrencia

    @Test
    void seleccionModificadaPorOtraPersonaSeDetectaSinPisarla() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        API.muestrearPorOtro(20, "B-003");
        enviar(s, payload(20, "Manual", null, "B-001")).andExpect(jsonPath("$.error").value(MSG_CONFLICTO));
        assertThat(API.muestreosRecibidos()).isEmpty();
        assertThat(abrir(s, 20).at("/muestreadas/0/numeroBobina").asString()).isEqualTo("B-003"); // reabre y ve el cambio
        enviar(s, payload(20, "Manual", null, "B-001")).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void dosSesionesConcurrentesLaSegundaRecibeElConflicto() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        abrir(a, 20);
        abrir(b, 20);
        enviar(a, payload(20, "Aleatoria", null, "B-001", "B-002")).andExpect(jsonPath("$.ok").value(true));
        enviar(b, payload(20, "Manual", null, "B-004")).andExpect(jsonPath("$.error").value(MSG_CONFLICTO));
        assertThat(API.muestreosRecibidos()).hasSize(1);
        // Sin reabrir el lote, ni siquiera quien guardó puede volver a guardar encima (la vista siempre reabre).
        enviar(a, payload(20, "Manual", null, "B-003")).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        // La apertura de una sesión no sirve para otra.
        MockHttpSession c = login("operador1");
        enviar(c, payload(20, "Manual", null, "B-003")).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
    }

    // ------------------------------------------------------------------ CSRF, 401, errores y auditoría

    @Test
    void csrf401YErroresUpstream() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        mockMvc.perform(post("/api/v1/bridge").session(s).contentType(MediaType.APPLICATION_JSON).content(payload(20, "Manual", null, "B-001")))
                .andExpect(status().isForbidden());
        enviar(s, payload(20, "Manual", null, "ERROR_500")).andExpect(jsonPath("$.error").value("Error al comunicarse con la API Innpack"));
        assertThat(API.muestreosRecibidos()).hasSize(1); // sin reintento
        API.revocarTokens(10);
        enviar(s, payload(20, "Manual", null, "B-001")).andExpect(status().isUnauthorized());
        assertThat(s.isInvalid()).isTrue();
        assertThat(API.muestreosRecibidos()).hasSize(1);
    }

    @Test
    void auditoriaConLoteYCantidadSinNumerosNiMotivo(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        enviar(s, payload(20, "Manual", "Motivo Sensible 12.345.678-9", "B-002", "B-003")).andExpect(jsonPath("$.ok").value(true));
        abrir(s, 20);
        enviar(s, payload(20, "Manual", null, "ZZZ")).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=recepcion\\.bobinas\\.muestrear "
                + "recurso=recepcion:20:muestreadas:2 resultado=OK ms=\\d+");
        assertThat(log).contains("accion=recepcion.bobinas.muestrear recurso=recepcion:20:muestreadas:1 resultado=ERROR");
        assertThat(log).doesNotContain("Motivo Sensible", "12.345.678-9", "B-002", FakeInnpackApi.firmaDeToken(10), PASS);
    }

    // ------------------------------------------------------------------ helpers

    /** "Ver" de Photino: recepcion.detalle (registra la huella de la selección en la sesión). */
    private JsonNode abrir(MockHttpSession sesion, int id) throws Exception {
        MvcResult res = crear(sesion, "{\"action\":\"recepcion.detalle\",\"data\":{\"id\":" + id + "}}").andExpect(jsonPath("$.ok").value(true)).andReturn();
        return json(res).get("data");
    }

    private ResultActions enviar(MockHttpSession sesion, String cuerpo) throws Exception {
        return crear(sesion, cuerpo);
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.23.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-3q");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
