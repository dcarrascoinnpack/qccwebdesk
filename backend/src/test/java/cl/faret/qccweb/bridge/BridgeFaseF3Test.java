package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeFaretApi;
import cl.faret.qccweb.auth.FakeFaretSinAuthApi;
import cl.faret.qccweb.auth.FakeInnpackApi;
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
 * Fase F3 - ESCRITURAS ADITIVAS de No Conformidades FARET (Photino 1.8.15, 9e1b556, FaretHandler.cs) contra MejoraContinua, SIN
 * Authorization: faret.nc.{seguimiento.crear, acciones.crear, analisis.guardar, adjuntos.subir}. TODA la API es un fake en localhost
 * (FakeFaretSinAuthApi); nunca se escribe en una API real.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFaseF3Test {

    private static final FakeFaretApi QC = new FakeFaretApi(Clock.systemUTC());
    private static final FakeInnpackApi INNPACK = new FakeInnpackApi(Clock.systemUTC());
    private static final FakeFaretSinAuthApi MC = new FakeFaretSinAuthApi("/mejora-continua");
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveFaretF3#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String NC = "/api/no-conformidades";
    private static final String SEGUIMIENTO = "faret.nc.seguimiento.crear";
    private static final String ACCION = "faret.nc.acciones.crear";
    private static final String ANALISIS = "faret.nc.analisis.guardar";
    private static final String SUBIR = "faret.nc.adjuntos.subir";
    private static final List<String> ESCRITURAS = List.of(SEGUIMIENTO, ACCION, ANALISIS, SUBIR);
    private static final String RUTA_ARCHIVO = "/api/v1/bridge/archivo";
    private static final String PDF = Base64.getEncoder().encodeToString(FakeInnpackApi.pdfMinimo(1));
    private static final String PNG = Base64.getEncoder().encodeToString(FakeInnpackApi.pngMinimo());
    private static final String SIN_ANALISIS_MC = "{\"mensaje\":\"Esta no conformidad aún no tiene un análisis registrado\"}";

    static {
        QC.agregar(new FakeFaretApi.Usuario(10, "admin", "admin@faret.cl", PASS, "Admin Faret", List.of("ADMIN")));
        QC.agregar(new FakeFaretApi.Usuario(20, "ana", "ana@faret.cl", PASS, "Ana Calidad", List.of("CALIDAD")));
        QC.agregar(new FakeFaretApi.Usuario(30, "clara", null, PASS, "Clara Consulta", List.of("CONSULTA")));
        QC.agregar(new FakeFaretApi.Usuario(50, "tecnico", "tecnico@faret.cl", PASS, "Tecnico TI", List.of("ADMIN_TI")));
        QC.agregar(new FakeFaretApi.Usuario(60, "ines", "ines@faret.cl", PASS, "Ines Inspector", List.of("INSPECTOR")));
        INNPACK.agregar(new FakeInnpackApi.Usuario(41, "adminti1", PASS, "Admin TI Innpack", "admin_ti", true));
        INNPACK.agregar(new FakeInnpackApi.Usuario(40, "operador1", PASS, "Operador Uno", "operador", true));
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

    @Autowired
    private ActionPolicy policy;

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

    // ------------------------------------------------------------------------------- seguimiento.crear

    @Test
    void seguimientoEnviaElCuerpoExactoConElAutorDeLaSesionYSinAuthorization() throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder(NC + "/7", 200, "{\"id\":7}");
        MC.responder("POST", NC + "/7/seguimiento", 200, "{\"id\":55,\"interno\":\"SECRETO\"}");
        // El frontend de Photino manda `autor` desde sessionStorage: se acepta la clave pero su valor se descarta.
        MvcResult r = accion(ana, SEGUIMIENTO, Map.of("id", 7, "comentario", "  Se revisó 5<6 -> OK  ", "autor", "Falso Autor")).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).get("data").toString()).isEqualTo("{\"id\":55}");
        assertThat(MC.peticiones()).containsExactly("GET " + NC + "/7", "POST " + NC + "/7/seguimiento");
        assertThat(MC.cuerpos().get(1)).isEqualTo("{\"comentario\":\"Se revisó 5<6 -> OK\",\"autor\":\"Ana Calidad\"}");
        assertThat(MC.authorizations()).hasSize(2).containsOnlyNulls();
        assertThat(QC.lecturas()).isEmpty();
        // Sin `autor` en el payload el resultado es el mismo.
        accion(ana, SEGUIMIENTO, Map.of("id", "7", "comentario", "otro")).andExpect(jsonPath("$.ok").value(true));
        assertThat(MC.cuerpos().get(3)).isEqualTo("{\"comentario\":\"otro\",\"autor\":\"Ana Calidad\"}");
    }

    @Test
    void seguimientoValidaClavesIdYComentarioSinLlamarALaApi() throws Exception {
        MockHttpSession ana = login("ana");
        for (String clave : List.of("usuario", "usuarioId", "creadoPor", "empresa", "rol", "data", "ruta", "x<y")) {
            Map<String, Object> m = new LinkedHashMap<>(Map.of("id", 7, "comentario", "c"));
            m.put(clave, "x");
            accion(ana, SEGUIMIENTO, m).andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Campo no permitido: " + (clave.equals("x<y") ? "?" : clave)));
        }
        accion(ana, SEGUIMIENTO, Map.of("comentario", "c")).andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
        accion(ana, SEGUIMIENTO, Map.of("id", 0, "comentario", "c")).andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
        accion(ana, SEGUIMIENTO, Map.of("id", -3, "comentario", "c")).andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
        for (Object malo : List.of(true, List.of(1), Map.of("a", 1), 1.5, "abc", 3000000000L)) {
            accion(ana, SEGUIMIENTO, Map.of("id", malo, "comentario", "c")).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        }
        accion(ana, SEGUIMIENTO, Map.of("id", 7)).andExpect(jsonPath("$.error").value("Falta el comentario de seguimiento"));
        accion(ana, SEGUIMIENTO, Map.of("id", 7, "comentario", "   ")).andExpect(jsonPath("$.error").value("Falta el comentario de seguimiento"));
        accion(ana, SEGUIMIENTO, Map.of("id", 7, "comentario", 12)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        accion(ana, SEGUIMIENTO, Map.of("id", 7, "comentario", "a\u0000b")).andExpect(jsonPath("$.error").value("El comentario contiene caracteres no permitidos."));
        for (String html : List.of("<b>x</b>", "hola <script>alert(1)</script>", "<!-- x -->", "a</p>", "<?php")) {
            accion(ana, SEGUIMIENTO, Map.of("id", 7, "comentario", html)).andExpect(jsonPath("$.error")
                    .value("El comentario no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\")."));
        }
        accion(ana, SEGUIMIENTO, Map.of("id", 7, "comentario", "x".repeat(65_536))).andExpect(jsonPath("$.error")
                .value("El comentario supera el máximo permitido (65.535 bytes)."));
        assertThat(MC.peticiones()).isEmpty();
        // Saltos de línea y tabulaciones son válidos (textarea).
        MC.responder(NC + "/7", 200, "{\"id\":7}");
        MC.responder("POST", NC + "/7/seguimiento", 200, "{\"id\":1}");
        accion(ana, SEGUIMIENTO, Map.of("id", 7, "comentario", "línea 1\n\tlínea 2 ñ 😀")).andExpect(jsonPath("$.ok").value(true));
        accion(ana, SEGUIMIENTO, Map.of("id", 7, "comentario", "x".repeat(65_535))).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void seguimientoErroresDeLaApiSePropaganYNoSeEscribeEnUnaNcInexistente() throws Exception {
        MockHttpSession ana = login("ana");
        // NC inexistente: el GET previo da 404 y no se intenta el POST.
        accion(ana, SEGUIMIENTO, Map.of("id", 404, "comentario", "c")).andExpect(jsonPath("$.error").value("No conformidad no encontrada"));
        assertThat(MC.peticiones()).containsExactly("GET " + NC + "/404");
        MC.limpiar();
        MC.responder(NC + "/7", 200, "{\"id\":7}");
        MC.responder("POST", NC + "/7/seguimiento", 500, "{\"title\":\"Internal Server Error\",\"stack\":\"SqlException x\"}");
        MvcResult r = accion(ana, SEGUIMIENTO, Map.of("id", 7, "comentario", "c")).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Internal Server Error")).andReturn();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SqlException");
        MC.responder("POST", NC + "/7/seguimiento", 502, "<html>gateway</html>");
        accion(ana, SEGUIMIENTO, Map.of("id", 7, "comentario", "c")).andExpect(jsonPath("$.error").value("Error al comunicarse con la API de Mejora Continua"));
        // Un 401 de MejoraContinua es un error normal: no invalida la sesión FARET.
        MC.responder("POST", NC + "/7/seguimiento", 401, "{\"title\":\"Unauthorized\"}");
        accion(ana, SEGUIMIENTO, Map.of("id", 7, "comentario", "c")).andExpect(status().isOk()).andExpect(jsonPath("$.error").value("Unauthorized"));
        assertThat(ana.isInvalid()).isFalse();
        // Sin reintentos: un solo POST por intento.
        assertThat(MC.peticiones().stream().filter(p -> p.startsWith("POST"))).hasSize(3);
    }

    // ------------------------------------------------------------------------------- acciones.crear

    @Test
    void accionEnviaElCuerpoExactoDePhotinoConElCreadoPorDeLaSesion() throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder("POST", NC + "/7/acciones", 200, "{\"id\":12,\"estado\":\"PENDIENTE\",\"interno\":\"SECRETO\"}");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", 7);
        m.put("analisisId", null);
        m.put("creadoPor", "Falso Autor");
        m.put("descripcion", "  Reentrenar al operador ");
        m.put("responsable", " Pedro Soto ");
        m.put("fechaLimite", "2026-12-31");
        m.put("prioridad", "ALTA");
        MvcResult r = accion(ana, ACCION, m).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).get("data").toString()).isEqualTo("{\"id\":12}");
        assertThat(MC.peticiones()).containsExactly("POST " + NC + "/7/acciones");
        assertThat(MC.cuerpos().get(0)).isEqualTo("{\"analisisId\":null,\"descripcion\":\"Reentrenar al operador\",\"responsable\":\"Pedro Soto\","
                + "\"fechaLimite\":\"2026-12-31\",\"prioridad\":\"ALTA\",\"creadoPor\":\"Ana Calidad\"}");
        assertThat(MC.authorizations()).containsOnlyNulls();
        // prioridad vacía o null (la vista manda `|| null`) viaja como null.
        for (Object vacia : java.util.Arrays.asList("", null, "  ")) {
            Map<String, Object> m2 = new LinkedHashMap<>(m);
            m2.put("prioridad", vacia);
            accion(ana, ACCION, m2).andExpect(jsonPath("$.ok").value(true));
        }
        assertThat(MC.cuerpos().subList(1, 4)).allSatisfy(c -> assertThat(c).contains("\"prioridad\":null,\"creadoPor\":\"Ana Calidad\""));
    }

    @Test
    void accionConAnalisisIdSoloSiEsElDeEsaNc() throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder("POST", NC + "/7/acciones", 200, "{\"id\":13}");
        MC.responder("GET", NC + "/7/analisis", 200, "{\"id\":3,\"noConformidadId\":7,\"causaRaiz\":\"x\"}");
        accion(ana, ACCION, accionValida("analisisId", 3)).andExpect(jsonPath("$.ok").value(true));
        assertThat(MC.peticiones()).containsExactly("GET " + NC + "/7/analisis", "POST " + NC + "/7/acciones");
        assertThat(MC.cuerpos().get(1)).startsWith("{\"analisisId\":3,\"descripcion\":");
        MC.limpiar();
        MC.responder("GET", NC + "/7/analisis", 200, "{\"id\":3,\"noConformidadId\":7,\"causaRaiz\":\"x\"}");
        // Un análisis de otra NC (o inexistente) no se acepta y no se escribe.
        accion(ana, ACCION, accionValida("analisisId", 99)).andExpect(jsonPath("$.error").value("El análisis indicado no corresponde a la no conformidad."));
        MC.responder("GET", NC + "/7/analisis", 404, SIN_ANALISIS_MC);
        accion(ana, ACCION, accionValida("analisisId", 3)).andExpect(jsonPath("$.error").value("El análisis indicado no corresponde a la no conformidad."));
        MC.responder("GET", NC + "/7/analisis", 404, "{\"mensaje\":\"No conformidad no encontrada\"}");
        accion(ana, ACCION, accionValida("analisisId", 3)).andExpect(jsonPath("$.error").value("No conformidad no encontrada"));
        for (Object malo : List.of(0, -1, "3", true, 1.5, List.of(3), Map.of("a", 1))) {
            accion(ana, ACCION, accionValida("analisisId", malo)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        }
        assertThat(MC.peticiones().stream().filter(p -> p.startsWith("POST"))).isEmpty();
    }

    @Test
    void accionValidaObligatoriosTiposTextosFechaYPrioridadSinLlamarALaApi() throws Exception {
        MockHttpSession ana = login("ana");
        accion(ana, ACCION, mapa("id", 7, "responsable", "R", "fechaLimite", "2026-01-01")).andExpect(jsonPath("$.error").value("Falta la descripción de la acción"));
        accion(ana, ACCION, mapa("id", 7, "descripcion", "D", "fechaLimite", "2026-01-01")).andExpect(jsonPath("$.error").value("Falta el responsable"));
        accion(ana, ACCION, mapa("id", 7, "descripcion", "D", "responsable", "R")).andExpect(jsonPath("$.error").value("Falta la fecha límite"));
        accion(ana, ACCION, mapa("descripcion", "D", "responsable", "R", "fechaLimite", "2026-01-01")).andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
        accion(ana, ACCION, accionValida("descripcion", 5)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        accion(ana, ACCION, accionValida("responsable", List.of("a"))).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        accion(ana, ACCION, accionValida("prioridad", 1)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        accion(ana, ACCION, accionValida("prioridad", "URGENTE")).andExpect(jsonPath("$.error").value("Prioridad inválida (ALTA, MEDIA o BAJA)."));
        accion(ana, ACCION, accionValida("prioridad", "alta")).andExpect(jsonPath("$.error").value("Prioridad inválida (ALTA, MEDIA o BAJA)."));
        for (String f : List.of("31/12/2026", "2026-13-01", "2026-02-30", "2026-1-1", "ayer", "2026-12-31T10:00:00")) {
            accion(ana, ACCION, accionValida("fechaLimite", f)).andExpect(jsonPath("$.error").value("La fecha límite no es válida (formato AAAA-MM-DD)."));
        }
        accion(ana, ACCION, accionValida("fechaLimite", 20261231)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        accion(ana, ACCION, accionValida("descripcion", "<img src=x onerror=alert(1)>")).andExpect(jsonPath("$.error")
                .value("El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\")."));
        accion(ana, ACCION, accionValida("responsable", "<b>Pedro</b>")).andExpect(jsonPath("$.error")
                .value("El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\")."));
        accion(ana, ACCION, accionValida("descripcion", "línea\nsalto")).andExpect(jsonPath("$.error").value("El texto contiene caracteres no permitidos."));
        accion(ana, ACCION, accionValida("responsable", "a\u0007b")).andExpect(jsonPath("$.error").value("El texto contiene caracteres no permitidos."));
        accion(ana, ACCION, accionValida("descripcion", "x".repeat(1001))).andExpect(jsonPath("$.error").value("El campo descripcion supera el máximo de 1000 caracteres."));
        accion(ana, ACCION, accionValida("responsable", "x".repeat(201))).andExpect(jsonPath("$.error").value("El campo responsable supera el máximo de 200 caracteres."));
        for (String clave : List.of("usuario", "actualizadoPor", "estado", "subidoPor", "autor", "empresa", "data")) {
            Map<String, Object> m = accionValida();
            m.put(clave, "z");
            accion(ana, ACCION, m).andExpect(jsonPath("$.error").value("Campo no permitido: " + clave));
        }
        assertThat(MC.peticiones()).isEmpty();
        MC.responder("POST", NC + "/7/acciones", 200, "{\"id\":1}");
        accion(ana, ACCION, accionValida("descripcion", "x".repeat(1000))).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void accionErroresDeLaApiSePropagan() throws Exception {
        MockHttpSession ana = login("ana");
        accion(ana, ACCION, accionValida()).andExpect(jsonPath("$.error").value("Not Found"));
        MC.responder("POST", NC + "/7/acciones", 404, "{\"mensaje\":\"No conformidad no encontrada\"}");
        accion(ana, ACCION, accionValida()).andExpect(jsonPath("$.error").value("No conformidad no encontrada"));
        MC.responder("POST", NC + "/7/acciones", 400, "{\"mensaje\":\"responsable es obligatorio\"}");
        accion(ana, ACCION, accionValida()).andExpect(jsonPath("$.error").value("responsable es obligatorio"));
        MC.responder("POST", NC + "/7/acciones", 500, "<html>boom</html>");
        accion(ana, ACCION, accionValida()).andExpect(jsonPath("$.error").value("Error al comunicarse con la API de Mejora Continua"));
        assertThat(MC.peticiones()).hasSize(4);
    }

    // ------------------------------------------------------------------------------- analisis.guardar

    @Test
    void analisisSinAnalisisPrevioCreaConPostAunqueElNavegadorDigaQueExiste() throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder("GET", NC + "/7/analisis", 404, SIN_ANALISIS_MC);
        MC.responder("POST", NC + "/7/analisis", 200, "{\"id\":3}");
        // La vista abre el análisis (registra la huella: "sin análisis") y luego guarda.
        leerAnalisis(ana, 7).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data").isEmpty());
        Map<String, Object> m = analisisValido();
        m.put("existeAnalisis", true); // el navegador se equivoca: el gateway lo ignora
        m.put("actualizadoPor", "Falso Autor");
        m.put("creadoPor", "Otro Falso");
        MvcResult r = accion(ana, ANALISIS, m).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).get("data").toString()).isEqualTo("{\"id\":3}");
        assertThat(MC.peticiones()).containsExactly("GET " + NC + "/7/analisis", "GET " + NC + "/7/analisis", "POST " + NC + "/7/analisis");
        assertThat(MC.cuerpos().get(2)).isEqualTo("{\"metodologia\":\"CINCO_PORQUES\",\"problemaDetectado\":\"Burbujas en el sello\","
                + "\"porque1\":\"Calor\",\"porque2\":\"\",\"porque3\":null,\"porque4\":null,\"porque5\":null,\"causaRaiz\":\"Temperatura alta\","
                + "\"conclusion\":\"Ajustar\",\"creadoPor\":\"Ana Calidad\",\"actualizadoPor\":null}");
        assertThat(MC.authorizations()).containsOnlyNulls();
        // Tras guardar la huella se descarta: sin releer no se puede volver a guardar encima.
        accion(ana, ANALISIS, analisisValido()).andExpect(jsonPath("$.error").value("Abre el análisis de la no conformidad antes de guardarlo."));
        assertThat(MC.peticiones().stream().filter(p -> p.startsWith("POST"))).hasSize(1);
    }

    @Test
    void analisisExistenteActualizaConPutAunqueElNavegadorDigaQueNoExiste() throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder("GET", NC + "/7/analisis", 200, "{\"id\":3,\"noConformidadId\":7,\"metodologia\":\"MIXTA\",\"problemaDetectado\":\"P\",\"causaRaiz\":\"C\","
                + "\"creadoPor\":\"Pedro\",\"fechaCreacion\":\"2026-10-01T10:00:00\",\"actualizadoPor\":null,\"fechaActualizacion\":null}");
        MC.responder("PUT", NC + "/7/analisis", 200, "{\"mensaje\":\"Análisis actualizado correctamente\",\"interno\":\"SECRETO\"}");
        leerAnalisis(ana, 7).andExpect(jsonPath("$.data.id").value(3));
        Map<String, Object> m = analisisValido();
        m.put("existeAnalisis", false);
        m.put("creadoPor", "Falso");
        MvcResult r = accion(ana, ANALISIS, m).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).get("data").toString()).isEqualTo("{\"mensaje\":\"Análisis actualizado correctamente\"}");
        assertThat(MC.peticiones()).containsExactly("GET " + NC + "/7/analisis", "GET " + NC + "/7/analisis", "PUT " + NC + "/7/analisis");
        assertThat(MC.cuerpos().get(2)).isEqualTo("{\"metodologia\":\"CINCO_PORQUES\",\"problemaDetectado\":\"Burbujas en el sello\","
                + "\"porque1\":\"Calor\",\"porque2\":\"\",\"porque3\":null,\"porque4\":null,\"porque5\":null,\"causaRaiz\":\"Temperatura alta\","
                + "\"conclusion\":\"Ajustar\",\"creadoPor\":null,\"actualizadoPor\":\"Ana Calidad\"}");
        assertThat(MC.authorizations()).containsOnlyNulls();
    }

    @Test
    void analisisExigeHaberLeidoYRechazaSiOtraPersonaLoCambio() throws Exception {
        MockHttpSession ana = login("ana");
        MockHttpSession admin = login("admin");
        String v1 = "{\"id\":3,\"metodologia\":\"MIXTA\",\"problemaDetectado\":\"P\",\"causaRaiz\":\"C\",\"creadoPor\":\"Pedro\",\"fechaCreacion\":\"2026-10-01T10:00:00\"}";
        MC.responder("GET", NC + "/7/analisis", 200, v1);
        MC.responder("PUT", NC + "/7/analisis", 200, "{\"mensaje\":\"ok\"}");
        // Sin lectura previa de ESTA sesión no se escribe (aunque otra sesión sí leyó).
        leerAnalisis(admin, 7);
        accion(ana, ANALISIS, analisisValido()).andExpect(jsonPath("$.error").value("Abre el análisis de la no conformidad antes de guardarlo."));
        // Ana lee; otra persona cambia el análisis; el guardado de Ana se rechaza sin escribir.
        leerAnalisis(ana, 7);
        MC.responder("GET", NC + "/7/analisis", 200, v1.replace("\"causaRaiz\":\"C\"", "\"causaRaiz\":\"CAMBIADA POR OTRA PERSONA\""));
        accion(ana, ANALISIS, analisisValido()).andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value(
                "El análisis fue modificado por otra persona desde que lo abriste. Cierra y vuelve a abrir el análisis para ver la versión actual antes de guardar (no se guardó nada)."));
        // Alguien creó el análisis mientras Ana tenía abierto "sin análisis": tampoco se escribe.
        MockHttpSession clara = login("clara");
        MC.responder("GET", NC + "/8/analisis", 404, SIN_ANALISIS_MC);
        leerAnalisis(clara, 8);
        MC.responder("GET", NC + "/8/analisis", 200, v1);
        accion(clara, ANALISIS, analisisValido(8)).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("El análisis fue modificado por otra persona")));
        assertThat(MC.peticiones().stream().filter(p -> p.startsWith("PUT") || p.startsWith("POST"))).isEmpty();
        // Si Ana relee, vuelve a poder guardar.
        leerAnalisis(ana, 7);
        accion(ana, ANALISIS, analisisValido()).andExpect(jsonPath("$.ok").value(true));
        assertThat(MC.peticiones().stream().filter(p -> p.startsWith("PUT"))).hasSize(1);
    }

    @Test
    void analisisValidaClavesMetodologiaTextosYTipos() throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder("GET", NC + "/7/analisis", 404, SIN_ANALISIS_MC);
        leerAnalisis(ana, 7);
        MC.limpiar();
        accion(ana, ANALISIS, analisisValido("usuario", "x")).andExpect(jsonPath("$.error").value("Campo no permitido: usuario"));
        accion(ana, ANALISIS, analisisValido("empresa", "x")).andExpect(jsonPath("$.error").value("Campo no permitido: empresa"));
        accion(ana, ANALISIS, analisisValido("existeAnalisis", "si")).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        accion(ana, ANALISIS, analisisValido("id", null)).andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
        accion(ana, ANALISIS, analisisValido("id", true)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        accion(ana, ANALISIS, analisisValido("metodologia", "")).andExpect(jsonPath("$.error").value("Falta la metodología"));
        accion(ana, ANALISIS, analisisValido("metodologia", "OTRA")).andExpect(jsonPath("$.error")
                .value("Metodología inválida. Valores permitidos: CINCO_PORQUES, ISHIKAWA, MIXTA"));
        accion(ana, ANALISIS, analisisValido("metodologia", "mixta")).andExpect(jsonPath("$.error")
                .value("Metodología inválida. Valores permitidos: CINCO_PORQUES, ISHIKAWA, MIXTA"));
        accion(ana, ANALISIS, analisisValido("problemaDetectado", "  ")).andExpect(jsonPath("$.error").value("Falta el problema detectado"));
        for (String campo : List.of("problemaDetectado", "porque1", "causaRaiz", "conclusion", "metodologia")) {
            accion(ana, ANALISIS, analisisValido(campo, 5)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
            accion(ana, ANALISIS, analisisValido(campo, List.of("x"))).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        }
        for (String campo : List.of("problemaDetectado", "porque1", "porque3", "causaRaiz", "conclusion")) {
            accion(ana, ANALISIS, analisisValido(campo, "x <script>alert(1)</script>")).andExpect(jsonPath("$.error")
                    .value("El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\")."));
            accion(ana, ANALISIS, analisisValido(campo, "a\u0000b")).andExpect(jsonPath("$.error").value("El texto contiene caracteres no permitidos."));
        }
        // Una línea en los porqués; multilínea en los textareas.
        accion(ana, ANALISIS, analisisValido("porque2", "a\nb")).andExpect(jsonPath("$.error").value("El texto contiene caracteres no permitidos."));
        for (String campo : List.of("problemaDetectado", "porque4", "causaRaiz")) {
            accion(ana, ANALISIS, analisisValido(campo, "x".repeat(501))).andExpect(jsonPath("$.error").value("El campo " + campo + " supera el máximo de 500 caracteres."));
        }
        accion(ana, ANALISIS, analisisValido("conclusion", "x".repeat(65_536))).andExpect(jsonPath("$.error")
                .value("El campo conclusion supera el máximo permitido (65.535 bytes)."));
        assertThat(MC.peticiones()).isEmpty();
        // Sin errores previos: multilínea válida y límites exactos.
        MC.responder("GET", NC + "/7/analisis", 404, SIN_ANALISIS_MC);
        MC.responder("POST", NC + "/7/analisis", 200, "{\"id\":1}");
        Map<String, Object> m = analisisValido("problemaDetectado", "x".repeat(500));
        m.put("causaRaiz", "x".repeat(499) + "\n");
        m.put("conclusion", "línea 1\r\nlínea 2");
        accion(ana, ANALISIS, m).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void analisisErroresDeLaApiSePropagan() throws Exception {
        MockHttpSession ana = login("ana");
        // NC inexistente al leer.
        MC.responder("GET", NC + "/9/analisis", 404, "{\"mensaje\":\"No conformidad no encontrada\"}");
        accion(ana, ANALISIS, analisisValido(9)).andExpect(jsonPath("$.error").value("No conformidad no encontrada"));
        // PUT sin causaRaiz: lo rechaza la API y llega su mensaje.
        MC.responder("GET", NC + "/7/analisis", 200, "{\"id\":3,\"metodologia\":\"MIXTA\",\"problemaDetectado\":\"P\"}");
        leerAnalisis(ana, 7);
        MC.responder("PUT", NC + "/7/analisis", 400, "{\"mensaje\":\"causaRaiz es obligatoria para guardar el análisis final\"}");
        Map<String, Object> m = analisisValido();
        m.remove("causaRaiz");
        accion(ana, ANALISIS, m).andExpect(jsonPath("$.error").value("causaRaiz es obligatoria para guardar el análisis final"));
        MC.responder("PUT", NC + "/7/analisis", 500, "{\"title\":\"Internal Server Error\"}");
        accion(ana, ANALISIS, analisisValido()).andExpect(jsonPath("$.error").value("Internal Server Error"));
        // Carrera: otra persona creó el análisis entre el GET y el POST -> 409 de la API con su mensaje, sin reintento.
        MC.responder("GET", NC + "/8/analisis", 404, SIN_ANALISIS_MC);
        leerAnalisis(ana, 8);
        MC.responder("POST", NC + "/8/analisis", 409, "{\"mensaje\":\"Esta no conformidad ya tiene un análisis. Use PUT para actualizarlo.\"}");
        accion(ana, ANALISIS, analisisValido(8)).andExpect(jsonPath("$.error").value("Esta no conformidad ya tiene un análisis. Use PUT para actualizarlo."));
        assertThat(MC.peticiones().stream().filter(p -> p.startsWith("POST"))).hasSize(1);
        assertThat(ana.isInvalid()).isFalse();
    }

    // ------------------------------------------------------------------------------- adjuntos.subir

    @Test
    void adjuntoEnviaElCuerpoExactoConElSubidoPorDeLaSesionPorLaRutaDeArchivos() throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder("POST", NC + "/7/adjuntos", 200, "{\"id\":21,\"interno\":\"SECRETO\"}");
        MvcResult r = subir(ana, adjunto("CAUSA_RAIZ_PDF", "application/pdf", "causa raíz.pdf", PDF)).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).get("data").toString()).isEqualTo("{\"id\":21}");
        assertThat(MC.peticiones()).containsExactly("POST " + NC + "/7/adjuntos");
        assertThat(mapper.readTree(MC.cuerpos().get(0))).isEqualTo(mapper.readTree("{\"tipo\":\"CAUSA_RAIZ_PDF\",\"nombreArchivo\":\"causa raíz.pdf\","
                + "\"tipoMime\":\"application/pdf\",\"contenidoBase64\":\"" + PDF + "\",\"subidoPor\":\"Ana Calidad\"}"));
        assertThat(MC.authorizations()).containsOnlyNulls();
        assertThat(QC.lecturas()).isEmpty();
        subir(ana, adjunto("EVIDENCIA_FOTO", "image/png", "foto.png", PNG)).andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(MC.cuerpos().get(1)).get("tipoMime").asString()).isEqualTo("image/png");
        // Por la ruta normal (256 KB) las acciones de archivo no entran, y viceversa.
        accion(ana, SUBIR, mapa("id", 7, "tipo", "CAUSA_RAIZ_PDF")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        mockMvc.perform(post(RUTA_ARCHIVO).session(ana).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"" + SEGUIMIENTO + "\",\"_modulo\":\"faret-nc\",\"id\":7,\"comentario\":\"c\"}")).andExpect(status().isForbidden());
        assertThat(MC.peticiones()).hasSize(2);
    }

    @Test
    void adjuntoValidaClavesTipoMimeFirmaRealBase64YTamano() throws Exception {
        MockHttpSession ana = login("ana");
        Map<String, Object> m = adjunto("CAUSA_RAIZ_PDF", "application/pdf", "a.pdf", PDF);
        m.put("usuario", "x");
        subir(ana, m).andExpect(jsonPath("$.error").value("Campo no permitido: usuario"));
        subir(ana, adjunto("OTRO", "application/pdf", "a.pdf", PDF)).andExpect(jsonPath("$.error").value("Tipo de adjunto inválido (CAUSA_RAIZ_PDF o EVIDENCIA_FOTO)."));
        subir(ana, adjunto("CAUSA_RAIZ_PDF", "image/png", "a.pdf", PDF)).andExpect(jsonPath("$.error").value("Solo se permite un archivo PDF"));
        for (String mime : List.of("application/pdf", "image/gif", "IMAGE/PNG", "text/html")) {
            subir(ana, adjunto("EVIDENCIA_FOTO", mime, "a.png", PNG)).andExpect(jsonPath("$.error").value("Solo se permiten fotografías JPG o PNG"));
        }
        subir(ana, adjunto("CAUSA_RAIZ_PDF", "application/pdf", "  ", PDF)).andExpect(jsonPath("$.error").value("Falta el nombre del archivo"));
        subir(ana, adjunto("CAUSA_RAIZ_PDF", "application/pdf", "a.pdf", "")).andExpect(jsonPath("$.error").value("Falta el contenido del archivo"));
        subir(ana, adjunto("CAUSA_RAIZ_PDF", "application/pdf", "a.pdf", 12)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        String html = Base64.getEncoder().encodeToString("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        subir(ana, adjunto("CAUSA_RAIZ_PDF", "application/pdf", "a.pdf", html)).andExpect(jsonPath("$.error").value("El contenido del archivo no corresponde a un PDF válido."));
        subir(ana, adjunto("EVIDENCIA_FOTO", "image/jpeg", "a.jpg", PNG)).andExpect(jsonPath("$.error").value("El contenido del archivo no corresponde a un JPG/PNG válido."));
        subir(ana, adjunto("EVIDENCIA_FOTO", "image/png", "a.png", PDF)).andExpect(jsonPath("$.error").value("El contenido del archivo no corresponde a un JPG/PNG válido."));
        for (String malo : List.of("%%%no-base64%%%", "JVBER i0x", "JVBERi0x\n")) {
            subir(ana, adjunto("CAUSA_RAIZ_PDF", "application/pdf", "a.pdf", malo)).andExpect(jsonPath("$.error").value("El adjunto no es válido."));
        }
        byte[] pdf10 = Arrays.copyOf(FakeInnpackApi.pdfMinimo(1), 10 * 1024 * 1024 + 1);
        subir(ana, adjunto("CAUSA_RAIZ_PDF", "application/pdf", "a.pdf", Base64.getEncoder().encodeToString(pdf10)))
                .andExpect(jsonPath("$.error").value("El PDF excede el tamaño máximo de 10 MB"));
        byte[] foto5 = Arrays.copyOf(FakeInnpackApi.pngMinimo(), 5 * 1024 * 1024 + 1);
        subir(ana, adjunto("EVIDENCIA_FOTO", "image/png", "a.png", Base64.getEncoder().encodeToString(foto5)))
                .andExpect(jsonPath("$.error").value("La fotografía excede el tamaño máximo de 5 MB"));
        Map<String, Object> sinId = adjunto("CAUSA_RAIZ_PDF", "application/pdf", "a.pdf", PDF);
        sinId.remove("id");
        subir(ana, sinId).andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
        assertThat(MC.peticiones()).isEmpty();
    }

    @Test
    void adjuntoSaneaElNombreYLaApiArbitraNcCerradaYMaximoDeFotos() throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder("POST", NC + "/7/adjuntos", 200, "{\"id\":1}");
        subir(ana, adjunto("CAUSA_RAIZ_PDF", "application/pdf", "..\\<img src=x onerror=alert(1)>\"'`&.pdf", PDF)).andExpect(jsonPath("$.ok").value(true));
        subir(ana, adjunto("CAUSA_RAIZ_PDF", "application/pdf", "a".repeat(300) + ".pdf", PDF)).andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(MC.cuerpos().get(0)).get("nombreArchivo").asString()).isEqualTo("_img src=x onerror=alert(1)_____.pdf");
        assertThat(mapper.readTree(MC.cuerpos().get(1)).get("nombreArchivo").asString()).hasSizeLessThanOrEqualTo(150);
        MC.responder("POST", NC + "/7/adjuntos", 409, "{\"mensaje\":\"La no conformidad está cerrada, no se pueden agregar adjuntos\"}");
        subir(ana, adjunto("CAUSA_RAIZ_PDF", "application/pdf", "a.pdf", PDF)).andExpect(jsonPath("$.error").value("La no conformidad está cerrada, no se pueden agregar adjuntos"));
        MC.responder("POST", NC + "/7/adjuntos", 409, "{\"mensaje\":\"Ya existen 10 fotografías para esta no conformidad, el máximo permitido\"}");
        subir(ana, adjunto("EVIDENCIA_FOTO", "image/png", "a.png", PNG)).andExpect(jsonPath("$.error").value("Ya existen 10 fotografías para esta no conformidad, el máximo permitido"));
        MC.responder("POST", NC + "/7/adjuntos", 404, "{\"mensaje\":\"No conformidad no encontrada\"}");
        subir(ana, adjunto("EVIDENCIA_FOTO", "image/png", "a.png", PNG)).andExpect(jsonPath("$.error").value("No conformidad no encontrada"));
        MC.responder("POST", NC + "/7/adjuntos", 500, "boom");
        subir(ana, adjunto("EVIDENCIA_FOTO", "image/png", "a.png", PNG)).andExpect(jsonPath("$.error").value("Error al comunicarse con la API de Mejora Continua"));
        assertThat(MC.peticiones()).hasSize(6);
    }

    // ------------------------------------------------------------------------------- adjuntos.list (saneo del nombre)

    @Test
    void adjuntosListSaneaNombreArchivoPorqueLaVistaLoInsertaEnInnerHtml() throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder(NC + "/7/adjuntos", 200, "[{\"id\":1,\"tipo\":\"CAUSA_RAIZ_PDF\",\"nombreArchivo\":\"<img src=x onerror=alert(1)>\\\"'.pdf\",\"tipoMime\":\"application/pdf\","
                + "\"tamanoBytes\":10,\"subidoPor\":\"Ana\"},{\"id\":2,\"tipo\":\"EVIDENCIA_FOTO\",\"nombreArchivo\":\"foto normal ñ.png\"},{\"id\":3,\"tipo\":\"EVIDENCIA_FOTO\"}]");
        JsonNode j = json(accion(ana, "faret.nc.adjuntos.list", Map.of("id", 7)).andExpect(jsonPath("$.ok").value(true)).andReturn());
        assertThat(j.at("/data/0/nombreArchivo").asString()).doesNotContain("<").doesNotContain(">").doesNotContain("\"").endsWith(".pdf");
        assertThat(j.at("/data/0/id").asInt()).isEqualTo(1);
        assertThat(j.at("/data/0/tipo").asString()).isEqualTo("CAUSA_RAIZ_PDF");
        assertThat(j.at("/data/0/subidoPor").asString()).isEqualTo("Ana");
        assertThat(j.at("/data/1/nombreArchivo").asString()).isEqualTo("foto normal ñ.png");
        assertThat(j.at("/data/2/nombreArchivo").isMissingNode()).isTrue();
    }

    // ------------------------------------------------------------------------------- roles, empresa, módulo, sesión

    @Test
    void rolesEmpresaIdentidadYAuditoriaDeLaPolitica() {
        for (String a : ESCRITURAS) {
            Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(a)).findFirst().orElseThrow();
            assertThat(((List<?>) d.get("roles")).stream().map(Object::toString).sorted().toList())
                    .as(a).containsExactly("admin", "admin_ti", "calidad", "consulta", "inspector");
            assertThat(d.get("empresas")).isEqualTo(List.of("FARET"));
            assertThat(d.get("escritura")).isEqualTo(true);
            for (String rol : List.of("ADMIN", "ADMIN_TI", "CALIDAD", "CONSULTA", "INSPECTOR")) {
                assertThat(policy.evaluar(a, usuario("FARET", rol))).as(a + " " + rol).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            }
            assertThat(policy.evaluar(a, usuario("FARET", "operador"))).as(a).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
            for (String rol : List.of("admin", "admin_ti", "operador")) {
                assertThat(policy.evaluar(a, usuario("INNPACK", rol))).as(a + " " + rol).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
            }
            assertThat(PermisosModulo.esLectura(a)).as(a).isFalse();
        }
        assertThat(BridgeController.ACCIONES_ARCHIVO).contains(SUBIR).doesNotContain(SEGUIMIENTO, ACCION, ANALISIS);
    }

    @Test
    void sesionInnpackRolesYModuloCruzadosNoLlamanALaApi() throws Exception {
        MockHttpSession operador = loginInnpack("operador1");
        MockHttpSession adminTiInnpack = loginInnpack("adminti1");
        for (MockHttpSession s : List.of(operador, adminTiInnpack)) {
            accion(s, SEGUIMIENTO, Map.of("id", 7, "comentario", "c")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
            accion(s, ACCION, accionValida()).andExpect(status().isForbidden());
            accion(s, ANALISIS, analisisValido()).andExpect(status().isForbidden());
            subir(s, adjunto("CAUSA_RAIZ_PDF", "application/pdf", "a.pdf", PDF)).andExpect(status().isForbidden());
        }
        // Permisos por módulo de Photino: solo faret-nc con EDITAR; otro módulo o ninguno se rechaza.
        MockHttpSession ana = login("ana");
        for (String modulo : List.of("faret-data", "faret", "faret-talleres-externos", "faret-nc-internas", "inventado")) {
            mockMvc.perform(post("/api/v1/bridge").session(ana).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"" + SEGUIMIENTO + "\",\"_modulo\":\"" + modulo + "\",\"id\":7,\"comentario\":\"c\"}")).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/v1/bridge").session(ana).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"" + SEGUIMIENTO + "\",\"id\":7,\"comentario\":\"c\"}")).andExpect(status().isForbidden());
        assertThat(MC.peticiones()).isEmpty();
        // Los 5 roles FARET pueden escribir (CONSULTA e INSPECTOR incluidos, como en Photino).
        MC.responder(NC + "/7", 200, "{\"id\":7}");
        MC.responder("POST", NC + "/7/seguimiento", 200, "{\"id\":1}");
        for (String u : List.of("admin", "ana", "clara", "tecnico", "ines")) {
            accion(login(u), SEGUIMIENTO, Map.of("id", 7, "comentario", "c")).andExpect(jsonPath("$.ok").value(true));
        }
        assertThat(MC.cuerpos().stream().filter(c -> c.startsWith("{\"comentario\""))
                .map(c -> mapper.readTree(c).get("autor").asString())).containsExactly("Admin Faret", "Ana Calidad", "Clara Consulta", "Tecnico TI", "Ines Inspector");
    }

    @Test
    void sinSesionOSinCsrfNoSeEscribe() throws Exception {
        String cuerpo = "{\"action\":\"" + SEGUIMIENTO + "\",\"_modulo\":\"faret-nc\",\"id\":7,\"comentario\":\"c\"}";
        mockMvc.perform(post("/api/v1/bridge").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isUnauthorized());
        MockHttpSession ana = login("ana");
        for (String a : List.of(SEGUIMIENTO, ACCION, ANALISIS)) {
            mockMvc.perform(post("/api/v1/bridge").session(ana).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"" + a + "\",\"_modulo\":\"faret-nc\",\"id\":7,\"comentario\":\"c\"}")).andExpect(status().isForbidden());
        }
        mockMvc.perform(post(RUTA_ARCHIVO).session(ana).contentType(MediaType.APPLICATION_JSON)
                .with(r -> {
                    r.setRequestedSessionId(ana.getId());
                    return r;
                })
                .content(mapper.writeValueAsString(adjunto("CAUSA_RAIZ_PDF", "application/pdf", "a.pdf", PDF)))).andExpect(status().isForbidden());
        mockMvc.perform(post(RUTA_ARCHIVO).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(adjunto("CAUSA_RAIZ_PDF", "application/pdf", "a.pdf", PDF)))).andExpect(status().isUnauthorized());
        assertThat(MC.peticiones()).isEmpty();
    }

    @Test
    void dosSesionesCadaEscrituraConSuPropioAutor() throws Exception {
        MockHttpSession ana = login("ana");
        MockHttpSession admin = login("admin");
        MC.responder(NC + "/7", 200, "{\"id\":7}");
        MC.responder("POST", NC + "/7/seguimiento", 200, "{\"id\":1}");
        MC.responder("POST", NC + "/7/acciones", 200, "{\"id\":2}");
        MC.responder("POST", NC + "/7/adjuntos", 200, "{\"id\":3}");
        for (int i = 0; i < 4; i++) {
            MockHttpSession s = i % 2 == 0 ? ana : admin;
            accion(s, SEGUIMIENTO, Map.of("id", 7, "comentario", "c" + i, "autor", i % 2 == 0 ? "Admin Faret" : "Ana Calidad")).andExpect(jsonPath("$.ok").value(true));
            accion(s, ACCION, accionValida()).andExpect(jsonPath("$.ok").value(true));
            subir(s, adjunto("EVIDENCIA_FOTO", "image/png", "f" + i + ".png", PNG)).andExpect(jsonPath("$.ok").value(true));
        }
        List<String> cuerpos = MC.cuerpos();
        List<String> peticiones = MC.peticiones();
        for (int i = 0; i < peticiones.size(); i++) {
            if (!peticiones.get(i).startsWith("POST")) {
                continue;
            }
            JsonNode c = mapper.readTree(cuerpos.get(i));
            String autor = c.has("autor") ? c.get("autor").asString() : c.has("creadoPor") ? c.get("creadoPor").asString() : c.get("subidoPor").asString();
            // Orden de las iteraciones: 0 y 2 son Ana; 1 y 3 son Admin (3 POST por iteración).
            int iteracion = i / 4;
            assertThat(autor).isEqualTo(iteracion % 2 == 0 ? "Ana Calidad" : "Admin Faret");
        }
        assertThat(MC.authorizations()).containsOnlyNulls();
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void auditoriaConRecursoEIdSinContenido(CapturedOutput salida) throws Exception {
        MockHttpSession ana = login("ana");
        MC.responder(NC + "/7", 200, "{\"id\":7}");
        MC.responder("POST", NC + "/7/seguimiento", 200, "{\"id\":55}");
        accion(ana, SEGUIMIENTO, Map.of("id", 7, "comentario", "Comentario confidencial 12.345.678-9")).andExpect(jsonPath("$.ok").value(true));
        MC.responder("POST", NC + "/7/acciones", 200, "{\"id\":12}");
        accion(ana, ACCION, accionValida("descripcion", "Descripción reservada")).andExpect(jsonPath("$.ok").value(true));
        MC.responder("POST", NC + "/7/adjuntos", 200, "{\"id\":21}");
        subir(ana, adjunto("CAUSA_RAIZ_PDF", "application/pdf", "secreto.pdf", PDF)).andExpect(jsonPath("$.ok").value(true));
        MC.responder("GET", NC + "/7/analisis", 404, SIN_ANALISIS_MC);
        MC.responder("POST", NC + "/7/analisis", 200, "{\"id\":3}");
        leerAnalisis(ana, 7);
        accion(ana, ANALISIS, analisisValido()).andExpect(jsonPath("$.ok").value(true));
        MC.responder("GET", NC + "/7/analisis", 200, "{\"id\":3,\"metodologia\":\"MIXTA\",\"problemaDetectado\":\"P\",\"causaRaiz\":\"C\"}");
        MC.responder("PUT", NC + "/7/analisis", 400, "{\"mensaje\":\"x\"}");
        leerAnalisis(ana, 7);
        accion(ana, ANALISIS, analisisValido()).andExpect(jsonPath("$.ok").value(false));
        accion(ana, SEGUIMIENTO, Map.of("id", 7, "comentario", "<b>x</b>")).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=ana empresa=FARET accion=faret\\.nc\\.seguimiento\\.crear "
                + "recurso=faret:nc:7:seguimiento:55 resultado=OK ms=\\d+");
        assertThat(log).containsPattern("accion=faret\\.nc\\.acciones\\.crear recurso=faret:nc:7:accion:12 resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.nc\\.adjuntos\\.subir recurso=faret:nc:7:adjunto:21 resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.nc\\.analisis\\.guardar recurso=faret:nc:7:analisis:3:NUEVO resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.nc\\.analisis\\.guardar recurso=faret:nc:7:analisis:REEMPLAZO resultado=ERROR");
        assertThat(log).containsPattern("accion=faret\\.nc\\.seguimiento\\.crear recurso=faret:nc:7:seguimiento resultado=ERROR");
        assertThat(log).doesNotContain("12.345.678-9", "Descripción reservada", "secreto.pdf", PDF, "<b>x</b>", PASS);
    }

    // ------------------------------------------------------------------------------- helpers

    private static Map<String, Object> mapa(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Acción válida para la NC 7 con `campo` = `valor` (pisa o agrega). */
    private static Map<String, Object> accionValida(String campo, Object valor) {
        Map<String, Object> m = mapa("id", 7, "descripcion", "Capacitar al personal", "responsable", "Pedro Soto", "fechaLimite", "2026-12-31", "prioridad", "MEDIA");
        m.put(campo, valor);
        return m;
    }

    private static Map<String, Object> accionValida() {
        return mapa("id", 7, "descripcion", "Capacitar al personal", "responsable", "Pedro Soto", "fechaLimite", "2026-12-31", "prioridad", "MEDIA");
    }

    private static Map<String, Object> analisisValido() {
        return analisisValido(7);
    }

    private static Map<String, Object> analisisValido(int ncId) {
        return mapa("id", ncId, "metodologia", "CINCO_PORQUES", "problemaDetectado", " Burbujas en el sello ", "porque1", "Calor", "porque2", "",
                "causaRaiz", "Temperatura alta", "conclusion", "Ajustar");
    }

    private static Map<String, Object> analisisValido(String campo, Object valor) {
        Map<String, Object> m = analisisValido(7);
        m.put(campo, valor);
        return m;
    }

    private static Map<String, Object> adjunto(String tipo, String mime, String nombre, Object base64) {
        return mapa("id", 7, "tipo", tipo, "nombreArchivo", nombre, "tipoMime", mime, "contenidoBase64", base64, "subidoPor", "Falso Autor");
    }

    private ResultActions leerAnalisis(MockHttpSession sesion, int ncId) throws Exception {
        return accion(sesion, "faret.nc.analisis.get", Map.of("id", ncId));
    }

    private static SessionUser usuario(String empresa, String rol) {
        return new SessionUser(1, "u", "U", rol, empresa, "t", Instant.now(), Instant.now().plusSeconds(60));
    }

    private ResultActions accion(MockHttpSession sesion, String accion, Map<String, Object> campos) throws Exception {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", accion);
        p.put("_modulo", "faret-nc");
        campos.forEach((k, v) -> p.set(k, v == null ? mapper.nullNode() : mapper.valueToTree(v)));
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(p.toString()));
    }

    private ResultActions subir(MockHttpSession sesion, Map<String, Object> campos) throws Exception {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", SUBIR);
        p.put("_modulo", "faret-nc");
        campos.forEach((k, v) -> p.set(k, v == null ? mapper.nullNode() : mapper.valueToTree(v)));
        return mockMvc.perform(post(RUTA_ARCHIVO).session(sesion).with(csrf())
                .with(r -> {
                    r.setRequestedSessionId(sesion.getId());
                    return r;
                })
                .contentType(MediaType.APPLICATION_JSON).content(p.toString()));
    }

    private MockHttpSession login(String identificador) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/faret/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.83.0." + IP.getAndIncrement());
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
                            r.setRemoteAddr("10.83.1." + IP.getAndIncrement());
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
            Path dir = Files.createTempDirectory("qcc-web-fixture-f3");
            Files.writeString(dir.resolve("index.html"), "<!doctype html><title>fixture</title>");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
