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
import tools.jackson.databind.node.ObjectNode;

/**
 * Fase 3n — noConformidades.acciones.actualizar (cambiar el estado de una acción correctiva). Para el usuario, igual que
 * Photino (solo cambia el estado; también en NC cerradas, decisión 3n-b). Foco: la vista reenvía descripción/responsable
 * copiados de acciones.list (que la web ESCAPA): el gateway envía los ORIGINALES (sin doble escape ni falsificación),
 * identidad de sesión y lost update. API SIMULADA: se verifica el cuerpo EXACTO que recibe upstream.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3nTest {

    private static final String ACTUALIZAR = "noConformidades.acciones.actualizar";
    private static final String MSG_SIN_LEER = "Abre el análisis de la no conformidad antes de actualizar la acción.";
    private static final String MSG_CONFLICTO = "La acción fue modificada por otra persona desde que la abriste. "
            + "Vuelve a abrir el análisis para ver los cambios.";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveAccionesAct#2026";
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

    /** Payload EXACTO de _actualizarEstadoAccion de Photino, armado desde el ítem que devolvió acciones.list. */
    private String payloadDesde(JsonNode accion, String nuevoEstado, Map<String, Object> cambios) {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", ACTUALIZAR);
        p.put("accionId", accion.get("id").asInt());
        p.set("descripcion", accion.get("descripcion"));
        p.set("responsable", accion.get("responsable"));
        String fecha = accion.path("fechaLimite").asString("");
        p.put("fechaLimite", fecha.length() > 10 ? fecha.substring(0, 10) : fecha);
        if (accion.path("prioridad").asString("").isEmpty()) {
            p.putNull("prioridad");
        } else {
            p.set("prioridad", accion.get("prioridad"));
        }
        p.put("estado", nuevoEstado);
        p.put("actualizadoPor", "Operador Uno");
        cambios.forEach((k, v) -> p.set(k, mapper.valueToTree(v)));
        return p.toString();
    }

    // ------------------------------------------------------------------ flujo exitoso

    @Test
    void cambioDeEstadoEnviaLosValoresOriginalesSinDobleEscape() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode accion = listar(s, 901).get(0);
        assertThat(accion.get("descripcion").asString()).isEqualTo("&lt;img src=x onerror=alert(1)&gt; &amp; ñ"); // escapada para la vista
        enviar(s, payloadDesde(accion, "COMPLETADA", Map.of())).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.peticionesNoConformidades()).containsExactly("GET /api/no-conformidades/901/acciones",
                "GET /api/no-conformidades/901/acciones", "PUT /api/no-conformidades/acciones/4");
        SeguimientoRecibido r = API.accionesActualizadas().get(0);
        assertThat(r.ncId()).isEqualTo(4);
        assertThat(r.sub()).isEqualTo(10);
        assertThat(mapper.readTree(r.cuerpo())).isEqualTo(mapper.readTree("{\"descripcion\":\"<img src=x onerror=alert(1)> & ñ\","
                + "\"responsable\":\"<b>Mallory</b>\",\"fechaLimite\":\"2026-10-15\",\"prioridad\":\"ALTA\",\"estado\":\"COMPLETADA\","
                + "\"actualizadoPor\":\"Operador Uno\"}"));
        // La vista recarga la lista: refleja el nuevo estado y se puede volver a cambiar.
        JsonNode recargada = listar(s, 901).get(0);
        assertThat(recargada.get("estado").asString()).isEqualTo("COMPLETADA");
        enviar(s, payloadDesde(recargada, "EN_PROCESO", Map.of())).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void soloElEstadoVieneDelNavegadorEIdentidadDeSesion() throws Exception {
        String[][] casos = {{"operador1", "Operador Uno"}, {"admin1", "Admin Uno"}, {"adminti1", "Admin TI"}};
        for (String[] c : casos) {
            MockHttpSession s = login(c[0]);
            JsonNode accion = listar(s, 501).get(0);
            enviar(s, payloadDesde(accion, "EN_PROCESO", Map.of("descripcion", "Otra cosa", "responsable", "Hacker",
                    "fechaLimite", "2030-01-01", "prioridad", "BAJA", "actualizadoPor", "Otro Usuario"))).andExpect(jsonPath("$.ok").value(true));
        }
        for (int k = 0; k < 3; k++) {
            JsonNode b = mapper.readTree(API.accionesActualizadas().get(k).cuerpo());
            assertThat(b.get("descripcion").asString()).isEqualTo("Cambiar rodillo");
            assertThat(b.get("responsable").asString()).isEqualTo("Juan Pérez");
            assertThat(b.get("fechaLimite").asString()).isEqualTo("2026-10-20");
            assertThat(b.get("prioridad").isNull()).isTrue();
            assertThat(b.get("actualizadoPor").asString()).isEqualTo(casos[k][1]);
        }
    }

    @Test
    void ncCerradaPermitidaComoEnPhotino() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode accion = listar(s, 503).get(0); // la NC 503 del fake está CERRADA
        enviar(s, payloadDesde(accion, "CANCELADA", Map.of())).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.accionesActualizadas()).hasSize(1);
    }

    // ------------------------------------------------------------------ lectura previa y lost update

    @Test
    void sinListarOListadaEnOtraSesionSeRechaza() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode accion = listar(login("operador1"), 901).get(0); // otra sesión
        enviar(s, payloadDesde(accion, "COMPLETADA", Map.of())).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        enviar(s, payloadDesde(accion, "COMPLETADA", Map.of("accionId", 999))).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        assertThat(API.accionesActualizadas()).isEmpty();
    }

    @Test
    void cambioPorOtraPersonaYReintentoTrasGuardar() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode accion = listar(s, 901).get(0);
        API.modificarAccionPorOtro(4);
        enviar(s, payloadDesde(accion, "COMPLETADA", Map.of())).andExpect(jsonPath("$.error").value(MSG_CONFLICTO));
        assertThat(API.accionesActualizadas()).isEmpty();
        accion = listar(s, 901).get(0);
        enviar(s, payloadDesde(accion, "COMPLETADA", Map.of())).andExpect(jsonPath("$.ok").value(true));
        // Sin recargar la lista, un segundo cambio no se acepta (la vista siempre recarga tras guardar).
        enviar(s, payloadDesde(accion, "PENDIENTE", Map.of())).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        assertThat(API.accionesActualizadas()).hasSize(1);
    }

    // ------------------------------------------------------------------ validación

    @Test
    void estadoCamposYTipos() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode accion = listar(s, 901).get(0);
        enviar(s, payloadDesde(accion, "", Map.of())).andExpect(jsonPath("$.error").value("Falta el estado"));
        enviar(s, payloadDesde(accion, "CERRADA", Map.of()))
                .andExpect(jsonPath("$.error").value("Estado inválido. Valores permitidos: PENDIENTE, EN_PROCESO, COMPLETADA, CANCELADA"));
        enviar(s, payloadDesde(accion, "completada", Map.of())).andExpect(jsonPath("$.ok").value(false));
        enviar(s, payloadDesde(accion, "COMPLETADA", Map.of("descripcion", 5))).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        for (String extra : List.of("id", "analisisId", "creadoPor", "usuario", "empresa", "ncId")) {
            enviar(s, payloadDesde(accion, "COMPLETADA", Map.of(extra, 1))).andExpect(jsonPath("$.error").value("Campo no permitido: " + extra));
        }
        ObjectNode sinId = (ObjectNode) mapper.readTree(payloadDesde(accion, "COMPLETADA", Map.of()));
        sinId.remove("accionId");
        enviar(s, sinId.toString()).andExpect(jsonPath("$.error").value("Falta el id de la acción correctiva"));
        assertThat(API.accionesActualizadas()).isEmpty();
    }

    // ------------------------------------------------------------------ autorización y auditoría

    @Test
    void rolesPoliticaYAuditoriaSinTextos(CapturedOutput salida) throws Exception {
        crear(login("consulta1"), "{\"action\":\"" + ACTUALIZAR + "\",\"accionId\":4,\"estado\":\"COMPLETADA\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(policy.evaluar(ACTUALIZAR, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(ACTUALIZAR)).findFirst().orElseThrow();
        assertThat(d.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
        assertThat(d.get("identidad")).isEqualTo(Map.of("actualizadoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO));
        MockHttpSession s = login("operador1");
        JsonNode accion = listar(s, 901).get(0);
        enviar(s, payloadDesde(accion, "COMPLETADA", Map.of())).andExpect(jsonPath("$.ok").value(true));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=noConformidades\\.acciones\\.actualizar "
                + "recurso=nc:901:accion:4:COMPLETADA resultado=OK ms=\\d+");
        assertThat(log).doesNotContain("Mallory", "onerror", FakeInnpackApi.firmaDeToken(10), PASS);
    }

    // ------------------------------------------------------------------ helpers

    /** acciones.list de la NC (tal como la recibe la vista: descripción/responsable escapados). */
    private JsonNode listar(MockHttpSession sesion, int ncId) throws Exception {
        MvcResult res = crear(sesion, "{\"action\":\"noConformidades.acciones.list\",\"id\":" + ncId + "}")
                .andExpect(jsonPath("$.ok").value(true)).andReturn();
        return mapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private ResultActions enviar(MockHttpSession sesion, String cuerpo) throws Exception {
        return crear(sesion, cuerpo);
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.22.0." + IP.getAndIncrement());
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

    private static SessionUser usuario(String empresa, String rol) {
        return new SessionUser(1, "u", "U", rol, empresa, "t", Instant.now(), Instant.now().plusSeconds(60));
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-3n");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
