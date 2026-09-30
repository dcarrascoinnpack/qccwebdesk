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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fase 3m/3o — modal "Gestionar": noConformidades.gestion.actualizar y noConformidades.cerrar con las MISMAS reglas y
 * roles que Photino (cualquier usuario INNPACK; gestión acepta CERRADA y reabre; cerrar otra vez vuelve a registrar
 * quién/cuándo). Seguridad transparente: identidad de sesión, listas blancas, lost update en gestión. API SIMULADA.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3mTest {

    private static final String GESTION = "noConformidades.gestion.actualizar";
    private static final String CERRAR = "noConformidades.cerrar";
    private static final String MSG_SIN_LEER = "Abre la gestión de la no conformidad antes de guardarla.";
    private static final String MSG_CONFLICTO = "La no conformidad fue modificada por otra persona desde que la abriste. "
            + "Ciérrala y vuelve a abrirla para ver los cambios antes de editar.";
    private static final String MSG_HTML = "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").";
    private static final String MSG_CARACTERES = "El texto contiene caracteres no permitidos.";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveGestionNc#2026";
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

    /** Payload exacto de _guardarGestion de Photino. */
    private String gestion(Map<String, Object> cambios) {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", GESTION);
        p.put("id", 501);
        p.put("responsable", "Juan Pérez (Calidad)");
        p.put("estadoGestion", "PENDIENTE");
        p.put("fechaCompromiso", "2026-10-15");
        p.put("actualizadoPor", "Admin Uno");
        cambios.forEach((k, v) -> {
            if (v == Borrar.CAMPO) {
                p.remove(k);
            } else {
                p.set(k, mapper.valueToTree(v));
            }
        });
        return p.toString();
    }

    /** Payload exacto de _cerrarNc de Photino. */
    private String cierre(int id, Object comentario) {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", CERRAR);
        p.put("id", id);
        p.put("cerradoPor", "Admin Uno");
        p.set("comentarioCierre", mapper.valueToTree(comentario));
        return p.toString();
    }

    private enum Borrar { CAMPO }

    // ------------------------------------------------------------------ gestión

    @Test
    void gestionExitosaConCuerpoExactoYModalAbiertoPermiteVolverAGuardar() throws Exception {
        MockHttpSession s = login("admin1");
        abrir(s, 501);
        enviar(s, gestion(Map.of("actualizadoPor", "Otro"))).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.peticionesNoConformidades()).containsExactly("GET /api/no-conformidades/501", "GET /api/no-conformidades/501",
                "PATCH /api/no-conformidades/501/gestion", "GET /api/no-conformidades/501");
        SeguimientoRecibido r = API.gestionesRecibidas().get(0);
        assertThat(r.ncId()).isEqualTo(501);
        assertThat(r.sub()).isEqualTo(20);
        assertThat(mapper.readTree(r.cuerpo())).isEqualTo(mapper.readTree(
                "{\"responsable\":\"Juan Pérez (Calidad)\",\"estadoGestion\":\"PENDIENTE\",\"fechaCompromiso\":\"2026-10-15\",\"actualizadoPor\":\"Admin Uno\"}"));
        // El modal sigue abierto: se puede volver a guardar (la huella se renovó tras el PATCH).
        enviar(s, gestion(Map.of("estadoGestion", "CERRADA", "fechaCompromiso", "", "responsable", ""))).andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(API.gestionesRecibidas().get(1).cuerpo())).isEqualTo(mapper.readTree(
                "{\"responsable\":\"\",\"estadoGestion\":\"CERRADA\",\"fechaCompromiso\":null,\"actualizadoPor\":\"Admin Uno\"}"));
    }

    @Test
    void gestionAceptaCerradaComoPhotinoYValidaValores() throws Exception {
        MockHttpSession s = login("admin1");
        abrir(s, 501);
        enviar(s, gestion(Map.of("estadoGestion", "CERRADA"))).andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(API.gestionesRecibidas().get(0).cuerpo()).get("estadoGestion").asString()).isEqualTo("CERRADA");
        String invalido = "Estado de gestión inválido. Valores permitidos: PENDIENTE, CERRADA";
        enviar(s, gestion(Map.of("estadoGestion", "cerrada"))).andExpect(jsonPath("$.error").value(invalido));
        // Fase 3u: Photino 1.8.14 quitó Asignada y En gestión del select (la API aún los acepta).
        enviar(s, gestion(Map.of("estadoGestion", "ASIGNADA"))).andExpect(jsonPath("$.error").value(invalido));
        enviar(s, gestion(Map.of("estadoGestion", "EN_GESTION"))).andExpect(jsonPath("$.error").value(invalido));
        enviar(s, gestion(Map.of("estadoGestion", ""))).andExpect(jsonPath("$.error").value("Falta el estado de gestión"));
        enviar(s, gestion(Map.of("estadoGestion", Borrar.CAMPO))).andExpect(jsonPath("$.error").value("Falta el estado de gestión"));
        enviar(s, gestion(Map.of("responsable", "R".repeat(151)))).andExpect(jsonPath("$.error").value("El responsable supera el máximo de 150 caracteres."));
        enviar(s, gestion(Map.of("responsable", "<img src=x onerror=alert(1)>"))).andExpect(jsonPath("$.error").value(MSG_HTML));
        enviar(s, gestion(Map.of("responsable", "Juan\nPérez"))).andExpect(jsonPath("$.error").value(MSG_CARACTERES));
        enviar(s, gestion(Map.of("fechaCompromiso", "2026-02-30"))).andExpect(jsonPath("$.error").value("La fecha compromiso no es válida (formato AAAA-MM-DD)."));
        enviar(s, gestion(Map.of("responsable", 12))).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        for (String extra : List.of("cerradoPor", "comentarioCierre", "empresa", "estado", "fechaCierre", "usuario")) {
            enviar(s, gestion(Map.of(extra, "x"))).andExpect(jsonPath("$.error").value("Campo no permitido: " + extra));
        }
        enviar(s, gestion(Map.of("id", Borrar.CAMPO))).andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
        assertThat(API.gestionesRecibidas()).hasSize(1);
    }

    @Test
    void gestionExigeNcAbiertaSinCambiosYReabreComoPhotino() throws Exception {
        MockHttpSession s = login("admin1");
        enviar(s, gestion(Map.of())).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));
        abrir(s, 501);
        API.modificarNcPorOtro(501);
        enviar(s, gestion(Map.of())).andExpect(jsonPath("$.error").value(MSG_CONFLICTO));
        assertThat(API.gestionesRecibidas()).isEmpty();
        abrir(s, 503); // CERRADA: Photino permite reabrirla desde Gestión
        enviar(s, gestion(Map.of("id", 503, "estadoGestion", "PENDIENTE"))).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.gestionesRecibidas()).hasSize(1);
        assertThat(mapper.readTree(API.gestionesRecibidas().get(0).cuerpo()).get("estadoGestion").asString()).isEqualTo("PENDIENTE");
    }

    // ------------------------------------------------------------------ cierre

    @Test
    void cierreConCerradoPorDeSesionYCerrarOtraVezComoPhotino() throws Exception {
        MockHttpSession s = login("adminti1");
        enviar(s, cierre(502, "Se reprocesó el lote\ny se liberó")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.peticionesNoConformidades()).containsExactly("GET /api/no-conformidades/502", "POST /api/no-conformidades/502/cerrar");
        SeguimientoRecibido r = API.cierresRecibidos().get(0);
        assertThat(r.sub()).isEqualTo(25);
        assertThat(mapper.readTree(r.cuerpo())).isEqualTo(mapper.readTree(
                "{\"cerradoPor\":\"Admin TI\",\"comentarioCierre\":\"Se reprocesó el lote\\ny se liberó\"}"));
        // Cerrar una NC ya cerrada: la API vuelve a registrar quién/cuándo/comentario (igual que Photino).
        MockHttpSession otro = login("operador1");
        enviar(otro, cierre(502, null)).andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(API.cierresRecibidos().get(1).cuerpo())).isEqualTo(mapper.readTree(
                "{\"cerradoPor\":\"Operador Uno\",\"comentarioCierre\":null}"));
        // Y se puede reabrir desde Gestión.
        abrir(s, 502);
        enviar(s, gestion(Map.of("id", 502, "estadoGestion", "PENDIENTE"))).andExpect(jsonPath("$.ok").value(true));
        enviar(s, cierre(501, "")).andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(API.cierresRecibidos().get(2).cuerpo()).get("comentarioCierre").isNull()).isTrue();
    }

    @Test
    void cierreValidaComentarioCamposYExistencia() throws Exception {
        MockHttpSession s = login("admin1");
        enviar(s, cierre(501, "<script>alert(1)</script>")).andExpect(jsonPath("$.error").value(MSG_HTML));
        enviar(s, cierre(501, "x\u0000y")).andExpect(jsonPath("$.error").value(MSG_CARACTERES));
        enviar(s, cierre(501, "ñ".repeat(32_768))).andExpect(jsonPath("$.error").value("El campo comentarioCierre supera el máximo permitido (65.535 bytes)."));
        enviar(s, cierre(501, 5)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        for (String extra : List.of("estadoGestion", "fechaCierre", "responsable", "usuario")) {
            ObjectNode p = (ObjectNode) mapper.readTree(cierre(501, "ok"));
            p.put(extra, "x");
            enviar(s, p.toString()).andExpect(jsonPath("$.error").value("Campo no permitido: " + extra));
        }
        enviar(s, cierre(404, "ok")).andExpect(jsonPath("$.error").value("No conformidad no encontrada"));
        assertThat(API.cierresRecibidos()).isEmpty();
    }

    // ------------------------------------------------------------------ autorización y auditoría

    @Test
    void mismosRolesQuePhotinoCualquierUsuarioInnpack() throws Exception {
        MockHttpSession op = login("operador1");
        abrir(op, 501);
        enviar(op, gestion(Map.of("actualizadoPor", "Admin Uno"))).andExpect(jsonPath("$.ok").value(true));
        enviar(op, cierre(501, "x")).andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(API.gestionesRecibidas().get(0).cuerpo()).get("actualizadoPor").asString()).isEqualTo("Operador Uno");
        assertThat(mapper.readTree(API.cierresRecibidos().get(0).cuerpo()).get("cerradoPor").asString()).isEqualTo("Operador Uno");
        MockHttpSession c = login("consulta1"); // rol inexistente en la BD real: sin acceso a nada del módulo
        enviar(c, gestion(Map.of())).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        enviar(c, cierre(501, "x")).andExpect(status().isForbidden());
        for (String accion : List.of(GESTION, CERRAR)) {
            assertThat(policy.evaluar(accion, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
            Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(accion)).findFirst().orElseThrow();
            assertThat(d.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
        }
        assertThat(policy.describir().stream().filter(x -> x.get("accion").equals(CERRAR)).findFirst().orElseThrow().get("identidad"))
                .isEqualTo(Map.of("cerradoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO));
    }

    @Test
    void auditoriaSinContenido(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("admin1");
        abrir(s, 501);
        enviar(s, gestion(Map.of("responsable", "Responsable Sensible 12.345.678-9"))).andExpect(jsonPath("$.ok").value(true));
        enviar(s, cierre(502, "Comentario Sensible")).andExpect(jsonPath("$.ok").value(true));
        enviar(s, cierre(404, "otra vez")).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=admin1 empresa=INNPACK accion=noConformidades\\.gestion\\.actualizar "
                + "recurso=nc:501:gestion:PENDIENTE resultado=OK ms=\\d+");
        assertThat(log).containsPattern("accion=noConformidades\\.cerrar recurso=nc:502:cierre resultado=OK");
        assertThat(log).containsPattern("accion=noConformidades\\.cerrar recurso=nc:404:cierre resultado=ERROR");
        assertThat(log).doesNotContain("Responsable Sensible", "12.345.678-9", "Comentario Sensible", FakeInnpackApi.firmaDeToken(20), PASS);
    }

    // ------------------------------------------------------------------ helpers

    /** "Gestionar" de Photino abre con noConformidades.get (registra la huella de lo leído en la sesión). */
    private void abrir(MockHttpSession sesion, int id) throws Exception {
        enviar(sesion, "{\"action\":\"noConformidades.get\",\"id\":" + id + "}").andExpect(jsonPath("$.ok").value(true));
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.21.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private ResultActions enviar(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private static SessionUser usuario(String empresa, String rol) {
        return new SessionUser(1, "u", "U", rol, empresa, "t", Instant.now(), Instant.now().plusSeconds(60));
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-3m");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
