package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
 * Fase 4e-1 — piloto de las 25 escrituras de Laboratorio (muestraLab): `crear` y `ph.guardar`, igual
 * que Photino para el usuario. Fija el patrón de las otras 23: usuarioId/usuarioNombre (o
 * analistaUsuarioId/analistaNombre) SIEMPRE de sesión, ni siquiera son claves aceptadas del payload;
 * origen/tipoMuestra/etapaOrigen restringidos a los {@code <select>} de Photino; textos sin
 * caracteres de control ni marcado HTML; `ensayoOriginalId`/`motivoReemplazo` (corrección) lo valida
 * la API. Sin huella ni candado: son altas puras, sin control de duplicados.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase4e1Test {

    private static final String CREAR = "muestraLab.crear";
    private static final String PH_GUARDAR = "muestraLab.ph.guardar";

    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveMuestraLab4e1#2026";
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

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reiniciar() {
        API.reiniciarDashboard();
    }

    @AfterAll
    static void cerrar() {
        API.close();
    }

    // ------------------------------------------------------------------ crear

    @Test
    void crearMuestraMinimaYUsuarioManipuladoSeRechaza() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(enviar(s, acc(CREAR, cuerpoCrear())).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.at("/data/id").asInt()).isGreaterThan(0);
        assertThat(API.cuerposMuestraLabCreados()).hasSize(1);
        JsonNode recibido = API.cuerposMuestraLabCreados().get(0);
        assertThat(recibido.get("usuarioNombre").asString()).isEqualTo("Operador Uno");
        assertThat(recibido.get("usuarioId").asInt()).isEqualTo(10);

        // usuarioId/usuarioNombre SIEMPRE salen de la sesión: ni siquiera son claves aceptadas del payload.
        ObjectNode conUsuarioId = cuerpoCrear();
        conUsuarioId.put("usuarioId", 999);
        enviar(s, acc(CREAR, conUsuarioId)).andExpect(jsonPath("$.error").value("Campo no permitido: usuarioId"));

        ObjectNode conUsuarioNombre = cuerpoCrear();
        conUsuarioNombre.put("usuarioNombre", "Hacker");
        enviar(s, acc(CREAR, conUsuarioNombre)).andExpect(jsonPath("$.error").value("Campo no permitido: usuarioNombre"));
    }

    @Test
    void crearCampoNoPermitido() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode cuerpo = cuerpoCrear();
        cuerpo.put("idMuestra", 1);
        enviar(s, acc(CREAR, cuerpo)).andExpect(jsonPath("$.error").value("Campo no permitido: idMuestra"));
    }

    @Test
    void crearOrigenYTipoMuestraObligatoriosYRestringidos() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode sinOrigen = cuerpoCrear();
        sinOrigen.put("origen", "");
        enviar(s, acc(CREAR, sinOrigen)).andExpect(jsonPath("$.error").value("Origen y Tipo de muestra son obligatorios"));

        ObjectNode origenInvalido = cuerpoCrear();
        origenInvalido.put("origen", "Inventado");
        enviar(s, acc(CREAR, origenInvalido)).andExpect(jsonPath("$.error").value("Valor no permitido en origen."));

        ObjectNode tipoInvalido = cuerpoCrear();
        tipoInvalido.put("tipoMuestra", "Inventado");
        enviar(s, acc(CREAR, tipoInvalido)).andExpect(jsonPath("$.error").value("Valor no permitido en tipoMuestra."));

        ObjectNode etapaInvalida = cuerpoCrear();
        etapaInvalida.put("etapaOrigen", "Inventado");
        enviar(s, acc(CREAR, etapaInvalida)).andExpect(jsonPath("$.error").value("Valor no permitido en etapaOrigen."));
    }

    @Test
    void crearTextoConHtmlSeRechaza() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode conHtml = cuerpoCrear();
        conHtml.put("descripcion", "<script>alert(1)</script>");
        enviar(s, acc(CREAR, conHtml)).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\")."));
    }

    @Test
    void crearMonotapaRelacionadaInexistenteYDeOtroOrigen() throws Exception {
        MockHttpSession s = login("operador1");
        // Monotapa relacionada inexistente.
        ObjectNode conMonotapaInexistente = cuerpoCrear();
        conMonotapaInexistente.put("monotapaRelacionadaId", 123456);
        enviar(s, acc(CREAR, conMonotapaInexistente))
                .andExpect(jsonPath("$.error").value("No existe la muestra #123456 indicada como Monotapa relacionada"));

        // Crea una muestra de origen ControlRecepcion (no Monotapa) y la referencia como relacionada.
        JsonNode creada = json(enviar(s, acc(CREAR, cuerpoCrear())).andExpect(status().isOk()).andReturn());
        int idOtroOrigen = creada.at("/data/id").asInt();
        ObjectNode conMonotapaDeOtroOrigen = cuerpoCrear();
        conMonotapaDeOtroOrigen.put("origen", "Emplacado").put("monotapaRelacionadaId", idOtroOrigen);
        enviar(s, acc(CREAR, conMonotapaDeOtroOrigen)).andExpect(jsonPath("$.error")
                .value("La muestra #" + idOtroOrigen + " no es de origen Monotapa (es ControlRecepcion)"));

        // Crea una Monotapa real y la relaciona desde un Emplacado: ahora sí procede.
        ObjectNode monotapa = cuerpoCrear();
        monotapa.put("origen", "Monotapa").put("tipoMuestra", "Monotapa");
        JsonNode creadaMonotapa = json(enviar(s, acc(CREAR, monotapa)).andExpect(status().isOk()).andReturn());
        int idMonotapa = creadaMonotapa.at("/data/id").asInt();
        ObjectNode emplacado = cuerpoCrear();
        emplacado.put("origen", "Emplacado").put("monotapaRelacionadaId", idMonotapa);
        enviar(s, acc(CREAR, emplacado)).andExpect(jsonPath("$.ok").value(true));
    }

    // ------------------------------------------------------------------ ph.guardar

    @Test
    void phGuardarMinimoYAnalistaManipuladoSeRechaza() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(enviar(s, acc(PH_GUARDAR, cuerpoPh(501))).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.at("/data/ensayoId").asInt()).isGreaterThan(0);
        JsonNode recibido = API.cuerposPhGuardados().get(0);
        assertThat(recibido.get("analistaNombre").asString()).isEqualTo("Operador Uno");
        assertThat(recibido.get("analistaUsuarioId").asInt()).isEqualTo(10);

        ObjectNode conAnalistaId = cuerpoPh(501);
        conAnalistaId.put("analistaUsuarioId", 999);
        enviar(s, acc(PH_GUARDAR, conAnalistaId)).andExpect(jsonPath("$.error").value("Campo no permitido: analistaUsuarioId"));

        ObjectNode conAnalistaNombre = cuerpoPh(501);
        conAnalistaNombre.put("analistaNombre", "Hacker");
        enviar(s, acc(PH_GUARDAR, conAnalistaNombre)).andExpect(jsonPath("$.error").value("Campo no permitido: analistaNombre"));
    }

    @Test
    void phGuardarCamposObligatorios() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode sinMuestra = cuerpoPh(0);
        enviar(s, acc(PH_GUARDAR, sinMuestra)).andExpect(jsonPath("$.error").value("Falta indicar la muestra"));

        ObjectNode sinValor = cuerpoPh(501);
        sinValor.put("valorTexto", "");
        enviar(s, acc(PH_GUARDAR, sinValor)).andExpect(jsonPath("$.error").value("Falta el valor o rango leído en la tira"));
    }

    @Test
    void phGuardarMuestraAnuladaSeRechaza() throws Exception {
        MockHttpSession s = login("operador1");
        API.anularMuestraLab(777);
        enviar(s, acc(PH_GUARDAR, cuerpoPh(777)))
                .andExpect(jsonPath("$.error").value("El registro está anulado, no se pueden agregar más ensayos"));
    }

    @Test
    void phGuardarReemplazoExitosoYSinMotivoYOriginalInexistente() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode original = json(enviar(s, acc(PH_GUARDAR, cuerpoPh(501))).andExpect(status().isOk()).andReturn());
        int ensayoOriginalId = original.at("/data/ensayoId").asInt();

        ObjectNode sinMotivo = cuerpoPh(501);
        sinMotivo.put("ensayoOriginalId", ensayoOriginalId);
        enviar(s, acc(PH_GUARDAR, sinMotivo)).andExpect(jsonPath("$.error").value("Debes indicar el motivo de la corrección"));

        ObjectNode originalInexistente = cuerpoPh(501);
        originalInexistente.put("ensayoOriginalId", 999999).put("motivoReemplazo", "Error de lectura");
        enviar(s, acc(PH_GUARDAR, originalInexistente))
                .andExpect(jsonPath("$.error").value("El ensayo original no existe o no está Finalizado"));

        ObjectNode correccion = cuerpoPh(501);
        correccion.put("ensayoOriginalId", ensayoOriginalId).put("motivoReemplazo", "Error de lectura");
        enviar(s, acc(PH_GUARDAR, correccion)).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void phGuardarCampoNoPermitido() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode cuerpo = cuerpoPh(501);
        cuerpo.put("idEnsayo", 1);
        enviar(s, acc(PH_GUARDAR, cuerpo)).andExpect(jsonPath("$.error").value("Campo no permitido: idEnsayo"));
    }

    // ------------------------------------------------------------------ roles / auditoría

    @Test
    void rolConsultaDenegado() throws Exception {
        enviar(login("consulta1"), acc(CREAR, cuerpoCrear())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        enviar(login("consulta1"), acc(PH_GUARDAR, cuerpoPh(501))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
    }

    @Test
    void auditoriaConRecursoPorAccion(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode creado = json(enviar(s, acc(CREAR, cuerpoCrear())).andExpect(status().isOk()).andReturn());
        int id = creado.at("/data/id").asInt();
        String log1 = salida.getAll();
        assertThat(log1).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=muestraLab\\.crear recurso=muestraLab:"
                        + id + ":crear resultado=OK ms=\\d+");

        JsonNode ensayo = json(enviar(s, acc(PH_GUARDAR, cuerpoPh(id))).andExpect(status().isOk()).andReturn());
        int ensayoId = ensayo.at("/data/ensayoId").asInt();
        String log2 = salida.getAll();
        assertThat(log2).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=muestraLab\\.ph\\.guardar recurso=muestraLab:"
                        + id + ":ph:" + ensayoId + " resultado=OK ms=\\d+");
        assertThat(log2).doesNotContain(FakeInnpackApi.firmaDeToken(10), PASS);
    }

    // ------------------------------------------------------------------ helpers

    private ObjectNode cuerpoCrear() {
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("origen", "ControlRecepcion").put("tipoMuestra", "Papel").put("np", "NP-1")
                .put("cliente", "Cliente ñ").put("codigoProducto", "COD-1").put("descripcion", "Bobina de prueba")
                .put("maquina", "Corrugadora 1").put("turno", "A").put("lote", "L-1").put("proveedor", "Proveedor 1")
                .put("observacion", "ok");
        return cuerpo;
    }

    private ObjectNode cuerpoPh(int muestraId) {
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("muestraId", muestraId).put("observacion", "ok").put("valorTexto", "7.2")
                .put("colorObservado", "Verde");
        return cuerpo;
    }

    private String acc(String accion, ObjectNode data) {
        ObjectNode raiz = mapper.createObjectNode();
        raiz.put("action", accion);
        raiz.set("data", data);
        return raiz.toString();
    }

    private ResultActions enviar(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.29.0." + IP.getAndIncrement());
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

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-4e1");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
