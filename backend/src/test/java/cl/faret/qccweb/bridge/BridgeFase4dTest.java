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
import java.util.Base64;
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
 * Fase 4d — escrituras de Control Documental (create, update, version.crear, eliminar,
 * adjunto.subir), igual que Photino para el usuario. La API no tiene columna de versión (a
 * diferencia de Talleres Externos): `update` usa huella de sesión + relectura + comparación antes
 * del PUT (mismo patrón que noConformidades.update); `eliminar`/`version.crear` solo relee para
 * convertir el "no existe" silencioso de la API en un error real, sin exigir huella. `create`/
 * `version.crear`/`adjunto.subir` viajan por /api/v1/bridge/archivo (adjunto en base64).
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase4dTest {

    private static final String RUTA_ARCHIVO = "/api/v1/bridge/archivo";
    private static final String GET = "controlDocumental.get";
    private static final String CREAR = "controlDocumental.create";
    private static final String ACTUALIZAR = "controlDocumental.update";
    private static final String VERSION_CREAR = "controlDocumental.version.crear";
    private static final String ELIMINAR = "controlDocumental.eliminar";
    private static final String ADJUNTO_SUBIR = "controlDocumental.adjunto.subir";

    private static final String MSG_SIN_LEER = "Abre el documento antes de editarlo.";
    private static final String MSG_CONFLICTO = "El documento fue modificado por otra persona desde que lo abriste. "
            + "Vuelve a abrirlo para ver los cambios.";

    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveControlDoc4d#2026";
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
    void crearDocumentoMinimoYCreadoPorManipuladoSeIgnora() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode cuerpo = cuerpoCrear();
        cuerpo.put("creadoPor", "Hacker"); // ignorado: siempre sale de la sesión
        JsonNode json = json(enviarArchivo(s, acc(CREAR, cuerpo)).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.at("/data/id").asInt()).isGreaterThan(0);
    }

    @Test
    void crearCamposObligatoriosYEnumsInvalidos() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode sinCodigo = cuerpoCrear();
        sinCodigo.remove("codigoBase");
        enviarArchivo(s, acc(CREAR, sinCodigo)).andExpect(jsonPath("$.error").value("Falta el código base del documento"));

        ObjectNode estadoMalo = cuerpoCrear();
        estadoMalo.put("estado", "ARCHIVADO");
        enviarArchivo(s, acc(CREAR, estadoMalo)).andExpect(jsonPath("$.error").value("Valor no permitido en estado."));

        ObjectNode alcanceMalo = cuerpoCrear();
        alcanceMalo.put("alcanceEmpresa", "TODAS");
        enviarArchivo(s, acc(CREAR, alcanceMalo)).andExpect(jsonPath("$.error").value("Valor no permitido en alcanceEmpresa."));
    }

    @Test
    void crearFechaInvalidaYTextoConHtml() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode fechaMala = cuerpoCrear();
        fechaMala.put("fechaActualizacion", "24/09/2026");
        enviarArchivo(s, acc(CREAR, fechaMala)).andExpect(jsonPath("$.error").value("La fecha no es válida (formato AAAA-MM-DD)."));

        ObjectNode conHtml = cuerpoCrear();
        conHtml.put("observaciones", "<script>alert(1)</script>");
        enviarArchivo(s, acc(CREAR, conHtml)).andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void crearCampoNoPermitido() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode cuerpo = cuerpoCrear();
        cuerpo.put("id", 1);
        enviarArchivo(s, acc(CREAR, cuerpo)).andExpect(jsonPath("$.error").value("Campo no permitido: id"));
    }

    @Test
    void crearConAdjuntoPdfValidoYFirmaFalsaRechazada() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode conPdf = cuerpoCrear();
        conPdf.put("adjuntoNombreArchivo", "manual.pdf");
        conPdf.put("adjuntoContenidoBase64", Base64.getEncoder().encodeToString(FakeInnpackApi.pdfMinimo(1)));
        enviarArchivo(s, acc(CREAR, conPdf)).andExpect(jsonPath("$.ok").value(true));

        ObjectNode firmaFalsa = cuerpoCrear();
        firmaFalsa.put("adjuntoNombreArchivo", "falso.pdf");
        firmaFalsa.put("adjuntoContenidoBase64", Base64.getEncoder().encodeToString("no es un pdf real".getBytes(StandardCharsets.UTF_8)));
        enviarArchivo(s, acc(CREAR, firmaFalsa))
                .andExpect(jsonPath("$.error").value("El archivo no corresponde al tipo declarado por su extensión."));

        ObjectNode extensionMala = cuerpoCrear();
        extensionMala.put("adjuntoNombreArchivo", "virus.exe");
        extensionMala.put("adjuntoContenidoBase64", Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
        enviarArchivo(s, acc(CREAR, extensionMala)).andExpect(jsonPath("$.error")
                .value("Tipo de archivo no permitido. Formatos válidos: .pdf, .doc, .docx, .jpg, .jpeg, .png, .webp"));
    }

    @Test
    void crearConAdjuntoDocxValidoPorFirmaZip() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode conDocx = cuerpoCrear();
        conDocx.put("adjuntoNombreArchivo", "instructivo.docx");
        byte[] zipMinimo = {0x50, 0x4b, 0x03, 0x04, 0x14, 0, 0, 0, 1, 2, 3};
        conDocx.put("adjuntoContenidoBase64", Base64.getEncoder().encodeToString(zipMinimo));
        enviarArchivo(s, acc(CREAR, conDocx)).andExpect(jsonPath("$.ok").value(true));
    }

    // ------------------------------------------------------------------ update

    @Test
    void actualizarExigeHaberAbiertoElDocumentoYDetectaConflicto() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode cuerpo = mapper.createObjectNode().put("id", 301).put("codigoBase", "PRO-1").put("nombre", "Doc")
                .put("tipoDocumento", "Procedimiento");
        enviar(s, acc(ACTUALIZAR, cuerpo)).andExpect(jsonPath("$.error").value(MSG_SIN_LEER));

        abrir(s, 301);
        JsonNode ok = json(enviar(s, acc(ACTUALIZAR, cuerpo)).andExpect(status().isOk()).andReturn());
        assertThat(ok.get("ok").asBoolean()).isTrue();

        // Conflicto: otra persona cambió el documento entre que se abrió y se guardó.
        abrir(s, 302);
        API.estadoDocumentoPorOtro(302, "OBSOLETO");
        ObjectNode cuerpo2 = mapper.createObjectNode().put("id", 302).put("codigoBase", "PRO-2").put("nombre", "Doc")
                .put("tipoDocumento", "Procedimiento");
        enviar(s, acc(ACTUALIZAR, cuerpo2)).andExpect(jsonPath("$.error").value(MSG_CONFLICTO));

        // Reabrir (ver el cambio) permite guardar a sabiendas.
        abrir(s, 302);
        enviar(s, acc(ACTUALIZAR, cuerpo2)).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void actualizarEnumInvalidoYCampoNoPermitido() throws Exception {
        MockHttpSession s = login("operador1");
        abrir(s, 303);
        ObjectNode estadoMalo = mapper.createObjectNode().put("id", 303).put("estado", "ARCHIVADO");
        enviar(s, acc(ACTUALIZAR, estadoMalo)).andExpect(jsonPath("$.error").value("Valor no permitido en estado."));

        ObjectNode conVersion = mapper.createObjectNode().put("id", 303).put("version", "1.2");
        enviar(s, acc(ACTUALIZAR, conVersion)).andExpect(jsonPath("$.error").value("Campo no permitido: version"));
    }

    @Test
    void getDeDocumentoInexistenteDa404RealDeLaApi() throws Exception {
        // 404 es un id centinela del fake: nunca "existe" en GET, así que nunca se puede abrir (ni luego actualizar).
        enviar(login("operador1"), "{\"action\":\"" + GET + "\",\"id\":404}").andExpect(jsonPath("$.error").value("Documento no encontrado"));
    }

    // ------------------------------------------------------------------ version.crear

    @Test
    void versionCrearExitosaYDocumentoInexistente() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode cuerpo = mapper.createObjectNode().put("documentoId", 305).put("version", "2.0")
                .put("fechaActualizacion", "2026-10-01");
        JsonNode ok = json(enviarArchivo(s, acc(VERSION_CREAR, cuerpo)).andExpect(status().isOk()).andReturn());
        assertThat(ok.get("ok").asBoolean()).isTrue();

        ObjectNode sinDoc = mapper.createObjectNode().put("documentoId", 404).put("version", "2.0")
                .put("fechaActualizacion", "2026-10-01");
        enviarArchivo(s, acc(VERSION_CREAR, sinDoc)).andExpect(jsonPath("$.error").value("Documento no encontrado"));
    }

    @Test
    void versionCrearFaltaVersionOFecha() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode sinVersion = mapper.createObjectNode().put("documentoId", 306).put("fechaActualizacion", "2026-10-01");
        enviarArchivo(s, acc(VERSION_CREAR, sinVersion)).andExpect(jsonPath("$.error").value("Falta la versión"));
    }

    // ------------------------------------------------------------------ eliminar

    @Test
    void eliminarExitosoYRespetaElFlagEliminado() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, "{\"action\":\"" + ELIMINAR + "\",\"id\":307,\"actualizadoPor\":\"x\"}").andExpect(jsonPath("$.ok").value(true));
        // Ya eliminado: la relectura ahora falla con el mensaje real de la API.
        enviar(s, "{\"action\":\"" + ELIMINAR + "\",\"id\":307,\"actualizadoPor\":\"x\"}")
                .andExpect(jsonPath("$.error").value("Documento no encontrado"));
    }

    @Test
    void eliminarCampoNoPermitidoEIdFaltante() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, "{\"action\":\"" + ELIMINAR + "\",\"id\":308,\"creadoPor\":\"x\"}")
                .andExpect(jsonPath("$.error").value("Campo no permitido: creadoPor"));
        enviar(s, "{\"action\":\"" + ELIMINAR + "\"}").andExpect(jsonPath("$.error").value("Falta el id del documento"));
    }

    // ------------------------------------------------------------------ adjunto.subir

    @Test
    void adjuntoSubirPngValidoYFaltaContenido() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode cuerpo = mapper.createObjectNode().put("documentoVersionId", 1001).put("nombreArchivo", "diagrama.png")
                .put("contenidoBase64", Base64.getEncoder().encodeToString(FakeInnpackApi.pngMinimo()))
                .put("subidoPor", "Hacker"); // ignorado
        enviarArchivo(s, acc(ADJUNTO_SUBIR, cuerpo)).andExpect(jsonPath("$.ok").value(true));

        ObjectNode sinContenido = mapper.createObjectNode().put("documentoVersionId", 1001).put("nombreArchivo", "diagrama.png");
        enviarArchivo(s, acc(ADJUNTO_SUBIR, sinContenido)).andExpect(jsonPath("$.error").value("Falta el contenido del archivo"));
    }

    @Test
    void adjuntoSubirExcesivoSeRechaza() throws Exception {
        MockHttpSession s = login("operador1");
        String base64Grande = "A".repeat(((10 * 1024 * 1024 + 2) / 3) * 4 + 4);
        ObjectNode cuerpo = mapper.createObjectNode().put("documentoVersionId", 1002).put("nombreArchivo", "grande.pdf")
                .put("contenidoBase64", base64Grande);
        enviarArchivo(s, acc(ADJUNTO_SUBIR, cuerpo)).andExpect(jsonPath("$.error").value("El archivo supera el tamaño máximo permitido (10 MB)"));
    }

    // ------------------------------------------------------------------ roles, empresa, contrato, auditoría

    @Test
    void lasCincoEscriturasQuedanHabilitadasConRolesDePhotino() {
        for (String accion : List.of(CREAR, ACTUALIZAR, VERSION_CREAR, ELIMINAR, ADJUNTO_SUBIR)) {
            Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(accion)).findFirst().orElseThrow();
            assertThat(d.get("escritura")).isEqualTo(true);
            assertThat(d.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
            assertThat(d.get("identidad")).isEqualTo(Map.of());
        }
    }

    @Test
    void rolConsultaDenegadoYEmpresaFaretDenegada() throws Exception {
        enviarArchivo(login("consulta1"), acc(CREAR, cuerpoCrear())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(policy.evaluar(CREAR, usuario("FARET", "admin")))
                .isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
    }

    @Test
    void auditoriaConRecursoPorAccion(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode creado = json(enviarArchivo(s, acc(CREAR, cuerpoCrear())).andExpect(status().isOk()).andReturn());
        int id = creado.at("/data/id").asInt();
        abrir(s, 309);
        enviar(s, acc(ACTUALIZAR, mapper.createObjectNode().put("id", 309).put("nombre", "x"))).andExpect(jsonPath("$.ok").value(true));
        String log = salida.getAll();
        assertThat(log).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=controlDocumental\\.create recurso=controlDocumental:"
                        + id + ":crear resultado=OK ms=\\d+");
        assertThat(log).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=controlDocumental\\.update "
                + "recurso=controlDocumental:309:actualizar resultado=OK ms=\\d+");
        assertThat(log).doesNotContain(FakeInnpackApi.firmaDeToken(10), PASS);
    }

    @Test
    void csrfYSesionExpirada() throws Exception {
        MockHttpSession s = login("operador1");
        mockMvc.perform(post(RUTA_ARCHIVO).session(s).contentType(MediaType.APPLICATION_JSON).content(acc(CREAR, cuerpoCrear())))
                .andExpect(status().isForbidden());
        API.revocarTokens(10);
        enviarArchivo(s, acc(CREAR, cuerpoCrear())).andExpect(status().isUnauthorized());
        assertThat(s.isInvalid()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private ObjectNode cuerpoCrear() {
        return mapper.createObjectNode().put("codigoBase", "PRO-CDP").put("nombre", "Procedimiento de calidad")
                .put("tipoDocumento", "Procedimiento").put("version", "V01").put("fechaActualizacion", "2026-10-01")
                .put("estado", "VIGENTE").put("alcanceEmpresa", "INNPACK");
    }

    private String acc(String accion, ObjectNode cuerpo) {
        ObjectNode raiz = cuerpo.deepCopy();
        raiz.put("action", accion);
        return raiz.toString();
    }

    private JsonNode abrir(MockHttpSession sesion, int id) throws Exception {
        return json(enviar(sesion, "{\"action\":\"" + GET + "\",\"id\":" + id + "}")
                .andExpect(jsonPath("$.ok").value(true)).andReturn());
    }

    private ResultActions enviar(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private ResultActions enviarArchivo(MockHttpSession sesion, String cuerpo) throws Exception {
        // RequestSizeLimitFilter exige una "cookie de sesión" antes de aceptar un cuerpo grande; MockMvc no la
        // simula con .session(), hay que marcarla a mano (mismo patrón que BridgeFase3kTest).
        return mockMvc.perform(post(RUTA_ARCHIVO).session(sesion).with(csrf())
                .with(r -> {
                    r.setRequestedSessionId(sesion.getId());
                    return r;
                })
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.27.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-4d");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
