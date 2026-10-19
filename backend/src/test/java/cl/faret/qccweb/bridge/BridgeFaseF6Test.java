package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeFaretApi;
import cl.faret.qccweb.auth.FakeFaretApi.Escritura;
import cl.faret.qccweb.auth.FakeFaretSinAuthApi;
import cl.faret.qccweb.auth.FakeInnpackApi;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
 * Fase F6 - IMPORTACIÓN MASIVA FARET (Photino 1.8.15, 9e1b556, FaretHandler.HandleImportacionValidar / Confirmar): faret.importacion.validar
 * (multipart a QualityControlFaret.Api por /api/v1/bridge/archivo) y faret.importacion.confirmar (solo un lote validado por la misma
 * sesión). La API es un fake en localhost; nunca se importa nada real.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000",
    "qcc.web.bridge.validaciones-importacion-por-minuto=1000",
    "qcc.web.bridge.confirmaciones-importacion-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFaseF6Test {

    private static final FakeFaretApi QC = new FakeFaretApi(Clock.systemUTC());
    private static final FakeInnpackApi INNPACK = new FakeInnpackApi(Clock.systemUTC());
    private static final FakeFaretSinAuthApi MC = new FakeFaretSinAuthApi("/mejora-continua");
    private static final FakeFaretSinAuthApi CALIDAD = new FakeFaretSinAuthApi("/calidad/api");
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveFaretF6#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String VALIDAR = "faret.importacion.validar";
    private static final String CONFIRMAR = "faret.importacion.confirmar";
    private static final String MODULO = "faret-importacion";
    private static final String RUTA_BRIDGE = "/api/v1/bridge";
    private static final String RUTA_ARCHIVO = "/api/v1/bridge/archivo";
    private static final String LOTE = "0123456789abcdef0123456789abcdef";
    private static final String OTRO_LOTE = "fedcba9876543210fedcba9876543210";
    private static final String RUTA_VALIDAR = "/api/importaciones/validar";
    private static final String RUTA_CONFIRMAR = "/api/importaciones/confirmar";
    private static final String NO_EXISTE = "El lote no existe o expiró. Vuelva a validar el archivo.";
    private static final String NO_XLSX = "El archivo no es un libro .xlsx válido.";
    private static final String VALIDAR_OK = "{\"success\":true,\"message\":\"ok\",\"data\":{\"loteId\":\"" + LOTE
            + "\",\"nombreArchivo\":\"x.xlsx\",\"totalFilas\":10,\"filasValidas\":8,\"filasConError\":2,\"interno\":\"X\","
            + "\"errores\":[{\"fila\":3,\"errores\":[\"Cliente <b>x</b> inválido\"]},{\"fila\":5,\"errores\":[\"Falta fecha\"]}]},\"errors\":null}";
    private static final String CONFIRMAR_OK = "{\"success\":true,\"message\":\"ok\",\"data\":{\"importacionId\":77,\"filasInsertadas\":8,\"interno\":\"X\"},\"errors\":null}";

    static {
        QC.agregar(new FakeFaretApi.Usuario(10, "admin", "admin@faret.cl", PASS, "Admin Faret", List.of("ADMIN")));
        QC.agregar(new FakeFaretApi.Usuario(11, "admin2", "admin2@faret.cl", PASS, "Admin Dos", List.of("ADMIN")));
        QC.agregar(new FakeFaretApi.Usuario(20, "ana", "ana@faret.cl", PASS, "Ana Calidad", List.of("CALIDAD")));
        QC.agregar(new FakeFaretApi.Usuario(30, "clara", null, PASS, "Clara Consulta", List.of("CONSULTA")));
        QC.agregar(new FakeFaretApi.Usuario(50, "tecnico", "tecnico@faret.cl", PASS, "Tecnico TI", List.of("ADMIN_TI")));
        QC.agregar(new FakeFaretApi.Usuario(60, "ines", "ines@faret.cl", PASS, "Ines Inspector", List.of("INSPECTOR")));
        INNPACK.agregar(new FakeInnpackApi.Usuario(41, "adminti1", PASS, "Admin TI Innpack", "admin_ti", true));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.faret-api-base-url", QC::baseUrl);
        registry.add("qcc.web.auth.innpack-api-base-url", INNPACK::baseUrl);
        registry.add("qcc.web.faret.mejora-continua-base-url", MC::baseUrl);
        registry.add("qcc.web.faret.calidad-base-url", CALIDAD::baseUrl);
        registry.add("qcc.web.www-dir", WWW::toString);
        registry.add("qcc.web.manifest-file", () -> WWW.resolve("no-existe.json").toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reiniciar() {
        QC.limpiarLecturas();
        MC.limpiar();
        CALIDAD.limpiar();
    }

    @AfterAll
    static void cerrar() {
        QC.close();
        INNPACK.close();
        MC.close();
        CALIDAD.close();
    }

    // ------------------------------------------------------------------------------- validar

    @Test
    void validarEnviaMultipartConElBearerYReproyectaLaRespuesta() throws Exception {
        QC.respuestaEscritura("POST " + RUTA_VALIDAR, 200, VALIDAR_OK);
        MvcResult r = validar(login("admin"), mapa("fileName", "carga.xlsx", "base64", xlsxB64())).andExpect(jsonPath("$.ok").value(true)).andReturn();
        JsonNode d = json(r).get("data");
        assertThat(d.get("loteId").asString()).isEqualTo(LOTE);
        assertThat(d.get("nombreArchivo").asString()).isEqualTo("carga.xlsx");
        assertThat(d.get("totalFilas").asInt()).isEqualTo(10);
        assertThat(d.get("filasValidas").asInt()).isEqualTo(8);
        assertThat(d.get("filasConError").asInt()).isEqualTo(2);
        assertThat(d.has("interno")).isFalse();
        // Los textos de error llevan valores de celdas y la vista usa innerHTML: llegan escapados.
        assertThat(d.get("errores").get(0).get("errores").get(0).asString()).isEqualTo("Cliente &lt;b&gt;x&lt;/b&gt; inválido");
        List<Escritura> e = QC.escrituras();
        assertThat(e).hasSize(1);
        assertThat(e.get(0).metodo()).isEqualTo("POST");
        assertThat(e.get(0).uri()).isEqualTo(RUTA_VALIDAR);
        assertThat(e.get(0).sub()).isEqualTo(10);
        assertThat(e.get(0).contentType()).matches("multipart/form-data; ?boundary=qccweb[0-9a-f]{32}");
        assertThat(e.get(0).cuerpo()).contains("name=\"archivo\"; filename=\"carga.xlsx\"")
                .contains("Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(MC.peticiones()).isEmpty();
        assertThat(CALIDAD.peticiones()).isEmpty();
    }

    @Test
    void validarRechazaClavesNombreContenidoYTamanoSinLlamarALaApi() throws Exception {
        MockHttpSession admin = login("admin");
        String ok = xlsxB64();
        esperarValidar(admin, mapa("base64", ok), "Falta fileName");
        esperarValidar(admin, mapa("fileName", "a.xlsx"), "Falta el contenido del archivo (base64)");
        esperarValidar(admin, mapa("fileName", "a.xlsx", "base64", 5), "Parámetro inválido.");
        for (String nombre : List.of("a.xls", "a.csv", "a.xlsx.exe", "a", ".xlsx")) {
            validar(admin, mapa("fileName", nombre, "base64", ok)).andExpect(jsonPath("$.ok").value(false));
        }
        esperarValidar(admin, mapa("fileName", "a.xlsx", "base64", "###"), "Archivo inválido (base64 corrupto)");
        // No es un libro: texto, ZIP sin las partes de un xlsx o con rutas peligrosas.
        String texto = Base64.getEncoder().encodeToString("hola".getBytes(StandardCharsets.UTF_8));
        esperarValidar(admin, mapa("fileName", "a.xlsx", "base64", texto), NO_XLSX);
        esperarValidar(admin, mapa("fileName", "a.xlsx", "base64", Base64.getEncoder().encodeToString(zip("otra.txt"))), NO_XLSX);
        esperarValidar(admin, mapa("fileName", "a.xlsx", "base64",
                Base64.getEncoder().encodeToString(zip("../x", "[Content_Types].xml", "xl/workbook.xml"))), NO_XLSX);
        // Tope de 10 MB.
        esperarValidar(admin, mapa("fileName", "a.xlsx", "base64", "A".repeat(14_000_000)), "El archivo excede el tamaño máximo de 10 MB.");
        validar(admin, mapa("fileName", "a.xlsx", "base64", ok, "usuario", "otro")).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("Campo no permitido")));
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void validarSaneaElNombreDelArchivo() throws Exception {
        QC.respuestaEscritura("POST " + RUTA_VALIDAR, 200, VALIDAR_OK);
        MvcResult r = validar(login("admin"), mapa("fileName", "C:\\tmp\\<img src=x onerror=1>\"a'&.xlsx", "base64", xlsxB64())).andReturn();
        // Con el nombre saneado se acepta; si no queda un nombre válido se rechaza. En ningún caso llega marcado a la API.
        if (json(r).get("ok").asBoolean()) {
            String nombre = json(r).get("data").get("nombreArchivo").asString();
            assertThat(nombre).doesNotContain("<", ">", "\"", "'", "&", "\\", "/").endsWith(".xlsx");
        }
        for (Escritura e : QC.escrituras()) {
            assertThat(e.cuerpo()).doesNotContain("<img");
        }
    }

    @Test
    void validarPropagaLosErroresDeLaApiYRechazaRespuestasSinLote() throws Exception {
        MockHttpSession admin = login("admin");
        QC.respuestaEscritura("POST " + RUTA_VALIDAR, 400, "{\"success\":false,\"message\":\"Faltan columnas: Cliente\",\"data\":null}");
        validar(admin, mapa("fileName", "a.xlsx", "base64", xlsxB64())).andExpect(jsonPath("$.error").value("Faltan columnas: Cliente"));
        QC.respuestaEscritura("POST " + RUTA_VALIDAR, 500, "{\"title\":\"x\",\"stack\":\"SqlException x\"}");
        MvcResult r = validar(admin, mapa("fileName", "a.xlsx", "base64", xlsxB64())).andExpect(jsonPath("$.ok").value(false)).andReturn();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SqlException");
        // Éxito sin loteId válido: no se acepta ni se registra nada.
        QC.respuestaEscritura("POST " + RUTA_VALIDAR, 200, "{\"success\":true,\"data\":{\"loteId\":\"../x\",\"totalFilas\":1}}");
        validar(admin, mapa("fileName", "a.xlsx", "base64", xlsxB64())).andExpect(jsonPath("$.ok").value(false));
        assertThat(QC.escrituras()).hasSize(3);
    }

    @Test
    void validarSoloVaPorLaRutaDeArchivos() throws Exception {
        accion(login("admin"), VALIDAR, MODULO, mapa("fileName", "a.xlsx", "base64", xlsxB64())).andExpect(jsonPath("$.ok").value(false));
        assertThat(QC.escrituras()).isEmpty();
    }

    // ------------------------------------------------------------------------------- confirmar

    @Test
    void confirmarSoloUnLoteValidadoPorLaMismaSesion() throws Exception {
        MockHttpSession admin = login("admin");
        MockHttpSession admin2 = login("admin2");
        QC.respuestaEscritura("POST " + RUTA_VALIDAR, 200, VALIDAR_OK);
        QC.respuestaEscritura("POST " + RUTA_CONFIRMAR, 200, CONFIRMAR_OK);
        // Sin validar antes, o con un lote validado por otra sesión: no se llama a la API.
        confirmar(admin, LOTE).andExpect(jsonPath("$.error").value(NO_EXISTE));
        validar(admin2, mapa("fileName", "a.xlsx", "base64", xlsxB64())).andExpect(jsonPath("$.ok").value(true));
        confirmar(admin, LOTE).andExpect(jsonPath("$.ok").value(false));
        assertThat(QC.escrituras()).hasSize(1);
        // Un lote inventado dentro de la sesión que sí validó tampoco pasa.
        confirmar(admin2, OTRO_LOTE).andExpect(jsonPath("$.ok").value(false));
        MvcResult r = confirmar(admin2, LOTE).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).get("data").toString()).isEqualTo("{\"importacionId\":77,\"filasInsertadas\":8}");
        List<Escritura> e = QC.escrituras();
        assertThat(e).hasSize(2);
        assertThat(e.get(1).uri()).isEqualTo(RUTA_CONFIRMAR);
        assertThat(e.get(1).cuerpo()).isEqualTo("{\"loteId\":\"" + LOTE + "\"}");
        assertThat(e.get(1).sub()).isEqualTo(11);
        // Confirmado: el lote se olvida y no se puede volver a confirmar.
        confirmar(admin2, LOTE).andExpect(jsonPath("$.ok").value(false));
        assertThat(QC.escrituras()).hasSize(2);
    }

    @Test
    void confirmarValidaLaForma() throws Exception {
        MockHttpSession admin = login("admin");
        accion(admin, CONFIRMAR, MODULO, mapa()).andExpect(jsonPath("$.error").value("Falta loteId"));
        accion(admin, CONFIRMAR, MODULO, mapa("loteId", 5)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        confirmar(admin, "../x").andExpect(jsonPath("$.error").value(NO_EXISTE));
        accion(admin, CONFIRMAR, MODULO, mapa("loteId", LOTE, "x", 1))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("Campo no permitido")));
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void confirmarConFalloDeLaApiOResultadoIncierto() throws Exception {
        MockHttpSession admin = login("admin");
        QC.respuestaEscritura("POST " + RUTA_VALIDAR, 200, VALIDAR_OK);
        validar(admin, mapa("fileName", "a.xlsx", "base64", xlsxB64())).andExpect(jsonPath("$.ok").value(true));
        // Error normal de la API: el lote sigue vigente para reintentar.
        QC.respuestaEscritura("POST " + RUTA_CONFIRMAR, 400, "{\"success\":false,\"message\":\"El lote tiene filas con error\",\"data\":null}");
        confirmar(admin, LOTE).andExpect(jsonPath("$.error").value("El lote tiene filas con error"));
        // El lote expiró en la API: se olvida.
        QC.respuestaEscritura("POST " + RUTA_CONFIRMAR, 404, "{\"success\":false,\"message\":\"El lote no existe o expiró.\",\"data\":null}");
        confirmar(admin, LOTE).andExpect(jsonPath("$.ok").value(false));
        confirmar(admin, LOTE).andExpect(jsonPath("$.error").value(NO_EXISTE));
        assertThat(QC.escrituras()).hasSize(3);
        // Éxito con forma inesperada: pudo insertarse; se pide revisar el historial y no se permite reintentar.
        QC.respuestaEscritura("POST " + RUTA_VALIDAR, 200, VALIDAR_OK);
        validar(admin, mapa("fileName", "a.xlsx", "base64", xlsxB64())).andExpect(jsonPath("$.ok").value(true));
        QC.respuestaEscritura("POST " + RUTA_CONFIRMAR, 200, "{\"success\":true,\"data\":{}}");
        confirmar(admin, LOTE).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("historial")));
        confirmar(admin, LOTE).andExpect(jsonPath("$.error").value(NO_EXISTE));
    }

    // ------------------------------------------------------------------------------- seguridad

    @Test
    void soloAdminYAdminTiEnFaretYNuncaInnpack() throws Exception {
        QC.respuestaEscritura("POST " + RUTA_VALIDAR, 200, VALIDAR_OK);
        for (String u : List.of("ana", "clara", "ines")) {
            MockHttpSession s = login(u);
            validar(s, mapa("fileName", "a.xlsx", "base64", xlsxB64())).andExpect(status().isForbidden());
            confirmar(s, LOTE).andExpect(status().isForbidden());
        }
        MockHttpSession innpack = loginInnpack("adminti1");
        validar(innpack, mapa("fileName", "a.xlsx", "base64", xlsxB64())).andExpect(status().isForbidden());
        confirmar(innpack, LOTE).andExpect(status().isForbidden());
        assertThat(QC.escrituras()).isEmpty();
        // admin_ti sí.
        validar(login("tecnico"), mapa("fileName", "a.xlsx", "base64", xlsxB64())).andExpect(jsonPath("$.ok").value(true));
        assertThat(QC.escrituras()).hasSize(1);
    }

    @Test
    void moduloCruzadoYCsrf() throws Exception {
        MockHttpSession admin = login("admin");
        for (String modulo : List.of("faret-data", "faret-nc", "inventado")) {
            accion(admin, CONFIRMAR, modulo, mapa("loteId", LOTE)).andExpect(status().isForbidden());
            enviar(RUTA_ARCHIVO, admin, VALIDAR, modulo, mapa("fileName", "a.xlsx", "base64", xlsxB64())).andExpect(status().isForbidden());
        }
        // Sin token CSRF.
        mockMvc.perform(post(RUTA_BRIDGE).session(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"" + CONFIRMAR + "\",\"_modulo\":\"" + MODULO + "\",\"loteId\":\"" + LOTE + "\"}")).andExpect(status().isForbidden());
        mockMvc.perform(post(RUTA_ARCHIVO).session(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"" + VALIDAR + "\",\"_modulo\":\"" + MODULO + "\",\"fileName\":\"a.xlsx\",\"base64\":\"" + xlsxB64() + "\"}"))
                .andExpect(status().isForbidden());
        // Sin sesión.
        mockMvc.perform(post(RUTA_BRIDGE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"" + CONFIRMAR + "\",\"_modulo\":\"" + MODULO + "\",\"loteId\":\"" + LOTE + "\"}")).andExpect(status().isUnauthorized());
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void auditoriaConLoteYSinContenido(CapturedOutput salida) throws Exception {
        MockHttpSession admin = login("admin");
        QC.respuestaEscritura("POST " + RUTA_VALIDAR, 200, VALIDAR_OK);
        QC.respuestaEscritura("POST " + RUTA_CONFIRMAR, 200, CONFIRMAR_OK);
        validar(admin, mapa("fileName", "secreto-cliente.xlsx", "base64", xlsxB64())).andExpect(jsonPath("$.ok").value(true));
        confirmar(admin, LOTE).andExpect(jsonPath("$.ok").value(true));
        confirmar(admin, OTRO_LOTE).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=admin empresa=FARET accion=faret\\.importacion\\.validar recurso=faret:importacion:validar:lote:"
                + LOTE + " resultado=OK ms=\\d+");
        assertThat(log).containsPattern("accion=faret\\.importacion\\.confirmar recurso=faret:importacion:77:lote:" + LOTE + " resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.importacion\\.confirmar recurso=faret:importacion:lote:" + OTRO_LOTE + " resultado=ERROR");
        assertThat(log).doesNotContain("secreto-cliente").doesNotContain(PASS);
    }

    // ------------------------------------------------------------------------------- helpers

    private static byte[] zip(String... entradas) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(bos)) {
                for (String e : entradas) {
                    z.putNextEntry(new ZipEntry(e));
                    z.write("<x/>".getBytes(StandardCharsets.UTF_8));
                    z.closeEntry();
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String xlsxB64() {
        return Base64.getEncoder().encodeToString(zip("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml"));
    }

    private static Map<String, Object> mapa(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private void esperarValidar(MockHttpSession sesion, Map<String, Object> campos, String error) throws Exception {
        validar(sesion, campos).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value(error));
    }

    private ResultActions validar(MockHttpSession sesion, Map<String, Object> campos) throws Exception {
        return enviar(RUTA_ARCHIVO, sesion, VALIDAR, MODULO, campos);
    }

    private ResultActions confirmar(MockHttpSession sesion, String loteId) throws Exception {
        return accion(sesion, CONFIRMAR, MODULO, mapa("loteId", loteId));
    }

    private ResultActions accion(MockHttpSession sesion, String accion, String modulo, Map<String, Object> campos) throws Exception {
        return enviar(RUTA_BRIDGE, sesion, accion, modulo, campos);
    }

    private ResultActions enviar(String ruta, MockHttpSession sesion, String accion, String modulo, Map<String, Object> campos) throws Exception {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", accion);
        p.put("_modulo", modulo);
        campos.forEach((k, v) -> p.set(k, v == null ? mapper.nullNode() : mapper.valueToTree(v)));
        // El filtro de tamaño exige la cookie de sesión antes de aceptar un cuerpo grande en la ruta de archivos.
        return mockMvc.perform(post(ruta).session(sesion).with(csrf()).with(r -> {
            r.setRequestedSessionId(sesion.getId());
            return r;
        }).contentType(MediaType.APPLICATION_JSON).content(p.toString()));
    }

    private MockHttpSession login(String identificador) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/faret/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.86.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identificador\":\"" + identificador + "\",\"password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private MockHttpSession loginInnpack(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.86.1." + IP.getAndIncrement());
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
            Path dir = Files.createTempDirectory("qcc-web-fixture-f6");
            Files.writeString(dir.resolve("index.html"), "<!doctype html><title>fixture</title>");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
