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
 * Fase 4c — escrituras de Talleres Externos (create, update, eliminar, catalogos.eliminarTaller/
 * eliminarProceso, sincronizarFps), igual que Photino para el usuario: la API ya tiene concurrencia
 * optimista real por "version" (404 si no existe, 409 con mensaje humano si no coincide) — el gateway
 * no la reimplementa, solo reenvía el version del payload y confía en la API como autoridad. Seguridad
 * transparente: lista blanca de campos; usuarioId ← sesión (nunca del payload); textos sin controles
 * ni HTML; fechas AAAA-MM-DD; prioridad/estado restringidos a las opciones de los selects; huella de
 * sesión (haber cargado la lista/catálogo antes) como piso mínimo, igual que el resto del sistema.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase4cTest {

    private static final String LIST = "talleresExternos.list";
    private static final String CATALOGOS = "talleresExternos.catalogos";
    private static final String CREAR = "talleresExternos.create";
    private static final String ACTUALIZAR = "talleresExternos.update";
    private static final String ELIMINAR = "talleresExternos.eliminar";
    private static final String ELIMINAR_TALLER = "talleresExternos.catalogos.eliminarTaller";
    private static final String ELIMINAR_PROCESO = "talleresExternos.catalogos.eliminarProceso";
    private static final String SINCRONIZAR_FPS = "talleresExternos.sincronizarFps";

    private static final String MSG_CONFLICTO = "El registro fue modificado por otro usuario. Vuelve a cargarlo antes de guardar.";
    private static final String MSG_SIN_LEER_TRABAJO = "Actualiza la lista antes de editar o eliminar este trabajo.";
    private static final String MSG_SIN_LEER_CATALOGO = "Actualiza el catálogo antes de eliminar este valor.";

    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveTalleres4c#2026";
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

    // ------------------------------------------------------------------ create

    @Test
    void crearTrabajoMinimoYUsuarioIdManipuladoSeRechaza() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(enviar(s, acc(CREAR, cuerpoTrabajo())).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        long id = json.at("/data/id").asLong();
        assertThat(id).isGreaterThan(0);
        // La vista reabre el detalle: crear registra la huella del nuevo id, permitiendo editarlo enseguida.
        assertThat(API.versionTrabajo(id)).isEqualTo(1);

        // usuarioId SIEMPRE sale de la sesión: ni siquiera se acepta en el payload (lista blanca).
        ObjectNode manipulado = cuerpoTrabajo();
        manipulado.put("usuarioId", 999);
        enviar(s, acc(CREAR, manipulado)).andExpect(jsonPath("$.error").value("Campo no permitido: usuarioId"));
    }

    @Test
    void crearCamposObligatoriosYOpcionesInvalidas() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode sinNv = cuerpoTrabajo();
        sinNv.remove("nv");
        enviar(s, acc(CREAR, sinNv)).andExpect(jsonPath("$.error").value("NV es obligatorio."));

        ObjectNode prioridadInvalida = cuerpoTrabajo();
        prioridadInvalida.put("prioridad", "URGENTE");
        enviar(s, acc(CREAR, prioridadInvalida)).andExpect(jsonPath("$.error").value("Valor no permitido en prioridad."));

        ObjectNode estadoInvalido = cuerpoTrabajo();
        estadoInvalido.put("estado", "CANCELADO");
        enviar(s, acc(CREAR, estadoInvalido)).andExpect(jsonPath("$.error").value("Valor no permitido en estado."));
    }

    @Test
    void crearFechaInvalidaYTextoConHtmlSeRechazan() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode fechaMala = cuerpoTrabajo();
        fechaMala.put("fechaAsignacion", "20-09-2026");
        enviar(s, acc(CREAR, fechaMala)).andExpect(jsonPath("$.error").value("La fecha no es válida (formato AAAA-MM-DD)."));

        ObjectNode conHtml = cuerpoTrabajo();
        conHtml.put("observaciones", "<script>alert(1)</script>");
        enviar(s, acc(CREAR, conHtml)).andExpect(jsonPath("$.ok").value(false));

        ObjectNode precioLargo = cuerpoTrabajo();
        precioLargo.put("precioCotizacion", "$".repeat(201));
        enviar(s, acc(CREAR, precioLargo))
                .andExpect(jsonPath("$.error").value("El campo precioCotizacion supera el máximo de 200 caracteres."));
    }

    @Test
    void crearCampoNoPermitidoSeRechaza() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode cuerpo = cuerpoTrabajo();
        cuerpo.put("tallerExternoId", 5);
        enviar(s, acc(CREAR, cuerpo)).andExpect(jsonPath("$.error").value("Campo no permitido: tallerExternoId"));
    }

    // ------------------------------------------------------------------ update

    @Test
    void actualizarExigeHaberCargadoLaListaYRespetaVersion() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode cuerpo = cuerpoTrabajo();
        cuerpo.put("id", 9001).put("version", 1);
        enviar(s, acc(ACTUALIZAR, cuerpo)).andExpect(jsonPath("$.error").value(MSG_SIN_LEER_TRABAJO));

        cargarLista(s);
        JsonNode ok = json(enviar(s, acc(ACTUALIZAR, cuerpo)).andExpect(status().isOk()).andReturn());
        assertThat(ok.get("ok").asBoolean()).isTrue();
        assertThat(API.versionTrabajo(9001)).isEqualTo(2);
    }

    @Test
    void actualizarConVersionDesactualizadaDaConflictoYReabrirPermite() throws Exception {
        MockHttpSession s = login("operador1");
        cargarLista(s);
        API.avanzarVersionTrabajoPorOtro(9002);
        ObjectNode cuerpo = cuerpoTrabajo();
        cuerpo.put("id", 9002).put("version", 1);
        enviar(s, acc(ACTUALIZAR, cuerpo)).andExpect(jsonPath("$.error").value(MSG_CONFLICTO));

        cargarLista(s); // reabrir
        cuerpo.put("version", 2);
        enviar(s, acc(ACTUALIZAR, cuerpo)).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void actualizarUnTrabajoYaEliminadoDa404ConElMensajeRealDeLaApi() throws Exception {
        MockHttpSession s = login("operador1");
        cargarLista(s);
        enviar(s, "{\"action\":\"" + ELIMINAR + "\",\"data\":{\"id\":9006,\"version\":1}}")
                .andExpect(jsonPath("$.ok").value(true));
        // dataTalleresList no filtra eliminados (es sintética): la sesión lo vuelve a "ver" en la lista,
        // pero la API ya lo considera inexistente.
        cargarLista(s);
        ObjectNode cuerpo = cuerpoTrabajo();
        cuerpo.put("id", 9006).put("version", 2);
        enviar(s, acc(ACTUALIZAR, cuerpo)).andExpect(jsonPath("$.error").value("No existe un trabajo con id 9006."));
    }

    @Test
    void actualizarSinHaberCargadoNingunaLista() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode cuerpo = cuerpoTrabajo();
        cuerpo.put("id", 9007).put("version", 1);
        enviar(s, acc(ACTUALIZAR, cuerpo)).andExpect(jsonPath("$.error").value(MSG_SIN_LEER_TRABAJO));
    }

    @Test
    void actualizarIdFaltanteOInvalido() throws Exception {
        MockHttpSession s = login("operador1");
        cargarLista(s);
        ObjectNode cero = cuerpoTrabajo();
        cero.put("id", 0).put("version", 1);
        enviar(s, acc(ACTUALIZAR, cero)).andExpect(jsonPath("$.error").value("Falta 'id' para actualizar."));

        ObjectNode negativo = cuerpoTrabajo();
        negativo.put("id", -1).put("version", 1);
        enviar(s, acc(ACTUALIZAR, negativo)).andExpect(jsonPath("$.error").value("Falta 'id' para actualizar."));

        ObjectNode texto = cuerpoTrabajo();
        texto.put("id", "x").put("version", 1);
        enviar(s, acc(ACTUALIZAR, texto)).andExpect(jsonPath("$.error").value("Falta 'id' para actualizar."));
    }

    // ------------------------------------------------------------------ eliminar

    @Test
    void eliminarExitosoYConConflictoDeVersion() throws Exception {
        MockHttpSession s = login("operador1");
        cargarLista(s);
        enviar(s, "{\"action\":\"" + ELIMINAR + "\",\"data\":{\"id\":9003,\"version\":1}}")
                .andExpect(jsonPath("$.ok").value(true));
        assertThat(API.versionTrabajo(9003)).isNull(); // eliminado

        cargarLista(s);
        API.avanzarVersionTrabajoPorOtro(9004);
        enviar(s, "{\"action\":\"" + ELIMINAR + "\",\"data\":{\"id\":9004,\"version\":1}}")
                .andExpect(jsonPath("$.error").value(MSG_CONFLICTO));
    }

    @Test
    void eliminarSinHaberCargadoLaLista() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, "{\"action\":\"" + ELIMINAR + "\",\"data\":{\"id\":9005,\"version\":1}}")
                .andExpect(jsonPath("$.error").value(MSG_SIN_LEER_TRABAJO));
    }

    @Test
    void eliminarCampoNoPermitido() throws Exception {
        MockHttpSession s = login("operador1");
        cargarLista(s);
        enviar(s, "{\"action\":\"" + ELIMINAR + "\",\"data\":{\"id\":9001,\"version\":1,\"usuarioId\":5}}")
                .andExpect(jsonPath("$.error").value("Campo no permitido: usuarioId"));
    }

    // ------------------------------------------------------------------ catálogos

    @Test
    void eliminarTallerYProcesoDelCatalogo() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, "{\"action\":\"" + ELIMINAR_TALLER + "\",\"data\":{\"id\":1}}")
                .andExpect(jsonPath("$.error").value(MSG_SIN_LEER_CATALOGO));

        cargarCatalogos(s);
        enviar(s, "{\"action\":\"" + ELIMINAR_TALLER + "\",\"data\":{\"id\":1}}").andExpect(jsonPath("$.ok").value(true));
        // Ya inactivo: un segundo intento (otra sesión que lo vio antes) da el 404 real de la API.
        cargarCatalogos(s);
        enviar(s, "{\"action\":\"" + ELIMINAR_TALLER + "\",\"data\":{\"id\":1}}")
                .andExpect(jsonPath("$.error").value("No existe un taller externo activo con id 1."));

        cargarCatalogos(s);
        enviar(s, "{\"action\":\"" + ELIMINAR_PROCESO + "\",\"data\":{\"id\":2}}").andExpect(jsonPath("$.ok").value(true));
    }

    // ------------------------------------------------------------------ sincronizarFps

    @Test
    void sincronizarFpsNormalConErroresYNoConfigurada() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode ok = json(enviar(s, "{\"action\":\"" + SINCRONIZAR_FPS + "\"}").andExpect(status().isOk()).andReturn());
        assertThat(ok.at("/data/trabajosRevisados").asInt()).isEqualTo(3);
        assertThat(ok.at("/data/errores")).isEmpty();

        API.modoSincronizarFps("CON_ERRORES");
        JsonNode conErrores = json(enviar(s, "{\"action\":\"" + SINCRONIZAR_FPS + "\"}").andReturn());
        assertThat(conErrores.at("/data/errores")).hasSize(1);

        API.modoSincronizarFps("NO_CONFIGURADO");
        enviar(s, "{\"action\":\"" + SINCRONIZAR_FPS + "\"}")
                .andExpect(jsonPath("$.error").value("La integración con FPS no está configurada (revisa la sección \"FpsApi\" en appsettings)."));
    }

    @Test
    void sincronizarFpsAceptaElWrapperDeLaVistaYRechazaCamposAjenosEnLaRaiz() throws Exception {
        MockHttpSession s = login("operador1");
        // Wrapper real de la vista: this._send(action, {}) → {action, data:{}}. El contenido de "data" se ignora.
        enviar(s, "{\"action\":\"" + SINCRONIZAR_FPS + "\",\"data\":{}}").andExpect(jsonPath("$.ok").value(true));
        enviar(s, "{\"action\":\"" + SINCRONIZAR_FPS + "\",\"id\":1}")
                .andExpect(jsonPath("$.error").value("Campo no permitido: id"));
    }

    // ------------------------------------------------------------------ roles, empresa, contrato, auditoría

    @Test
    void lasSeisEscriturasQuedanHabilitadasConRolesDePhotino() {
        for (String accion : List.of(CREAR, ACTUALIZAR, ELIMINAR, ELIMINAR_TALLER, ELIMINAR_PROCESO, SINCRONIZAR_FPS)) {
            Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(accion)).findFirst().orElseThrow();
            assertThat(d.get("escritura")).isEqualTo(true);
            assertThat(d.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
            assertThat(d.get("identidad")).isEqualTo(Map.of());
        }
    }

    @Test
    void rolConsultaDenegadoYEmpresaFaretDenegada() throws Exception {
        enviar(login("consulta1"), "{\"action\":\"" + SINCRONIZAR_FPS + "\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(policy.evaluar(CREAR, usuario("FARET", "admin")))
                .isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
    }

    @Test
    void auditoriaConRecursoPorAccion(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        cargarLista(s);
        JsonNode creado = json(enviar(s, acc(CREAR, cuerpoTrabajo())).andExpect(status().isOk()).andReturn());
        long id = creado.at("/data/id").asLong();
        String log1 = salida.getAll();
        assertThat(log1).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=talleresExternos\\.create recurso=talleresExternos:"
                        + id + ":crear resultado=OK ms=\\d+");

        enviar(s, "{\"action\":\"" + SINCRONIZAR_FPS + "\"}").andExpect(jsonPath("$.ok").value(true));
        String log2 = salida.getAll();
        assertThat(log2).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=talleresExternos\\.sincronizarFps "
                + "recurso=talleresExternos:sincronizarFps resultado=OK ms=\\d+");
        assertThat(log2).doesNotContain(FakeInnpackApi.firmaDeToken(10), PASS);
    }

    @Test
    void csrfYSesionExpirada() throws Exception {
        MockHttpSession s = login("operador1");
        mockMvc.perform(post("/api/v1/bridge").session(s).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + SINCRONIZAR_FPS + "\"}"))
                .andExpect(status().isForbidden());
        API.revocarTokens(10);
        enviar(s, "{\"action\":\"" + SINCRONIZAR_FPS + "\"}").andExpect(status().isUnauthorized());
        assertThat(s.isInvalid()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private ObjectNode cuerpoTrabajo() {
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("nv", "NV-1").put("producto", "Caja ñ").put("item", "ITEM 1").put("prioridad", "ALTA")
                .put("estado", "ASIGNADO").put("cantidadARevisar", 100).put("cantidadRevisadaEntregada", 0)
                .put("observaciones", "ok");
        return cuerpo;
    }

    private String acc(String accion, ObjectNode data) {
        ObjectNode raiz = mapper.createObjectNode();
        raiz.put("action", accion);
        raiz.set("data", data);
        return raiz.toString();
    }

    private JsonNode cargarLista(MockHttpSession sesion) throws Exception {
        return json(enviar(sesion, "{\"action\":\"" + LIST + "\",\"data\":{}}")
                .andExpect(jsonPath("$.ok").value(true)).andReturn());
    }

    private JsonNode cargarCatalogos(MockHttpSession sesion) throws Exception {
        return json(enviar(sesion, "{\"action\":\"" + CATALOGOS + "\",\"data\":{}}")
                .andExpect(jsonPath("$.ok").value(true)).andReturn());
    }

    private ResultActions enviar(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.26.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-4c");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
