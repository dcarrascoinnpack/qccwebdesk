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
 * Fase 4a — recepcion.plan.generar ("Generar plan" del detalle), igual que Photino para el usuario: la API calcula el plan
 * NCh44 con el tamaño del lote y lo guarda (UPDATE, o INSERT si no había — R10); regenerar reemplaza sin preguntar. Sin
 * autor. Seguridad transparente: lista blanca, nivel/AQL = opciones de los selects de Photino (defaults del handler C#),
 * lote de la empresa de sesión, detalle abierto en la sesión y candado por lote (dos INSERT simultáneos → PK, R10).
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase4aTest {

    private static final String GENERAR = "recepcion.plan.generar";
    private static final String MSG_FALTA = "Falta indicar el lote";
    private static final String MSG_SIN_LEER = "Abre el detalle del lote antes de generar el plan de muestreo.";
    private static final String MSG_NIVEL = "Valor no permitido en nivelInspeccion.";
    private static final String MSG_AQL = "Valor no permitido en aql.";
    private static final String SOLO_VISTA = "Solo vista: no tienes permiso para modificar datos en este módulo.";
    private static final String CUERPO_II_25 = "{\"nivelInspeccion\":\"II\",\"aql\":2.5}";
    private static final List<String> RECEPCION = List.of("recepcion.list", "recepcion.detalle", "recepcion.foto.abrir",
            "recepcion.sap.consultar", "recepcion.sap.lotes", "recepcion.bobinas.muestrear", "recepcion.muestra.crear",
            "recepcion.estado.actualizar", "recepcion.nc.crear", "recepcion.crear", GENERAR);
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClavePlanLote#2026";
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

    /** Payload EXACTO de generarPlan de Photino INNPACK: {action, data:{loteId, nivelInspeccion, aql}}. */
    private static String payload(Object loteId, Object nivel, Object aql) {
        return "{\"action\":\"" + GENERAR + "\",\"data\":{\"loteId\":" + loteId + ",\"nivelInspeccion\":" + nivel + ",\"aql\":" + aql + "}}";
    }

    /** Lo que envía la vista de Photino con los selects por defecto (II / parseFloat("2.5")). */
    private static String pSelects(int loteId) {
        return payload(loteId, "\"II\"", "2.5");
    }

    // ------------------------------------------------------------------ flujo exitoso y refresco

    @Test
    void generacionCorrectaConCuerpoExactoYRefresco() throws Exception {
        MockHttpSession s = login("operador1");
        assertThat(abrir(s, 20).get("plan").isNull()).isTrue();
        JsonNode json = json(enviar(s, pSelects(20)).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.at("/data/letraCodigo").asString()).isEqualTo("A");
        assertThat(json.at("/data/tamanoMuestra").asInt()).isEqualTo(2);
        assertThat(API.peticionesRecepcion()).containsExactly("GET /api/recepcion-calidad/20?empresa=INNPACK",
                "GET /api/recepcion-calidad/20?empresa=INNPACK", "POST /api/recepcion-calidad/20/plan");
        SeguimientoRecibido r = API.planesRecibidos().get(0);
        assertThat(r.sub()).isEqualTo(10);
        assertThat(r.contentType()).startsWith("application/json");
        assertThat(mapper.readTree(r.cuerpo())).isEqualTo(mapper.readTree(CUERPO_II_25));
        assertThat(API.planesInsertados()).isEqualTo(1);
        // Refresco inmediato (la vista reabre el detalle): "Plan vigente" con el plan generado.
        JsonNode plan = abrir(s, 20).get("plan");
        assertThat(plan.get("letraCodigo").asString()).isEqualTo("A");
        assertThat(plan.get("numeroAceptacion").asInt()).isZero();
    }

    @Test
    void defaultsDelHandlerDePhotino() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        // GetString: vacío/espacios/null/no texto → "II". GetDecimal: ausente/null/NaN de parseFloat/no numérico → 2.5.
        List<String> cuerpos = List.of(payload(20, "\"\"", "2.5"), payload(20, "\"  \"", "2.5"), payload(20, "null", "2.5"),
                payload(20, "true", "2.5"), payload(20, "\"II\"", "null"), payload(20, "\"II\"", "\"2.5\""),
                payload(20, "\"II\"", "\"2.50\""), payload(20, "\"II\"", "\"abc\""),
                "{\"action\":\"" + GENERAR + "\",\"data\":{\"loteId\":20}}", "{\"action\":\"" + GENERAR + "\",\"data\":{\"loteId\":\"20\"}}");
        for (String cuerpo : cuerpos) {
            enviar(s, cuerpo).andExpect(jsonPath("$.ok").value(true));
        }
        assertThat(API.planesRecibidos()).hasSize(cuerpos.size());
        for (SeguimientoRecibido r : API.planesRecibidos()) {
            assertThat(mapper.readTree(r.cuerpo())).isEqualTo(mapper.readTree(CUERPO_II_25));
        }
    }

    // ------------------------------------------------------------------ plan ya existente / otra sesión

    @Test
    void planExistenteSeRegeneraYReemplazaComoPhotino() throws Exception {
        API.generarPlanPorOtro(20); // otra persona (Photino u otra sesión) ya lo generó
        MockHttpSession s = login("operador1");
        assertThat(abrir(s, 20).get("plan").get("letraCodigo").asString()).isEqualTo("A");
        enviar(s, pSelects(20)).andExpect(jsonPath("$.ok").value(true));
        // Sin volver a abrir (doble envío tardío / segundo clic): regenerar es idempotente, se permite como en Photino.
        enviar(s, pSelects(20)).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.planesRecibidos()).hasSize(2);
        assertThat(API.planesInsertados()).isZero(); // UPDATE del plan existente (R10), nunca un segundo INSERT
        assertThat(abrir(s, 20).get("plan").get("tamanoMuestra").asInt()).isEqualTo(2);
    }

    @Test
    void planGeneradoPorOtraSesionDespuesDeAbrirNoEsConflicto() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        abrir(a, 22);
        abrir(b, 22);
        enviar(b, pSelects(22)).andExpect(jsonPath("$.ok").value(true));
        // El plan es determinista (lote, nivel y AQL): regenerarlo sobre el de la otra sesión no pierde nada.
        enviar(a, pSelects(22)).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.planesInsertados()).isEqualTo(1);
        assertThat(API.planesRecibidos()).extracting(SeguimientoRecibido::sub).containsExactly(20, 10);
    }

    @Test
    void dobleClicYDosSesionesSimultaneasSinViolarLaPk() throws Exception {
        API.demoraPlan(400);
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        List<JsonNode> doble = simultaneos(() -> json(enviar(s, pSelects(20)).andReturn()),
                () -> json(enviar(s, pSelects(20)).andReturn()));
        // Photino: dos clics = dos generaciones con el mismo resultado. Sin el candado, la PK rechazaría el 2º INSERT (R10).
        assertThat(doble).allSatisfy(j -> assertThat(j.get("ok").asBoolean()).isTrue());
        assertThat(API.planesInsertados()).isEqualTo(1);

        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        abrir(a, 22);
        abrir(b, 22);
        List<JsonNode> dos = simultaneos(() -> json(enviar(a, pSelects(22)).andReturn()),
                () -> json(enviar(b, pSelects(22)).andReturn()));
        assertThat(dos).allSatisfy(j -> assertThat(j.get("ok").asBoolean()).isTrue());
        assertThat(API.planesInsertados()).isEqualTo(2);
        assertThat(API.planesRecibidos()).hasSize(4);
    }

    // ------------------------------------------------------------------ identidad, empresa, roles y permisos

    @Test
    void identidadEmpresaYRolesComoPhotino() throws Exception {
        for (String usuario : List.of("operador1", "admin1", "adminti1")) {
            MockHttpSession s = login(usuario);
            abrir(s, 20);
            enviar(s, pSelects(20)).andExpect(jsonPath("$.ok").value(true));
        }
        assertThat(API.planesRecibidos()).extracting(SeguimientoRecibido::sub).containsExactly(10, 20, 25);
        enviar(login("consulta1"), pSelects(20)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(policy.evaluar(GENERAR, usuario("FARET", "admin")))
                .isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(GENERAR)).findFirst().orElseThrow();
        assertThat(d.get("escritura")).isEqualTo(true);
        assertThat(d.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
        assertThat(d.get("identidad")).isEqualTo(Map.of());
        // Recepción completa: las 11 acciones de Photino habilitadas.
        assertThat(policy.accionesRegistradas()).containsAll(RECEPCION);
    }

    @Test
    void soloVistaDelModuloRechazaSinLlamarALaApi() throws Exception {
        API.permisos(10, "[{\"modulo\":\"recepcion-calidad\",\"nivel\":\"VER\"}]");
        MockHttpSession s = login("operador1");
        crear(s, "{\"action\":\"recepcion.detalle\",\"data\":{\"id\":20},\"_modulo\":\"recepcion-calidad\"}")
                .andExpect(jsonPath("$.ok").value(true));
        crear(s, "{\"action\":\"" + GENERAR + "\",\"data\":{\"loteId\":20,\"nivelInspeccion\":\"II\",\"aql\":2.5},"
                + "\"_modulo\":\"recepcion-calidad\"}").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(SOLO_VISTA));
        assertThat(API.planesRecibidos()).isEmpty();
    }

    @Test
    void conEditarDelModuloFuncionaYElModuloNoLlegaAlHandler() throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, "{\"action\":\"recepcion.detalle\",\"data\":{\"id\":20},\"_modulo\":\"recepcion-calidad\"}")
                .andExpect(jsonPath("$.ok").value(true));
        crear(s, "{\"action\":\"" + GENERAR + "\",\"data\":{\"loteId\":20,\"nivelInspeccion\":\"II\",\"aql\":2.5},"
                + "\"_modulo\":\"recepcion-calidad\"}").andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(API.planesRecibidos().get(0).cuerpo())).isEqualTo(mapper.readTree(CUERPO_II_25));
    }

    // ------------------------------------------------------------------ validaciones

    @Test
    void camposNoPermitidosSeRechazanSinLlamarALaApi() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        for (String campo : List.of("empresa", "usuarioId", "usuario", "usuarioNombre", "tamanoLote", "letraCodigo", "norma")) {
            ObjectNode raiz = (ObjectNode) mapper.readTree(pSelects(20));
            raiz.put(campo, "FARET");
            enviar(s, raiz.toString()).andExpect(jsonPath("$.error").value("Campo no permitido: " + campo));
            ObjectNode enData = (ObjectNode) mapper.readTree(pSelects(20));
            ((ObjectNode) enData.get("data")).put(campo, "99");
            enviar(s, enData.toString()).andExpect(jsonPath("$.error").value("Campo no permitido: " + campo));
        }
        assertThat(API.planesRecibidos()).isEmpty();
    }

    @Test
    void loteFaltanteOInvalido() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        for (String lote : List.of("0", "-1", "\"abc\"", "true", "null", "1.5", "{}")) {
            enviar(s, payload(lote, "\"II\"", "2.5")).andExpect(jsonPath("$.error").value(MSG_FALTA));
        }
        enviar(s, "{\"action\":\"" + GENERAR + "\",\"data\":{}}").andExpect(jsonPath("$.error").value(MSG_FALTA));
        enviar(s, "{\"action\":\"" + GENERAR + "\"}").andExpect(jsonPath("$.error").value(MSG_FALTA));
        assertThat(API.planesRecibidos()).isEmpty();
    }

    @Test
    void nivelYAqlFueraDeLosSelectsDePhotino() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        for (String nivel : List.of("\"I\"", "\"III\"", "\"ii\"", "\" II \"", "\"S-1\"", "\"<b>II</b>\"", "2")) {
            enviar(s, payload(20, nivel, "2.5")).andExpect(jsonPath("$.error").value(MSG_NIVEL));
        }
        for (String aql : List.of("0", "1.0", "4", "6.5", "-2.5", "\"4.0\"", "1e3")) {
            enviar(s, payload(20, "\"II\"", aql)).andExpect(jsonPath("$.error").value(MSG_AQL));
        }
        assertThat(API.planesRecibidos()).isEmpty();
    }

    @Test
    void errorDeNegocioDeLaApiLlegaComoEnPhotino() throws Exception {
        MockHttpSession s = login("operador1");
        // PVA: cantidad_total_lote = 0 (sin bobinas) → la API no encuentra letra NCh44.
        abrir(s, 1);
        enviar(s, pSelects(1)).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("No hay tabla de muestreo NCh44 cargada para nivel II y tamaño de lote 0"));
        abrir(s, 21);
        enviar(s, pSelects(21)).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("No hay tabla de muestreo NCh44 cargada para nivel II y tamaño de lote 1"));
        assertThat(API.planesInsertados()).isZero();
    }

    // ------------------------------------------------------------------ lectura previa, empresa y sesión

    @Test
    void sinAbrirElLoteOConLaAperturaDeOtraSesionNoSeGenera() throws Exception {
        enviar(login("operador1"), pSelects(20)).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        MockHttpSession a = login("operador1");
        abrir(a, 20);
        MockHttpSession b = login("operador1");
        enviar(b, pSelects(20)).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        // Abrir el lote 20 no habilita otro lote.
        enviar(a, pSelects(22)).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        assertThat(API.planesRecibidos()).isEmpty();
    }

    @Test
    void loteInexistenteODeOtraEmpresaNoSePuedeGenerar() throws Exception {
        MockHttpSession s = login("operador1");
        // Nunca se pudo abrir (empresa≠INNPACK o inexistente en la simulación): sin lectura registrada.
        crear(s, "{\"action\":\"recepcion.detalle\",\"data\":{\"id\":99}}").andExpect(jsonPath("$.ok").value(false));
        enviar(s, pSelects(99)).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        assertThat(API.planesRecibidos()).isEmpty();
    }

    @Test
    void sinSesionCsrfY401() throws Exception {
        mockMvc.perform(post("/api/v1/bridge").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(pSelects(20)))
                .andExpect(status().isUnauthorized());
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        mockMvc.perform(post("/api/v1/bridge").session(s).contentType(MediaType.APPLICATION_JSON).content(pSelects(20)))
                .andExpect(status().isForbidden());
        API.revocarTokens(10);
        enviar(s, pSelects(20)).andExpect(status().isUnauthorized());
        assertThat(s.isInvalid()).isTrue();
        assertThat(API.planesRecibidos()).isEmpty();
    }

    // ------------------------------------------------------------------ auditoría

    @Test
    void auditoriaConLoteYLetraSinTokens(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 20);
        enviar(s, pSelects(20)).andExpect(jsonPath("$.ok").value(true));
        abrir(s, 1);
        enviar(s, pSelects(1)).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=recepcion\\.plan\\.generar "
                + "recurso=recepcion:20:plan:A resultado=OK ms=\\d+");
        assertThat(log).contains("accion=recepcion.plan.generar recurso=recepcion:1:plan resultado=ERROR");
        assertThat(log).doesNotContain(FakeInnpackApi.firmaDeToken(10), PASS);
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

    /** "Ver" de Photino: recepcion.detalle (registra la lectura en la sesión). */
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
                            r.setRemoteAddr("10.25.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-4a");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
