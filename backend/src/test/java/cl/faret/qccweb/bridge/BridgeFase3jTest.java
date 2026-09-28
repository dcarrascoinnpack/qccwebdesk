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
import cl.faret.qccweb.bridge.handlers.NoConformidadesBridgeHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
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
 * Fase 3j — noConformidades.create ("Nueva NC"). Foco: IDENTIDAD (creadoPor → sesión), ALCANCE (sin empresa/ambito ni
 * columnas que Photino INNPACK no manda), CABECERA recalculada en el gateway (severidad/título/descripción/proceso),
 * límites del esquema de no_conformidades. API SIMULADA: se verifica el cuerpo EXACTO que recibe upstream.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3jTest {

    private static final String CREAR = "noConformidades.create";
    private static final String MSG_HTML = "El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\").";
    private static final String MSG_CARACTERES = "El texto contiene caracteres no permitidos.";
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveNuevaNc#2026";
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

    /** Payload exacto de _guardarForm de Photino (Nueva NC), con overrides opcionales. */
    private String payload(Map<String, Object> cambios) {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", CREAR);
        p.put("creadoPor", "Operador Uno");
        p.put("fechaIngreso", "2026-09-28");
        p.put("npNv", "3996");
        p.put("cliente", "Viña Ñandú Ltda.");
        p.put("codigoProducto", "CP-40");
        p.put("producto", "Caja 40x30");
        p.put("familiaProducto", "Cajas");
        p.put("tipoPnc", "Cuarentena");
        p.put("nivel", "Mayor");
        p.put("categoriaDefecto", "Impresión");
        p.put("tipoFalla", "Corrimiento");
        p.put("impacto", "Calidad");
        p.put("cantRequerida", 1000);
        p.put("cantRechazada", 120.5);
        p.putNull("cantRecuperada");
        p.putNull("pncReal");
        p.put("disposicion", "No aplica");
        p.putNull("cantDestruida");
        p.putNull("cantRepuesta");
        p.put("area", "Impresión");
        p.put("maquina", "Bobst 1");
        p.put("operador", "Pedro Soto");
        p.put("supervisor", "Ana Díaz");
        p.put("revisadoPor", "");
        p.putNull("fechaSalida");
        p.putNull("fechaFabricacion");
        p.put("descripcionDefecto", "Registro corrido\nen 2 colores");
        p.put("observacion", "");
        p.put("causaRaiz", "");
        p.put("accionesCorrectivas", "");
        p.put("verificacionSeguimiento", "");
        // Cabecera que arma Photino en el navegador (el gateway la recalcula).
        p.put("tipo", "INTERNA");
        p.put("origen", "AUDITORIA_INTERNA");
        p.put("titulo", "PNC 3996 - Caja 40x30");
        p.put("descripcion", "Impresión - Registro corrido\nen 2 colores");
        p.put("severidad", "MEDIA");
        p.put("proceso", "Cuarentena");
        p.put("fechaDeteccion", "2026-09-28");
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
    void altaExitosaConCuerpoExactoYCabeceraCalculada() throws Exception {
        JsonNode json = json(crear(login("operador1"), payload(Map.of())).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.at("/data/id").asInt()).isEqualTo(951);
        assertThat(json.at("/data/codigo").asString()).isEqualTo("NC-2026-951");
        assertThat(API.peticionesNoConformidades()).containsExactly("POST /api/no-conformidades");
        SeguimientoRecibido r = unica();
        assertThat(r.sub()).isEqualTo(10);
        assertThat(r.contentType()).startsWith("application/json");
        ObjectNode esperado = (ObjectNode) mapper.readTree(payload(Map.of()));
        esperado.remove("action");
        assertThat(mapper.readTree(r.cuerpo())).isEqualTo(esperado);
        // Mismo conjunto de claves que Photino: 30 campos + 7 de cabecera + creadoPor.
        assertThat(mapper.readTree(r.cuerpo()).propertyNames()).hasSize(38);
    }

    @Test
    void cabeceraFalsificadaSeRecalculaYSeveridadComoPhotino() throws Exception {
        MockHttpSession s = login("operador1");
        Map<String, Object> falso = new HashMap<>(Map.of("tipo", "EXTERNA", "origen", "CLIENTE", "titulo", "otro", "descripcion", "x",
                "severidad", "BAJA", "proceso", "Y", "fechaDeteccion", "2020-01-01"));
        crear(s, payload(falso)).andExpect(jsonPath("$.ok").value(true));
        JsonNode c = mapper.readTree(unica().cuerpo());
        assertThat(c.get("tipo").asString()).isEqualTo("INTERNA");
        assertThat(c.get("origen").asString()).isEqualTo("AUDITORIA_INTERNA");
        assertThat(c.get("titulo").asString()).isEqualTo("PNC 3996 - Caja 40x30");
        assertThat(c.get("descripcion").asString()).isEqualTo("Impresión - Registro corrido\nen 2 colores");
        assertThat(c.get("severidad").asString()).isEqualTo("MEDIA");
        assertThat(c.get("proceso").asString()).isEqualTo("Cuarentena");
        assertThat(c.get("fechaDeteccion").asString()).isEqualTo("2026-09-28");
        // _mapNivelASeveridad de Photino es por substring en mayúsculas: "Crítico" → "CRÍTICO" NO contiene "CRIT" → MEDIA
        // (paridad con Photino, documentado en la matriz); sin tilde → ALTA.
        assertThat(NoConformidadesBridgeHandler.severidadDeNivel("Crítico")).isEqualTo("MEDIA");
        assertThat(NoConformidadesBridgeHandler.severidadDeNivel("Critico")).isEqualTo("ALTA");
        assertThat(NoConformidadesBridgeHandler.severidadDeNivel("Menor")).isEqualTo("BAJA");
        assertThat(NoConformidadesBridgeHandler.severidadDeNivel("mayor")).isEqualTo("MEDIA");
        assertThat(NoConformidadesBridgeHandler.severidadDeNivel("Urgente")).isEqualTo("MEDIA");
        // proceso: tipoPnc || area || "PNC Nueva"; descripción sin categoría vacía.
        crear(s, payload(Map.of("tipoPnc", "", "area", "Troquelado"))).andExpect(jsonPath("$.ok").value(true));
        crear(s, payload(Map.of("tipoPnc", "", "area", ""))).andExpect(jsonPath("$.ok").value(true));
        crear(s, payload(Map.of("nivel", "Menor"))).andExpect(jsonPath("$.ok").value(true));
        List<SeguimientoRecibido> r = API.ncCreadasRecibidas();
        assertThat(mapper.readTree(r.get(1).cuerpo()).get("proceso").asString()).isEqualTo("Troquelado");
        assertThat(mapper.readTree(r.get(2).cuerpo()).get("proceso").asString()).isEqualTo("PNC Nueva");
        assertThat(mapper.readTree(r.get(3).cuerpo()).get("severidad").asString()).isEqualTo("BAJA");
    }

    @Test
    void creadoPorSiempreEsLaSesion() throws Exception {
        String[][] casos = {{"operador1", "Operador Uno"}, {"admin1", "Admin Uno"}, {"adminti1", "Admin TI"}};
        for (String[] c : casos) {
            crear(login(c[0]), payload(Map.of("creadoPor", "Otro Usuario"))).andExpect(jsonPath("$.ok").value(true));
        }
        crear(login("operador1"), payload(Map.of("creadoPor", Borrar.CAMPO))).andExpect(jsonPath("$.ok").value(true));
        List<SeguimientoRecibido> r = API.ncCreadasRecibidas();
        assertThat(r).hasSize(4);
        for (int k = 0; k < 3; k++) {
            assertThat(mapper.readTree(r.get(k).cuerpo()).get("creadoPor").asString()).isEqualTo(casos[k][1]);
        }
        assertThat(mapper.readTree(r.get(3).cuerpo()).get("creadoPor").asString()).isEqualTo("Operador Uno");
    }

    @Test
    void fechaIngresoAusenteEsHoyEnChileYFechasOpcionalesNull() throws Exception {
        crear(login("operador1"), payload(Map.of("fechaIngreso", ""))).andExpect(jsonPath("$.ok").value(true));
        JsonNode c = mapper.readTree(unica().cuerpo());
        String hoy = LocalDate.now(ZoneId.of("America/Santiago")).toString();
        assertThat(c.get("fechaIngreso").asString()).isEqualTo(hoy);
        assertThat(c.get("fechaDeteccion").asString()).isEqualTo(hoy);
        assertThat(c.get("fechaSalida").isNull()).isTrue();
        assertThat(c.get("cantRecuperada").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ lista blanca y alcance

    @Test
    void camposFueraDelFormularioDePhotinoSeRechazanSinLlamarUpstream() throws Exception {
        MockHttpSession s = login("admin1");
        for (String extra : List.of("empresa", "ambito", "reportadoPor", "norma", "areasSecundarias", "tiempoPerdidoHoras", "estado",
                "estadoGestion", "responsable", "id", "usuario", "codigo", "eliminado", "pctRecuperacion", "actualizadoPor")) {
            crear(s, payload(Map.of(extra, "INTERNA")))
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Campo no permitido: " + extra));
        }
        crear(s, payload(Map.of("<img src=x>", 1))).andExpect(jsonPath("$.error").value("Campo no permitido: ?"));
        assertThat(API.peticionesNoConformidades()).isEmpty();
    }

    @Test
    void obligatoriosDePhotino() throws Exception {
        MockHttpSession s = login("operador1");
        for (String campo : List.of("npNv", "cliente", "codigoProducto", "producto", "categoriaDefecto", "nivel", "descripcionDefecto")) {
            crear(s, payload(Map.of(campo, "   "))).andExpect(jsonPath("$.error").value(NoConformidadesBridgeHandler.MENSAJE_NC_OBLIGATORIOS));
            crear(s, payload(Map.of(campo, Borrar.CAMPO))).andExpect(jsonPath("$.error").value(NoConformidadesBridgeHandler.MENSAJE_NC_OBLIGATORIOS));
        }
        Map<String, Object> sinCantidad = new HashMap<>();
        sinCantidad.put("cantRequerida", null);
        crear(s, payload(sinCantidad)).andExpect(jsonPath("$.error").value(NoConformidadesBridgeHandler.MENSAJE_NC_OBLIGATORIOS));
        crear(s, payload(Map.of("cantRechazada", Borrar.CAMPO))).andExpect(jsonPath("$.error").value(NoConformidadesBridgeHandler.MENSAJE_NC_OBLIGATORIOS));
        assertThat(API.peticionesNoConformidades()).isEmpty();
    }

    // ------------------------------------------------------------------ límites del esquema

    @Test
    void largosDeColumnaYTituloCompuesto() throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, payload(Map.of("npNv", "9".repeat(101)))).andExpect(jsonPath("$.error").value("El campo npNv supera el máximo de 100 caracteres."));
        crear(s, payload(Map.of("nivel", "N".repeat(21)))).andExpect(jsonPath("$.error").value("El campo nivel supera el máximo de 20 caracteres."));
        crear(s, payload(Map.of("producto", "P".repeat(256)))).andExpect(jsonPath("$.error").value("El campo producto supera el máximo de 255 caracteres."));
        crear(s, payload(Map.of("impacto", "I".repeat(51)))).andExpect(jsonPath("$.error").value("El campo impacto supera el máximo de 50 caracteres."));
        crear(s, payload(Map.of("npNv", "9".repeat(100), "producto", "P".repeat(255))))
                .andExpect(jsonPath("$.error").value("El título de la no conformidad (PNC + NP/NV + producto) supera el máximo de 255 caracteres."));
        crear(s, payload(Map.of("observacion", "ñ".repeat(32_768)))).andExpect(jsonPath("$.error")
                .value("El campo observacion supera el máximo permitido (65.535 bytes)."));
        assertThat(API.peticionesNoConformidades()).isEmpty();
        crear(s, payload(Map.of("npNv", "9".repeat(100), "producto", "P".repeat(148), "nivel", "N".repeat(20))))
                .andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(unica().cuerpo()).get("titulo").asString()).hasSize(255);
    }

    @Test
    void htmlControlesYSaltosDeLinea() throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, payload(Map.of("cliente", "ACME <img src=x onerror=alert(1)>"))).andExpect(jsonPath("$.error").value(MSG_HTML));
        crear(s, payload(Map.of("observacion", "ok\n<script>alert(1)</script>"))).andExpect(jsonPath("$.error").value(MSG_HTML));
        crear(s, payload(Map.of("cliente", "ACME\nSur"))).andExpect(jsonPath("$.error").value(MSG_CARACTERES));
        crear(s, payload(Map.of("causaRaiz", "x\u0000y"))).andExpect(jsonPath("$.error").value(MSG_CARACTERES));
        crear(s, "{\"action\":\"" + CREAR + "\",\"npNv\":\"1\",\"cliente\":\"A \\ud800\"}").andExpect(jsonPath("$.error").value(MSG_CARACTERES));
        assertThat(API.peticionesNoConformidades()).isEmpty();
        // Textareas: saltos de línea permitidos; UTF-8 y comillas se conservan exactos.
        String utf8 = "Registro «corrido» — 5<6 & O'Higgins \"Sur\" ✓ 😀\r\nsegunda línea";
        crear(s, payload(Map.of("observacion", utf8))).andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(unica().cuerpo()).get("observacion").asString()).isEqualTo(utf8);
    }

    @Test
    void selectsTiposFechasYCantidades() throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, payload(Map.of("tipoPnc", "Otro"))).andExpect(jsonPath("$.error").value("Tipo PNC inválido."));
        crear(s, payload(Map.of("disposicion", "Quemar"))).andExpect(jsonPath("$.error").value("Disposición inválida."));
        for (Object malo : new Object[] {123, true, List.of("x"), Map.of("a", 1)}) {
            crear(s, payload(Map.of("cliente", malo))).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        }
        crear(s, payload(Map.of("cantRequerida", "1000"))).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        crear(s, payload(Map.of("cantRechazada", -1))).andExpect(jsonPath("$.error").value("La cantidad cantRechazada no es válida (0 a 9.999.999.999,99)."));
        crear(s, payload(Map.of("cantRequerida", 1e11))).andExpect(jsonPath("$.error").value("La cantidad cantRequerida no es válida (0 a 9.999.999.999,99)."));
        crear(s, payload(Map.of("fechaSalida", "2026-13-01"))).andExpect(jsonPath("$.error").value("La fecha fechaSalida no es válida (formato AAAA-MM-DD)."));
        crear(s, payload(Map.of("fechaIngreso", "28-09-2026"))).andExpect(jsonPath("$.error").value("La fecha fechaIngreso no es válida (formato AAAA-MM-DD)."));
        crear(s, payload(Map.of("fechaFabricacion", 20260928))).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        assertThat(API.peticionesNoConformidades()).isEmpty();
        crear(s, payload(Map.of("tipoPnc", "Rechazo Cliente", "disposicion", "Reposición y destrucción", "cantDestruida", 0,
                "cantRepuesta", 9999999999.99, "fechaSalida", "2026-02-28"))).andExpect(jsonPath("$.ok").value(true));
        JsonNode c = mapper.readTree(unica().cuerpo());
        assertThat(c.get("disposicion").asString()).isEqualTo("Reposición y destrucción");
        assertThat(c.get("cantRepuesta").decimalValue()).isEqualByComparingTo("9999999999.99");
        assertThat(c.get("proceso").asString()).isEqualTo("Rechazo Cliente");
    }

    // ------------------------------------------------------------------ autorización

    @Test
    void rolesPoliticaYAdjuntosSiguenDenegados() throws Exception {
        crear(login("consulta1"), payload(Map.of())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(API.peticionesNoConformidades()).isEmpty();
        assertThat(policy.evaluar(CREAR, usuario("FARET", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        assertThat(policy.evaluar(CREAR, usuario("INNPACK", "consulta"))).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
        Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(CREAR)).findFirst().orElseThrow();
        assertThat(d.get("escritura")).isEqualTo(true);
        assertThat(d.get("roles")).isEqualTo(List.of("admin", "admin_ti", "operador"));
        assertThat(d.get("identidad")).isEqualTo(Map.of("creadoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO));
        // El flujo de Photino sube adjuntos después del alta con adjuntos.subir: solo por /api/v1/bridge/archivo (Fase 3k);
        // por el bridge normal se rechaza por ruta.
        MockHttpSession s = login("operador1");
        crear(s, "{\"action\":\"noConformidades.adjuntos.subir\",\"id\":951,\"tipo\":\"EVIDENCIA_FOTO\",\"nombreArchivo\":\"a.png\","
                + "\"tipoMime\":\"image/png\",\"contenidoBase64\":\"AA==\"}").andExpect(status().isForbidden());
        for (String otra : List.of("noConformidades.eliminar", "noConformidades.cerrar")) {
            assertThat(policy.accionesRegistradas()).doesNotContain(otra);
        }
        assertThat(API.peticionesNoConformidades()).isEmpty();
    }

    // ------------------------------------------------------------------ errores y auditoría

    @Test
    void errorDeNegocioYErrorInternoSinReintento() throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, payload(Map.of("cliente", "ERROR_API"))).andExpect(jsonPath("$.error").value("Falta el campo obligatorio: cliente"));
        crear(s, payload(Map.of("cliente", "ERROR_500"))).andExpect(jsonPath("$.error").value("Error al comunicarse con la API Innpack"));
        assertThat(API.ncCreadasRecibidas()).hasSize(2);
    }

    @Test
    void auditoriaConIdCreadoSinContenido(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        crear(s, payload(Map.of("cliente", "Cliente Sensible 12.345.678-9"))).andExpect(jsonPath("$.ok").value(true));
        crear(s, payload(Map.of("cliente", "ERROR_API"))).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=noConformidades\\.create "
                + "recurso=nc:951 resultado=OK ms=\\d+");
        assertThat(log).contains("accion=noConformidades.create recurso=nc:nueva resultado=ERROR");
        assertThat(log).doesNotContain("Cliente Sensible", "12.345.678-9", "Registro corrido", FakeInnpackApi.firmaDeToken(10), PASS);
    }

    // ------------------------------------------------------------------ helpers

    private SeguimientoRecibido unica() {
        List<SeguimientoRecibido> r = API.ncCreadasRecibidas();
        assertThat(r).hasSize(1);
        return r.get(0);
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.17.0." + IP.getAndIncrement());
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
            Path www = Files.createTempDirectory("qcc-web-fixture-3j");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
