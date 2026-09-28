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
 * Fase 3r — Photino dd147ad: NC Internas (INNPACK) reutiliza noConformidades.* con ambito "INTERNA" + empresa. Foco:
 * filtros con ámbito/empresa de sesión/categoriaDefecto en el orden de Photino, catálogos nciAreas/nciTiposDesviacion,
 * alta y edición internas (lista blanca exacta, empresa y autor de sesión, título/fechaIngreso recalculados), borrado
 * lógico de NC y adjuntos con los roles de Photino, y escape de listas solo ante marcado real. API SIMULADA.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3rTest {

    private static final String BASE = "GET /api/no-conformidades";
    private static final String MSG_OBLIGATORIOS = "Fecha, NP/NV, Cliente, Área responsable, Tipo de desviación, Etapa y Descripción son obligatorios";
    private static final String MSG_HORAS = "El tiempo perdido debe ser un número mayor o igual a 0";
    private static final String MSG_HTML = "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveNcInternas#2026";
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

    /** Payload EXACTO de `_guardarForm` de nc-internas (alta), con overrides. */
    private String alta(Map<String, Object> cambios) {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", "noConformidades.create");
        p.put("creadoPor", "Operador Uno");
        p.put("ambito", "INTERNA");
        p.put("empresa", "INNPACK");
        campos(p);
        cambios.forEach((k, v) -> p.set(k, mapper.valueToTree(v)));
        return p.toString();
    }

    /** Payload EXACTO de `_guardarForm` de nc-internas (edición): {action, id, actualizadoPor, ...campos}. */
    private String edicion(int id, Map<String, Object> cambios) {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", "noConformidades.update");
        p.put("id", id);
        p.put("actualizadoPor", "Operador Uno");
        campos(p);
        cambios.forEach((k, v) -> p.set(k, mapper.valueToTree(v)));
        return p.toString();
    }

    private static void campos(ObjectNode p) {
        p.put("fechaDeteccion", "2026-09-28");
        p.put("npNv", "4001");
        p.put("cliente", "Viña Ñandú");
        p.put("area", "Pre-prensa");
        p.put("categoriaDefecto", "Error de archivo");
        p.put("proceso", "Montaje");
        p.put("descripcion", "Archivo con\nerror de capas");
        p.put("observacion", "");
        p.put("areasSecundarias", "Diseño; Ventas");
        p.put("tiempoPerdidoHoras", 1.5);
        p.put("fechaIngreso", "2026-09-28");
        p.put("titulo", "Error de archivo - NP 4001");
    }

    // ------------------------------------------------------------------ lecturas con ámbito

    @Test
    void listResumenYFiltrosConAmbitoEmpresaDeSesionYCategoriaEnElOrdenDePhotino() throws Exception {
        MockHttpSession s = login("operador1");
        String filtros = "\"cliente\":\"Viña\",\"area\":\"Pre-prensa\",\"categoriaDefecto\":\"Error & archivo\",\"estadoGestion\":\"\","
                + "\"fechaDesde\":\"2026-09-01\",\"fechaHasta\":\"\",\"ambito\":\"INTERNA\",\"empresa\":\"FARET\"";
        crear(s, "{\"action\":\"noConformidades.list\",\"page\":2,\"pageSize\":20," + filtros + "}").andExpect(jsonPath("$.ok").value(true));
        crear(s, "{\"action\":\"noConformidades.resumen\"," + filtros + "}").andExpect(jsonPath("$.ok").value(true));
        crear(s, "{\"action\":\"noConformidades.filtrosOpciones\",\"ambito\":\"INTERNA\",\"empresa\":\"INNPACK\"}").andExpect(jsonPath("$.ok").value(true));
        crear(s, "{\"action\":\"noConformidades.filtrosOpciones\"}").andExpect(jsonPath("$.ok").value(true)); // PNC: sin query
        String q = "ambito=INTERNA&empresa=INNPACK&cliente=Vi%C3%B1a&area=Pre-prensa&categoriaDefecto=Error%20%26%20archivo&fechaDesde=2026-09-01";
        assertThat(API.peticionesNoConformidades()).containsExactly(BASE + "?page=2&pageSize=20&" + q, BASE + "/resumen?" + q,
                BASE + "/filtros-opciones?ambito=INTERNA&empresa=INNPACK", BASE + "/filtros-opciones");
        crear(s, "{\"action\":\"noConformidades.filtrosOpciones\",\"ambito\":{\"x\":1}}").andExpect(jsonPath("$.error").value("Parámetro de filtro inválido."));
    }

    @Test
    void catalogosDeNcInternasSoloLectura() throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, "{\"action\":\"noConformidades.catalogos.nciAreas.list\"}").andExpect(jsonPath("$.ok").value(true));
        crear(s, "{\"action\":\"noConformidades.catalogos.nciTiposDesviacion.list\"}").andExpect(jsonPath("$.ok").value(true));
        assertThat(API.peticionesNoConformidades()).containsExactly("GET /api/nc-catalogos/nciAreas", "GET /api/nc-catalogos/nciTiposDesviacion");
        for (String a : List.of("noConformidades.catalogos.nciAreas.crear", "noConformidades.catalogos.nciAreas.desactivar",
                "noConformidades.catalogos.nciTiposDesviacion.crear")) {
            crear(s, "{\"action\":\"" + a + "\",\"nombre\":\"X\",\"id\":1}").andExpect(status().isForbidden());
        }
    }

    @Test
    void escapeDeListasSoloConMarcadoReal() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode normal = json(crear(s, "{\"action\":\"noConformidades.seguimiento.list\",\"id\":902}").andReturn());
        assertThat(normal.at("/data/0/comentario").asString()).isEqualTo("R&D 5<6 'x' \"y\""); // la vista NCI ya escapa: sin doble escape
        assertThat(normal.at("/data/0/autor").asString()).isEqualTo("Ana & Co");
        JsonNode malicioso = json(crear(s, "{\"action\":\"noConformidades.seguimiento.list\",\"id\":901}").andReturn());
        assertThat(malicioso.at("/data/0/comentario").asString()).startsWith("&lt;img src=x onerror=alert(1)&gt;"); // la vista PNC no escapa
        assertThat(malicioso.at("/data/0/autor").asString()).isEqualTo("&lt;b&gt;Mallory&lt;/b&gt;");
    }

    // ------------------------------------------------------------------ alta / edición internas

    @Test
    void altaInternaConCuerpoExactoEmpresaYAutorDeSesionYCabeceraRecalculada() throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, alta(Map.of("creadoPor", "Otro", "empresa", "FARET", "titulo", "falso", "fechaIngreso", "2020-01-01")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        SeguimientoRecibido r = API.ncCreadasRecibidas().get(0);
        assertThat(r.sub()).isEqualTo(10);
        assertThat(mapper.readTree(r.cuerpo())).isEqualTo(mapper.readTree("{\"creadoPor\":\"Operador Uno\",\"ambito\":\"INTERNA\","
                + "\"empresa\":\"INNPACK\",\"fechaDeteccion\":\"2026-09-28\",\"npNv\":\"4001\",\"cliente\":\"Viña Ñandú\",\"area\":\"Pre-prensa\","
                + "\"categoriaDefecto\":\"Error de archivo\",\"proceso\":\"Montaje\",\"descripcion\":\"Archivo con\\nerror de capas\","
                + "\"observacion\":\"\",\"areasSecundarias\":\"Diseño; Ventas\",\"tiempoPerdidoHoras\":1.5,\"fechaIngreso\":\"2026-09-28\","
                + "\"titulo\":\"Error de archivo - NP 4001\"}"));
        // Sin horas → null; título > 255 se corta como el JS.
        crear(s, alta(Map.of("tiempoPerdidoHoras", mapper.nullNode(), "categoriaDefecto", "T".repeat(150), "npNv", "9".repeat(100))))
                .andExpect(jsonPath("$.ok").value(true));
        JsonNode c2 = mapper.readTree(API.ncCreadasRecibidas().get(1).cuerpo());
        assertThat(c2.get("tiempoPerdidoHoras").isNull()).isTrue();
        assertThat(c2.get("titulo").asString()).hasSize(255).startsWith("T".repeat(150) + " - NP ");
    }

    @Test
    void altaInternaValidaComoPhotinoYEsquema() throws Exception {
        MockHttpSession s = login("operador1");
        for (String campo : List.of("fechaDeteccion", "npNv", "cliente", "area", "categoriaDefecto", "proceso", "descripcion")) {
            crear(s, alta(Map.of(campo, ""))).andExpect(jsonPath("$.error").value(MSG_OBLIGATORIOS));
        }
        crear(s, alta(Map.of("tiempoPerdidoHoras", -1))).andExpect(jsonPath("$.error").value(MSG_HORAS));
        crear(s, alta(Map.of("tiempoPerdidoHoras", 10000))).andExpect(jsonPath("$.error").value(MSG_HORAS));
        crear(s, alta(Map.of("tiempoPerdidoHoras", "2"))).andExpect(jsonPath("$.error").value(MSG_HORAS));
        crear(s, alta(Map.of("areasSecundarias", "A".repeat(301)))).andExpect(jsonPath("$.error").value("El campo areasSecundarias supera el máximo de 300 caracteres."));
        crear(s, alta(Map.of("cliente", "<img src=x onerror=alert(1)>"))).andExpect(jsonPath("$.error").value(MSG_HTML));
        crear(s, alta(Map.of("fechaDeteccion", "2026-02-30"))).andExpect(jsonPath("$.error").value("La fecha fechaDeteccion no es válida (formato AAAA-MM-DD)."));
        for (String extra : List.of("codigoProducto", "tipo", "severidad", "responsable", "estadoGestion", "reportadoPor")) {
            crear(s, alta(Map.of(extra, "x"))).andExpect(jsonPath("$.error").value("Campo no permitido: " + extra));
        }
        assertThat(API.ncCreadasRecibidas()).isEmpty();
        // Etapa/área/tipo históricos (fuera de las opciones actuales) se aceptan: la edición de Photino los conserva.
        crear(s, alta(Map.of("proceso", "Etapa antigua"))).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void edicionInternaConLostUpdateYSinAmbitoNiEmpresa() throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, edicion(501, Map.of())).andExpect(jsonPath("$.error").value("Abre la no conformidad antes de editarla."));
        crear(s, "{\"action\":\"noConformidades.get\",\"id\":501}").andExpect(jsonPath("$.ok").value(true));
        crear(s, edicion(501, Map.of("actualizadoPor", "Otro", "titulo", "falso"))).andExpect(jsonPath("$.ok").value(true));
        SeguimientoRecibido r = API.ncActualizadasRecibidas().get(0);
        JsonNode c = mapper.readTree(r.cuerpo());
        assertThat(c.get("actualizadoPor").asString()).isEqualTo("Operador Uno");
        assertThat(c.get("titulo").asString()).isEqualTo("Error de archivo - NP 4001");
        assertThat(c.propertyNames()).doesNotContain("ambito", "empresa", "creadoPor").hasSize(13);
        crear(s, "{\"action\":\"noConformidades.get\",\"id\":501}");
        crear(s, edicion(501, Map.of("empresa", "FARET"))).andExpect(jsonPath("$.error").value("Campo no permitido: empresa"));
        crear(s, edicion(501, Map.of("ambito", "PRODUCTO"))).andExpect(jsonPath("$.error").value("Campo no permitido: ambito"));
        API.modificarNcPorOtro(501);
        crear(s, edicion(501, Map.of())).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("La no conformidad fue modificada")));
        assertThat(API.ncActualizadasRecibidas()).hasSize(1);
    }

    // ------------------------------------------------------------------ borrado lógico (roles de Photino)

    @Test
    void eliminarNcYAdjuntoConIdentidadDeSesionYRolesDePhotino() throws Exception {
        MockHttpSession op = login("operador1");
        crear(op, "{\"action\":\"noConformidades.eliminar\",\"id\":501,\"actualizadoPor\":\"Otro Usuario\"}").andExpect(jsonPath("$.ok").value(true));
        crear(op, "{\"action\":\"noConformidades.eliminar\",\"id\":404,\"actualizadoPor\":\"x\"}").andExpect(jsonPath("$.error").value("No conformidad no encontrada"));
        crear(op, "{\"action\":\"noConformidades.eliminar\",\"id\":501,\"motivo\":\"x\"}").andExpect(jsonPath("$.error").value("Campo no permitido: motivo"));
        crear(op, "{\"action\":\"noConformidades.adjuntos.eliminar\",\"id\":501,\"adjuntoId\":3}").andExpect(jsonPath("$.ok").value(true));
        crear(op, "{\"action\":\"noConformidades.adjuntos.eliminar\",\"id\":777,\"adjuntoId\":3}")
                .andExpect(jsonPath("$.error").value("La no conformidad está cerrada, no se pueden eliminar adjuntos"));
        crear(op, "{\"action\":\"noConformidades.adjuntos.eliminar\",\"id\":501}").andExpect(jsonPath("$.error").value("Falta el id del adjunto"));
        assertThat(API.peticionesNoConformidades()).contains("DELETE /api/no-conformidades/501?actualizadoPor=Operador%20Uno",
                "DELETE /api/no-conformidades/501/adjuntos/3", "DELETE /api/no-conformidades/777/adjuntos/3");
        assertThat(API.peticionesNoConformidades()).noneMatch(p -> p.contains("Otro%20Usuario") || p.startsWith("DELETE /api/no-conformidades/404"));
        crear(login("consulta1"), "{\"action\":\"noConformidades.eliminar\",\"id\":501}").andExpect(status().isForbidden());
        for (String a : List.of("noConformidades.eliminar", "noConformidades.adjuntos.eliminar")) {
            Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(a)).findFirst().orElseThrow();
            assertThat(d.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
            assertThat(policy.evaluar(a, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        }
    }

    @Test
    void auditoriaDeAltaInternaYBorradosSinContenido(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("admin1");
        crear(s, alta(Map.of("cliente", "Cliente Sensible 12.345.678-9"))).andExpect(jsonPath("$.ok").value(true));
        crear(s, "{\"action\":\"noConformidades.eliminar\",\"id\":502}").andExpect(jsonPath("$.ok").value(true));
        crear(s, "{\"action\":\"noConformidades.adjuntos.eliminar\",\"id\":502,\"adjuntoId\":7}").andExpect(jsonPath("$.ok").value(true));
        String log = salida.getAll();
        assertThat(log).containsPattern("usuario=admin1 empresa=INNPACK accion=noConformidades\\.create recurso=nc:951 resultado=OK");
        assertThat(log).containsPattern("accion=noConformidades\\.eliminar recurso=nc:502:eliminada resultado=OK");
        assertThat(log).containsPattern("accion=noConformidades\\.adjuntos\\.eliminar recurso=nc:502:adjunto:7:eliminado resultado=OK");
        assertThat(log).doesNotContain("Cliente Sensible", "12.345.678-9", FakeInnpackApi.firmaDeToken(20), PASS);
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.24.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-3r");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
