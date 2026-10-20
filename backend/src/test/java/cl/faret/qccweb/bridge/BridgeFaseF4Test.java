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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
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
 * Fase F4 - ESCRITURAS MODIFICADORAS de No Conformidades FARET (Photino 1.8.15, 9e1b556, FaretHandler.cs): faret.nc.{create, update,
 * gestion.actualizar, cerrar, acciones.actualizar} contra MejoraContinua SIN Authorization y faret.nc.{crearRegistro,
 * actualizarRegistro} contra QualityControlFaret.Api (Bearer del usuario) + MejoraContinua. TODA la API es un fake en localhost
 * (FakeFaretSinAuthApi / FakeFaretApi); nunca se escribe en una API real.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFaseF4Test {

    private static final FakeFaretApi QC = new FakeFaretApi(Clock.systemUTC());
    private static final FakeInnpackApi INNPACK = new FakeInnpackApi(Clock.systemUTC());
    private static final FakeFaretSinAuthApi MC = new FakeFaretSinAuthApi("/mejora-continua");
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveFaretF4#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String NC = "/api/no-conformidades";
    private static final String CREATE = "faret.nc.create";
    private static final String UPDATE = "faret.nc.update";
    private static final String GESTION = "faret.nc.gestion.actualizar";
    private static final String CERRAR = "faret.nc.cerrar";
    private static final String ACCION = "faret.nc.acciones.actualizar";
    private static final String CREAR_REGISTRO = "faret.nc.crearRegistro";
    private static final String ACTUALIZAR_REGISTRO = "faret.nc.actualizarRegistro";
    private static final String PNC = "/api/importaciones/pnc";
    private static final String NC7 = "{\"id\":7,\"codigo\":\"NC-2026-007\",\"tipo\":\"INTERNA\",\"origen\":\"AUDITORIA_INTERNA\","
            + "\"titulo\":\"Sello abierto\",\"descripcion\":\"D\",\"severidad\":\"ALTA\",\"estado\":\"ABIERTA\",\"estadoGestion\":\"PENDIENTE\","
            + "\"proceso\":\"Sellado\",\"responsable\":\"Pedro\",\"fechaDeteccion\":\"2026-10-01T00:00:00\",\"fechaCierre\":null}";
    private static final String NC7_CAMBIADA = NC7.replace("\"estadoGestion\":\"PENDIENTE\"", "\"estadoGestion\":\"CERRADA\"");
    private static final String NC7_CERRADA = NC7.replace("\"fechaCierre\":null", "\"fechaCierre\":\"2026-10-09T10:00:00\"");

    static {
        QC.agregar(new FakeFaretApi.Usuario(10, "admin", "admin@faret.cl", PASS, "Admin Faret", List.of("ADMIN")));
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
    }

    @AfterAll
    static void cerrar() {
        QC.close();
        INNPACK.close();
        MC.close();
    }

    // ------------------------------------------------------------------------------- create

    @Test
    void createEnviaElCuerpoExactoDePhotinoSinAuthorizationYReproyectaLaRespuesta() throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder("POST", NC, 200, "{\"id\":88,\"interno\":\"SECRETO\"}");
        // Formulario manual "Nueva NC" (reportadoPor/responsable/norma vacíos: la vista manda "").
        Map<String, Object> m = ncValida();
        m.put("norma", "ISO 9001");
        m.put("reportadoPor", "Luis");
        m.put("responsable", "");
        MvcResult r = accion(ana, CREATE, m).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).get("data").toString()).isEqualTo("{\"id\":88}");
        assertThat(MC.peticiones()).containsExactly("POST " + NC);
        assertThat(MC.cuerpos().get(0)).isEqualTo("{\"tipo\":\"INTERNA\",\"origen\":\"AUDITORIA_INTERNA\",\"sistemaOrigen\":null,\"origenId\":null,"
                + "\"titulo\":\"Sello abierto\",\"descripcion\":\"Descripción\\ncon salto\",\"severidad\":\"ALTA\",\"proceso\":\"Sellado\","
                + "\"norma\":\"ISO 9001\",\"reportadoPor\":\"Luis\",\"responsable\":\"\",\"fechaDeteccion\":\"2026-10-01\"}");
        assertThat(MC.authorizations()).containsOnlyNulls();
        assertThat(QC.lecturas()).isEmpty();
        assertThat(QC.escrituras()).isEmpty();
        // "Gestionar" desde una fila de Data o de Inspección: vínculo con origenId como texto.
        Map<String, Object> g = ncValida();
        g.put("sistemaOrigen", "DATA_FARET");
        g.put("origenId", "321");
        accion(ana, CREATE, g).andExpect(jsonPath("$.ok").value(true));
        assertThat(MC.cuerpos().get(1)).startsWith("{\"tipo\":\"INTERNA\",\"origen\":\"AUDITORIA_INTERNA\",\"sistemaOrigen\":\"DATA_FARET\",\"origenId\":\"321\",");
        g.put("sistemaOrigen", "INSPECCION_FARET");
        accion(ana, CREATE, g).andExpect(jsonPath("$.ok").value(true));
        assertThat(MC.cuerpos().get(2)).contains("\"sistemaOrigen\":\"INSPECCION_FARET\",\"origenId\":\"321\"");
    }

    @Test
    void createValidaClavesObligatoriosOpcionesTextosFechaYVinculoSinLlamarALaApi() throws Exception {
        MockHttpSession ana = login("ana");
        for (String clave : List.of("creadoPor", "usuario", "estado", "estadoGestion", "empresa", "codigo", "id", "data", "x<y")) {
            Map<String, Object> m = ncValida();
            m.put(clave, "x");
            accion(ana, CREATE, m).andExpect(jsonPath("$.error").value("Campo no permitido: " + (clave.equals("x<y") ? "?" : clave)));
        }
        esperar(ana, CREATE, sin(ncValida(), "tipo"), "Falta el tipo");
        esperar(ana, CREATE, sin(ncValida(), "origen"), "Falta el origen");
        esperar(ana, CREATE, sin(ncValida(), "titulo"), "Falta el título");
        esperar(ana, CREATE, sin(ncValida(), "descripcion"), "Falta la descripción");
        esperar(ana, CREATE, sin(ncValida(), "severidad"), "Falta la severidad");
        esperar(ana, CREATE, sin(ncValida(), "proceso"), "Falta el proceso");
        esperar(ana, CREATE, sin(ncValida(), "fechaDeteccion"), "Falta la fecha de detección");
        esperar(ana, CREATE, con(ncValida(), "titulo", "   "), "Falta el título");
        esperar(ana, CREATE, con(ncValida(), "tipo", "OTRO"), "Tipo inválido. Valores permitidos: INTERNA, EXTERNA");
        esperar(ana, CREATE, con(ncValida(), "origen", "CLIENTE"), "Origen inválido. Valores permitidos: AUDITORIA_INTERNA, AUDITORIA_EXTERNA");
        esperar(ana, CREATE, con(ncValida(), "severidad", "CRITICA"), "Severidad inválida. Valores permitidos: ALTA, MEDIA, BAJA");
        esperar(ana, CREATE, con(ncValida(), "fechaDeteccion", "2026-02-30"), "La fecha de detección no es válida (formato AAAA-MM-DD).");
        esperar(ana, CREATE, con(ncValida(), "fechaDeteccion", "01/10/2026"), "La fecha de detección no es válida (formato AAAA-MM-DD).");
        for (String campo : List.of("tipo", "origen", "titulo", "descripcion", "severidad", "proceso", "fechaDeteccion", "norma", "reportadoPor", "responsable")) {
            for (Object malo : List.of(5, true, List.of("x"), Map.of("a", 1))) {
                esperar(ana, CREATE, con(ncValida(), campo, malo), "Parámetro inválido.");
            }
        }
        esperar(ana, CREATE, con(ncValida(), "titulo", "x".repeat(256)), "El campo titulo supera el máximo de 255 caracteres.");
        esperar(ana, CREATE, con(ncValida(), "proceso", "x".repeat(151)), "El campo proceso supera el máximo de 150 caracteres.");
        esperar(ana, CREATE, con(ncValida(), "norma", "x".repeat(151)), "El campo norma supera el máximo de 150 caracteres.");
        esperar(ana, CREATE, con(ncValida(), "responsable", "x".repeat(151)), "El campo responsable supera el máximo de 150 caracteres.");
        esperar(ana, CREATE, con(ncValida(), "reportadoPor", "x".repeat(151)), "El campo reportadoPor supera el máximo de 150 caracteres.");
        esperar(ana, CREATE, con(ncValida(), "descripcion", "x".repeat(65_536)), "El campo descripcion supera el máximo permitido (65.535 bytes).");
        esperar(ana, CREATE, con(ncValida(), "titulo", "a\u0000b"), "El texto contiene caracteres no permitidos.");
        esperar(ana, CREATE, con(ncValida(), "titulo", "a\nb"), "El texto contiene caracteres no permitidos.");
        esperar(ana, CREATE, con(ncValida(), "descripcion", "a\u0007b"), "El texto contiene caracteres no permitidos.");
        for (String campo : List.of("titulo", "descripcion", "proceso", "norma", "reportadoPor", "responsable")) {
            esperar(ana, CREATE, con(ncValida(), campo, "<script>alert(1)</script>"),
                    "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").");
        }
        // Vínculo: juntos o ninguno, solo los sistemas de la vista, origenId de dígitos.
        esperar(ana, CREATE, con(ncValida(), "sistemaOrigen", "DATA_FARET"), "El vínculo de origen requiere sistemaOrigen y origenId.");
        esperar(ana, CREATE, con(ncValida(), "origenId", "5"), "El vínculo de origen requiere sistemaOrigen y origenId.");
        esperar(ana, CREATE, con(con(ncValida(), "sistemaOrigen", "OTRO_SISTEMA"), "origenId", "5"),
                "Sistema de origen inválido. Valores permitidos: DATA_FARET, INSPECCION_FARET");
        for (Object malo : List.of("abc", "-5", "0", "5; DROP", "1234567890123456789")) {
            esperar(ana, CREATE, con(con(ncValida(), "sistemaOrigen", "DATA_FARET"), "origenId", malo), "El origenId no es válido.");
        }
        for (Object malo : List.of(true, List.of(1), Map.of("a", 1), 1.5)) {
            esperar(ana, CREATE, con(con(ncValida(), "sistemaOrigen", "DATA_FARET"), "origenId", malo), "Parámetro inválido.");
        }
        assertThat(MC.peticiones()).isEmpty();
        // Texto normal con acentos, emojis y "a < b" pasa intacto.
        MC.responder("POST", NC, 200, "{\"id\":1}");
        accion(ana, CREATE, con(con(ncValida(), "titulo", "Falla 5<6 ñ 😀"), "origenId", 12)).andExpect(jsonPath("$.error").value("El vínculo de origen requiere sistemaOrigen y origenId."));
        accion(ana, CREATE, con(ncValida(), "titulo", "Falla 5<6 ñ 😀")).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void createErroresDeLaApiSePropaganSinReintentos() throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder("POST", NC, 400, "{\"mensaje\":\"fechaDeteccion es obligatoria\"}");
        accion(ana, CREATE, ncValida()).andExpect(jsonPath("$.error").value("fechaDeteccion es obligatoria"));
        MC.responder("POST", NC, 500, "{\"title\":\"Internal Server Error\",\"stack\":\"SqlException x\"}");
        MvcResult r = accion(ana, CREATE, ncValida()).andExpect(jsonPath("$.error").value("Internal Server Error")).andReturn();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SqlException");
        MC.responder("POST", NC, 502, "<html>gateway</html>");
        accion(ana, CREATE, ncValida()).andExpect(jsonPath("$.error").value("Error al comunicarse con la API de Mejora Continua"));
        MC.responder("POST", NC, 401, "{\"title\":\"Unauthorized\"}");
        accion(ana, CREATE, ncValida()).andExpect(status().isOk()).andExpect(jsonPath("$.error").value("Unauthorized"));
        assertThat(ana.isInvalid()).isFalse();
        assertThat(MC.peticiones()).hasSize(4);
    }

    // ------------------------------------------------------------------------------- update

    @Test
    void updateConHuellaDeLaListaEnviaElPutCompletoYSigueFuncionandoSinRecargar() throws Exception {
        MockHttpSession ana = login("ana");
        // Sin lectura previa no se escribe.
        accion(ana, UPDATE, ncUpdate()).andExpect(jsonPath("$.error").value(GestionMsg.SIN_LEER));
        assertThat(MC.peticiones()).isEmpty();
        // La vista abre el formulario con los datos de faret.nc.list: eso registra la huella de cada NC.
        MC.responder(NC, 200, "[" + NC7 + ",{\"id\":8,\"titulo\":\"otra\"}]");
        accion(ana, "faret.nc.list", Map.of()).andExpect(jsonPath("$.ok").value(true));
        MC.limpiar();
        MC.responder("GET", NC + "/7", 200, NC7);
        MC.responder("PUT", NC + "/7", 200, "{\"mensaje\":\"Actualizado correctamente\",\"interno\":\"SECRETO\"}");
        Map<String, Object> m = ncUpdate();
        m.put("reportadoPor", "");
        m.put("responsable", "Pedro");
        MvcResult r = accion(ana, UPDATE, m).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).get("data").toString()).isEqualTo("{\"mensaje\":\"Actualizado correctamente\"}");
        assertThat(MC.peticiones()).containsExactly("GET " + NC + "/7", "PUT " + NC + "/7", "GET " + NC + "/7");
        assertThat(MC.cuerpos().get(1)).isEqualTo("{\"tipo\":\"INTERNA\",\"origen\":\"AUDITORIA_INTERNA\",\"sistemaOrigen\":null,\"origenId\":null,"
                + "\"titulo\":\"Sello abierto\",\"descripcion\":\"Descripción\\ncon salto\",\"severidad\":\"ALTA\",\"proceso\":\"Sellado\","
                + "\"norma\":null,\"reportadoPor\":\"\",\"responsable\":\"Pedro\",\"fechaDeteccion\":\"2026-10-01\"}");
        assertThat(MC.authorizations()).containsOnlyNulls();
        // Tras escribir se vuelve a leer y registrar: se puede volver a guardar sin recargar la lista (el detalle de Gestionar no la recarga).
        MC.limpiar();
        MC.responder("GET", NC + "/7", 200, NC7);
        MC.responder("PUT", NC + "/7", 200, "{\"mensaje\":\"ok\"}");
        accion(ana, UPDATE, m).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void updateConHuellaDeFarEtNcGetYLostUpdateDetectado() throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder("GET", NC + "/7", 200, NC7);
        MC.responder("PUT", NC + "/7", 200, "{\"mensaje\":\"ok\"}");
        accion(ana, "faret.nc.get", Map.of("id", 7)).andExpect(jsonPath("$.ok").value(true));
        // Otra persona cambió la NC desde que se abrió: no se escribe nada.
        MC.responder("GET", NC + "/7", 200, NC7_CAMBIADA);
        accion(ana, UPDATE, ncUpdate()).andExpect(jsonPath("$.error").value(GestionMsg.CONFLICTO));
        assertThat(MC.peticiones().stream().filter(p -> p.startsWith("PUT"))).isEmpty();
        // Sigue sin poder guardar hasta releer (get o lista).
        accion(ana, UPDATE, ncUpdate()).andExpect(jsonPath("$.error").value(GestionMsg.CONFLICTO));
        accion(ana, "faret.nc.get", Map.of("id", 7)).andExpect(jsonPath("$.ok").value(true));
        accion(ana, UPDATE, ncUpdate()).andExpect(jsonPath("$.ok").value(true));
        // La huella es por sesión: otra sesión que no leyó la NC no escribe.
        accion(login("admin"), UPDATE, ncUpdate()).andExpect(jsonPath("$.error").value(GestionMsg.SIN_LEER));
        assertThat(MC.peticiones().stream().filter(p -> p.startsWith("PUT"))).hasSize(1);
    }

    @Test
    void updateValidaYPropagaErroresDeLaApi() throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder("GET", NC + "/7", 200, NC7);
        accion(ana, "faret.nc.get", Map.of("id", 7));
        MC.limpiar();
        esperar(ana, UPDATE, sin(ncUpdate(), "id"), "Falta el id de la no conformidad");
        esperar(ana, UPDATE, con(ncUpdate(), "id", 0), "Falta el id de la no conformidad");
        for (Object malo : List.of(true, List.of(1), Map.of("a", 1), 1.5, "abc", 3000000000L)) {
            esperar(ana, UPDATE, con(ncUpdate(), "id", malo), "Parámetro inválido.");
        }
        // El vínculo no se edita: sistemaOrigen/origenId no son claves de update.
        esperar(ana, UPDATE, con(ncUpdate(), "sistemaOrigen", "DATA_FARET"), "Campo no permitido: sistemaOrigen");
        esperar(ana, UPDATE, con(ncUpdate(), "origenId", "5"), "Campo no permitido: origenId");
        esperar(ana, UPDATE, con(ncUpdate(), "estadoGestion", "CERRADA"), "Campo no permitido: estadoGestion");
        esperar(ana, UPDATE, con(ncUpdate(), "titulo", ""), "Falta el título");
        esperar(ana, UPDATE, con(ncUpdate(), "severidad", "CRITICA"), "Severidad inválida. Valores permitidos: ALTA, MEDIA, BAJA");
        esperar(ana, UPDATE, con(ncUpdate(), "descripcion", "<b>x</b>"), "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").");
        assertThat(MC.peticiones()).isEmpty();
        // 404 en la relectura (NC eliminada) y errores del PUT.
        MC.responder("GET", NC + "/7", 404, "");
        accion(ana, UPDATE, ncUpdate()).andExpect(jsonPath("$.error").value("No conformidad no encontrada"));
        MC.responder("GET", NC + "/7", 200, NC7);
        MC.responder("PUT", NC + "/7", 404, "{\"title\":\"Not Found\"}");
        accion(ana, UPDATE, ncUpdate()).andExpect(jsonPath("$.error").value("Not Found"));
        MC.responder("PUT", NC + "/7", 500, "{\"title\":\"Internal Server Error\"}");
        accion(ana, UPDATE, ncUpdate()).andExpect(jsonPath("$.error").value("Internal Server Error"));
        assertThat(MC.peticiones().stream().filter(p -> p.startsWith("PUT"))).hasSize(2);
    }

    // ------------------------------------------------------------------------------- gestion.actualizar

    @Test
    void gestionEnviaElPatchConActualizadoPorDeLaSesionYCerradaEsValida() throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder("GET", NC + "/7", 200, NC7);
        MC.responder("PATCH", NC + "/7/gestion", 200, "{\"mensaje\":\"Gestión actualizada correctamente\",\"interno\":\"SECRETO\"}");
        accion(ana, "faret.nc.get", Map.of("id", 7));
        MC.limpiar();
        MC.responder("GET", NC + "/7", 200, NC7);
        MC.responder("PATCH", NC + "/7/gestion", 200, "{\"mensaje\":\"Gestión actualizada correctamente\",\"interno\":\"SECRETO\"}");
        Map<String, Object> m = mapa("id", 7, "responsable", " Pedro Soto ", "estadoGestion", "PENDIENTE", "fechaCompromiso", "2026-11-15", "actualizadoPor", "Falso Autor");
        MvcResult r = accion(ana, GESTION, m).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).get("data").toString()).isEqualTo("{\"mensaje\":\"Gestión actualizada correctamente\"}");
        assertThat(MC.peticiones()).containsExactly("GET " + NC + "/7", "PATCH " + NC + "/7/gestion", "GET " + NC + "/7");
        assertThat(MC.cuerpos().get(1)).isEqualTo("{\"responsable\":\"Pedro Soto\",\"estadoGestion\":\"PENDIENTE\",\"fechaCompromiso\":\"2026-11-15\",\"actualizadoPor\":\"Ana Calidad\"}");
        assertThat(MC.authorizations()).containsOnlyNulls();
        // Sin actualizadoPor, con fecha null y CERRADA (la vista lo ofrece): mismo resultado, la sesión pone el autor.
        Map<String, Object> c = mapa("id", 7, "responsable", "", "estadoGestion", "CERRADA", "fechaCompromiso", null);
        accion(ana, GESTION, c).andExpect(jsonPath("$.ok").value(true));
        assertThat(MC.cuerpos().get(4)).isEqualTo("{\"responsable\":\"\",\"estadoGestion\":\"CERRADA\",\"fechaCompromiso\":null,\"actualizadoPor\":\"Ana Calidad\"}");
    }

    @Test
    void gestionValidaYDetectaLostUpdate() throws Exception {
        MockHttpSession ana = login("ana");
        Map<String, Object> ok = mapa("id", 7, "responsable", "P", "estadoGestion", "PENDIENTE", "fechaCompromiso", null);
        accion(ana, GESTION, ok).andExpect(jsonPath("$.error").value(GestionMsg.SIN_LEER));
        MC.responder("GET", NC + "/7", 200, NC7);
        MC.responder("PATCH", NC + "/7/gestion", 200, "{\"mensaje\":\"ok\"}");
        accion(ana, "faret.nc.get", Map.of("id", 7));
        MC.limpiar();
        for (String clave : List.of("tipo", "titulo", "cerradoPor", "usuario", "fechaCierre")) {
            esperar(ana, GESTION, con(ok, clave, "x"), "Campo no permitido: " + clave);
        }
        esperar(ana, GESTION, sin(ok, "id"), "Falta el id de la no conformidad");
        esperar(ana, GESTION, sin(ok, "estadoGestion"), "Falta el estado de gestión");
        esperar(ana, GESTION, con(ok, "estadoGestion", ""), "Falta el estado de gestión");
        for (String estado : List.of("ASIGNADA", "EN_GESTION", "abierta", "Pendiente")) {
            esperar(ana, GESTION, con(ok, "estadoGestion", estado), "Estado de gestión inválido. Valores permitidos: PENDIENTE, CERRADA");
        }
        esperar(ana, GESTION, con(ok, "fechaCompromiso", "2026-13-01"), "La fecha compromiso no es válida (formato AAAA-MM-DD).");
        esperar(ana, GESTION, con(ok, "responsable", "x".repeat(151)), "El campo responsable supera el máximo de 150 caracteres.");
        esperar(ana, GESTION, con(ok, "responsable", "<img src=x>"), "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").");
        for (Object malo : List.of(7, true, List.of("a"), Map.of("a", 1))) {
            esperar(ana, GESTION, con(ok, "responsable", malo), "Parámetro inválido.");
            esperar(ana, GESTION, con(ok, "estadoGestion", malo), "Parámetro inválido.");
            esperar(ana, GESTION, con(ok, "fechaCompromiso", malo), "Parámetro inválido.");
        }
        assertThat(MC.peticiones()).isEmpty();
        MC.responder("GET", NC + "/7", 200, NC7_CAMBIADA);
        accion(ana, GESTION, ok).andExpect(jsonPath("$.error").value(GestionMsg.CONFLICTO));
        MC.responder("GET", NC + "/7", 404, "");
        accion(ana, GESTION, ok).andExpect(jsonPath("$.error").value("No conformidad no encontrada"));
        assertThat(MC.peticiones().stream().filter(p -> p.startsWith("PATCH"))).isEmpty();
        MC.responder("GET", NC + "/7", 200, NC7);
        MC.responder("PATCH", NC + "/7/gestion", 404, "");
        accion(ana, GESTION, ok).andExpect(jsonPath("$.error").value("HTTP 404: Not Found"));
        MC.responder("PATCH", NC + "/7/gestion", 500, "{\"title\":\"Internal Server Error\"}");
        accion(ana, GESTION, ok).andExpect(jsonPath("$.error").value("Internal Server Error"));
    }

    @Test
    void gestionCerradaSoloLaPuedenAdminAdminTiYCalidadYPendienteQuedaAbiertoATodos() throws Exception {
        String sinPermiso = "Solo administración o calidad puede dejar una no conformidad en estado CERRADA.";
        for (String usuario : List.of("clara", "ines")) {
            MockHttpSession s = login(usuario);
            MC.responder("GET", NC + "/7", 200, NC7);
            accion(s, "faret.nc.get", Map.of("id", 7));
            MC.limpiar();
            accion(s, GESTION, mapa("id", 7, "responsable", "P", "estadoGestion", "CERRADA", "fechaCompromiso", null))
                    .andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value(sinPermiso));
            // Rechazada antes de releer o escribir: ninguna llamada a la API.
            assertThat(MC.peticiones()).isEmpty();
            MC.responder("GET", NC + "/7", 200, NC7);
            MC.responder("PATCH", NC + "/7/gestion", 200, "{\"mensaje\":\"Gestión actualizada correctamente\"}");
            accion(s, GESTION, mapa("id", 7, "responsable", "P", "estadoGestion", "PENDIENTE", "fechaCompromiso", null))
                    .andExpect(jsonPath("$.ok").value(true));
            assertThat(MC.cuerpos().get(1)).contains("\"estadoGestion\":\"PENDIENTE\"");
            MC.limpiar();
        }
        for (String usuario : List.of("admin", "ana", "tecnico")) {
            MockHttpSession s = login(usuario);
            MC.responder("GET", NC + "/7", 200, NC7);
            MC.responder("PATCH", NC + "/7/gestion", 200, "{\"mensaje\":\"Gestión actualizada correctamente\"}");
            accion(s, "faret.nc.get", Map.of("id", 7));
            accion(s, GESTION, mapa("id", 7, "responsable", "P", "estadoGestion", "CERRADA", "fechaCompromiso", null))
                    .andExpect(jsonPath("$.ok").value(true));
            assertThat(MC.cuerpos().stream().anyMatch(c -> c.contains("\"estadoGestion\":\"CERRADA\""))).isTrue();
            MC.limpiar();
        }
    }

    // ------------------------------------------------------------------------------- cerrar

    @Test
    void cerrarEnviaCerradoPorDeLaSesionYSoloLoPuedenAdminAdminTiYCalidad() throws Exception {
        MC.responder("GET", NC + "/7", 200, NC7);
        MC.responder("POST", NC + "/7/cerrar", 200, "{\"mensaje\":\"No conformidad cerrada correctamente\",\"interno\":\"SECRETO\"}");
        MockHttpSession ana = login("ana");
        MvcResult r = accion(ana, CERRAR, mapa("id", 7, "cerradoPor", "Falso Autor", "comentarioCierre", "  Cierre\ncon salto  ")).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).get("data").toString()).isEqualTo("{\"mensaje\":\"No conformidad cerrada correctamente\"}");
        assertThat(MC.peticiones()).startsWith("GET " + NC + "/7", "POST " + NC + "/7/cerrar");
        assertThat(MC.cuerpos().get(1)).isEqualTo("{\"cerradoPor\":\"Ana Calidad\",\"comentarioCierre\":\"Cierre\\ncon salto\"}");
        assertThat(MC.authorizations()).containsOnlyNulls();
        // comentarioCierre null / vacío (la vista manda `|| null`) viaja como null; sin cerradoPor en el payload igual lo pone la sesión.
        for (Object vacio : java.util.Arrays.asList(null, "", "   ")) {
            Map<String, Object> m = mapa("id", 7);
            m.put("comentarioCierre", vacio);
            accion(admin(), CERRAR, m).andExpect(jsonPath("$.ok").value(true));
        }
        assertThat(MC.cuerpos().stream().filter(c -> c.startsWith("{\"cerradoPor\":\"Admin Faret\""))).containsOnly(
                "{\"cerradoPor\":\"Admin Faret\",\"comentarioCierre\":null}");
        accion(login("tecnico"), CERRAR, mapa("id", 7)).andExpect(jsonPath("$.ok").value(true));
        // inspector y consulta no cierran: ni siquiera llegan al handler.
        int antes = MC.peticiones().size();
        for (String u : List.of("ines", "clara")) {
            accion(login(u), CERRAR, mapa("id", 7)).andExpect(status().isForbidden());
        }
        assertThat(MC.peticiones()).hasSize(antes);
    }

    @Test
    void cerrarValidaYPropagaErroresDeLaApi() throws Exception {
        MockHttpSession ana = login("ana");
        for (String clave : List.of("usuario", "actualizadoPor", "estadoGestion", "fechaCierre")) {
            esperar(ana, CERRAR, con(mapa("id", 7), clave, "x"), "Campo no permitido: " + clave);
        }
        esperar(ana, CERRAR, mapa("comentarioCierre", "x"), "Falta el id de la no conformidad");
        for (Object malo : List.of(5, true, List.of("a"), Map.of("a", 1))) {
            esperar(ana, CERRAR, con(mapa("id", 7), "comentarioCierre", malo), "Parámetro inválido.");
        }
        esperar(ana, CERRAR, con(mapa("id", 7), "comentarioCierre", "a\u0000b"), "El comentario contiene caracteres no permitidos.");
        esperar(ana, CERRAR, con(mapa("id", 7), "comentarioCierre", "<b>x</b>"),
                "El comentario no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").");
        esperar(ana, CERRAR, con(mapa("id", 7), "comentarioCierre", "x".repeat(65_536)), "El comentario supera el máximo permitido (65.535 bytes).");
        assertThat(MC.peticiones()).isEmpty();
        // 404 previo: la API respondería OK sin cerrar nada.
        accion(ana, CERRAR, mapa("id", 404)).andExpect(jsonPath("$.error").value("No conformidad no encontrada"));
        assertThat(MC.peticiones()).containsExactly("GET " + NC + "/404");
        MC.limpiar();
        MC.responder("GET", NC + "/7", 200, NC7);
        MC.responder("POST", NC + "/7/cerrar", 500, "{\"title\":\"Internal Server Error\"}");
        accion(ana, CERRAR, mapa("id", 7)).andExpect(jsonPath("$.error").value("Internal Server Error"));
        // Con comentario y la NC sigue abierta tras el error: el error original (nada quedó cerrado).
        accion(ana, CERRAR, mapa("id", 7, "comentarioCierre", "c")).andExpect(jsonPath("$.error").value("Internal Server Error"));
        assertThat(MC.peticiones().stream().filter(p -> p.startsWith("POST"))).hasSize(2);
    }

    @Test
    void cerrarSiFallaElSeguimientoDeLaApiLaNcQuedoCerradaYElErrorLoDice() throws Exception {
        MockHttpSession ana = login("ana");
        // La API cierra y DESPUÉS crea el seguimiento "NC cerrada. ..."; si éste falla responde 500 con la NC YA cerrada.
        // Lectura previa: abierta; relectura tras el error: cerrada -> el error lo informa.
        MC.encolar("GET", NC + "/7", 200, NC7);
        MC.encolar("GET", NC + "/7", 200, NC7_CERRADA);
        MC.responder("POST", NC + "/7/cerrar", 500, "{\"title\":\"Internal Server Error\"}");
        accion(ana, CERRAR, mapa("id", 7, "comentarioCierre", "Cierre OK")).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(GestionMsg.CIERRE_SIN_SEGUIMIENTO));
        assertThat(MC.peticiones()).containsExactly("GET " + NC + "/7", "POST " + NC + "/7/cerrar", "GET " + NC + "/7");
        // Sin comentario no hay seguimiento que pueda fallar: el error de la API tal cual (sin relectura).
        MC.limpiar();
        MC.encolar("GET", NC + "/7", 200, NC7);
        MC.responder("POST", NC + "/7/cerrar", 500, "{\"title\":\"Internal Server Error\"}");
        accion(ana, CERRAR, mapa("id", 7)).andExpect(jsonPath("$.error").value("Internal Server Error"));
        assertThat(MC.peticiones()).containsExactly("GET " + NC + "/7", "POST " + NC + "/7/cerrar");
        // NC que ya estaba cerrada antes de intentar: el error NO se interpreta como "quedó cerrada" (ya lo estaba).
        MC.limpiar();
        MC.encolar("GET", NC + "/7", 200, NC7_CERRADA);
        MC.encolar("GET", NC + "/7", 200, NC7_CERRADA);
        MC.responder("POST", NC + "/7/cerrar", 500, "{\"title\":\"Internal Server Error\"}");
        accion(ana, CERRAR, mapa("id", 7, "comentarioCierre", "Otra vez")).andExpect(jsonPath("$.error").value("Internal Server Error"));
        // Re-cerrar una NC cerrada con éxito: la API lo permite (vuelve a registrar quién y cuándo), igual que Photino.
        MC.limpiar();
        MC.responder("GET", NC + "/7", 200, NC7_CERRADA);
        MC.responder("POST", NC + "/7/cerrar", 200, "{\"mensaje\":\"ok\"}");
        accion(ana, CERRAR, mapa("id", 7)).andExpect(jsonPath("$.ok").value(true));
    }

    // ------------------------------------------------------------------------------- acciones.actualizar

    @Test
    void accionesListEscapaMarcadoYActualizarEnviaLosOriginalesConActualizadoPorDeLaSesion() throws Exception {
        MockHttpSession ana = login("ana");
        String lista = "[{\"id\":12,\"descripcion\":\"<b>Capacitar</b> R&D\",\"responsable\":\"Pedro <script>x</script>\",\"fechaLimite\":\"2026-12-31T00:00:00\","
                + "\"prioridad\":\"ALTA\",\"estado\":\"PENDIENTE\",\"integracionTareasEstado\":\"NO_INTEGRADA\"},"
                + "{\"id\":13,\"descripcion\":\"normal 5<6\",\"responsable\":\"Luis\",\"fechaLimite\":\"2026-11-01T00:00:00\",\"prioridad\":null,\"estado\":\"EN_PROCESO\"}]";
        MC.responder("GET", NC + "/7/acciones", 200, lista);
        MC.responder("PUT", "/api/acciones-correctivas/12", 200, "{\"mensaje\":\"Acción correctiva actualizada correctamente\",\"interno\":\"SECRETO\"}");
        MC.responder("PUT", "/api/acciones-correctivas/13", 200, "{\"mensaje\":\"ok\"}");
        // La lectura entrega los textos con marcado escapados (la vista los pinta con innerHTML); lo normal queda igual.
        MvcResult l = accion(ana, "faret.nc.acciones.list", Map.of("id", 7)).andExpect(jsonPath("$.ok").value(true)).andReturn();
        JsonNode datos = json(l).get("data");
        assertThat(datos.get(0).get("descripcion").asString()).isEqualTo("&lt;b&gt;Capacitar&lt;/b&gt; R&amp;D");
        assertThat(datos.get(0).get("responsable").asString()).isEqualTo("Pedro &lt;script&gt;x&lt;/script&gt;");
        assertThat(datos.get(1).get("descripcion").asString()).isEqualTo("normal 5<6");
        // La vista reenvía lo que leyó (escapado) y la fecha recortada: el gateway usa los ORIGINALES y solo toma el estado.
        Map<String, Object> m = mapa("accionId", 12, "descripcion", "&lt;b&gt;Capacitar&lt;/b&gt; R&amp;D", "responsable", "FALSO", "fechaLimite", "2026-12-31",
                "prioridad", "BAJA", "estado", "COMPLETADA", "actualizadoPor", "Falso Autor");
        MvcResult r = accion(ana, ACCION, m).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).get("data").toString()).isEqualTo("{\"mensaje\":\"Acción correctiva actualizada correctamente\"}");
        assertThat(MC.peticiones()).containsExactly("GET " + NC + "/7/acciones", "GET " + NC + "/7/acciones", "PUT /api/acciones-correctivas/12");
        assertThat(MC.cuerpos().get(2)).isEqualTo("{\"descripcion\":\"<b>Capacitar</b> R&D\",\"responsable\":\"Pedro <script>x</script>\","
                + "\"fechaLimite\":\"2026-12-31\",\"prioridad\":\"ALTA\",\"estado\":\"COMPLETADA\",\"actualizadoPor\":\"Ana Calidad\"}");
        assertThat(MC.authorizations()).containsOnlyNulls();
        // Prioridad null viaja null. Tras actualizar, hay que recargar la lista para volver a actualizar la misma acción.
        accion(ana, ACCION, mapa("accionId", 13, "estado", "CANCELADA")).andExpect(jsonPath("$.ok").value(true));
        assertThat(MC.cuerpos().get(4)).isEqualTo("{\"descripcion\":\"normal 5<6\",\"responsable\":\"Luis\",\"fechaLimite\":\"2026-11-01\",\"prioridad\":null,"
                + "\"estado\":\"CANCELADA\",\"actualizadoPor\":\"Ana Calidad\"}");
        accion(ana, ACCION, mapa("accionId", 13, "estado", "PENDIENTE")).andExpect(jsonPath("$.error").value(GestionMsg.ACCION_SIN_LEER));
    }

    @Test
    void accionesActualizarValidaYDetectaLostUpdate() throws Exception {
        MockHttpSession ana = login("ana");
        accion(ana, ACCION, mapa("accionId", 12, "estado", "COMPLETADA")).andExpect(jsonPath("$.error").value(GestionMsg.ACCION_SIN_LEER));
        String accion12 = "{\"id\":12,\"descripcion\":\"D\",\"responsable\":\"R\",\"fechaLimite\":\"2026-12-31T00:00:00\",\"prioridad\":\"ALTA\",\"estado\":\"PENDIENTE\"}";
        MC.responder("GET", NC + "/7/acciones", 200, "[" + accion12 + "]");
        MC.responder("PUT", "/api/acciones-correctivas/12", 200, "{\"mensaje\":\"ok\"}");
        accion(ana, "faret.nc.acciones.list", Map.of("id", 7));
        MC.limpiar();
        for (String clave : List.of("id", "creadoPor", "usuario", "analisisId", "ncId")) {
            esperar(ana, ACCION, con(mapa("accionId", 12, "estado", "COMPLETADA"), clave, "x"), "Campo no permitido: " + clave);
        }
        esperar(ana, ACCION, mapa("estado", "COMPLETADA"), "Falta el id de la acción correctiva");
        esperar(ana, ACCION, mapa("accionId", 0, "estado", "COMPLETADA"), "Falta el id de la acción correctiva");
        esperar(ana, ACCION, mapa("accionId", 12), "Falta el estado");
        esperar(ana, ACCION, mapa("accionId", 12, "estado", "  "), "Falta el estado");
        for (String estado : List.of("ABIERTA", "completada", "CERRADA")) {
            esperar(ana, ACCION, mapa("accionId", 12, "estado", estado), "Estado inválido. Valores permitidos: PENDIENTE, EN_PROCESO, COMPLETADA, CANCELADA");
        }
        for (String campo : List.of("descripcion", "responsable", "fechaLimite", "prioridad", "estado")) {
            for (Object malo : List.of(5, true, List.of("a"), Map.of("a", 1))) {
                esperar(ana, ACCION, con(mapa("accionId", 12, "estado", "COMPLETADA"), campo, malo), "Parámetro inválido.");
            }
        }
        assertThat(MC.peticiones()).isEmpty();
        // Cambió desde que se abrió: no se escribe. Desapareció: tampoco.
        MC.responder("GET", NC + "/7/acciones", 200, "[" + accion12.replace("\"PENDIENTE\"", "\"EN_PROCESO\"") + "]");
        accion(ana, ACCION, mapa("accionId", 12, "estado", "COMPLETADA")).andExpect(jsonPath("$.error").value(GestionMsg.ACCION_CONFLICTO));
        MC.responder("GET", NC + "/7/acciones", 200, "[]");
        accion(ana, ACCION, mapa("accionId", 12, "estado", "COMPLETADA")).andExpect(jsonPath("$.error").value("La acción correctiva ya no existe."));
        MC.responder("GET", NC + "/7/acciones", 404, "{\"mensaje\":\"No conformidad no encontrada\"}");
        accion(ana, ACCION, mapa("accionId", 12, "estado", "COMPLETADA")).andExpect(jsonPath("$.error").value("No conformidad no encontrada"));
        assertThat(MC.peticiones().stream().filter(p -> p.startsWith("PUT"))).isEmpty();
        // Errores del PUT (404/400/500) llegan con su mensaje.
        MC.responder("GET", NC + "/7/acciones", 200, "[" + accion12 + "]");
        for (String[] e : new String[][] {{"404", "{\"mensaje\":\"Acción correctiva no encontrada\"}", "Acción correctiva no encontrada"},
                {"400", "{\"mensaje\":\"descripcion es obligatoria\"}", "descripcion es obligatoria"},
                {"500", "{\"title\":\"Internal Server Error\"}", "Internal Server Error"}}) {
            accion(ana, "faret.nc.acciones.list", Map.of("id", 7));
            MC.responder("PUT", "/api/acciones-correctivas/12", Integer.parseInt(e[0]), e[1]);
            accion(ana, ACCION, mapa("accionId", 12, "estado", "COMPLETADA")).andExpect(jsonPath("$.error").value(e[2]));
        }
    }

    @Test
    void seguimientoListEscapaComentarioYAutorSoloSiTraenMarcado() throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder("GET", NC + "/7/seguimiento", 200, "[{\"id\":1,\"comentario\":\"<img src=x onerror=alert(1)>\",\"autor\":\"<b>Ana</b>\",\"creadoEn\":\"2026-10-01T10:00:00\"},"
                + "{\"id\":2,\"comentario\":\"R&D 5<6 ok\",\"autor\":\"Luis\",\"creadoEn\":\"2026-10-02T10:00:00\"}]");
        JsonNode datos = json(accion(ana, "faret.nc.seguimiento.list", Map.of("id", 7)).andExpect(jsonPath("$.ok").value(true)).andReturn()).get("data");
        assertThat(datos.get(0).get("comentario").asString()).isEqualTo("&lt;img src=x onerror=alert(1)&gt;");
        assertThat(datos.get(0).get("autor").asString()).isEqualTo("&lt;b&gt;Ana&lt;/b&gt;");
        assertThat(datos.get(1).get("comentario").asString()).isEqualTo("R&D 5<6 ok");
        assertThat(datos.get(1).get("autor").asString()).isEqualTo("Luis");
    }

    // ------------------------------------------------------------------------------- crearRegistro

    @Test
    void crearRegistroHaceLosDosPasosConBearerSoloHaciaQcYRecalculaLaCabecera() throws Exception {
        MockHttpSession ana = login("ana");
        QC.respuestaEscritura("POST " + PNC, 201, "{\"success\":true,\"message\":\"Registro creado.\",\"data\":{\"id\":321,\"importacionId\":9,\"fechaIngreso\":\"2026-10-05T00:00:00\"}}");
        MC.responder("POST", NC, 200, "{\"id\":88,\"interno\":\"SECRETO\"}");
        MvcResult r = accion(ana, CREAR_REGISTRO, registroValido()).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).get("data").toString()).isEqualTo("{\"pncId\":321,\"nc\":{\"id\":88}}");
        List<Escritura> e = QC.escrituras();
        assertThat(e).hasSize(1);
        assertThat(e.get(0).metodo()).isEqualTo("POST");
        assertThat(e.get(0).uri()).isEqualTo(PNC);
        assertThat(e.get(0).sub()).isEqualTo(20);
        assertThat(e.get(0).cuerpo()).isEqualTo("{\"fechaIngreso\":\"2026-10-05\",\"tipoPnc\":\"Cuarentena\",\"fechaSalida\":null,\"npNv\":\"NP-100\",\"cliente\":\"ACME\","
                + "\"codigo\":\"C-1\",\"producto\":\"Bolsa 30x20\",\"familiaProducto\":null,\"cantRequerida\":1000,\"cantRechazada\":50.5,\"cantRecuperada\":10,"
                + "\"pncReal\":null,\"disposicion\":\"No aplica\",\"cantDestruida\":1,\"cantRepuesta\":2,\"fechaFabricacion\":\"2026-09-01\","
                + "\"descripcionDefecto\":\"Sello abierto\",\"categoriaDefecto\":\"Sellado\",\"nivel\":\"Critico\",\"tipoFalla\":\"\",\"area\":\"Línea 2\","
                + "\"maquina\":\"M1\",\"operador\":\"Juan\",\"supervisor\":\"Sup\",\"revisadoPor\":\"Rev\",\"impacto\":\"Alto\",\"observacion\":\"obs\","
                + "\"causaRaiz\":\"cr\",\"accionesCorrectivas\":\"ac\",\"verificacionSeguimiento\":\"vs\"}");
        // Paso 2: MejoraContinua sin Authorization; la cabecera se RECALCULA (lo que mandó el navegador se descarta).
        assertThat(MC.peticiones()).containsExactly("POST " + NC);
        assertThat(MC.authorizations()).containsOnlyNulls();
        assertThat(MC.cuerpos().get(0)).isEqualTo("{\"tipo\":\"INTERNA\",\"origen\":\"AUDITORIA_INTERNA\",\"sistemaOrigen\":\"DATA_FARET\",\"origenId\":\"321\","
                + "\"titulo\":\"PNC NP-100 - Bolsa 30x20\",\"descripcion\":\"Sellado - Sello abierto\",\"severidad\":\"ALTA\",\"proceso\":\"Cuarentena\","
                + "\"norma\":null,\"reportadoPor\":null,\"responsable\":null,\"fechaDeteccion\":\"2026-10-05\"}");
    }

    @Test
    void crearRegistroDefaultsDeLaCabeceraYSinFechaDeIngreso() throws Exception {
        MockHttpSession ana = login("ana");
        QC.respuestaEscritura("POST " + PNC, 201, "{\"success\":true,\"data\":{\"id\":322}}");
        MC.responder("POST", NC, 200, "{\"id\":89}");
        Map<String, Object> m = registroMinimo();
        accion(ana, CREAR_REGISTRO, m).andExpect(jsonPath("$.ok").value(true));
        String hoy = java.time.LocalDate.now(java.time.ZoneId.of("America/Santiago")).toString();
        JsonNode pnc = mapper.readTree(QC.escrituras().get(0).cuerpo());
        assertThat(pnc.get("fechaIngreso").asString()).isEqualTo(hoy);
        JsonNode nc = mapper.readTree(MC.cuerpos().get(0));
        // Sin producto no aplica (es obligatorio); título con el cliente cuando producto sale vacío no ocurre; proceso por área y por defecto.
        assertThat(nc.get("proceso").asString()).isEqualTo("PNC Nueva");
        assertThat(nc.get("severidad").asString()).isEqualTo("MEDIA");
        assertThat(nc.get("fechaDeteccion").asString()).isEqualTo(hoy);
        m.put("area", "Línea 3");
        accion(ana, CREAR_REGISTRO, m).andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(MC.cuerpos().get(1)).get("proceso").asString()).isEqualTo("Línea 3");
        m.put("nivel", "Menor");
        accion(ana, CREAR_REGISTRO, m).andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(MC.cuerpos().get(2)).get("severidad").asString()).isEqualTo("BAJA");
    }

    @Test
    void crearRegistroValidaTodoAntesDeLlamarALasApis() throws Exception {
        MockHttpSession ana = login("ana");
        for (String clave : List.of("creadoPor", "usuario", "sistemaOrigen", "origenId", "norma", "reportadoPor", "responsable", "id", "estado", "x<y")) {
            esperar(ana, CREAR_REGISTRO, con(registroValido(), clave, "x"), "Campo no permitido: " + (clave.equals("x<y") ? "?" : clave));
        }
        String obligatorios = "NP/NV, Cliente, Código, Producto, Categoría defecto, Nivel, Descripción defecto, Cant. requerida y Cant. rechazada son obligatorios";
        for (String campo : List.of("npNv", "cliente", "codigo", "producto", "categoriaDefecto", "nivel", "descripcionDefecto", "cantRequerida", "cantRechazada")) {
            esperar(ana, CREAR_REGISTRO, sin(registroValido(), campo), obligatorios);
            esperar(ana, CREAR_REGISTRO, con(registroValido(), campo, null), obligatorios);
        }
        esperar(ana, CREAR_REGISTRO, con(registroValido(), "npNv", "  "), obligatorios);
        // Largos de las columnas de importacion_pnc.
        Map<String, Integer> largos = Map.ofEntries(Map.entry("npNv", 50), Map.entry("cliente", 150), Map.entry("codigo", 50), Map.entry("producto", 255),
                Map.entry("familiaProducto", 50), Map.entry("categoriaDefecto", 150), Map.entry("nivel", 30), Map.entry("tipoFalla", 100),
                Map.entry("area", 100), Map.entry("maquina", 100), Map.entry("operador", 150), Map.entry("supervisor", 150),
                Map.entry("revisadoPor", 150), Map.entry("impacto", 100));
        largos.forEach((campo, max) -> {
            try {
                esperar(ana, CREAR_REGISTRO, con(registroValido(), campo, "x".repeat(max + 1)), "El campo " + campo + " supera el máximo de " + max + " caracteres.");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        esperar(ana, CREAR_REGISTRO, con(registroValido(), "observacion", "x".repeat(65_536)), "El campo observacion supera el máximo permitido (65.535 bytes).");
        esperar(ana, CREAR_REGISTRO, con(registroValido(), "tipoPnc", "Otro"), "Tipo PNC inválido.");
        esperar(ana, CREAR_REGISTRO, con(registroValido(), "disposicion", "Quemar"), "Disposición inválida.");
        esperar(ana, CREAR_REGISTRO, con(registroValido(), "fechaIngreso", "2026-02-30"), "La fecha fechaIngreso no es válida (formato AAAA-MM-DD).");
        esperar(ana, CREAR_REGISTRO, con(registroValido(), "fechaSalida", "x"), "La fecha fechaSalida no es válida (formato AAAA-MM-DD).");
        esperar(ana, CREAR_REGISTRO, con(registroValido(), "fechaFabricacion", "10/09/2026"), "La fecha fechaFabricacion no es válida (formato AAAA-MM-DD).");
        for (String campo : List.of("cantRequerida", "cantRechazada", "cantRecuperada", "pncReal", "cantDestruida", "cantRepuesta")) {
            esperar(ana, CREAR_REGISTRO, con(registroValido(), campo, -1), "El número no es válido: " + campo + ".");
            esperar(ana, CREAR_REGISTRO, con(registroValido(), campo, "abc"), "El número no es válido: " + campo + ".");
            esperar(ana, CREAR_REGISTRO, con(registroValido(), campo, 1e17), "El número no es válido: " + campo + ".");
            esperar(ana, CREAR_REGISTRO, con(registroValido(), campo, true), "Parámetro inválido.");
            esperar(ana, CREAR_REGISTRO, con(registroValido(), campo, List.of(1)), "Parámetro inválido.");
        }
        for (String campo : List.of("npNv", "cliente", "observacion", "area", "tipoPnc", "fechaIngreso")) {
            for (Object malo : List.of(5, true, List.of("a"), Map.of("a", 1))) {
                esperar(ana, CREAR_REGISTRO, con(registroValido(), campo, malo), "Parámetro inválido.");
            }
        }
        esperar(ana, CREAR_REGISTRO, con(registroValido(), "npNv", "a\u0000b"), "El texto contiene caracteres no permitidos.");
        esperar(ana, CREAR_REGISTRO, con(registroValido(), "area", "a\nb"), "El texto contiene caracteres no permitidos.");
        for (String campo : List.of("npNv", "cliente", "producto", "descripcionDefecto", "observacion", "operador")) {
            esperar(ana, CREAR_REGISTRO, con(registroValido(), campo, "<b>x</b>"), "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").");
        }
        // titulo = "PNC <npNv> - <producto>" > 255.
        esperar(ana, CREAR_REGISTRO, con(con(registroValido(), "producto", "x".repeat(255)), "npNv", "N".repeat(10)),
                "El título de la no conformidad (PNC + NP/NV + producto) supera el máximo de 255 caracteres.");
        assertThat(QC.escrituras()).isEmpty();
        assertThat(MC.peticiones()).isEmpty();
    }

    @Test
    void crearRegistroSiFallaElPaso1NoSeLlamaAMejoraContinua() throws Exception {
        MockHttpSession ana = login("ana");
        QC.respuestaEscritura("POST " + PNC, 400, "{\"success\":false,\"message\":\"NP/NV no puede estar vacío.\",\"errors\":null}");
        accion(ana, CREAR_REGISTRO, registroValido()).andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value("NP/NV no puede estar vacío."));
        QC.respuestaEscritura("POST " + PNC, 201, "{\"success\":true,\"data\":{\"importacionId\":9}}");
        accion(ana, CREAR_REGISTRO, registroValido()).andExpect(jsonPath("$.error").value("La API de Faret no devolvió el id del registro creado."));
        QC.respuestaEscritura("POST " + PNC, 201, "{\"success\":true,\"data\":{\"id\":0}}");
        accion(ana, CREAR_REGISTRO, registroValido()).andExpect(jsonPath("$.error").value("La API de Faret no devolvió el id del registro creado."));
        QC.respuestaEscritura("POST " + PNC, 500, "<html>boom</html>");
        accion(ana, CREAR_REGISTRO, registroValido()).andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret"));
        assertThat(QC.escrituras()).hasSize(4);
        assertThat(MC.peticiones()).isEmpty();
    }

    @Test
    void crearRegistroSiFallaElPaso2InformaElPncIdSinCompensarYLaAuditoriaLoRegistra(CapturedOutput salida) throws Exception {
        MockHttpSession ana = login("ana");
        QC.respuestaEscritura("POST " + PNC, 201, "{\"success\":true,\"data\":{\"id\":777}}");
        MC.responder("POST", NC, 500, "{\"title\":\"Internal Server Error\",\"stack\":\"SqlException x\"}");
        MvcResult r = accion(ana, CREAR_REGISTRO, registroValido()).andExpect(jsonPath("$.ok").value(false)).andReturn();
        String error = json(r).get("error").asString();
        assertThat(error).startsWith("Internal Server Error").contains("#777").contains("ya fue creado");
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SqlException");
        // Sin compensación (como Photino): ningún borrado del registro de Data, ni reintento del paso 2.
        assertThat(QC.escrituras()).hasSize(1);
        assertThat(QC.escrituras()).noneMatch(e -> e.metodo().equals("DELETE"));
        assertThat(MC.peticiones()).containsExactly("POST " + NC);
        // MejoraContinua 400 y red caída también informan el pncId.
        MC.responder("POST", NC, 400, "{\"mensaje\":\"fechaDeteccion es obligatoria\"}");
        String error400 = json(accion(ana, CREAR_REGISTRO, registroValido()).andReturn()).get("error").asString();
        assertThat(error400).startsWith("fechaDeteccion es obligatoria").contains("#777");
        String log = salida.getAll();
        assertThat(log).containsPattern("accion=faret\\.nc\\.crearRegistro recurso=faret:nc:registro:777:crear:SIN_GESTION resultado=ERROR");
        // Éxito: el recurso incluye el id de la NC.
        MC.responder("POST", NC, 200, "{\"id\":55}");
        accion(ana, CREAR_REGISTRO, registroValido()).andExpect(jsonPath("$.ok").value(true));
        assertThat(salida.getAll()).containsPattern("accion=faret\\.nc\\.crearRegistro recurso=faret:nc:registro:777:crear:nc:55 resultado=OK");
        assertThat(salida.getAll()).doesNotContain("Bolsa 30x20", "ACME", PASS);
    }

    @Test
    void crearRegistroUnaSesionDeFaretSinTokenOUnPaso1Con401InvalidaSoloEsaSesion() throws Exception {
        MockHttpSession ana = login("ana");
        MockHttpSession admin = login("admin");
        QC.rechazarLecturas(true);
        accion(ana, CREAR_REGISTRO, registroValido()).andExpect(status().isUnauthorized());
        assertThat(ana.isInvalid()).isTrue();
        assertThat(admin.isInvalid()).isFalse();
        assertThat(MC.peticiones()).isEmpty();
    }

    // ------------------------------------------------------------------------------- actualizarRegistro

    @Test
    void actualizarRegistroEnviaSoloLosCamposQueLlegan() throws Exception {
        MockHttpSession ana = login("ana");
        QC.respuestaEscritura("PUT " + PNC + "/55", 200, "{\"success\":true,\"message\":\"Registro actualizado.\",\"data\":null,\"errors\":null}");
        // "Guardar solo el último campo editado": la vista manda {id, campo}.
        MvcResult r = accion(ana, ACTUALIZAR_REGISTRO, mapa("id", 55, "observacion", "  nueva obs  ")).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).get("data").toString()).isEqualTo("{\"id\":55}");
        Escritura e = QC.escrituras().get(0);
        assertThat(e.metodo()).isEqualTo("PUT");
        assertThat(e.uri()).isEqualTo(PNC + "/55");
        assertThat(e.sub()).isEqualTo(20);
        assertThat(e.cuerpo()).isEqualTo("{\"fechaIngreso\":null,\"tipoPnc\":null,\"fechaSalida\":null,\"npNv\":null,\"cliente\":null,\"codigo\":null,\"producto\":null,"
                + "\"familiaProducto\":null,\"cantRequerida\":null,\"cantRechazada\":null,\"cantRecuperada\":null,\"pncReal\":null,\"disposicion\":null,"
                + "\"cantDestruida\":null,\"cantRepuesta\":null,\"fechaFabricacion\":null,\"descripcionDefecto\":null,\"categoriaDefecto\":null,\"nivel\":null,"
                + "\"tipoFalla\":null,\"area\":null,\"maquina\":null,\"operador\":null,\"supervisor\":null,\"revisadoPor\":null,\"impacto\":null,"
                + "\"observacion\":\"nueva obs\",\"causaRaiz\":null,\"accionesCorrectivas\":null,\"verificacionSeguimiento\":null}");
        // "Guardar todo": el formulario completo (cantidades y fechas); "" de texto se aplica, null no cambia.
        Map<String, Object> todo = registroValido();
        todo.keySet().removeAll(List.of("tipo", "origen", "titulo", "descripcion", "severidad", "proceso", "fechaDeteccion"));
        todo.put("id", 55);
        accion(ana, ACTUALIZAR_REGISTRO, todo).andExpect(jsonPath("$.ok").value(true));
        JsonNode b = mapper.readTree(QC.escrituras().get(1).cuerpo());
        assertThat(b.get("npNv").asString()).isEqualTo("NP-100");
        assertThat(b.get("tipoFalla").asString()).isEmpty();
        assertThat(b.get("cantRechazada").decimalValue()).isEqualByComparingTo("50.5");
        assertThat(MC.peticiones()).isEmpty();
    }

    @Test
    void actualizarRegistroValidaYPropagaErroresDeLaApi() throws Exception {
        MockHttpSession ana = login("ana");
        esperar(ana, ACTUALIZAR_REGISTRO, mapa("observacion", "x"), "Falta el id del registro a actualizar");
        esperar(ana, ACTUALIZAR_REGISTRO, mapa("id", 0, "observacion", "x"), "Falta el id del registro a actualizar");
        for (Object malo : List.of(true, List.of(1), Map.of("a", 1), 1.5, "abc", 3000000000L)) {
            esperar(ana, ACTUALIZAR_REGISTRO, mapa("id", malo, "observacion", "x"), "Parámetro inválido.");
        }
        for (String clave : List.of("creadoPor", "actualizadoPor", "usuario", "tipo", "titulo", "origenId", "sistemaOrigen", "eliminado")) {
            esperar(ana, ACTUALIZAR_REGISTRO, mapa("id", 55, clave, "x"), "Campo no permitido: " + clave);
        }
        esperar(ana, ACTUALIZAR_REGISTRO, mapa("id", 55, "producto", "x".repeat(256)), "El campo producto supera el máximo de 255 caracteres.");
        esperar(ana, ACTUALIZAR_REGISTRO, mapa("id", 55, "cantRequerida", -1), "El número no es válido: cantRequerida.");
        esperar(ana, ACTUALIZAR_REGISTRO, mapa("id", 55, "fechaIngreso", "2026-00-10"), "La fecha fechaIngreso no es válida (formato AAAA-MM-DD).");
        esperar(ana, ACTUALIZAR_REGISTRO, mapa("id", 55, "tipoPnc", "Otro"), "Tipo PNC inválido.");
        esperar(ana, ACTUALIZAR_REGISTRO, mapa("id", 55, "causaRaiz", "<script>"), "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").");
        esperar(ana, ACTUALIZAR_REGISTRO, mapa("id", 55, "observacion", 5), "Parámetro inválido.");
        assertThat(QC.escrituras()).isEmpty();
        // La API arbitra: no existe (404), campo requerido vacío (400), error interno (500).
        QC.respuestaEscritura("PUT " + PNC + "/55", 404, "{\"success\":false,\"message\":\"No existe un registro PNC con id 55.\",\"errors\":null}");
        accion(ana, ACTUALIZAR_REGISTRO, mapa("id", 55, "observacion", "x")).andExpect(jsonPath("$.error").value("No existe un registro PNC con id 55."));
        QC.respuestaEscritura("PUT " + PNC + "/55", 400, "{\"success\":false,\"message\":\"NP/NV no puede quedar vacío.\",\"errors\":null}");
        accion(ana, ACTUALIZAR_REGISTRO, mapa("id", 55, "npNv", "")).andExpect(jsonPath("$.error").value("NP/NV no puede quedar vacío."));
        QC.respuestaEscritura("PUT " + PNC + "/55", 500, "<html>boom</html>");
        accion(ana, ACTUALIZAR_REGISTRO, mapa("id", 55, "observacion", "x")).andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret"));
        assertThat(QC.escrituras()).hasSize(3);
        assertThat(MC.peticiones()).isEmpty();
    }

    // ------------------------------------------------------------------------------- roles, CSRF, auditoría

    @Test
    void rolesEmpresaYModuloCruzadosCsrfYAccionesNoHabilitadas() throws Exception {
        MC.responder("GET", NC + "/7", 200, NC7);
        MC.responder("POST", NC, 200, "{\"id\":1}");
        // Los 5 roles de Photino escriben (CONSULTA e INSPECTOR incluidos), menos cerrar.
        for (String u : List.of("admin", "ana", "clara", "tecnico", "ines")) {
            accion(login(u), CREATE, ncValida()).andExpect(jsonPath("$.ok").value(true));
        }
        // Una sesión INNPACK nunca llega a una acción faret.*.
        MockHttpSession innpack = loginInnpack("adminti1");
        for (String a : List.of(CREATE, UPDATE, GESTION, CERRAR, ACCION, CREAR_REGISTRO, ACTUALIZAR_REGISTRO)) {
            accion(innpack, a, mapa("id", 7)).andExpect(status().isForbidden());
        }
        // Módulo cruzado: la acción declarada desde otro módulo (o ninguno) no entra.
        MockHttpSession ana = login("ana");
        int antes = MC.peticiones().size();
        for (String modulo : List.of("faret-talleres-externos", "faret-data", "inventado")) {
            mockMvc.perform(post("/api/v1/bridge").session(ana).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"" + CREATE + "\",\"_modulo\":\"" + modulo + "\",\"tipo\":\"INTERNA\"}")).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/v1/bridge").session(ana).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"" + CREATE + "\",\"tipo\":\"INTERNA\"}")).andExpect(status().isForbidden());
        assertThat(MC.peticiones()).hasSize(antes);
        // Sin sesión o sin CSRF no se escribe.
        for (String a : List.of(CREATE, UPDATE, GESTION, CERRAR, ACCION, CREAR_REGISTRO, ACTUALIZAR_REGISTRO)) {
            mockMvc.perform(post("/api/v1/bridge").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"" + a + "\",\"_modulo\":\"faret-nc\",\"id\":7}")).andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/v1/bridge").session(ana).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"" + a + "\",\"_modulo\":\"faret-nc\",\"id\":7}")).andExpect(status().isForbidden());
        }
        assertThat(MC.peticiones()).hasSize(antes);
        assertThat(QC.escrituras()).isEmpty();
        // Lo que el frontend de 9e1b556 no usa sigue denegado (los borrados son de la Fase F5).
        for (String a : List.of("faret.usuarios.eliminarDefinitivo", "faret.pncCatalogos.clientes.desactivar")) {
            accion(ana, a, mapa("id", 7)).andExpect(status().isForbidden());
        }
    }

    @Test
    void dosSesionesCadaEscrituraConSuPropioAutor() throws Exception {
        MockHttpSession ana = login("ana");
        MockHttpSession admin = login("admin");
        MC.responder("GET", NC + "/7", 200, NC7);
        MC.responder("PATCH", NC + "/7/gestion", 200, "{\"mensaje\":\"ok\"}");
        MC.responder("POST", NC + "/7/cerrar", 200, "{\"mensaje\":\"ok\"}");
        accion(ana, "faret.nc.get", Map.of("id", 7));
        accion(admin, "faret.nc.get", Map.of("id", 7));
        accion(ana, GESTION, mapa("id", 7, "responsable", "A", "estadoGestion", "PENDIENTE", "actualizadoPor", "Admin Faret")).andExpect(jsonPath("$.ok").value(true));
        accion(admin, GESTION, mapa("id", 7, "responsable", "B", "estadoGestion", "PENDIENTE", "actualizadoPor", "Ana Calidad")).andExpect(jsonPath("$.ok").value(true));
        accion(ana, CERRAR, mapa("id", 7, "cerradoPor", "Admin Faret")).andExpect(jsonPath("$.ok").value(true));
        accion(admin, CERRAR, mapa("id", 7, "cerradoPor", "Ana Calidad")).andExpect(jsonPath("$.ok").value(true));
        List<String> autores = new java.util.ArrayList<>();
        List<String> cuerpos = MC.cuerpos();
        List<String> peticiones = MC.peticiones();
        for (int i = 0; i < peticiones.size(); i++) {
            if (peticiones.get(i).startsWith("PATCH") || peticiones.get(i).startsWith("POST")) {
                JsonNode c = mapper.readTree(cuerpos.get(i));
                autores.add(c.has("actualizadoPor") ? c.get("actualizadoPor").asString() : c.get("cerradoPor").asString());
            }
        }
        assertThat(autores).containsExactly("Ana Calidad", "Admin Faret", "Ana Calidad", "Admin Faret");
        assertThat(MC.authorizations()).containsOnlyNulls();
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void auditoriaConRecursoEIdSinContenido(CapturedOutput salida) throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder("GET", NC + "/7", 200, NC7);
        MC.responder("POST", NC, 200, "{\"id\":42}");
        MC.responder("PUT", NC + "/7", 200, "{\"mensaje\":\"ok\"}");
        MC.responder("PATCH", NC + "/7/gestion", 200, "{\"mensaje\":\"ok\"}");
        MC.responder("POST", NC + "/7/cerrar", 200, "{\"mensaje\":\"ok\"}");
        MC.responder("GET", NC + "/7/acciones", 200, "[{\"id\":12,\"descripcion\":\"D\",\"responsable\":\"R\",\"fechaLimite\":\"2026-12-31T00:00:00\",\"prioridad\":null,\"estado\":\"PENDIENTE\"}]");
        MC.responder("PUT", "/api/acciones-correctivas/12", 200, "{\"mensaje\":\"ok\"}");
        QC.respuestaEscritura("PUT " + PNC + "/55", 200, "{\"success\":true,\"data\":null}");
        accion(ana, CREATE, con(ncValida(), "titulo", "Titulo confidencial 12.345.678-9")).andExpect(jsonPath("$.ok").value(true));
        accion(ana, "faret.nc.get", Map.of("id", 7));
        accion(ana, UPDATE, ncUpdate()).andExpect(jsonPath("$.ok").value(true));
        accion(ana, GESTION, mapa("id", 7, "responsable", "Reservado", "estadoGestion", "CERRADA")).andExpect(jsonPath("$.ok").value(true));
        accion(ana, CERRAR, mapa("id", 7, "comentarioCierre", "Cierre reservado")).andExpect(jsonPath("$.ok").value(true));
        accion(ana, "faret.nc.acciones.list", Map.of("id", 7));
        accion(ana, ACCION, mapa("accionId", 12, "estado", "COMPLETADA")).andExpect(jsonPath("$.ok").value(true));
        accion(ana, ACTUALIZAR_REGISTRO, mapa("id", 55, "observacion", "Obs reservada")).andExpect(jsonPath("$.ok").value(true));
        accion(ana, CREATE, sin(ncValida(), "tipo")).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=ana empresa=FARET accion=faret\\.nc\\.create recurso=faret:nc:42:crear resultado=OK ms=\\d+");
        assertThat(log).containsPattern("accion=faret\\.nc\\.update recurso=faret:nc:7:actualizar resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.nc\\.gestion\\.actualizar recurso=faret:nc:7:gestion:CERRADA resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.nc\\.cerrar recurso=faret:nc:7:cierre resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.nc\\.acciones\\.actualizar recurso=faret:nc:7:accion:12:COMPLETADA resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.nc\\.actualizarRegistro recurso=faret:nc:registro:55:actualizar resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.nc\\.create recurso=faret:nc:nueva:crear resultado=ERROR");
        assertThat(log).doesNotContain("12.345.678-9", "Reservado", "Cierre reservado", "Obs reservada", PASS);
    }

    // ------------------------------------------------------------------------------- helpers

    /** Mensajes de las defensas de lost update (los mismos que usa el handler). */
    private static final class GestionMsg {
        static final String SIN_LEER = "Abre la no conformidad antes de editarla (actualiza la lista).";
        static final String CONFLICTO = "La no conformidad fue modificada por otra persona desde que cargaste la lista. "
                + "Actualiza la lista y vuelve a abrirla antes de guardar (no se guardó nada).";
        static final String CIERRE_SIN_SEGUIMIENTO = "La no conformidad quedó cerrada, pero no se pudo registrar el comentario de cierre en el seguimiento. "
                + "Agrégalo como comentario de seguimiento.";
        static final String ACCION_SIN_LEER = "Abre el análisis de la no conformidad antes de actualizar la acción.";
        static final String ACCION_CONFLICTO = "La acción fue modificada por otra persona desde que la abriste. Vuelve a abrir el análisis para ver los cambios.";
    }

    private static Map<String, Object> mapa(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> con(Map<String, Object> base, String clave, Object valor) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        m.put(clave, valor);
        return m;
    }

    private static Map<String, Object> sin(Map<String, Object> base, String clave) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        m.remove(clave);
        return m;
    }

    /** Payload de faret.nc.create (formulario "Nueva NC" de la vista). */
    private static Map<String, Object> ncValida() {
        return mapa("tipo", "INTERNA", "origen", "AUDITORIA_INTERNA", "titulo", "Sello abierto", "descripcion", "Descripción\ncon salto",
                "severidad", "ALTA", "proceso", "Sellado", "fechaDeteccion", "2026-10-01");
    }

    /** Payload de faret.nc.update de la NC 7 (sin vínculo). */
    private static Map<String, Object> ncUpdate() {
        return con(ncValida(), "id", 7);
    }

    private static Map<String, Object> registroMinimo() {
        return mapa("npNv", "NP-1", "cliente", "ACME", "codigo", "C-1", "producto", "Bolsa", "descripcionDefecto", "Defecto", "categoriaDefecto", "Sellado",
                "nivel", "Nivel desconocido", "cantRequerida", 10, "cantRechazada", 2);
    }

    /** Payload de faret.nc.crearRegistro como lo arma _guardarNuevoPnc (incluida la cabecera de la NC, que el gateway recalcula). */
    private static Map<String, Object> registroValido() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipoPnc", "Cuarentena");
        m.put("fechaIngreso", "2026-10-05");
        m.put("npNv", "NP-100");
        m.put("cliente", "ACME");
        m.put("codigo", "C-1");
        m.put("producto", "Bolsa 30x20");
        m.put("familiaProducto", null);
        m.put("cantRequerida", 1000);
        m.put("cantRechazada", 50.5);
        m.put("cantRecuperada", 10);
        m.put("pncReal", null);
        m.put("disposicion", "No aplica");
        m.put("cantDestruida", 1);
        m.put("cantRepuesta", 2);
        m.put("fechaSalida", null);
        m.put("fechaFabricacion", "2026-09-01");
        m.put("descripcionDefecto", "Sello abierto");
        m.put("categoriaDefecto", "Sellado");
        m.put("nivel", "Critico");
        m.put("tipoFalla", "");
        m.put("area", "Línea 2");
        m.put("maquina", "M1");
        m.put("operador", "Juan");
        m.put("supervisor", "Sup");
        m.put("revisadoPor", "Rev");
        m.put("impacto", "Alto");
        m.put("observacion", "obs");
        m.put("causaRaiz", "cr");
        m.put("accionesCorrectivas", "ac");
        m.put("verificacionSeguimiento", "vs");
        // Cabecera falsificada: se descarta y se recalcula.
        m.put("tipo", "EXTERNA");
        m.put("origen", "AUDITORIA_EXTERNA");
        m.put("titulo", "FALSO");
        m.put("descripcion", "FALSO");
        m.put("severidad", "BAJA");
        m.put("proceso", "FALSO");
        m.put("fechaDeteccion", "1999-01-01");
        return m;
    }

    private MockHttpSession admin() throws Exception {
        return login("admin");
    }

    /** La acción devuelve ese error (y no llegó a ninguna API: el llamador comprueba las peticiones). */
    private void esperar(MockHttpSession sesion, String accion, Map<String, Object> campos, String error) throws Exception {
        accion(sesion, accion, campos).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value(error));
    }

    private ResultActions accion(MockHttpSession sesion, String accion, Map<String, Object> campos) throws Exception {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", accion);
        p.put("_modulo", "faret-nc");
        campos.forEach((k, v) -> p.set(k, v == null ? mapper.nullNode() : mapper.valueToTree(v)));
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(p.toString()));
    }

    private MockHttpSession login(String identificador) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/faret/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.84.0." + IP.getAndIncrement());
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
                            r.setRemoteAddr("10.84.1." + IP.getAndIncrement());
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
            Path dir = Files.createTempDirectory("qcc-web-fixture-f4");
            Files.writeString(dir.resolve("index.html"), "<!doctype html><title>fixture</title>");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
