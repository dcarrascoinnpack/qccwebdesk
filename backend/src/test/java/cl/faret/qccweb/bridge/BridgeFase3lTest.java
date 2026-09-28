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
import cl.faret.qccweb.bridge.handlers.NoConformidadesBridgeHandler;
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
 * Fase 3l — noConformidades.update (editar NC). Mismo contrato que create (validación común, probada en 3j) +
 * IDENTIDAD (actualizadoPor → sesión), LOST UPDATE (huella del detalle abierto en la sesión, relectura antes del PUT),
 * NC CERRADA editable igual que Photino (Fase 3o). API SIMULADA: se verifica el cuerpo EXACTO que recibe upstream.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3lTest {

    private static final String EDITAR = "noConformidades.update";
    private static final String MSG_SIN_LEER = "Abre la no conformidad antes de editarla.";
    private static final String MSG_CONFLICTO = "La no conformidad fue modificada por otra persona desde que la abriste. "
            + "Ciérrala y vuelve a abrirla para ver los cambios antes de editar.";
    private static final String MSG_HTML = "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").";
    private static final String MSG_CARACTERES = "El texto contiene caracteres no permitidos.";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveEditarNc#2026";
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

    /** Payload exacto de _guardarForm de Photino en modo edición ({action, id, actualizadoPor, ...payload}). */
    private String payload(Map<String, Object> cambios) {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", EDITAR);
        p.put("id", 501);
        p.put("actualizadoPor", "Operador Uno");
        p.put("fechaIngreso", "2026-09-28");
        p.put("npNv", "3996");
        p.put("cliente", "Viña Ñandú Ltda.");
        p.put("codigoProducto", "CP-40");
        p.put("producto", "Caja 40x30");
        p.put("familiaProducto", "Cajas");
        p.put("tipoPnc", "Cuarentena");
        p.put("nivel", "Mayor");
        p.put("categoriaDefecto", "Impresión");
        p.put("tipoFalla", "Corrimiento");
        p.put("impacto", "Calidad");
        p.put("cantRequerida", 1000);
        p.put("cantRechazada", 120.5);
        p.putNull("cantRecuperada");
        p.putNull("pncReal");
        p.put("disposicion", "No aplica");
        p.putNull("cantDestruida");
        p.putNull("cantRepuesta");
        p.put("area", "Impresión");
        p.put("maquina", "Bobst 1");
        p.put("operador", "Pedro Soto");
        p.put("supervisor", "Ana Díaz");
        p.put("revisadoPor", "");
        p.putNull("fechaSalida");
        p.putNull("fechaFabricacion");
        p.put("descripcionDefecto", "Registro corrido\nen 2 colores");
        p.put("observacion", "");
        p.put("causaRaiz", "");
        p.put("accionesCorrectivas", "");
        p.put("verificacionSeguimiento", "");
        // Cabecera que arma Photino en el navegador (el gateway la recalcula).
        p.put("tipo", "INTERNA");
        p.put("origen", "AUDITORIA_INTERNA");
        p.put("titulo", "PNC 3996 - Caja 40x30");
        p.put("descripcion", "Impresión - Registro corrido\nen 2 colores");
        p.put("severidad", "MEDIA");
        p.put("proceso", "Cuarentena");
        p.put("fechaDeteccion", "2026-09-28");
        cambios.forEach((k, v) -> {
            if (v == Borrar.CAMPO) {
                p.remove(k);
            } else {
                p.set(k, mapper.valueToTree(v));
            }
        });
        return p.toString();
    }

    private enum Borrar { CAMPO }

    // ------------------------------------------------------------------ flujo exitoso

    @Test
    void edicionTrasAbrirConCuerpoExacto() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 501);
        JsonNode json = json(editar(s, payload(Map.of())).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.at("/data/id").asInt()).isEqualTo(501);
        assertThat(API.peticionesNoConformidades()).containsExactly("GET /api/no-conformidades/501", "GET /api/no-conformidades/501",
                "PUT /api/no-conformidades/501");
        SeguimientoRecibido r = unica();
        assertThat(r.ncId()).isEqualTo(501);
        assertThat(r.sub()).isEqualTo(10);
        ObjectNode esperado = (ObjectNode) mapper.readTree(payload(Map.of()));
        esperado.remove("action");
        esperado.remove("id");
        assertThat(mapper.readTree(r.cuerpo())).isEqualTo(esperado);
        assertThat(mapper.readTree(r.cuerpo()).propertyNames()).hasSize(38).doesNotContain("creadoPor", "id", "empresa");
    }

    @Test
    void actualizadoPorSiempreEsLaSesionYCabeceraRecalculada() throws Exception {
        String[][] casos = {{"operador1", "Operador Uno"}, {"admin1", "Admin Uno"}, {"adminti1", "Admin TI"}};
        for (String[] c : casos) {
            MockHttpSession s = login(c[0]);
            abrir(s, 501);
            editar(s, payload(Map.of("actualizadoPor", "Otro Usuario", "severidad", "BAJA", "titulo", "x"))).andExpect(jsonPath("$.ok").value(true));
        }
        List<SeguimientoRecibido> r = API.ncActualizadasRecibidas();
        assertThat(r).hasSize(3);
        for (int k = 0; k < 3; k++) {
            JsonNode c = mapper.readTree(r.get(k).cuerpo());
            assertThat(c.get("actualizadoPor").asString()).isEqualTo(casos[k][1]);
            assertThat(c.get("severidad").asString()).isEqualTo("MEDIA");
            assertThat(c.get("titulo").asString()).isEqualTo("PNC 3996 - Caja 40x30");
        }
    }

    // ------------------------------------------------------------------ lost update y estado

    @Test
    void sinAbrirOAbiertaEnOtraSesionSeRechaza() throws Exception {
        MockHttpSession s = login("operador1");
        editar(s, payload(Map.of())).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        abrir(login("operador1"), 501); // otra sesión del mismo usuario
        editar(s, payload(Map.of())).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        abrir(s, 502);
        editar(s, payload(Map.of())).andExpect(jsonPath("$.error").value(MSG_SIN_LEER)); // abrió otra NC
        assertThat(API.ncActualizadasRecibidas()).isEmpty();
    }

    @Test
    void cambioPorOtraPersonaEntreAbrirYGuardarSeRechazaSinEscribir() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 501);
        API.modificarNcPorOtro(501);
        editar(s, payload(Map.of())).andExpect(jsonPath("$.error").value(MSG_CONFLICTO));
        assertThat(API.ncActualizadasRecibidas()).isEmpty();
        abrir(s, 501); // reabre y ve los cambios
        editar(s, payload(Map.of())).andExpect(jsonPath("$.ok").value(true));
        // Dos sesiones abren la misma NC: la segunda en guardar recibe el conflicto (no pisa a la primera).
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        abrir(a, 502);
        abrir(b, 502);
        editar(a, payload(Map.of("id", 502))).andExpect(jsonPath("$.ok").value(true));
        editar(b, payload(Map.of("id", 502))).andExpect(jsonPath("$.error").value(MSG_CONFLICTO));
        assertThat(API.ncActualizadasRecibidas()).hasSize(2);
    }

    @Test
    void trasGuardarHayQueReabrirParaVolverAEditar() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 501);
        editar(s, payload(Map.of())).andExpect(jsonPath("$.ok").value(true));
        editar(s, payload(Map.of())).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        assertThat(API.ncActualizadasRecibidas()).hasSize(1);
    }

    @Test
    void ncCerradaSeEditaComoEnPhotinoEInexistenteNo() throws Exception {
        MockHttpSession s = login("admin1");
        abrir(s, 503); // CERRADA en el fake
        editar(s, payload(Map.of("id", 503))).andExpect(jsonPath("$.ok").value(true));
        crear(s, "{\"action\":\"noConformidades.get\",\"id\":404}").andExpect(jsonPath("$.ok").value(false));
        editar(s, payload(Map.of("id", 404))).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        assertThat(API.ncActualizadasRecibidas()).hasSize(1);
        assertThat(API.ncActualizadasRecibidas().get(0).ncId()).isEqualTo(503);
    }

    // ------------------------------------------------------------------ lista blanca y validación común

    @Test
    void camposNoPermitidosIdYValidacionComun() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 501);
        for (String extra : List.of("creadoPor", "empresa", "ambito", "estado", "estadoGestion", "responsable", "eliminado", "codigo")) {
            editar(s, payload(Map.of(extra, "x"))).andExpect(jsonPath("$.error").value("Campo no permitido: " + extra));
        }
        editar(s, payload(Map.of("id", Borrar.CAMPO))).andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
        editar(s, payload(Map.of("id", "abc"))).andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
        editar(s, payload(Map.of("cliente", "<b>x</b>"))).andExpect(jsonPath("$.error").value(MSG_HTML));
        editar(s, payload(Map.of("npNv", "9".repeat(101)))).andExpect(jsonPath("$.error").value("El campo npNv supera el máximo de 100 caracteres."));
        editar(s, payload(Map.of("npNv", ""))).andExpect(jsonPath("$.error").value(NoConformidadesBridgeHandler.MENSAJE_NC_OBLIGATORIOS));
        assertThat(API.ncActualizadasRecibidas()).isEmpty();
    }

    // ------------------------------------------------------------------ autorización, errores y auditoría

    @Test
    void rolesYPolitica() throws Exception {
        MockHttpSession c = login("consulta1");
        editar(c, payload(Map.of())).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(policy.evaluar(EDITAR, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        assertThat(policy.evaluar(EDITAR, usuario("INNPACK", "consulta"))).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
        Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(EDITAR)).findFirst().orElseThrow();
        assertThat(d.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
        assertThat(d.get("identidad")).isEqualTo(Map.of("actualizadoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO));
        assertThat(policy.accionesRegistradas()).doesNotContain("noConformidades.eliminar", "noConformidades.adjuntos.eliminar");
    }

    @Test
    void errorDeNegocioYAuditoriaSinContenido(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 501);
        editar(s, payload(Map.of("cliente", "ERROR_API"))).andExpect(jsonPath("$.error").value("No se recibió ningún campo para actualizar"));
        editar(s, payload(Map.of("cliente", "Cliente Sensible 12.345.678-9"))).andExpect(jsonPath("$.ok").value(true));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=noConformidades\\.update "
                + "recurso=nc:501 resultado=ERROR ms=\\d+");
        assertThat(log).containsPattern("accion=noConformidades\\.update recurso=nc:501 resultado=OK");
        assertThat(log).doesNotContain("Cliente Sensible", "12.345.678-9", "Registro corrido", FakeInnpackApi.firmaDeToken(10), PASS);
    }

    // ------------------------------------------------------------------ helpers

    private SeguimientoRecibido unica() {
        List<SeguimientoRecibido> r = API.ncActualizadasRecibidas();
        assertThat(r).hasSize(1);
        return r.get(0);
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.20.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    /** "Ver" de Photino: noConformidades.get (registra la huella de lo leído en la sesión). */
    private void abrir(MockHttpSession sesion, int id) throws Exception {
        crear(sesion, "{\"action\":\"noConformidades.get\",\"id\":" + id + "}").andExpect(jsonPath("$.ok").value(true));
    }

    private ResultActions editar(MockHttpSession sesion, String cuerpo) throws Exception {
        return crear(sesion, cuerpo);
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
            Path www = Files.createTempDirectory("qcc-web-fixture-3l");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
