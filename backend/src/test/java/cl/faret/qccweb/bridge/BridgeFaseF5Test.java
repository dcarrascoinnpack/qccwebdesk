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
 * Fase F5 - BORRADOS FARET (Photino 1.8.15, 9e1b556, FaretHandler.cs): faret.nc.adjuntos.eliminar, faret.nc.eliminarFila,
 * faret.inspecciones.eliminar y faret.inspeccionesPallet.eliminar. TODAS las APIs son fakes en localhost (QualityControlFaret.Api con
 * Bearer, MejoraContinua y Calidad sin autenticación); nunca se borra nada real.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFaseF5Test {

    private static final FakeFaretApi QC = new FakeFaretApi(Clock.systemUTC());
    private static final FakeInnpackApi INNPACK = new FakeInnpackApi(Clock.systemUTC());
    private static final FakeFaretSinAuthApi MC = new FakeFaretSinAuthApi("/mejora-continua");
    private static final FakeFaretSinAuthApi CALIDAD = new FakeFaretSinAuthApi("/calidad/api");
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveFaretF5#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String NC = "/api/no-conformidades";
    private static final String PNC = "/api/importaciones/pnc";
    private static final String ADJUNTO = "faret.nc.adjuntos.eliminar";
    private static final String FILA = "faret.nc.eliminarFila";
    private static final String INSPECCION = "faret.inspecciones.eliminar";
    private static final String PALLET = "faret.inspeccionesPallet.eliminar";
    private static final List<String> TODAS = List.of(ADJUNTO, FILA, INSPECCION, PALLET);
    private static final String OK_QC = "{\"success\":true,\"message\":\"Registro eliminado.\",\"data\":null,\"errors\":null}";

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

    // ------------------------------------------------------------------------------- nc.adjuntos.eliminar

    @Test
    void adjuntoEliminarBorraEnMejoraContinuaSinAuthorization() throws Exception {
        MC.responder("DELETE", NC + "/7/adjuntos/12", 200, "{\"mensaje\":\"Adjunto eliminado correctamente\",\"interno\":\"X\"}");
        // La vista manda adjuntoId tal cual lo leyó del DOM (número o texto de dígitos).
        MvcResult r = accion(login("ana"), ADJUNTO, mapa("id", 7, "adjuntoId", "12")).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).get("data").toString()).isEqualTo("{\"mensaje\":\"Adjunto eliminado correctamente\"}");
        assertThat(MC.peticiones()).containsExactly("DELETE " + NC + "/7/adjuntos/12");
        assertThat(MC.authorizations()).containsOnlyNulls();
        assertThat(QC.escrituras()).isEmpty();
        assertThat(CALIDAD.peticiones()).isEmpty();
    }

    @Test
    void adjuntoEliminarValidaIdsYClavesAntesDeLaRuta() throws Exception {
        MockHttpSession ana = login("ana");
        esperar(ana, ADJUNTO, mapa("adjuntoId", 12), "Falta el id de la no conformidad");
        esperar(ana, ADJUNTO, mapa("id", 7), "Falta el id del adjunto");
        esperar(ana, ADJUNTO, mapa("id", 0, "adjuntoId", 12), "Falta el id de la no conformidad");
        esperar(ana, ADJUNTO, mapa("id", 7, "adjuntoId", -3), "Falta el id del adjunto");
        for (Object malo : List.of(true, Map.of("a", 1), List.of(1), 1.5, "../12", "1e3", 3_000_000_000L)) {
            esperar(ana, ADJUNTO, mapa("id", 7, "adjuntoId", malo), "Parámetro inválido.");
            esperar(ana, ADJUNTO, mapa("id", malo, "adjuntoId", 12), "Parámetro inválido.");
        }
        // Lista cerrada: nada de identidad ni de rutas.
        accion(ana, ADJUNTO, mapa("id", 7, "adjuntoId", 12, "usuario", "otro")).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("Campo no permitido")));
        assertThat(MC.peticiones()).isEmpty();
    }

    @Test
    void adjuntoEliminarPropagaLosErroresDeLaApiSinReintentos() throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder("DELETE", NC + "/7/adjuntos/12", 404, "{\"mensaje\":\"Adjunto no encontrado\"}");
        accion(ana, ADJUNTO, mapa("id", 7, "adjuntoId", 12)).andExpect(jsonPath("$.error").value("Adjunto no encontrado"));
        MC.responder("DELETE", NC + "/7/adjuntos/12", 409, "{\"mensaje\":\"La no conformidad está cerrada, no se pueden eliminar adjuntos\"}");
        accion(ana, ADJUNTO, mapa("id", 7, "adjuntoId", 12))
                .andExpect(jsonPath("$.error").value("La no conformidad está cerrada, no se pueden eliminar adjuntos"));
        MC.responder("DELETE", NC + "/7/adjuntos/12", 500, "{\"title\":\"Internal Server Error\",\"stack\":\"SqlException x\"}");
        MvcResult r = accion(ana, ADJUNTO, mapa("id", 7, "adjuntoId", 12)).andExpect(jsonPath("$.ok").value(false)).andReturn();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SqlException");
        assertThat(MC.peticiones()).hasSize(3);
    }

    // ------------------------------------------------------------------------------- nc.eliminarFila

    @Test
    void eliminarFilaConDataYNcBorraLosDosLadosEnElOrdenDePhotino() throws Exception {
        QC.respuestaEscritura("DELETE " + PNC + "/55", 200, OK_QC);
        MC.responder("DELETE", NC + "/7", 200, "{\"mensaje\":\"No conformidad eliminada correctamente\"}");
        MvcResult r = accion(login("ana"), FILA, mapa("dataId", 55, "ncId", 7)).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).get("data").toString()).isEqualTo("{\"eliminado\":true}");
        List<Escritura> e = QC.escrituras();
        assertThat(e).hasSize(1);
        assertThat(e.get(0).metodo()).isEqualTo("DELETE");
        assertThat(e.get(0).uri()).isEqualTo(PNC + "/55");
        assertThat(e.get(0).sub()).isEqualTo(20);
        assertThat(MC.peticiones()).containsExactly("DELETE " + NC + "/7");
        assertThat(MC.authorizations()).containsOnlyNulls();
        assertThat(CALIDAD.peticiones()).isEmpty();
    }

    @Test
    void eliminarFilaConUnSoloLadoYComoLoManda_la_vista() throws Exception {
        MockHttpSession admin = login("admin");
        QC.respuestaEscritura("DELETE " + PNC + "/55", 200, OK_QC);
        MC.responder("DELETE", NC + "/7", 200, "{\"mensaje\":\"ok\"}");
        // La vista omite el lado que la fila no tiene (fila.dataId || undefined) y puede mandar null.
        accion(admin, FILA, mapa("dataId", 55)).andExpect(jsonPath("$.ok").value(true));
        assertThat(QC.escrituras()).hasSize(1);
        assertThat(MC.peticiones()).isEmpty();
        accion(admin, FILA, mapa("dataId", null, "ncId", 7)).andExpect(jsonPath("$.ok").value(true));
        assertThat(QC.escrituras()).hasSize(1);
        assertThat(MC.peticiones()).containsExactly("DELETE " + NC + "/7");
    }

    @Test
    void eliminarFilaSinIdsOConIdsInvalidosNoLlamaANingunaApi() throws Exception {
        MockHttpSession ana = login("ana");
        esperar(ana, FILA, mapa(), "Falta dataId o ncId");
        esperar(ana, FILA, mapa("dataId", null, "ncId", null), "Falta dataId o ncId");
        for (Object malo : List.of(true, Map.of("a", 1), List.of(1), 1.5, "abc", 0, -5, "", 3_000_000_000L)) {
            esperar(ana, FILA, mapa("dataId", malo, "ncId", 7), "Parámetro inválido.");
            esperar(ana, FILA, mapa("dataId", 55, "ncId", malo), "Parámetro inválido.");
        }
        accion(ana, FILA, mapa("dataId", 55, "id", 3)).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("Campo no permitido")));
        accion(ana, FILA, mapa("ncId", 7, "motivo", "x")).andExpect(jsonPath("$.ok").value(false));
        assertThat(QC.escrituras()).isEmpty();
        assertThat(MC.peticiones()).isEmpty();
    }

    @Test
    void eliminarFilaSiFallaDataElNcIgualSeBorraYElErrorLoInformaSinRevertir() throws Exception {
        MockHttpSession ana = login("ana");
        QC.respuestaEscritura("DELETE " + PNC + "/55", 404, "{\"success\":false,\"message\":\"No existe un registro PNC con id 55.\",\"data\":null}");
        MC.responder("DELETE", NC + "/7", 200, "{\"mensaje\":\"ok\"}");
        accion(ana, FILA, mapa("dataId", 55, "ncId", 7)).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Data: No existe un registro PNC con id 55."));
        // Sin compensación: el lado de la gestión NC se borró y no hay ninguna llamada de reversión.
        assertThat(MC.peticiones()).containsExactly("DELETE " + NC + "/7");
        assertThat(QC.escrituras()).hasSize(1);
    }

    @Test
    void eliminarFilaSiFallaNcLaDataYaFueBorradaYElErrorLoInformaSinRevertir() throws Exception {
        MockHttpSession ana = login("ana");
        QC.respuestaEscritura("DELETE " + PNC + "/55", 200, OK_QC);
        MC.responder("DELETE", NC + "/7", 404, "");
        MvcResult r = accion(ana, FILA, mapa("dataId", 55, "ncId", 7)).andExpect(jsonPath("$.ok").value(false)).andReturn();
        assertThat(json(r).get("error").asString()).startsWith("Gestión NC: ").doesNotContain("Data:");
        assertThat(QC.escrituras()).hasSize(1);
        assertThat(MC.peticiones()).containsExactly("DELETE " + NC + "/7");
        // Los dos lados fallan: error concatenado "Data: ... | Gestión NC: ...".
        QC.respuestaEscritura("DELETE " + PNC + "/55", 500, "<html>boom</html>");
        MC.responder("DELETE", NC + "/7", 500, "{\"title\":\"Internal Server Error\"}");
        String ambos = json(accion(ana, FILA, mapa("dataId", 55, "ncId", 7)).andReturn()).get("error").asString();
        assertThat(ambos).isEqualTo("Data: Error al comunicarse con la API Faret | Gestión NC: Internal Server Error");
        // Un solo lado que falla.
        MC.responder("DELETE", NC + "/7", 409, "{\"mensaje\":\"conflicto\"}");
        accion(ana, FILA, mapa("ncId", 7)).andExpect(jsonPath("$.error").value("Gestión NC: conflicto"));
    }

    @Test
    void eliminarFilaUnaSesionConElTokenRechazadoPorLaApiSeInvalidaSinBorrarElLadoNc() throws Exception {
        MockHttpSession ana = login("ana");
        MockHttpSession admin = login("admin");
        QC.rechazarLecturas(true);
        accion(ana, FILA, mapa("dataId", 55, "ncId", 7)).andExpect(status().isUnauthorized());
        assertThat(ana.isInvalid()).isTrue();
        assertThat(admin.isInvalid()).isFalse();
        // El paso 1 falló con 401 antes de tocar MejoraContinua.
        assertThat(MC.peticiones()).isEmpty();
    }

    // ------------------------------------------------------------------------------- inspecciones

    @Test
    void inspeccionesEliminarBorraEnCalidadSinAuthorization() throws Exception {
        CALIDAD.responder("DELETE", "/calidad-faret/registros/9", 200, "{\"success\":true,\"data\":{\"id\":9,\"eliminado\":true}}");
        CALIDAD.responder("DELETE", "/calidad-faret-pallet/registros/4", 200, "{\"success\":true,\"data\":{\"id\":4}}");
        MockHttpSession admin = login("admin");
        MvcResult r = accion(admin, INSPECCION, "faret-inspecciones", mapa("id", 9)).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).has("data") && !json(r).get("data").isNull()).isFalse();
        accion(admin, PALLET, "faret-inspecciones-pallet", mapa("id", "4")).andExpect(jsonPath("$.ok").value(true));
        assertThat(CALIDAD.peticiones()).containsExactly("DELETE /calidad-faret/registros/9", "DELETE /calidad-faret-pallet/registros/4");
        assertThat(CALIDAD.authorizations()).containsOnlyNulls();
        assertThat(QC.escrituras()).isEmpty();
        assertThat(MC.peticiones()).isEmpty();
    }

    @Test
    void inspeccionesValidanIdYClavesYPropaganLosErrores() throws Exception {
        MockHttpSession ana = login("ana");
        for (String a : List.of(INSPECCION, PALLET)) {
            String modulo = a.equals(INSPECCION) ? "faret-inspecciones" : "faret-inspecciones-pallet";
            esperar(ana, a, modulo, mapa(), "id es requerido");
            esperar(ana, a, modulo, mapa("id", 0), "id es requerido");
            esperar(ana, a, modulo, mapa("id", -1), "id es requerido");
            for (Object malo : List.of(true, Map.of("a", 1), List.of(1), 1.5, "x/../9", 3_000_000_000L)) {
                esperar(ana, a, modulo, mapa("id", malo), "Parámetro inválido.");
            }
            accion(ana, a, modulo, mapa("id", 9, "ncId", 1)).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("Campo no permitido")));
        }
        assertThat(CALIDAD.peticiones()).isEmpty();
        CALIDAD.responder("DELETE", "/calidad-faret/registros/9", 404, "{\"success\":false,\"message\":\"Registro no encontrado\"}");
        accion(ana, INSPECCION, "faret-inspecciones", mapa("id", 9)).andExpect(jsonPath("$.error").value("Registro no encontrado"));
        CALIDAD.responder("DELETE", "/calidad-faret-pallet/registros/9", 500, "<html>boom</html>");
        accion(ana, PALLET, "faret-inspecciones-pallet", mapa("id", 9)).andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret"));
        assertThat(CALIDAD.peticiones()).hasSize(2);
    }

    // ------------------------------------------------------------------------------- roles, empresa, CSRF, auditoría

    @Test
    void soloAdminAdminTiYCalidadInspectorYConsultaDenegados() throws Exception {
        QC.respuestaEscritura("DELETE " + PNC + "/55", 200, OK_QC);
        MC.responder("DELETE", NC + "/7", 200, "{\"mensaje\":\"ok\"}");
        MC.responder("DELETE", NC + "/7/adjuntos/12", 200, "{\"mensaje\":\"ok\"}");
        CALIDAD.responder("DELETE", "/calidad-faret/registros/9", 200, "{\"success\":true,\"data\":null}");
        CALIDAD.responder("DELETE", "/calidad-faret-pallet/registros/9", 200, "{\"success\":true,\"data\":null}");
        for (String u : List.of("admin", "ana", "tecnico")) {
            MockHttpSession s = login(u);
            accion(s, ADJUNTO, mapa("id", 7, "adjuntoId", 12)).andExpect(jsonPath("$.ok").value(true));
            accion(s, FILA, mapa("dataId", 55, "ncId", 7)).andExpect(jsonPath("$.ok").value(true));
            accion(s, INSPECCION, "faret-inspecciones", mapa("id", 9)).andExpect(jsonPath("$.ok").value(true));
            accion(s, PALLET, "faret-inspecciones-pallet", mapa("id", 9)).andExpect(jsonPath("$.ok").value(true));
        }
        int mc = MC.peticiones().size();
        int calidad = CALIDAD.peticiones().size();
        int qc = QC.escrituras().size();
        // Photino los permitía (menos CONSULTA); la web no: inspector y consulta nunca llegan a ninguna API.
        for (String u : List.of("ines", "clara")) {
            MockHttpSession s = login(u);
            for (String a : TODAS) {
                accion(s, a, moduloDe(a), camposValidos(a)).andExpect(status().isForbidden()).andExpect(jsonPath("$.ok").value(false));
            }
        }
        assertThat(MC.peticiones()).hasSize(mc);
        assertThat(CALIDAD.peticiones()).hasSize(calidad);
        assertThat(QC.escrituras()).hasSize(qc);
    }

    @Test
    void empresaModuloCruzadosYCsrf() throws Exception {
        MockHttpSession innpack = loginInnpack("adminti1");
        for (String a : TODAS) {
            accion(innpack, a, moduloDe(a), mapa("id", 7, "adjuntoId", 12)).andExpect(status().isForbidden());
        }
        // Módulo cruzado o ausente.
        MockHttpSession ana = login("ana");
        for (String modulo : List.of("faret-talleres-externos", "faret-data", "inventado")) {
            accion(ana, ADJUNTO, modulo, mapa("id", 7, "adjuntoId", 12)).andExpect(status().isForbidden());
            accion(ana, FILA, modulo, mapa("dataId", 55)).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/v1/bridge").session(ana).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"" + FILA + "\",\"dataId\":55}")).andExpect(status().isForbidden());
        // Sin sesión o sin CSRF.
        for (String a : TODAS) {
            mockMvc.perform(post("/api/v1/bridge").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"" + a + "\",\"_modulo\":\"" + moduloDe(a) + "\",\"id\":7,\"adjuntoId\":12}")).andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/v1/bridge").session(ana).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"" + a + "\",\"_modulo\":\"" + moduloDe(a) + "\",\"id\":7,\"adjuntoId\":12}")).andExpect(status().isForbidden());
        }
        assertThat(MC.peticiones()).isEmpty();
        assertThat(CALIDAD.peticiones()).isEmpty();
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void auditoriaConRecursoEIdsYResultadoParcialSinContenido(CapturedOutput salida) throws Exception {
        MockHttpSession ana = login("ana");
        QC.respuestaEscritura("DELETE " + PNC + "/55", 200, OK_QC);
        MC.responder("DELETE", NC + "/7", 200, "{\"mensaje\":\"ok\"}");
        MC.responder("DELETE", NC + "/8", 404, "");
        MC.responder("DELETE", NC + "/7/adjuntos/12", 200, "{\"mensaje\":\"ok\"}");
        CALIDAD.responder("DELETE", "/calidad-faret/registros/9", 200, "{\"success\":true,\"data\":null}");
        CALIDAD.responder("DELETE", "/calidad-faret-pallet/registros/4", 200, "{\"success\":true,\"data\":null}");
        accion(ana, ADJUNTO, mapa("id", 7, "adjuntoId", 12)).andExpect(jsonPath("$.ok").value(true));
        accion(ana, FILA, mapa("dataId", 55, "ncId", 7)).andExpect(jsonPath("$.ok").value(true));
        accion(ana, FILA, mapa("dataId", 55, "ncId", 8)).andExpect(jsonPath("$.ok").value(false));
        accion(ana, FILA, mapa("ncId", 8)).andExpect(jsonPath("$.ok").value(false));
        accion(ana, FILA, mapa()).andExpect(jsonPath("$.ok").value(false));
        accion(ana, INSPECCION, "faret-inspecciones", mapa("id", 9)).andExpect(jsonPath("$.ok").value(true));
        accion(ana, PALLET, "faret-inspecciones-pallet", mapa("id", 4)).andExpect(jsonPath("$.ok").value(true));
        accion(ana, INSPECCION, "faret-inspecciones", mapa("id", true)).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=ana empresa=FARET accion=faret\\.nc\\.adjuntos\\.eliminar recurso=faret:nc:7:adjunto:12:eliminar resultado=OK ms=\\d+");
        assertThat(log).containsPattern("accion=faret\\.nc\\.eliminarFila recurso=faret:nc:fila:data:55:OK:nc:7:OK:eliminar resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.nc\\.eliminarFila recurso=faret:nc:fila:data:55:OK:nc:8:ERROR:eliminar resultado=ERROR");
        assertThat(log).containsPattern("accion=faret\\.nc\\.eliminarFila recurso=faret:nc:fila:nc:8:ERROR:eliminar resultado=ERROR");
        assertThat(log).containsPattern("accion=faret\\.nc\\.eliminarFila recurso=faret:nc:fila:_:eliminar resultado=ERROR");
        assertThat(log).containsPattern("accion=faret\\.inspecciones\\.eliminar recurso=faret:inspeccion:9:eliminar resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.inspeccionesPallet\\.eliminar recurso=faret:inspeccionPallet:4:eliminar resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.inspecciones\\.eliminar recurso=faret:inspeccion:_:eliminar resultado=ERROR");
        assertThat(log).doesNotContain(PASS);
    }

    @Test
    void unaClaveReservadaDeAuditoriaDelNavegadorSeRechaza() throws Exception {
        MockHttpSession ana = login("ana");
        accion(ana, FILA, mapa("dataId", 55, "__qccAuditoriaFila", ":data:1:OK")).andExpect(jsonPath("$.ok").value(false));
        assertThat(QC.escrituras()).isEmpty();
    }

    // ------------------------------------------------------------------------------- helpers

    private static String moduloDe(String accion) {
        return switch (accion) {
            case INSPECCION -> "faret-inspecciones";
            case PALLET -> "faret-inspecciones-pallet";
            default -> "faret-nc";
        };
    }

    private static Map<String, Object> camposValidos(String accion) {
        return switch (accion) {
            case ADJUNTO -> mapa("id", 7, "adjuntoId", 12);
            case FILA -> mapa("dataId", 55, "ncId", 7);
            default -> mapa("id", 9);
        };
    }

    private static Map<String, Object> mapa(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private void esperar(MockHttpSession sesion, String accion, Map<String, Object> campos, String error) throws Exception {
        esperar(sesion, accion, moduloDe(accion), campos, error);
    }

    private void esperar(MockHttpSession sesion, String accion, String modulo, Map<String, Object> campos, String error) throws Exception {
        accion(sesion, accion, modulo, campos).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value(error));
    }

    private ResultActions accion(MockHttpSession sesion, String accion, Map<String, Object> campos) throws Exception {
        return accion(sesion, accion, moduloDe(accion), campos);
    }

    private ResultActions accion(MockHttpSession sesion, String accion, String modulo, Map<String, Object> campos) throws Exception {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", accion);
        p.put("_modulo", modulo);
        campos.forEach((k, v) -> p.set(k, v == null ? mapper.nullNode() : mapper.valueToTree(v)));
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(p.toString()));
    }

    private MockHttpSession login(String identificador) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/faret/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.85.0." + IP.getAndIncrement());
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
                            r.setRemoteAddr("10.85.1." + IP.getAndIncrement());
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
            Path dir = Files.createTempDirectory("qcc-web-fixture-f5");
            Files.writeString(dir.resolve("index.html"), "<!doctype html><title>fixture</title>");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
