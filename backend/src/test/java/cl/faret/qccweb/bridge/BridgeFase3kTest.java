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
import java.util.Arrays;
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
 * Fase 3k — noConformidades.adjuntos.subir. Foco: ruta de archivos con tope propio (y el resto sigue en 256 KB),
 * IDENTIDAD (subidoPor → sesión), firma real y tamaño decodificado, nombre saneado AL SUBIR (Photino escritorio lo lee
 * directo de la API y lo pinta con innerHTML). API SIMULADA: se verifica el cuerpo EXACTO que recibe upstream.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3kTest {

    private static final String SUBIR = "noConformidades.adjuntos.subir";
    private static final String RUTA = "/api/v1/bridge/archivo";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveAdjuntos#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String PDF = Base64.getEncoder().encodeToString(FakeInnpackApi.pdfMinimo(1));
    private static final String PNG = Base64.getEncoder().encodeToString(FakeInnpackApi.pngMinimo());
    private static final String JPG = Base64.getEncoder().encodeToString(FakeInnpackApi.jpegMinimo());

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

    /** Payload exacto de _subirPdfSeleccionado de Photino, con overrides opcionales. */
    private String payload(Map<String, Object> cambios) {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", SUBIR);
        p.put("id", 501);
        p.put("tipo", "CAUSA_RAIZ_PDF");
        p.put("nombreArchivo", "causa raíz ñ.pdf");
        p.put("tipoMime", "application/pdf");
        p.put("contenidoBase64", PDF);
        p.put("subidoPor", "Operador Uno");
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
    void pdfYFotosExitososConCuerpoExacto() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(subir(s, payload(Map.of())).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.at("/data/id").asInt()).isEqualTo(701);
        subir(s, payload(Map.of("tipo", "EVIDENCIA_FOTO", "nombreArchivo", "foto 1.png", "tipoMime", "image/png", "contenidoBase64", PNG)))
                .andExpect(jsonPath("$.ok").value(true));
        subir(s, payload(Map.of("tipo", "EVIDENCIA_FOTO", "nombreArchivo", "foto 2.jpg", "tipoMime", "image/jpeg", "contenidoBase64", JPG)))
                .andExpect(jsonPath("$.ok").value(true));
        assertThat(API.peticionesNoConformidades()).containsExactly("POST /api/no-conformidades/501/adjuntos",
                "POST /api/no-conformidades/501/adjuntos", "POST /api/no-conformidades/501/adjuntos");
        List<SeguimientoRecibido> r = API.adjuntosRecibidos();
        assertThat(r.get(0).sub()).isEqualTo(10);
        assertThat(r.get(0).contentType()).startsWith("application/json");
        assertThat(mapper.readTree(r.get(0).cuerpo())).isEqualTo(mapper.readTree("{\"tipo\":\"CAUSA_RAIZ_PDF\",\"nombreArchivo\":\"causa raíz ñ.pdf\","
                + "\"tipoMime\":\"application/pdf\",\"contenidoBase64\":\"" + PDF + "\",\"subidoPor\":\"Operador Uno\"}"));
        assertThat(mapper.readTree(r.get(2).cuerpo()).get("tipoMime").asString()).isEqualTo("image/jpeg");
    }

    @Test
    void subidoPorSiempreEsLaSesion() throws Exception {
        String[][] casos = {{"operador1", "Operador Uno"}, {"admin1", "Admin Uno"}, {"adminti1", "Admin TI"}};
        for (String[] c : casos) {
            subir(login(c[0]), payload(Map.of("subidoPor", "Otro Usuario"))).andExpect(jsonPath("$.ok").value(true));
        }
        subir(login("operador1"), payload(Map.of("subidoPor", Borrar.CAMPO))).andExpect(jsonPath("$.ok").value(true));
        List<SeguimientoRecibido> r = API.adjuntosRecibidos();
        for (int k = 0; k < 3; k++) {
            assertThat(mapper.readTree(r.get(k).cuerpo()).get("subidoPor").asString()).isEqualTo(casos[k][1]);
        }
        assertThat(mapper.readTree(r.get(3).cuerpo()).get("subidoPor").asString()).isEqualTo("Operador Uno");
    }

    // ------------------------------------------------------------------ rutas y topes de cuerpo

    @Test
    void soloPorLaRutaDeArchivosYLaRutaDeArchivosSoloAceptaSubidas() throws Exception {
        MockHttpSession s = login("operador1");
        mockMvc.perform(post("/api/v1/bridge").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload(Map.of())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        for (String otra : List.of("noConformidades.list", "noConformidades.create", "noConformidades.seguimiento.crear", "inicio.getDashboard")) {
            subir(s, "{\"action\":\"" + otra + "\",\"id\":501}").andExpect(status().isForbidden());
        }
        assertThat(API.peticionesNoConformidades()).isEmpty();
    }

    @Test
    void topesDeCuerpoPorRuta() throws Exception {
        MockHttpSession s = login("operador1");
        // PDF de ~1 MB (base64 ~1,4 MB): pasa por la ruta de archivos; por la ruta normal (256 KB) → 413.
        byte[] grande = Arrays.copyOf(FakeInnpackApi.pdfMinimo(1), 1024 * 1024);
        String cuerpo = payload(Map.of("contenidoBase64", Base64.getEncoder().encodeToString(grande)));
        subir(s, cuerpo).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        mockMvc.perform(post("/api/v1/bridge").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().is(413));
        // Sobre el tope de la ruta de archivos (14 MB) → 413 sin leer; cuerpo grande sin cookie de sesión → 401.
        String enorme = "{\"action\":\"" + SUBIR + "\",\"contenidoBase64\":\"" + "A".repeat(14_680_064) + "\"}";
        subir(s, enorme).andExpect(status().is(413));
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isUnauthorized());
        assertThat(API.adjuntosRecibidos()).hasSize(1);
    }

    // ------------------------------------------------------------------ validación

    @Test
    void camposTipoYMimeSeValidanSinLlamarUpstream() throws Exception {
        MockHttpSession s = login("operador1");
        for (String extra : List.of("usuario", "empresa", "adjuntoId", "eliminado", "tamanoBytes", "noConformidadId")) {
            subir(s, payload(Map.of(extra, "x"))).andExpect(jsonPath("$.error").value("Campo no permitido: " + extra));
        }
        subir(s, payload(Map.of("id", Borrar.CAMPO))).andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
        subir(s, payload(Map.of("id", -1))).andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
        subir(s, payload(Map.of("tipo", "OTRO"))).andExpect(jsonPath("$.error").value("Tipo de adjunto inválido (CAUSA_RAIZ_PDF o EVIDENCIA_FOTO)."));
        subir(s, payload(Map.of("tipoMime", "image/png"))).andExpect(jsonPath("$.error").value("Solo se permite un archivo PDF"));
        for (String mime : List.of("application/pdf", "image/gif", "image/webp", "IMAGE/PNG")) {
            subir(s, payload(Map.of("tipo", "EVIDENCIA_FOTO", "tipoMime", mime, "contenidoBase64", PNG)))
                    .andExpect(jsonPath("$.error").value("Solo se permiten fotografías JPG o PNG"));
        }
        subir(s, payload(Map.of("nombreArchivo", "  "))).andExpect(jsonPath("$.error").value("Falta el nombre del archivo"));
        subir(s, payload(Map.of("contenidoBase64", ""))).andExpect(jsonPath("$.error").value("Falta el contenido del archivo"));
        subir(s, payload(Map.of("contenidoBase64", 123))).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        assertThat(API.peticionesNoConformidades()).isEmpty();
    }

    @Test
    void firmaRealBase64EstrictoYTamanoDecodificado() throws Exception {
        MockHttpSession s = login("operador1");
        String html = Base64.getEncoder().encodeToString("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        subir(s, payload(Map.of("contenidoBase64", html))).andExpect(jsonPath("$.error").value("El contenido del archivo no corresponde a un PDF válido."));
        subir(s, payload(Map.of("tipo", "EVIDENCIA_FOTO", "tipoMime", "image/jpeg", "contenidoBase64", PNG)))
                .andExpect(jsonPath("$.error").value("El contenido del archivo no corresponde a un JPG/PNG válido."));
        subir(s, payload(Map.of("tipo", "EVIDENCIA_FOTO", "tipoMime", "image/png", "contenidoBase64", PDF)))
                .andExpect(jsonPath("$.error").value("El contenido del archivo no corresponde a un JPG/PNG válido."));
        for (String malo : List.of("%%%no-base64%%%", "JVBER i0x", "JVBERi0x\n")) {
            subir(s, payload(Map.of("contenidoBase64", malo))).andExpect(jsonPath("$.error").value("El adjunto no es válido."));
        }
        subir(s, payload(Map.of("contenidoBase64", "===="))).andExpect(jsonPath("$.ok").value(false));
        byte[] pdf10 = Arrays.copyOf(FakeInnpackApi.pdfMinimo(1), 10 * 1024 * 1024 + 1);
        subir(s, payload(Map.of("contenidoBase64", Base64.getEncoder().encodeToString(pdf10))))
                .andExpect(jsonPath("$.error").value("El PDF excede el tamaño máximo de 10 MB"));
        byte[] foto5 = Arrays.copyOf(FakeInnpackApi.pngMinimo(), 5 * 1024 * 1024 + 1);
        subir(s, payload(Map.of("tipo", "EVIDENCIA_FOTO", "tipoMime", "image/png", "contenidoBase64", Base64.getEncoder().encodeToString(foto5))))
                .andExpect(jsonPath("$.error").value("La fotografía excede el tamaño máximo de 5 MB"));
        assertThat(API.peticionesNoConformidades()).isEmpty();
        byte[] foto5justa = Arrays.copyOf(FakeInnpackApi.pngMinimo(), 5 * 1024 * 1024);
        subir(s, payload(Map.of("tipo", "EVIDENCIA_FOTO", "tipoMime", "image/png", "contenidoBase64", Base64.getEncoder().encodeToString(foto5justa))))
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void nombreSeSaneaAlSubir() throws Exception {
        MockHttpSession s = login("operador1");
        String[] malos = {"..\\<img src=x onerror=alert(1)>\"'`&.pdf", "C:\\temp\\evil\".pdf", "/etc/a'b.pdf", "\u202Efdp.exe", "...", "con.pdf",
            "x\u0000y.pdf", "a".repeat(300) + ".pdf"};
        for (String n : malos) {
            subir(s, payload(Map.of("nombreArchivo", n))).andExpect(jsonPath("$.ok").value(true));
        }
        List<SeguimientoRecibido> r = API.adjuntosRecibidos();
        assertThat(r).hasSize(malos.length);
        for (SeguimientoRecibido x : r) {
            String nombre = mapper.readTree(x.cuerpo()).get("nombreArchivo").asString();
            assertThat(nombre).doesNotContainPattern("[<>\"'`&\\\\/:|?*\\x00-\\x1f\\u202a-\\u202e]").hasSizeLessThanOrEqualTo(150).isNotBlank();
        }
        assertThat(mapper.readTree(r.get(0).cuerpo()).get("nombreArchivo").asString()).isEqualTo("_img src=x onerror=alert(1)_____.pdf");
        assertThat(mapper.readTree(r.get(1).cuerpo()).get("nombreArchivo").asString()).isEqualTo("evil_.pdf");
        assertThat(mapper.readTree(r.get(3).cuerpo()).get("nombreArchivo").asString()).isEqualTo("fdp.exe");
        assertThat(mapper.readTree(r.get(4).cuerpo()).get("nombreArchivo").asString()).isEqualTo("adjunto.pdf");
        assertThat(mapper.readTree(r.get(5).cuerpo()).get("nombreArchivo").asString()).isEqualTo("_con.pdf");
    }

    // ------------------------------------------------------------------ autorización, errores y auditoría

    @Test
    void rolesPoliticaYErroresDeNegocio() throws Exception {
        subir(login("consulta1"), payload(Map.of())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(policy.evaluar(SUBIR, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        assertThat(policy.evaluar(SUBIR, usuario("INNPACK", "consulta"))).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
        Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(SUBIR)).findFirst().orElseThrow();
        assertThat(d.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
        assertThat(d.get("identidad")).isEqualTo(Map.of("subidoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO));
        assertThat(policy.accionesRegistradas()).doesNotContain("noConformidades.catalogos.nciAreas.crear");
        MockHttpSession s = login("operador1");
        subir(s, payload(Map.of("id", 777))).andExpect(jsonPath("$.error").value("La no conformidad está cerrada, no se pueden agregar adjuntos"));
        subir(s, payload(Map.of("id", 404))).andExpect(jsonPath("$.error").value("No conformidad no encontrada"));
        assertThat(API.adjuntosRecibidos()).hasSize(2);
    }

    @Test
    void auditoriaConNcYAdjuntoSinNombreNiContenido(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        subir(s, payload(Map.of("nombreArchivo", "Informe Sensible 12.345.678-9.pdf"))).andExpect(jsonPath("$.ok").value(true));
        subir(s, payload(Map.of("id", 777))).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=noConformidades\\.adjuntos\\.subir "
                + "recurso=nc:501:adjunto:701 resultado=OK ms=\\d+");
        assertThat(log).contains("accion=noConformidades.adjuntos.subir recurso=nc:777 resultado=ERROR");
        assertThat(log).doesNotContain("Informe Sensible", "12.345.678-9", PDF, FakeInnpackApi.firmaDeToken(10), PASS);
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.18.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private ResultActions subir(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post(RUTA).session(sesion).with(csrf())
                .with(r -> {
                    r.setRequestedSessionId(sesion.getId());
                    return r;
                })
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
            Path www = Files.createTempDirectory("qcc-web-fixture-3k");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
