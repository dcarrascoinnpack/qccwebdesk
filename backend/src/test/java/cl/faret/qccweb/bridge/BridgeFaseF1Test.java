package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeFaretApi;
import cl.faret.qccweb.auth.FakeFaretApi.Escritura;
import cl.faret.qccweb.auth.FakeInnpackApi;
import cl.faret.qccweb.auth.SessionUser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
 * Fase F1 — ESCRITURAS de catálogos FARET (Photino 1.8.15, 9e1b556, FaretHandler.cs): faret.catalogos.{areas,operadores,maquinas}.crear
 * y faret.pncCatalogos.{clientes,categoriasDefecto,tiposFalla,supervisores,revisores,familiasProducto,niveles,impactos}.crear contra
 * QualityControlFaret.Api con el Bearer del usuario de la sesión. TODA la API es un fake en localhost (FakeFaretApi).
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFaseF1Test {

    private static final FakeFaretApi QC = new FakeFaretApi(Clock.systemUTC());
    private static final FakeInnpackApi INNPACK = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveFaretF1#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    /** {catálogo, segmento de ruta, largo máximo de `nombre`}. */
    private static final List<Object[]> PNC = List.of(
            new Object[] {"clientes", "clientes", 150}, new Object[] {"categoriasDefecto", "categorias-defecto", 150},
            new Object[] {"tiposFalla", "tipos-falla", 150}, new Object[] {"supervisores", "supervisores", 150},
            new Object[] {"revisores", "revisores", 150}, new Object[] {"familiasProducto", "familias-producto", 50},
            new Object[] {"niveles", "niveles", 30}, new Object[] {"impactos", "impactos", 100});
    private static final List<String> ACCIONES_PNC = PNC.stream().map(c -> "faret.pncCatalogos." + c[0] + ".crear").toList();
    private static final List<String> ACCIONES_PLANTA = List.of("faret.catalogos.areas.crear", "faret.catalogos.operadores.crear",
            "faret.catalogos.maquinas.crear");

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
    }

    @AfterAll
    static void cerrar() {
        QC.close();
        INNPACK.close();
    }

    // ------------------------------------------------------------------------------- catálogos PNC

    @Test
    void cadaCatalogoPncVaASuRutaConSoloElNombreYElBearerDeLaSesion() throws Exception {
        MockHttpSession ana = login("ana");
        for (Object[] c : PNC) {
            JsonNode json = json(crear(ana, "faret.pncCatalogos." + c[0] + ".crear", Map.of("nombre", "  Viña   Ñandú Ltda. ")).andExpect(status().isOk()).andReturn());
            assertThat(json.get("ok").asBoolean()).isTrue();
            // El frontend (CatalogCombo) usa el ítem devuelto: {id, nombre, activo}.
            assertThat(json.get("data").toString()).isEqualTo("{\"id\":901,\"nombre\":\"Viña Ñandú Ltda.\",\"activo\":true}");
        }
        List<Escritura> e = QC.escrituras();
        assertThat(e).hasSize(PNC.size());
        for (int i = 0; i < PNC.size(); i++) {
            assertThat(e.get(i).metodo()).isEqualTo("POST");
            assertThat(e.get(i).uri()).isEqualTo("/api/pnc-catalogos/" + PNC.get(i)[1]);
            assertThat(e.get(i).sub()).isEqualTo(20);
            assertThat(e.get(i).contentType()).startsWith("application/json");
            // Sin "action", sin autor: el `creado_por` lo pone la API desde el sub del JWT.
            assertThat(e.get(i).cuerpo()).isEqualTo("{\"nombre\":\"Viña Ñandú Ltda.\"}");
        }
        assertThat(QC.lecturas()).isEmpty();
    }

    @Test
    void lasClavesFueraDeLaListaCerradaSeRechazanSinLlamarALaApi() throws Exception {
        MockHttpSession ana = login("ana");
        for (String extra : List.of("creadoPor", "creado_por", "autor", "usuario", "usuarioId", "empresa", "rol", "id", "activo", "areaId", "codigo", "data")) {
            crear(ana, "faret.pncCatalogos.clientes.crear", Map.of("nombre", "ACME", extra, "Otro"))
                    .andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value("Campo no permitido: " + extra));
        }
        crear(ana, "faret.pncCatalogos.clientes.crear", Map.of("nombre", "ACME", "<script>alert(1)</script>", 1))
                .andExpect(jsonPath("$.error").value("Campo no permitido: ?"));
        crear(ana, "faret.catalogos.areas.crear", Map.of("nombre", "Corte", "areaId", 3)).andExpect(jsonPath("$.error").value("Campo no permitido: areaId"));
        crear(ana, "faret.catalogos.operadores.crear", Map.of("areaId", 3, "nombre", "Pepe", "codigo", "X")).andExpect(jsonPath("$.error").value("Campo no permitido: codigo"));
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void nombreInvalidoSeRechazaSinLlamarALaApi() throws Exception {
        MockHttpSession ana = login("ana");
        for (String accion : List.of("faret.pncCatalogos.clientes.crear", "faret.pncCatalogos.niveles.crear", "faret.catalogos.areas.crear")) {
            for (Object vacio : new Object[] {"", "   ", "   ", null}) {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("nombre", vacio);
                crear(ana, accion, m).andExpect(jsonPath("$.error").value("Falta el nombre"));
            }
            crear(ana, accion, Map.of()).andExpect(jsonPath("$.error").value("Falta el nombre"));
            for (Object malo : new Object[] {123, true, List.of("x"), Map.of("nombre", "x")}) {
                crear(ana, accion, Map.of("nombre", malo)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
            }
            for (String html : List.of("<b>x</b>", "A <img src=x>", "</td>", "<!--x-->", "<svg/onload=1>")) {
                crear(ana, accion, Map.of("nombre", html)).andExpect(jsonPath("$.error")
                        .value("El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\")."));
            }
            for (String ctl : List.of("ACME\nSur", "ACME\r", "ACME\u0000", "\u0007ACME", "ACME\u007F", "ACME\u001B[31m")) {
                crear(ana, accion, Map.of("nombre", ctl)).andExpect(jsonPath("$.error").value("El texto contiene caracteres no permitidos."));
            }
        }
        // Sustitutos UTF-16 sueltos (JSON con \ud800).
        mockMvc.perform(post("/api/v1/bridge").session(ana).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"faret.pncCatalogos.clientes.crear\",\"_modulo\":\"faret-nc\",\"nombre\":\"A \\ud800 B\"}"))
                .andExpect(jsonPath("$.error").value("El texto contiene caracteres no permitidos."));
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void largoMaximoPorCatalogoComoLaApiEnUnidadesUtf16() throws Exception {
        MockHttpSession ana = login("ana");
        for (Object[] c : PNC) {
            int max = (Integer) c[2];
            String accion = "faret.pncCatalogos." + c[0] + ".crear";
            crear(ana, accion, Map.of("nombre", "x".repeat(max + 1))).andExpect(jsonPath("$.error").value("El valor no puede superar los " + max + " caracteres."));
            crear(ana, accion, Map.of("nombre", "😀".repeat(max / 2) + "a")).andExpect(jsonPath("$.error").value("El valor no puede superar los " + max + " caracteres."));
        }
        assertThat(QC.escrituras()).isEmpty();
        for (Object[] c : PNC) {
            int max = (Integer) c[2];
            // justo el máximo, tras colapsar espacios (el largo se mide sobre el valor normalizado, como la API)
            String justo = "b".repeat(max / 2) + "     " + "c".repeat(max / 2 - 1);
            crear(ana, "faret.pncCatalogos." + c[0] + ".crear", Map.of("nombre", "  " + justo + "  ")).andExpect(jsonPath("$.ok").value(true));
        }
        assertThat(QC.escrituras()).hasSize(PNC.size());
        assertThat(mapper.readTree(QC.escrituras().get(5).cuerpo()).get("nombre").asString()).isEqualTo("b".repeat(25) + " " + "c".repeat(24));
    }

    // ------------------------------------------------------------------------------- área

    @Test
    void areaGeneraElCodigoComoGenerarCodigoAreaDePhotino() throws Exception {
        MockHttpSession admin = login("admin");
        // Ejemplos de la tabla real que cita Photino ("PRE-PRENSA" -> "PRE_PRENSA", "EDICIÓN" -> "EDICI_N") y bordes del algoritmo.
        String[][] casos = {
            {"PRE-PRENSA", "PRE_PRENSA"}, {"EDICIÓN", "EDICI_N"}, {"Corte", "CORTE"}, {"  Pre   prensa  ", "PRE_PRENSA"},
            {"Área de Corte", "REA_DE_CORTE"}, {"ñandú", "AND"}, {"Línea 2 (nueva)", "L_NEA_2_NUEVA"}, {"straße", "STRA_E"},
            {"__a__", "A"}, {"Linea de produccion numero uno", "LINEA_DE_PRODUCCION"}, {"ABCDEFGHIJKLMNOPQRST", "ABCDEFGHIJKLMNOPQRST"},
            {"ABCDEFGHIJKLMNOPQRS-U", "ABCDEFGHIJKLMNOPQRS"}, {"ABCDEFGHIJKLMNOPQRSTUVWXYZ", "ABCDEFGHIJKLMNOPQRST"}, {"3D", "3D"},
        };
        for (String[] caso : casos) {
            QC.limpiarLecturas();
            JsonNode json = json(crear(admin, "faret.catalogos.areas.crear", Map.of("nombre", caso[0])).andExpect(status().isOk()).andReturn());
            assertThat(json.get("ok").asBoolean()).as(caso[0]).isTrue();
            Escritura e = QC.escrituras().get(0);
            assertThat(e.uri()).isEqualTo("/api/catalogos/areas");
            // Cuerpo exacto de CrearAreaAsync: new { codigo, nombre } (nombre ya con trim/colapso).
            assertThat(e.cuerpo()).as(caso[0]).isEqualTo("{\"codigo\":\"" + caso[1] + "\",\"nombre\":\"" + caso[0].strip().replaceAll("\\s+", " ") + "\"}");
            assertThat(json.at("/data/codigo").asString()).isEqualTo(caso[1]);
            assertThat(e.sub()).isEqualTo(10);
        }
    }

    @Test
    void areaSinLetrasNiNumerosYDemasiadoLarga() throws Exception {
        MockHttpSession admin = login("admin");
        for (String n : List.of("---", "ñ", "ÁÉÍ", "?!", "😀")) {
            crear(admin, "faret.catalogos.areas.crear", Map.of("nombre", n)).andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("El nombre debe tener al menos una letra o número"));
        }
        crear(admin, "faret.catalogos.areas.crear", Map.of("nombre", "a".repeat(101))).andExpect(jsonPath("$.error").value("El valor no puede superar los 100 caracteres."));
        assertThat(QC.escrituras()).isEmpty();
        crear(admin, "faret.catalogos.areas.crear", Map.of("nombre", "a".repeat(100))).andExpect(jsonPath("$.ok").value(true));
        assertThat(QC.escrituras()).hasSize(1);
    }

    @Test
    void areaRespuestaReproyectadaYDuplicadoDeLaApiSePropaga() throws Exception {
        MockHttpSession admin = login("admin");
        QC.respuestaEscritura("POST /api/catalogos/areas", 201, "{\"success\":true,\"message\":\"Área creada exitosamente.\",\"data\":{\"id\":7,"
                + "\"codigo\":\"CORTE\",\"nombre\":\"Corte\",\"activo\":true,\"createdAt\":\"2026-10-09T10:00:00\",\"passwordHash\":\"x\",\"token\":\"t\"}}");
        JsonNode json = json(crear(admin, "faret.catalogos.areas.crear", Map.of("nombre", "Corte")).andReturn());
        assertThat(json.get("data").toString()).isEqualTo("{\"id\":7,\"nombre\":\"Corte\",\"codigo\":\"CORTE\",\"createdAt\":\"2026-10-09T10:00:00\",\"activo\":true}");
        // Duplicado de código: la API devuelve el área existente (con otro nombre) y el gateway no inventa otra política.
        QC.respuestaEscritura("POST /api/catalogos/areas", 201, "{\"success\":true,\"data\":{\"id\":3,\"codigo\":\"CORTE\",\"nombre\":\"Corte Antiguo\",\"activo\":true}}");
        crear(admin, "faret.catalogos.areas.crear", Map.of("nombre", "CORTE!")).andExpect(jsonPath("$.data.id").value(3))
                .andExpect(jsonPath("$.data.nombre").value("Corte Antiguo"));
    }

    // ------------------------------------------------------------------------------- operadores y máquinas

    @Test
    void operadoresYMaquinasMandanAreaIdYNombre() throws Exception {
        MockHttpSession clara = login("clara");
        for (String c : List.of("operadores", "maquinas")) {
            for (Object area : new Object[] {7, " 8 ", "9"}) {
                QC.limpiarLecturas();
                JsonNode json = json(crear(clara, "faret.catalogos." + c + ".crear", Map.of("areaId", area, "nombre", "  Juan   Pérez ")).andExpect(status().isOk()).andReturn());
                assertThat(json.get("ok").asBoolean()).isTrue();
                Escritura e = QC.escrituras().get(0);
                assertThat(e.uri()).isEqualTo("/api/catalogos/" + c);
                int esperado = Integer.parseInt(area.toString().trim());
                assertThat(e.cuerpo()).isEqualTo("{\"areaId\":" + esperado + ",\"nombre\":\"Juan Pérez\"}");
                assertThat(e.sub()).isEqualTo(30);
                assertThat(json.get("data").toString()).isEqualTo("{\"id\":901,\"nombre\":\"Juan Pérez\",\"areaId\":" + esperado + ",\"activo\":true}");
            }
        }
    }

    @Test
    void areaIdInvalidoOAusenteEsFaltaElAreaYSeValidaAntesQueElNombre() throws Exception {
        MockHttpSession clara = login("clara");
        for (String c : List.of("operadores", "maquinas")) {
            String accion = "faret.catalogos." + c + ".crear";
            for (Object malo : new Object[] {0, -3, "abc", "", 1.5, 99999999999L, "2147483648"}) {
                crear(clara, accion, Map.of("areaId", malo, "nombre", "Pepe")).andExpect(jsonPath("$.error").value("Falta el área"));
            }
            Map<String, Object> nulo = new java.util.HashMap<>();
            nulo.put("areaId", null);
            nulo.put("nombre", "Pepe");
            crear(clara, accion, nulo).andExpect(jsonPath("$.error").value("Falta el área"));
            crear(clara, accion, Map.of("nombre", "Pepe")).andExpect(jsonPath("$.error").value("Falta el área"));
            crear(clara, accion, Map.of("nombre", "")).andExpect(jsonPath("$.error").value("Falta el área"));
            for (Object tipo : new Object[] {true, false, List.of(1), Map.of("a", 1)}) {
                crear(clara, accion, Map.of("areaId", tipo, "nombre", "Pepe")).andExpect(jsonPath("$.error").value("Parámetro inválido."));
            }
            crear(clara, accion, Map.of("areaId", 3, "nombre", " ")).andExpect(jsonPath("$.error").value("Falta el nombre"));
            crear(clara, accion, Map.of("areaId", 3, "nombre", "<b>x</b>")).andExpect(jsonPath("$.ok").value(false));
            crear(clara, accion, Map.of("areaId", 3, "nombre", "x".repeat(101))).andExpect(jsonPath("$.error").value("El valor no puede superar los 100 caracteres."));
        }
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void areaInexistenteDeLaApiSePropagaConSuMensaje() throws Exception {
        MockHttpSession clara = login("clara");
        QC.respuestaEscritura("POST /api/catalogos/operadores", 404, "{\"success\":false,\"message\":\"Área no encontrada o inactiva.\",\"data\":null}");
        crear(clara, "faret.catalogos.operadores.crear", Map.of("areaId", 99, "nombre", "Pepe")).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Área no encontrada o inactiva."));
        assertThat(QC.escrituras()).hasSize(1);
    }

    // ------------------------------------------------------------------------------- roles, empresa, módulo

    @Test
    void rolesPorAccionComoLaApiYEmpresaCruzada() throws Exception {
        for (String a : ACCIONES_PLANTA) {
            assertThat(roles(a)).as(a).containsExactly("admin", "calidad", "consulta");
            for (String rol : List.of("ADMIN", "CALIDAD", "CONSULTA")) {
                assertThat(policy.evaluar(a, usuario("FARET", rol))).as(a + " " + rol).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            }
            for (String rol : List.of("INSPECTOR", "ADMIN_TI", "operador")) {
                assertThat(policy.evaluar(a, usuario("FARET", rol))).as(a + " " + rol).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
            }
        }
        for (String a : ACCIONES_PNC) {
            assertThat(roles(a)).as(a).containsExactly("admin", "admin_ti", "calidad", "consulta");
            for (String rol : List.of("ADMIN", "ADMIN_TI", "CALIDAD", "CONSULTA")) {
                assertThat(policy.evaluar(a, usuario("FARET", rol))).as(a + " " + rol).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            }
            for (String rol : List.of("INSPECTOR", "operador")) {
                assertThat(policy.evaluar(a, usuario("FARET", rol))).as(a + " " + rol).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
            }
        }
        for (String a : concat(ACCIONES_PLANTA, ACCIONES_PNC)) {
            // Una sesión INNPACK (incluso admin_ti) nunca llega; son escrituras sin identidad del navegador.
            for (String rol : List.of("admin", "admin_ti", "operador")) {
                assertThat(policy.evaluar(a, usuario("INNPACK", rol))).as(a + " " + rol).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
            }
            Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(a)).findFirst().orElseThrow();
            assertThat(d.get("escritura")).isEqualTo(true);
            assertThat(d.get("empresas")).isEqualTo(List.of("FARET"));
            assertThat(d.get("identidad")).isEqualTo(Map.of());
            assertThat(PermisosModulo.esLectura(a)).as(a).isFalse();
        }
    }

    @Test
    void rolCruzadoEmpresaCruzadaYModuloSinLlamarALaApi() throws Exception {
        MockHttpSession ines = login("ines");
        MockHttpSession tecnico = login("tecnico");
        for (String a : concat(ACCIONES_PLANTA, ACCIONES_PNC)) {
            crear(ines, a, Map.of("nombre", "ACME", "areaId", 1)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        }
        for (String a : ACCIONES_PLANTA) {
            crear(tecnico, a, Map.of("nombre", "ACME", "areaId", 1)).andExpect(status().isForbidden());
        }
        crear(tecnico, ACCIONES_PNC.get(0), Map.of("nombre", "ACME")).andExpect(jsonPath("$.ok").value(true));
        MockHttpSession operadorInnpack = loginInnpack("operador1");
        MockHttpSession adminTiInnpack = loginInnpack("adminti1");
        for (String a : concat(ACCIONES_PLANTA, ACCIONES_PNC)) {
            crear(operadorInnpack, a, Map.of("nombre", "ACME", "areaId", 1)).andExpect(status().isForbidden());
            crear(adminTiInnpack, a, Map.of("nombre", "ACME", "areaId", 1)).andExpect(status().isForbidden());
        }
        assertThat(QC.escrituras()).hasSize(1);
        QC.limpiarLecturas();
        // Permisos por módulo de Photino: faret-nc con EDITAR; otro módulo o sin módulo se rechaza.
        MockHttpSession ana = login("ana");
        mockMvc.perform(post("/api/v1/bridge").session(ana).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"faret.pncCatalogos.clientes.crear\",\"nombre\":\"ACME\"}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/bridge").session(ana).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"faret.pncCatalogos.clientes.crear\",\"_modulo\":\"faret-data\",\"nombre\":\"ACME\"}")).andExpect(status().isForbidden());
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void sinSesionOSinCsrfNoSeEscribe() throws Exception {
        mockMvc.perform(post("/api/v1/bridge").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"faret.pncCatalogos.clientes.crear\",\"_modulo\":\"faret-nc\",\"nombre\":\"ACME\"}")).andExpect(status().isUnauthorized());
        MockHttpSession ana = login("ana");
        mockMvc.perform(post("/api/v1/bridge").session(ana).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"faret.pncCatalogos.clientes.crear\",\"_modulo\":\"faret-nc\",\"nombre\":\"ACME\"}")).andExpect(status().isForbidden());
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void soloCrearEstaHabilitadoLoDemasSigueDenegado() throws Exception {
        MockHttpSession admin = login("admin");
        List<String> noHabilitadas = new ArrayList<>(List.of("faret.catalogos.areas.desactivar", "faret.catalogos.areas.editar", "faret.catalogos.defectos.crear",
                "faret.catalogos.inspectores.crear", "faret.catalogos.procesos.crear", "faret.catalogos.operadores.desactivar", "faret.catalogos.maquinas.eliminar",
                "faret.talleresExternos.catalogos.crearTaller", "faret.talleresExternos.catalogos.crearProceso", "faret.talleresExternos.lote",
                "faret.talleresExternos.create", "faret.pncCatalogos.inexistente.crear", "faret.pncCatalogos.clientes.editar"));
        PNC.forEach(c -> noHabilitadas.add("faret.pncCatalogos." + c[0] + ".desactivar"));
        for (String a : noHabilitadas) {
            assertThat(policy.accionesRegistradas()).as(a).doesNotContain(a);
            crear(admin, a, Map.of("nombre", "X", "id", 1)).andExpect(status().isForbidden());
        }
        assertThat(QC.escrituras()).isEmpty();
    }

    // ------------------------------------------------------------------------------- errores de la API y sesiones

    @Test
    void erroresDeLaApiSePropaganConSuMensajeYLosInternosSonGenericos() throws Exception {
        MockHttpSession ana = login("ana");
        // 409 / duplicado explícito de la API, 400 de negocio y 403 de rol: su mensaje.
        QC.respuestaEscritura("POST /api/pnc-catalogos/clientes", 409, "{\"success\":false,\"message\":\"El cliente ya existe\",\"data\":null}");
        crear(ana, "faret.pncCatalogos.clientes.crear", Map.of("nombre", "ACME")).andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value("El cliente ya existe"));
        QC.respuestaEscritura("POST /api/pnc-catalogos/niveles", 400, "{\"success\":false,\"message\":\"El valor no puede superar los 30 caracteres.\"}");
        crear(ana, "faret.pncCatalogos.niveles.crear", Map.of("nombre", "Alto")).andExpect(jsonPath("$.error").value("El valor no puede superar los 30 caracteres."));
        QC.respuestaEscritura("POST /api/pnc-catalogos/impactos", 403, "{\"success\":false,\"message\":\"Sin permiso\"}");
        crear(ana, "faret.pncCatalogos.impactos.crear", Map.of("nombre", "Alto")).andExpect(jsonPath("$.error").value("Sin permiso"));
        // 409 sin cuerpo, 500 con HTML, 201 sin ApiResponse o con data no objeto: genérico, sin filtrar nada del upstream.
        QC.respuestaEscritura("POST /api/pnc-catalogos/revisores", 409, null);
        QC.respuestaEscritura("POST /api/pnc-catalogos/supervisores", 500, "<html>boom stack trace</html>");
        QC.respuestaEscritura("POST /api/pnc-catalogos/tipos-falla", 201, "{\"algo\":1}");
        QC.respuestaEscritura("POST /api/pnc-catalogos/categorias-defecto", 201, "{\"success\":true,\"data\":\"texto\"}");
        QC.respuestaEscritura("POST /api/pnc-catalogos/familias-producto", 201, "{\"success\":true,\"data\":{\"nombre\":\"sin id\"}}");
        for (String c : List.of("revisores", "supervisores", "tiposFalla", "categoriasDefecto", "familiasProducto")) {
            MvcResult r = crear(ana, "faret.pncCatalogos." + c + ".crear", Map.of("nombre", "ACME")).andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret")).andReturn();
            assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("boom").doesNotContain("stack");
        }
        // Sin reintentos: una sola llamada por intento.
        assertThat(QC.escrituras()).hasSize(8);
    }

    @Test
    void unauthorizedDeLaApiInvalidaSoloEsaSesion() throws Exception {
        MockHttpSession a = login("ana");
        MockHttpSession b = login("admin");
        QC.rechazarLecturas(true);
        crear(a, "faret.pncCatalogos.clientes.crear", Map.of("nombre", "ACME")).andExpect(status().isUnauthorized());
        assertThat(a.isInvalid()).isTrue();
        QC.rechazarLecturas(false);
        assertThat(b.isInvalid()).isFalse();
        crear(b, "faret.pncCatalogos.clientes.crear", Map.of("nombre", "ACME")).andExpect(jsonPath("$.ok").value(true));
        assertThat(QC.escrituras()).hasSize(2);
        assertThat(QC.escrituras().get(0).sub()).isEqualTo(20);
        assertThat(QC.escrituras().get(1).sub()).isEqualTo(10);
    }

    @Test
    void dosSesionesCadaEscrituraConSuPropioToken() throws Exception {
        MockHttpSession ana = login("ana");
        MockHttpSession admin = login("admin");
        for (int i = 0; i < 6; i++) {
            boolean esAna = i % 2 == 0;
            crear(esAna ? ana : admin, "faret.pncCatalogos.clientes.crear", Map.of("nombre", "Cliente " + i)).andExpect(jsonPath("$.ok").value(true));
        }
        for (int i = 0; i < 6; i++) {
            assertThat(QC.escrituras().get(i).sub()).isEqualTo(i % 2 == 0 ? 20 : 10);
            assertThat(mapper.readTree(QC.escrituras().get(i).cuerpo()).get("nombre").asString()).isEqualTo("Cliente " + i);
        }
    }

    // ------------------------------------------------------------------------------- auditoría

    @Test
    void auditoriaConRecursoEIdSinElNombre(CapturedOutput salida) throws Exception {
        MockHttpSession ana = login("ana");
        crear(ana, "faret.pncCatalogos.clientes.crear", Map.of("nombre", "Sens 12.345.678-9")).andExpect(jsonPath("$.ok").value(true));
        crear(ana, "faret.catalogos.areas.crear", Map.of("nombre", "Corte Sensible")).andExpect(jsonPath("$.ok").value(true));
        crear(ana, "faret.catalogos.maquinas.crear", Map.of("areaId", 4, "nombre", "Máquina Sensible")).andExpect(jsonPath("$.ok").value(true));
        QC.respuestaEscritura("POST /api/pnc-catalogos/niveles", 400, "{\"success\":false,\"message\":\"x\"}");
        crear(ana, "faret.pncCatalogos.niveles.crear", Map.of("nombre", "Alto")).andExpect(jsonPath("$.ok").value(false));
        crear(ana, "faret.pncCatalogos.clientes.crear", Map.of("nombre", "<b>x</b>")).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=ana empresa=FARET accion=faret\\.pncCatalogos\\.clientes\\.crear "
                + "recurso=faret:catalogo:pnc-clientes:901 resultado=OK ms=\\d+");
        assertThat(log).containsPattern("accion=faret\\.catalogos\\.areas\\.crear recurso=faret:catalogo:areas:901 resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.catalogos\\.maquinas\\.crear recurso=faret:catalogo:maquinas:901 resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.pncCatalogos\\.niveles\\.crear recurso=faret:catalogo:pnc-niveles resultado=ERROR");
        assertThat(log).contains("recurso=faret:catalogo:pnc-clientes resultado=ERROR");
        assertThat(log).doesNotContain("Sens 12.345.678-9", "12.345.678-9", "Corte Sensible", "Máquina Sensible", "<b>x</b>",
                FakeFaretApi.firmaDeToken(20), PASS);
    }

    // ------------------------------------------------------------------------------- helpers

    private List<String> roles(String accion) {
        Object roles = policy.describir().stream().filter(x -> x.get("accion").equals(accion)).findFirst().orElseThrow().get("roles");
        return ((List<?>) roles).stream().map(Object::toString).toList();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> r = new ArrayList<>(a);
        r.addAll(b);
        return r;
    }

    private static SessionUser usuario(String empresa, String rol) {
        return new SessionUser(1, "u", "U", rol, empresa, "t", Instant.now(), Instant.now().plusSeconds(60));
    }

    private ResultActions crear(MockHttpSession sesion, String accion, Map<String, Object> campos) throws Exception {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", accion);
        p.put("_modulo", "faret-nc");
        campos.forEach((k, v) -> p.set(k, mapper.valueToTree(v)));
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(p.toString()));
    }

    private MockHttpSession login(String identificador) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/faret/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.79.0." + IP.getAndIncrement());
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
                            r.setRemoteAddr("10.79.1." + IP.getAndIncrement());
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
            Path dir = Files.createTempDirectory("qcc-web-fixture-f1");
            Files.writeString(dir.resolve("index.html"), "<!doctype html><title>fixture</title>");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
