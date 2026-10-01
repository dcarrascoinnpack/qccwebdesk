package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Fase 4b — validar/rechazar/eliminar INDIVIDUAL en Dashboard, Registros Producción y Registros de
 * Control, igual que Photino para el usuario: UPDATE/borrado lógico directo por id, sin autor real
 * (la API hardcodea usuario_validacion='SUPERVISOR') ni validación de que el id exista. Seguridad
 * transparente: lista blanca {action, id}; exige haber visto el registro en una lista cargada en esta
 * sesión; candado por id. Registros de Control además relee por id antes de escribir (detección de
 * conflicto real); Dashboard/Producción no tienen endpoint de detalle por id, así que solo exigen la
 * huella de haberlo visto. validarTodo/rechazarTodo quedan fuera de esta fase.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase4bTest {

    private static final String DASH_RESUMEN = "dashboard.obtenerResumen";
    private static final String DASH_VALIDAR = "dashboard.validarRegistro";
    private static final String DASH_RECHAZAR = "dashboard.rechazarRegistro";
    private static final String DASH_ELIMINAR = "dashboard.eliminarRegistro";

    private static final String PROD_RESUMEN = "registrosProduccion.obtenerResumen";
    private static final String PROD_VALIDAR = "registrosProduccion.validarRegistro";
    private static final String PROD_RECHAZAR = "registrosProduccion.rechazarRegistro";
    private static final String PROD_ELIMINAR = "registrosProduccion.eliminarRegistro";

    private static final String CTRL_OBTENER = "registrosControl.obtenerRegistros";
    private static final String CTRL_VALIDAR = "registrosControl.validarRegistro";
    private static final String CTRL_RECHAZAR = "registrosControl.rechazarRegistro";
    private static final String CTRL_ELIMINAR = "registrosControl.eliminarRegistro";

    private static final String MSG_FALTA_ID = "Falta el id del registro.";
    private static final String MSG_SIN_LEER = "Actualiza la lista antes de validar, rechazar o eliminar este registro.";
    private static final String MSG_CONFLICTO = "El registro fue modificado por otra persona desde que cargaste la lista. "
            + "Actualízala para ver los cambios.";

    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveValidacion#2026";
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

    // ------------------------------------------------------------------ Dashboard

    @Test
    void dashboardValidarRechazarEliminarFlujoCompleto() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode resumen = cargarDashboard(s);
        assertThat(resumen.at("/ultimosRegistros/0/id").asInt()).isEqualTo(500);
        assertThat(resumen.at("/ultimosRegistros/0/estadoValidacion").asString()).isEqualTo("Pendiente");

        JsonNode validar = json(enviar(s, acc(DASH_VALIDAR, 500)).andExpect(status().isOk()).andReturn());
        assertThat(validar.get("ok").asBoolean()).isTrue();
        assertThat(API.peticionesDashboard()).contains("validar:500");
        assertThat(API.estadoValidacionDashboard(500)).isEqualTo("VALIDADO");

        // La vista recarga la lista enseguida: vuelve a registrar la huella y permite rechazar otro.
        cargarDashboard(s);
        enviar(s, acc(DASH_RECHAZAR, 501)).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.estadoValidacionDashboard(501)).isEqualTo("RECHAZADO");

        cargarDashboard(s);
        enviar(s, acc(DASH_ELIMINAR, 500)).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.peticionesDashboard()).contains("eliminar:500");
        JsonNode despues = cargarDashboard(s);
        assertThat(despues.get("ultimosRegistros")).extracting(n -> n.get("id").asInt()).doesNotContain(500);
    }

    @Test
    void dashboardExigeHaberCargadoLaListaAntes() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(DASH_VALIDAR, 500)).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        assertThat(API.peticionesDashboard()).isEmpty();
        // Otra sesión que sí vio la lista, sí puede.
        MockHttpSession otra = login("admin1");
        cargarDashboard(otra);
        enviar(otra, acc(DASH_VALIDAR, 500)).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void dashboardIdFaltanteOInvalido() throws Exception {
        MockHttpSession s = login("operador1");
        cargarDashboard(s);
        for (String id : List.of("0", "-1", "\"x\"", "null")) {
            enviar(s, "{\"action\":\"" + DASH_VALIDAR + "\",\"id\":" + id + "}").andExpect(jsonPath("$.error").value(MSG_FALTA_ID));
        }
        enviar(s, "{\"action\":\"" + DASH_VALIDAR + "\"}").andExpect(jsonPath("$.error").value(MSG_FALTA_ID));
        assertThat(API.peticionesDashboard()).doesNotContain("validar:0", "validar:-1");
    }

    @Test
    void dashboardCampoNoPermitidoSeRechaza() throws Exception {
        MockHttpSession s = login("operador1");
        cargarDashboard(s);
        ObjectNode raiz = mapper.createObjectNode().put("action", DASH_VALIDAR).put("id", 500).put("usuarioNombre", "Otro");
        enviar(s, raiz.toString()).andExpect(jsonPath("$.error").value("Campo no permitido: usuarioNombre"));
        assertThat(API.peticionesDashboard()).doesNotContain("validar:500");
    }

    // ------------------------------------------------------------------ Registros Producción

    @Test
    void produccionValidarRechazarEliminarMismoPatronQueDashboard() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode resumen = cargarProduccion(s);
        assertThat(resumen.at("/area").asString()).isEqualTo("PRODUCCION");
        assertThat(resumen.at("/ultimosRegistros/0/id").asInt()).isEqualTo(500);

        enviar(s, acc(PROD_VALIDAR, 500)).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.estadoValidacionProduccion(500)).isEqualTo("VALIDADO");
        assertThat(API.peticionesProduccion()).contains("validar:500");

        cargarProduccion(s);
        enviar(s, acc(PROD_RECHAZAR, 501)).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.estadoValidacionProduccion(501)).isEqualTo("RECHAZADO");

        cargarProduccion(s);
        enviar(s, acc(PROD_ELIMINAR, 501)).andExpect(jsonPath("$.ok").value(true));
        JsonNode despues = cargarProduccion(s);
        assertThat(despues.get("ultimosRegistros")).extracting(n -> n.get("id").asInt()).doesNotContain(501);
    }

    @Test
    void produccionExigeHaberCargadoLaLista() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(PROD_VALIDAR, 500)).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        assertThat(API.peticionesProduccion()).isEmpty();
    }

    // ------------------------------------------------------------------ Registros de Control

    @Test
    void controlValidarRechazarEliminarFlujoCompleto() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode items = cargarControl(s);
        assertThat(items.at("/0/id").asInt()).isEqualTo(7000);
        assertThat(items.at("/0/estadoValidacion").asString()).isEqualTo("Pendiente");

        enviar(s, acc(CTRL_VALIDAR, 7000)).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.estadoValidacionControl(7000)).isEqualTo("VALIDADO");
        assertThat(API.peticionesControl()).contains("PUT /api/registros-control/7000/validar");

        cargarControl(s);
        enviar(s, acc(CTRL_RECHAZAR, 7001)).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.estadoValidacionControl(7001)).isEqualTo("RECHAZADO");

        cargarControl(s);
        enviar(s, acc(CTRL_ELIMINAR, 7000)).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.peticionesControl()).contains("DELETE /api/registros-control/7000");
        JsonNode despues = cargarControl(s);
        assertThat(despues).extracting(n -> n.get("id").asInt()).doesNotContain(7000);
    }

    @Test
    void controlExigeHaberCargadoLaLista() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc(CTRL_VALIDAR, 7000)).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        assertThat(API.peticionesControl()).isEmpty();
    }

    @Test
    void controlDetectaConflictoSiOtraPersonaYaValidoYReabrirLoPermite() throws Exception {
        MockHttpSession s = login("operador1");
        cargarControl(s);
        API.estadoValidacionControlPorOtro(7000, "VALIDADO");
        enviar(s, acc(CTRL_VALIDAR, 7000)).andExpect(jsonPath("$.error").value(MSG_CONFLICTO));
        assertThat(API.peticionesControl()).doesNotContain("PUT /api/registros-control/7000/validar");
        // Reabrir (recargar la lista) y ver el cambio permite decidir a sabiendas.
        JsonNode recargado = cargarControl(s);
        assertThat(recargado.at("/0/estadoValidacion").asString()).isEqualTo("VALIDADO");
        enviar(s, acc(CTRL_RECHAZAR, 7000)).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void controlDosSesionesSecuencialesLaSegundaRecibeElConflicto() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        cargarControl(a);
        cargarControl(b);
        enviar(a, acc(CTRL_VALIDAR, 7000)).andExpect(jsonPath("$.ok").value(true));
        enviar(b, acc(CTRL_RECHAZAR, 7000)).andExpect(jsonPath("$.error").value(MSG_CONFLICTO));
        assertThat(API.peticionesControl()).filteredOn(p -> p.startsWith("PUT")).hasSize(1);
    }

    // ------------------------------------------------------------------ roles, empresa, contrato

    @Test
    void soloLasNueveEscriturasDe4bQuedanHabilitadas() {
        for (String accion : List.of(DASH_VALIDAR, DASH_RECHAZAR, DASH_ELIMINAR, PROD_VALIDAR, PROD_RECHAZAR, PROD_ELIMINAR,
                CTRL_VALIDAR, CTRL_RECHAZAR, CTRL_ELIMINAR)) {
            Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(accion)).findFirst().orElseThrow();
            assertThat(d.get("escritura")).isEqualTo(true);
            assertThat(d.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
            assertThat(d.get("identidad")).isEqualTo(Map.of());
        }
        for (String bloqueada : List.of("dashboard.validarTodo", "dashboard.rechazarTodo",
                "registrosProduccion.validarTodo", "registrosProduccion.rechazarTodo")) {
            assertThat(policy.accionesRegistradas()).doesNotContain(bloqueada);
        }
    }

    @Test
    void rolConsultaDenegadoEmpresaFaretDenegada() throws Exception {
        MockHttpSession s = login("operador1");
        cargarDashboard(s);
        enviar(login("consulta1"), acc(DASH_VALIDAR, 500)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(policy.evaluar(DASH_VALIDAR, usuario("FARET", "admin")))
                .isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
    }

    @Test
    void auditoriaConRecursoPorModuloIdYAccion(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        cargarDashboard(s);
        enviar(s, acc(DASH_VALIDAR, 500)).andExpect(jsonPath("$.ok").value(true));
        cargarControl(s);
        enviar(s, acc(CTRL_RECHAZAR, 7001)).andExpect(jsonPath("$.ok").value(true));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=dashboard\\.validarRegistro "
                + "recurso=dashboard:500:validar resultado=OK ms=\\d+");
        assertThat(log).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=registrosControl\\.rechazarRegistro "
                + "recurso=registrosControl:7001:rechazar resultado=OK ms=\\d+");
        assertThat(log).doesNotContain(FakeInnpackApi.firmaDeToken(10), PASS);
    }

    @Test
    void csrfYSesionExpirada() throws Exception {
        MockHttpSession s = login("operador1");
        cargarDashboard(s);
        mockMvc.perform(post("/api/v1/bridge").session(s).contentType(MediaType.APPLICATION_JSON).content(acc(DASH_VALIDAR, 500)))
                .andExpect(status().isForbidden());
        API.revocarTokens(10);
        enviar(s, acc(DASH_VALIDAR, 500)).andExpect(status().isUnauthorized());
        assertThat(s.isInvalid()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private static String acc(String accion, int id) {
        return "{\"action\":\"" + accion + "\",\"id\":" + id + "}";
    }

    private JsonNode cargarDashboard(MockHttpSession sesion) throws Exception {
        return json(enviar(sesion, "{\"action\":\"" + DASH_RESUMEN + "\",\"data\":{}}")
                .andExpect(jsonPath("$.ok").value(true)).andReturn()).get("data");
    }

    private JsonNode cargarProduccion(MockHttpSession sesion) throws Exception {
        return json(enviar(sesion, "{\"action\":\"" + PROD_RESUMEN + "\",\"data\":{}}")
                .andExpect(jsonPath("$.ok").value(true)).andReturn()).get("data");
    }

    private JsonNode cargarControl(MockHttpSession sesion) throws Exception {
        return json(enviar(sesion, "{\"action\":\"" + CTRL_OBTENER + "\",\"data\":{}}")
                .andExpect(jsonPath("$.ok").value(true)).andReturn()).get("data").get("items");
    }

    private ResultActions enviar(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
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

    private JsonNode json(MvcResult res) throws Exception {
        return mapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static SessionUser usuario(String empresa, String rol) {
        return new SessionUser(1, "u", "U", rol, empresa, "t", Instant.now(), Instant.now().plusSeconds(60));
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-4b");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
