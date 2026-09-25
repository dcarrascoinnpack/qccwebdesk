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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Fase 3c — TERCERA ESCRITURA: noConformidades.analisis.guardar. A diferencia de 3a/3b SOBRESCRIBE el análisis
 * vigente (upsert sin versión en la API). Identidad `usuario` → sesión; textos de causa raíz → lista blanca y
 * límites del esquema; detección de lost update por huella de lectura en la sesión. API SIMULADA con estado.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3cTest {

    private static final String GUARDAR = "noConformidades.analisis.guardar";
    private static final String BASE = "/api/no-conformidades";
    private static final String CONFLICTO = "El análisis fue modificado por otra persona desde que lo abriste. "
            + "Cierra y vuelve a abrir el análisis para ver la versión actual antes de guardar (no se guardó nada).";
    private static final String SIN_LEER = "Abre el análisis de la no conformidad antes de guardarlo.";
    private static final String HTML = "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveAnalisis#2026";
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

    /** Payload exacto de _guardarAnalisis de Photino (los vacíos viajan como ""), con cambios. */
    private String payload(Map<String, Object> cambios) {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", GUARDAR);
        p.put("id", 501);
        p.put("usuario", "Operador Uno");
        p.put("metodologia", "CINCO_PORQUES");
        p.put("problemaDetectado", "Registro corrido en impresión");
        p.put("porque1", "La tinta se secó");
        p.put("porque2", "El rodillo estaba gastado");
        p.put("porque3", "");
        p.put("porque4", "");
        p.put("porque5", "");
        p.put("causaRaiz", "Falta de mantención preventiva");
        p.put("conclusion", "Programar mantención mensual");
        cambios.forEach((k, v) -> {
            if (v == Quitar.CAMPO) {
                p.remove(k);
            } else {
                p.set(k, mapper.valueToTree(v));
            }
        });
        return p.toString();
    }

    private enum Quitar { CAMPO }

    // ------------------------------------------------------ guardado inicial y sobrescritura

    @Test
    void guardadoInicialSinAnalisisPrevio(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        leer(s, 503).andExpect(jsonPath("$.data").value((Object) null));
        JsonNode r = json(guardar(s, payload(Map.of("id", 503))).andExpect(status().isOk()).andReturn());
        assertThat(r.get("ok").asBoolean()).isTrue();
        assertThat(r.at("/data/id").asInt()).isEqualTo(603);
        assertThat(API.peticionesNoConformidades()).containsExactly("GET " + BASE + "/503/analisis",
                "GET " + BASE + "/503", "GET " + BASE + "/503/analisis", "PUT " + BASE + "/503/analisis");
        SeguimientoRecibido put = unico();
        assertThat(mapper.readTree(put.cuerpo())).isEqualTo(mapper.readTree("{\"metodologia\":\"CINCO_PORQUES\","
                + "\"problemaDetectado\":\"Registro corrido en impresión\",\"porque1\":\"La tinta se secó\",\"porque2\":\"El rodillo estaba gastado\","
                + "\"porque3\":\"\",\"porque4\":\"\",\"porque5\":\"\",\"causaRaiz\":\"Falta de mantención preventiva\","
                + "\"conclusion\":\"Programar mantención mensual\",\"usuario\":\"Operador Uno\"}"));
        assertThat(API.analisisDe(503).get("creadoPor").asString()).isEqualTo("Operador Uno");
        assertThat(salida.getAll()).contains("accion=noConformidades.analisis.guardar recurso=nc:503:analisis:603:NUEVO resultado=OK");
    }

    @Test
    void sobrescrituraDelAnalisisExistenteYRelecturaMuestraLoGuardado(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        leer(s, 501).andExpect(jsonPath("$.data.problemaDetectado").value("Registro corrido"));
        guardar(s, payload(Map.of("problemaDetectado", "Nuevo problema ñ", "causaRaiz", "Causa nueva"))).andExpect(jsonPath("$.ok").value(true));
        // El análisis anterior se reemplaza EN SITIO (mismo id): la API no guarda historial.
        JsonNode r = json(leer(s, 501).andReturn());
        assertThat(r.at("/data/id").asInt()).isEqualTo(7);
        assertThat(r.at("/data/problemaDetectado").asString()).isEqualTo("Nuevo problema ñ");
        assertThat(r.at("/data/causaRaiz").asString()).isEqualTo("Causa nueva");
        assertThat(r.at("/data/actualizadoPor").asString()).isEqualTo("Operador Uno");
        assertThat(r.at("/data/creadoPor").asString()).isEqualTo("María");
        assertThat(salida.getAll()).contains("recurso=nc:501:analisis:7:REEMPLAZO resultado=OK");
    }

    // ------------------------------------------------------ identidad vs negocio

    @Test
    void identidadFalsificadaSeReemplazaYLosTextosDeNegocioSeConservan() throws Exception {
        MockHttpSession s = login("operador1");
        leer(s, 501);
        guardar(s, payload(Map.of("usuario", "Admin Uno", "conclusion", "Responsable: Admin Uno revisará"))).andExpect(jsonPath("$.ok").value(true));
        JsonNode c = mapper.readTree(unico().cuerpo());
        assertThat(c.get("usuario").asString()).isEqualTo("Operador Uno");
        assertThat(c.get("conclusion").asString()).isEqualTo("Responsable: Admin Uno revisará");
        assertThat(unico().sub()).isEqualTo(10);
    }

    @Test
    void camposInesperadosIncluidoAnalisisIdSeRechazan() throws Exception {
        MockHttpSession s = login("operador1");
        leer(s, 501);
        for (String extra : List.of("autor", "creadoPor", "actualizadoPor", "usuarioId", "analisisId", "empresa", "rol", "data", "creadoEn")) {
            guardar(s, payload(Map.of(extra, "x"))).andExpect(jsonPath("$.error").value("Campo no permitido: " + extra));
        }
        assertThat(API.analisisRecibidos()).isEmpty();
    }

    // ---------------------------------------------------------------- validaciones

    @Test
    void obligatoriosVaciosYNulosSegunLaApi() throws Exception {
        MockHttpSession s = login("operador1");
        leer(s, 501);
        guardar(s, payload(Map.of("problemaDetectado", "  "))).andExpect(jsonPath("$.error").value("Falta el problema detectado"));
        guardar(s, payload(Map.of("problemaDetectado", Quitar.CAMPO))).andExpect(jsonPath("$.error").value("Falta el problema detectado"));
        guardar(s, payload(Map.of("metodologia", ""))).andExpect(jsonPath("$.error").value("Falta la metodología"));
        guardar(s, payload(Map.of("metodologia", "OTRA"))).andExpect(jsonPath("$.error").value("Metodología inválida. Valores permitidos: CINCO_PORQUES, ISHIKAWA, MIXTA"));
        guardar(s, payload(Map.of("metodologia", "cinco_porques"))).andExpect(jsonPath("$.ok").value(false));
        for (Object malo : new Object[] {123, true, Map.of("a", 1), List.of("x")}) {
            guardar(s, payload(Map.of("porque1", malo))).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        }
        assertThat(API.analisisRecibidos()).isEmpty();
        // Opcionales: "" se conserva como "" (así los manda Photino); null o ausente → null.
        Map<String, Object> m = new HashMap<>();
        m.put("porque5", null);
        m.put("conclusion", Quitar.CAMPO);
        m.put("metodologia", "ISHIKAWA");
        guardar(s, payload(m)).andExpect(jsonPath("$.ok").value(true));
        JsonNode c = mapper.readTree(unico().cuerpo());
        assertThat(c.get("porque3").asString()).isEmpty();
        assertThat(c.get("porque5").isNull()).isTrue();
        assertThat(c.get("conclusion").isNull()).isTrue();
        assertThat(c.get("metodologia").asString()).isEqualTo("ISHIKAWA");
    }

    @Test
    void limitesRealesDelEsquema() throws Exception {
        MockHttpSession s = login("operador1");
        leer(s, 501);
        guardar(s, payload(Map.of("porque1", "x".repeat(501)))).andExpect(jsonPath("$.error").value("El campo porque1 supera el máximo de 500 caracteres."));
        // NVARCHAR(500) cuenta unidades UTF-16: un emoji ocupa 2.
        guardar(s, payload(Map.of("porque2", "😀".repeat(251)))).andExpect(jsonPath("$.error").value("El campo porque2 supera el máximo de 500 caracteres."));
        guardar(s, payload(Map.of("problemaDetectado", "ñ".repeat(32_768)))) // 65.536 bytes UTF-8
                .andExpect(jsonPath("$.error").value("El campo problemaDetectado supera el máximo permitido (65.535 bytes)."));
        assertThat(API.analisisRecibidos()).isEmpty();
        guardar(s, payload(Map.of("porque1", "x".repeat(500), "porque2", "😀".repeat(250), "problemaDetectado", "ñ".repeat(32_767) + "a")))
                .andExpect(jsonPath("$.ok").value(true));
        assertThat(API.analisisRecibidos()).hasSize(1);
    }

    @Test
    void utf8MultilineaYUnaLinea() throws Exception {
        MockHttpSession s = login("operador1");
        leer(s, 501);
        String problema = "Línea 1: ñandú — «ok» ✓ 😀\nLínea 2: a < b, 5<6, R&D\r\n\tsangría";
        guardar(s, payload(Map.of("problemaDetectado", problema, "causaRaiz", "Causa\nmultilínea", "conclusion", "Fin\n")))
                .andExpect(jsonPath("$.ok").value(true));
        JsonNode c = mapper.readTree(unico().cuerpo());
        assertThat(c.get("problemaDetectado").asString()).isEqualTo(problema);
        assertThat(c.get("causaRaiz").asString()).isEqualTo("Causa\nmultilínea");
        // Los "por qué" son inputs de una línea: sin saltos.
        leer(s, 501);
        guardar(s, payload(Map.of("porque1", "uno\ndos"))).andExpect(jsonPath("$.error").value("El texto contiene caracteres no permitidos."));
        guardar(s, payload(Map.of("problemaDetectado", "x\u0000y"))).andExpect(jsonPath("$.error").value("El texto contiene caracteres no permitidos."));
    }

    @Test
    void htmlYScriptEnCualquierCampoSeRechaza() throws Exception {
        MockHttpSession s = login("operador1");
        leer(s, 501);
        for (String campo : List.of("problemaDetectado", "porque1", "porque3", "porque5", "causaRaiz", "conclusion")) {
            for (String malo : List.of("<script>alert(1)</script>", "x <img src=x onerror=alert(1)>", "</textarea><b>")) {
                guardar(s, payload(Map.of(campo, malo))).andExpect(jsonPath("$.error").value(HTML));
            }
        }
        assertThat(API.analisisRecibidos()).isEmpty();
        guardar(s, payload(Map.of("causaRaiz", "&lt;b&gt; como texto"))).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void ncInexistenteEIdInvalido() throws Exception {
        MockHttpSession s = login("operador1");
        guardar(s, payload(Map.of("id", 404))).andExpect(jsonPath("$.error").value("No conformidad no encontrada"));
        for (Object malo : new Object[] {0, -1, "abc"}) {
            guardar(s, payload(Map.of("id", malo))).andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
        }
        assertThat(API.analisisRecibidos()).isEmpty();
    }

    // ------------------------------------------------------ lost update (sin versión en la API)

    @Test
    void sinLecturaPreviaNoSeGuarda() throws Exception {
        MockHttpSession s = login("operador1");
        guardar(s, payload(Map.of())).andExpect(jsonPath("$.error").value(SIN_LEER));
        assertThat(API.analisisRecibidos()).isEmpty();
        // Tras guardar hay que releer: no se puede encadenar otro guardado "a ciegas" sobre lo recién escrito.
        leer(s, 501);
        guardar(s, payload(Map.of())).andExpect(jsonPath("$.ok").value(true));
        guardar(s, payload(Map.of("conclusion", "otra"))).andExpect(jsonPath("$.error").value(SIN_LEER));
        assertThat(API.analisisRecibidos()).hasSize(1);
    }

    @Test
    void usuarioAGuardaVersionAntiguaDespuesDeQueBModificoConflictoSinEscribir() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        // 1. A abre la NC.  2. B abre y modifica el análisis.
        leer(a, 501);
        leer(b, 501);
        guardar(b, payload(Map.of("causaRaiz", "Versión de B"))).andExpect(jsonPath("$.ok").value(true));
        // 3. A guarda su versión antigua → conflicto; NO llega a la API.
        guardar(a, payload(Map.of("causaRaiz", "Versión de A"))).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(CONFLICTO));
        assertThat(API.analisisRecibidos()).hasSize(1);
        assertThat(API.analisisDe(501).get("causaRaiz").asString()).isEqualTo("Versión de B");
        // A relee (ve lo de B) y recién entonces puede decidir sobrescribir.
        JsonNode visto = json(leer(a, 501).andReturn());
        assertThat(visto.at("/data/causaRaiz").asString()).isEqualTo("Versión de B");
        assertThat(visto.at("/data/actualizadoPor").asString()).isEqualTo("Admin Uno");
        guardar(a, payload(Map.of("causaRaiz", "Versión de A tras ver B"))).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.analisisDe(501).get("causaRaiz").asString()).isEqualTo("Versión de A tras ver B");
        assertThat(API.analisisDe(501).get("actualizadoPor").asString()).isEqualTo("Operador Uno");
    }

    @Test
    void creacionConcurrenteDelPrimerAnalisisTambienEsConflicto() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("adminti1");
        leer(a, 503);
        leer(b, 503);
        guardar(b, payload(Map.of("id", 503, "problemaDetectado", "Primero B"))).andExpect(jsonPath("$.ok").value(true));
        guardar(a, payload(Map.of("id", 503, "problemaDetectado", "Primero A"))).andExpect(jsonPath("$.error").value(CONFLICTO));
        assertThat(API.analisisDe(503).get("problemaDetectado").asString()).isEqualTo("Primero B");
    }

    @Test
    void lasLecturasSonDeCadaSesion() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        leer(a, 501);
        guardar(b, payload(Map.of())).andExpect(jsonPath("$.error").value(SIN_LEER)); // B nunca leyó
        assertThat(API.analisisRecibidos()).isEmpty();
    }

    // ---------------------------------------------------------------- autorización y sesiones

    @Test
    void rolesYPolitica() throws Exception {
        MockHttpSession c = login("consulta1");
        guardar(c, payload(Map.of())).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(API.peticionesNoConformidades()).isEmpty();
        for (String u : List.of("admin1", "adminti1")) {
            MockHttpSession s = login(u);
            leer(s, 501);
            guardar(s, payload(Map.of())).andExpect(jsonPath("$.ok").value(true));
        }
        assertThat(policy.evaluar(GUARDAR, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(GUARDAR)).findFirst().orElseThrow();
        assertThat(d.get("escritura")).isEqualTo(true);
        assertThat(d.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
        assertThat(d.get("identidad")).isEqualTo(Map.of("usuario", IdentityOverride.Fuente.NOMBRE_COMPLETO));
    }

    @Test
    void csrfY401() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        leer(a, 501);
        mockMvc.perform(post("/api/v1/bridge").session(a).contentType(MediaType.APPLICATION_JSON).content(payload(Map.of())))
                .andExpect(status().isForbidden());
        assertThat(API.analisisRecibidos()).isEmpty();
        API.revocarTokens(10);
        guardar(a, payload(Map.of())).andExpect(status().isUnauthorized());
        assertThat(a.isInvalid()).isTrue();
        leer(b, 501);
        guardar(b, payload(Map.of())).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.analisisRecibidos()).hasSize(1);
    }

    @Test
    void errorDeNegocioYErrorInternoSinReintento() throws Exception {
        MockHttpSession s = login("operador1");
        leer(s, 501);
        guardar(s, payload(Map.of("problemaDetectado", "ERROR_API"))).andExpect(jsonPath("$.error").value("No se pudo guardar el análisis"));
        leer(s, 777);
        guardar(s, payload(Map.of("id", 777))).andExpect(jsonPath("$.error").value("Error al comunicarse con la API Innpack"));
        assertThat(API.analisisRecibidos()).hasSize(2);
    }

    @Test
    void dosSesionesEnParaleloSobreNcDistintasConSuUsuario() throws Exception {
        MockHttpSession a = login("operador1");
        MockHttpSession b = login("admin1");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tareas = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                int nc = 510 + i;
                boolean esA = i % 2 == 0;
                tareas.add(() -> {
                    MockHttpSession s = esA ? a : b;
                    leer(s, nc).andExpect(status().isOk());
                    return json(guardar(s, payload(Map.of("id", nc, "usuario", "Falso"))).andReturn()).get("ok").asBoolean();
                });
            }
            for (Future<Boolean> f : pool.invokeAll(tareas)) {
                assertThat(f.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        for (SeguimientoRecibido r : API.analisisRecibidos()) {
            assertThat(mapper.readTree(r.cuerpo()).get("usuario").asString()).isEqualTo(r.sub() == 10 ? "Operador Uno" : "Admin Uno");
        }
        assertThat(API.analisisRecibidos()).hasSize(10);
    }

    @Test
    void auditoriaSinContenidoDelAnalisis(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        leer(s, 501);
        guardar(s, payload(Map.of("causaRaiz", "Dato confidencial del proveedor 99.999.999-9"))).andExpect(jsonPath("$.ok").value(true));
        guardar(s, payload(Map.of())).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=noConformidades\\.analisis\\.guardar "
                + "recurso=nc:501:analisis:7:REEMPLAZO resultado=OK ms=\\d+");
        assertThat(log).contains("recurso=nc:501:analisis resultado=ERROR");
        assertThat(log).doesNotContain("Dato confidencial", "Registro corrido en impresión", "Programar mantención", PASS, FakeInnpackApi.firmaDeToken(10));
    }

    // ------------------------------------------------------------------ helpers

    private SeguimientoRecibido unico() {
        List<SeguimientoRecibido> r = API.analisisRecibidos();
        assertThat(r).hasSize(1);
        return r.get(r.size() - 1);
    }

    private ResultActions leer(MockHttpSession s, int nc) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"noConformidades.analisis.get\",\"id\":" + nc + "}"));
    }

    private ResultActions guardar(MockHttpSession s, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.16.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-3c");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
