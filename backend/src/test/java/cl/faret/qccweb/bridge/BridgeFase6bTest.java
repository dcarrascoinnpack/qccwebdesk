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
import cl.faret.qccweb.bridge.handlers.FaretBridgeHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

/**
 * Fase 6b — lecturas del inicio FARET (Photino 1.8.15, 9e1b556, FaretHandler.cs): faret.data.list,
 * faret.indicadoresCalidad.resumen, faret.talleresExternos.resumen (6b-2, QualityControlFaret.Api con Bearer),
 * faret.nc.list / faret.inspecciones.resumen / faret.maquinas.resumen (6b-3, MejoraContinua y Calidad SIN Authorization) y
 * faret.dashboard.resumen (6b-4). Payload PLANO. TODAS las APIs son fakes en localhost.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFase6bTest {

    private static final String DATA_LIST = "faret.data.list";
    private static final String INDICADORES = "faret.indicadoresCalidad.resumen";
    private static final String TALLERES = "faret.talleresExternos.resumen";
    private static final String PNC = "/api/importaciones/pnc";
    private static final FakeFaretApi QC = new FakeFaretApi(Clock.systemUTC());
    private static final FakeInnpackApi INNPACK = new FakeInnpackApi(Clock.systemUTC());
    private static final FakeFaretSinAuthApi MC = new FakeFaretSinAuthApi("/mejora-continua");
    private static final FakeFaretSinAuthApi CALIDAD = new FakeFaretSinAuthApi("/calidad/api");
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveFaret6b#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        QC.agregar(new FakeFaretApi.Usuario(10, "admin", "admin@faret.cl", PASS, "Admin Faret", List.of("ADMIN")));
        QC.agregar(new FakeFaretApi.Usuario(20, "ana", "ana@faret.cl", PASS, "Ana Calidad", List.of("CALIDAD")));
        QC.agregar(new FakeFaretApi.Usuario(30, "clara", null, PASS, "Clara Consulta", List.of("CONSULTA")));
        INNPACK.agregar(new FakeInnpackApi.Usuario(40, "operador1", PASS, "Operador Uno", "operador", true));
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

    @Autowired
    private ActionPolicy policy;

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

    // ----------------------------------------------------------------------------- 6b-2: data.list

    @Test
    void dataListSinFiltrosDesenvuelveElApiResponseYUsaElBearerDeLaSesion() throws Exception {
        MockHttpSession sesion = login("admin");
        JsonNode json = json(accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\"}")
                .andExpect(status().isOk()).andReturn());
        List<String> campos = new ArrayList<>();
        json.propertyNames().forEach(campos::add);
        assertThat(campos).containsExactly("ok", "success", "data", "error");
        assertThat(json.get("ok").asBoolean()).isTrue();
        // El fake responde data = {ruta, usuario}: llega SOLO el data, sin el sobre {success, message, data, errors}.
        assertThat(json.get("data").toString()).isEqualTo("{\"ruta\":\"" + PNC + "\",\"usuario\":10}");
        assertThat(QC.lecturas()).containsExactly("GET " + PNC);
        assertThat(QC.lecturasDeUsuario()).containsExactly(10);
        // Otro usuario, otro Bearer: nunca un token compartido.
        accion(login("ana"), "{\"action\":\"" + INDICADORES + "\",\"_modulo\":\"faret\"}").andExpect(status().isOk());
        assertThat(QC.lecturasDeUsuario()).containsExactly(10, 20);
        // Nada de MejoraContinua ni Calidad en estas tres acciones.
        assertThat(MC.peticiones()).isEmpty();
        assertThat(CALIDAD.peticiones()).isEmpty();
    }

    @Test
    void dataListConTodosLosFiltrosVaEnElOrdenFijoDePhotinoConTrimYEscape() throws Exception {
        MockHttpSession sesion = login("admin");
        // Claves en orden "inverso" en el JSON: la query sale igual en el orden de Photino (BuildDataFiltros + page, pageSize).
        accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\",\"pageSize\":50,\"page\":3,\"fechaHasta\":\"2026-10-31\",\"fechaDesde\":\"2026-10-01\","
                + "\"nivel\":\"Alto\",\"tipoPnc\":\"Rechazo\",\"cliente\":\"  Ñandú & Co/1  \",\"_modulo\":\"faret-data\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertThat(QC.lecturas()).containsExactly("GET " + PNC + "?cliente=%C3%91and%C3%BA%20%26%20Co%2F1&tipoPnc=Rechazo&nivel=Alto"
                + "&fechaDesde=2026-10-01&fechaHasta=2026-10-31&page=3&pageSize=50");
    }

    @Test
    void dataListPaginacionComoPhotinoYPageSizeAcotadoAlTopeDeLaApi() throws Exception {
        MockHttpSession sesion = login("admin");
        // faret.controller manda pageSize 500; la API recorta a 200: la web lo normaliza igual (misma página efectiva).
        accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\",\"page\":1,\"pageSize\":500,\"fechaDesde\":\"2026-10-01\"}").andExpect(status().isOk());
        accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\",\"page\":\"2\",\"pageSize\":\" 200 \"}").andExpect(status().isOk());
        // Valores que Photino ignora en silencio (no enteros o <= 0): no viajan.
        for (String extra : List.of("\"page\":0", "\"page\":-4", "\"page\":\"abc\"", "\"page\":1.5", "\"page\":\"\"", "\"page\":null",
                "\"pageSize\":0", "\"pageSize\":-1", "\"pageSize\":\"x\"", "\"pageSize\":99999999999")) {
            accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\"," + extra + "}").andExpect(status().isOk());
        }
        List<String> esperadas = new ArrayList<>(List.of("GET " + PNC + "?fechaDesde=2026-10-01&page=1&pageSize=200",
                "GET " + PNC + "?page=2&pageSize=200"));
        for (int i = 0; i < 10; i++) {
            esperadas.add("GET " + PNC);
        }
        assertThat(QC.lecturas()).containsExactlyElementsOf(esperadas);
        // Una página absurda se rechaza antes de llamar a la API.
        QC.limpiarLecturas();
        accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\",\"page\":1000001}").andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_PAGINACION));
        assertThat(QC.lecturas()).isEmpty();
    }

    @Test
    void filtrosConTipoInvalidoOValoresMalosSeRechazanSinLlamarALaApi() throws Exception {
        MockHttpSession sesion = login("admin");
        // Regla 2e: bool / objeto / array en cualquier filtro (y en page/pageSize).
        for (String clave : List.of("cliente", "tipoPnc", "nivel", "fechaDesde", "fechaHasta", "page", "pageSize")) {
            for (String valor : List.of("true", "false", "{\"a\":1}", "[\"x\"]", "[]")) {
                accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\",\"" + clave + "\":" + valor + "}")
                        .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                        .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
            }
        }
        // Fechas: solo AAAA-MM-DD reales.
        for (String fecha : List.of("2026-13-01", "2026-02-30", "2026/10/01", "20261001", "01-10-2026", "2026-10-01T00:00:00", "ayer", "2026-1-1")) {
            accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\",\"fechaDesde\":\"" + fecha + "\"}").andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("La fecha fechaDesde no es válida (formato AAAA-MM-DD)."));
        }
        // Largo máximo 200 (después del trim) y sin caracteres de control.
        accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\",\"cliente\":\"" + "a".repeat(201) + "\"}")
                .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_LARGO));
        accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\",\"cliente\":\"  " + "a".repeat(200) + "  \"}").andExpect(jsonPath("$.ok").value(true));
        for (String control : List.of("a\\nb", "a\\u0000b", "a\\tb", "a\\u007fb")) {
            accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\",\"tipoPnc\":\"" + control + "\"}")
                    .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
        }
        // Solo el que pasó (200 'a') llegó a la API.
        assertThat(QC.lecturas()).hasSize(1);
    }

    @Test
    void soloLaListaCerradaDeClavesLlegaALaUrlYElPayloadAnidadoSeIgnora() throws Exception {
        MockHttpSession sesion = login("admin");
        accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\",\"empresa\":\"INNPACK\",\"usuarioId\":99,\"rol\":\"ADMIN_TI\",\"token\":\"robado\","
                + "\"../x\":\"1\",\"cliente\":\"ACME\",\"data\":{\"cliente\":\"OTRO\",\"nivel\":\"X\"}}").andExpect(status().isOk());
        assertThat(QC.lecturas()).containsExactly("GET " + PNC + "?cliente=ACME");
        assertThat(QC.lecturasDeUsuario()).containsExactly(10);
    }

    // ----------------------------------------------------------------------------- 6b-2: indicadores y talleres

    @Test
    void indicadoresCalidadSoloAceptaElPeriodo() throws Exception {
        MockHttpSession sesion = login("ana");
        accion(sesion, "{\"action\":\"" + INDICADORES + "\",\"fechaDesde\":\"2026-10-01\",\"fechaHasta\":\"2026-10-09\",\"tipoPnc\":\"Rechazo\","
                + "\"cliente\":\"ACME\",\"_modulo\":\"faret\"}").andExpect(status().isOk());
        accion(sesion, "{\"action\":\"" + INDICADORES + "\",\"_modulo\":\"faret\"}").andExpect(status().isOk());
        accion(sesion, "{\"action\":\"" + INDICADORES + "\",\"fechaHasta\":\"mañana\",\"_modulo\":\"faret\"}")
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("La fecha fechaHasta no es válida (formato AAAA-MM-DD)."));
        assertThat(QC.lecturas()).containsExactly("GET " + PNC + "/indicadores-calidad?fechaDesde=2026-10-01&fechaHasta=2026-10-09",
                "GET " + PNC + "/indicadores-calidad");
    }

    @Test
    void talleresExternosResumenLosDoceFiltrosEnElOrdenDePhotino() throws Exception {
        MockHttpSession sesion = login("clara");
        accion(sesion, "{\"action\":\"" + TALLERES + "\",\"_modulo\":\"faret\"}").andExpect(status().isOk());
        accion(sesion, "{\"action\":\"" + TALLERES + "\",\"fechaCompromisoHasta\":\"2026-12-31\",\"fechaCompromisoDesde\":\"2026-12-01\","
                + "\"fechaAsignacionHasta\":\"2026-11-30\",\"fechaAsignacionDesde\":\"2026-11-01\",\"estado\":\"EN PROCESO\",\"prioridad\":\"ALTA\","
                + "\"responsable\":\"Pérez\",\"proceso\":\"Pintura\",\"tallerExterno\":\"Taller 1\",\"cliente\":\"ACME\",\"producto\":\"Caja\","
                + "\"nv\":\"12345\",\"_modulo\":\"faret-talleres-externos\"}").andExpect(status().isOk());
        accion(sesion, "{\"action\":\"" + TALLERES + "\",\"nv\":12345,\"estado\":\"   \",\"_modulo\":\"faret\"}").andExpect(status().isOk());
        accion(sesion, "{\"action\":\"" + TALLERES + "\",\"fechaCompromisoDesde\":\"2026-02-30\",\"_modulo\":\"faret\"}")
                .andExpect(jsonPath("$.error").value("La fecha fechaCompromisoDesde no es válida (formato AAAA-MM-DD)."));
        accion(sesion, "{\"action\":\"" + TALLERES + "\",\"proceso\":{\"a\":1},\"_modulo\":\"faret\"}")
                .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
        assertThat(QC.lecturas()).containsExactly(
                "GET /api/talleres-externos/resumen",
                "GET /api/talleres-externos/resumen?nv=12345&producto=Caja&cliente=ACME&tallerExterno=Taller%201&proceso=Pintura"
                        + "&responsable=P%C3%A9rez&prioridad=ALTA&estado=EN%20PROCESO&fechaAsignacionDesde=2026-11-01"
                        + "&fechaAsignacionHasta=2026-11-30&fechaCompromisoDesde=2026-12-01&fechaCompromisoHasta=2026-12-31",
                "GET /api/talleres-externos/resumen?nv=12345");
    }

    // ----------------------------------------------------------------------------- 6b-2: errores de la API

    @Test
    void erroresDeLaApiUsanLaLogicaDeTryUnwrapApiResponse() throws Exception {
        MockHttpSession sesion = login("admin");
        QC.respuestaLectura(PNC, 400, "{\"success\":false,\"message\":\"Rango de fechas inválido\",\"data\":null}");
        accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\"}").andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Rango de fechas inválido"));
        QC.respuestaLectura(PNC, 500, "{\"type\":\"about:blank\",\"title\":\"Internal Server Error\",\"detail\":\"SqlException en tabla x\"}");
        MvcResult r = accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\"}").andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret")).andReturn();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SqlException");
        // Un 200 con success=true pero sin data no es un resultado válido (TryUnwrapApiResponse devuelve false).
        QC.respuestaLectura(PNC, 200, "{\"success\":true,\"message\":null}");
        accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\"}").andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret"));
        // data null sí es válido y llega como null.
        QC.respuestaLectura(PNC, 200, "{\"success\":true,\"data\":null}");
        accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\"}").andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void un401DeLaApiInvalidaLaSesionDeEseUsuarioComoEnInnpack() throws Exception {
        MockHttpSession sesion = login("admin");
        MockHttpSession otra = login("ana");
        QC.rechazarLecturas(true);
        accion(sesion, "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\"}").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Sesión expirada. Inicia sesión nuevamente."));
        assertThat(sesion.isInvalid()).isTrue();
        assertThat(otra.isInvalid()).isFalse();
        QC.rechazarLecturas(false);
        accion(otra, "{\"action\":\"" + INDICADORES + "\",\"_modulo\":\"faret\"}").andExpect(status().isOk());
    }

    // ----------------------------------------------------------------------------- 6b-2: empresa, rol y módulo

    @Test
    void unaSesionInnpackNoAlcanzaLasAccionesFaretNiAlReves() throws Exception {
        MockHttpSession innpack = loginInnpack("operador1");
        for (String a : List.of(DATA_LIST, INDICADORES, TALLERES)) {
            accion(innpack, "{\"action\":\"" + a + "\",\"_modulo\":\"faret\"}").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        }
        assertThat(QC.lecturas()).isEmpty();
        // Y una sesión FARET no alcanza las acciones INNPACK (ya cubierto en 6a; aquí con una lectura de Formularios).
        accion(login("admin"), "{\"action\":\"formularios.list\",\"data\":{\"tipo\":\"inspeccionesVehiculares\"},\"_modulo\":\"faret-formularios\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void losCincoRolesFaretEstanEnLaReglaYCualquierOtroNo() {
        for (String a : List.of(DATA_LIST, INDICADORES, TALLERES)) {
            for (String rol : List.of("ADMIN", "ADMIN_TI", "CALIDAD", "INSPECTOR", "CONSULTA")) {
                assertThat(policy.evaluar(a, usuario("FARET", rol))).as(a + " " + rol).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            }
            for (String rol : List.of("operador", "OTRO", "")) {
                assertThat(policy.evaluar(a, usuario("FARET", rol))).as(a + " " + rol)
                        .isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
            }
            assertThat(policy.evaluar(a, usuario("INNPACK", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
            assertThat(PermisosModulo.esLectura(a)).isTrue();
        }
    }

    @Test
    void losPermisosPorModuloDePhotinoSiguenAplicandose() throws Exception {
        // CALIDAD no tiene faret-data (solo ADMIN): la lectura declarada desde ese módulo se rechaza antes de llamar a la API.
        accion(login("ana"), "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"faret-data\"}").andExpect(status().isForbidden());
        // Sin módulo declarado o con uno desconocido tampoco.
        accion(login("ana"), "{\"action\":\"" + DATA_LIST + "\"}").andExpect(status().isForbidden());
        accion(login("ana"), "{\"action\":\"" + DATA_LIST + "\",\"_modulo\":\"inventado\"}").andExpect(status().isForbidden());
        // CONSULTA solo entra a faret / talleres / nc / despachos: el inicio sí.
        accion(login("clara"), "{\"action\":\"" + INDICADORES + "\",\"_modulo\":\"faret\"}").andExpect(status().isOk());
        accion(login("clara"), "{\"action\":\"" + INDICADORES + "\",\"_modulo\":\"faret-data\"}").andExpect(status().isForbidden());
        assertThat(QC.lecturas()).hasSize(1);
    }

    @Test
    void laIdentidadDelPayloadNoCambiaQuienLlamaNiLaEmpresa() throws Exception {
        // Intento de hacerse pasar por otra empresa/usuario: la política usa la sesión; la API recibe el Bearer de la sesión.
        accion(login("ana"), "{\"action\":\"" + INDICADORES + "\",\"empresa\":\"INNPACK\",\"rol\":\"ADMIN_TI\",\"usuarioId\":10,\"_modulo\":\"faret\"}")
                .andExpect(status().isOk());
        assertThat(QC.lecturasDeUsuario()).containsExactly(20);
        assertThat(QC.lecturas()).containsExactly("GET " + PNC + "/indicadores-calidad");
    }

    // ----------------------------------------------------------------------------- 6b-3: MejoraContinua y Calidad

    private static final String NC_LIST = "faret.nc.list";
    private static final String INSPECCIONES = "faret.inspecciones.resumen";
    private static final String MAQUINAS = "faret.maquinas.resumen";

    @Test
    void ncListDevuelveElArregloCrudoDeMejoraContinuaSinAuthorization() throws Exception {
        MC.responder("/api/no-conformidades", 200, "[{\"id\":1,\"codigo\":\"NC-1\",\"estado\":\"ABIERTA\"},{\"id\":2,\"codigo\":\"NC-2\"}]");
        MockHttpSession sesion = login("ana");
        JsonNode json = json(accion(sesion, "{\"action\":\"" + NC_LIST + "\",\"_modulo\":\"faret-nc\",\"id\":5,\"data\":{\"x\":1},\"usuarioId\":9}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true)).andReturn());
        assertThat(json.get("data").toString()).isEqualTo("[{\"id\":1,\"codigo\":\"NC-1\",\"estado\":\"ABIERTA\"},{\"id\":2,\"codigo\":\"NC-2\"}]");
        assertThat(MC.peticiones()).containsExactly("GET /api/no-conformidades");
        // Photino nunca le hace SetToken al cliente de MejoraContinua: el Bearer del usuario NO sale hacia esa API.
        assertThat(MC.authorizations()).containsExactly((String) null);
        assertThat(QC.lecturas()).isEmpty();
        assertThat(CALIDAD.peticiones()).isEmpty();
        // Lista vacía también es un resultado válido.
        MC.responder("/api/no-conformidades", 200, "[]");
        accion(sesion, "{\"action\":\"" + NC_LIST + "\",\"_modulo\":\"faret\"}").andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void ncListErroresSegunExtractMcErrorMessageYUn401DeMcNoCierraLaSesion() throws Exception {
        MockHttpSession sesion = login("admin");
        MC.responder("/api/no-conformidades", 500, "{\"type\":\"about:blank\",\"title\":\"Internal Server Error\",\"status\":500,\"detail\":\"SqlException x\"}");
        accion(sesion, "{\"action\":\"" + NC_LIST + "\",\"_modulo\":\"faret-nc\"}").andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Internal Server Error"));
        MC.responder("/api/no-conformidades", 400, "{\"mensaje\":\"Sin permiso para listar\"}");
        accion(sesion, "{\"action\":\"" + NC_LIST + "\",\"_modulo\":\"faret-nc\"}").andExpect(jsonPath("$.error").value("Sin permiso para listar"));
        MC.responder("/api/no-conformidades", 500, null);
        accion(sesion, "{\"action\":\"" + NC_LIST + "\",\"_modulo\":\"faret-nc\"}").andExpect(jsonPath("$.error").value("HTTP 500: Internal Server Error"));
        MC.responder("/api/no-conformidades", 200, "esto no es json");
        accion(sesion, "{\"action\":\"" + NC_LIST + "\",\"_modulo\":\"faret-nc\"}").andExpect(jsonPath("$.error").value("Error al comunicarse con la API de Mejora Continua"));
        // 401 de MejoraContinua: sin token de por medio, es un error de esa API; la sesión del usuario sigue viva.
        MC.responder("/api/no-conformidades", 401, "{\"title\":\"Unauthorized\"}");
        accion(sesion, "{\"action\":\"" + NC_LIST + "\",\"_modulo\":\"faret-nc\"}").andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
        assertThat(sesion.isInvalid()).isFalse();
        assertThat(MC.authorizations()).containsOnlyNulls();
    }

    @Test
    void inspeccionesResumenFiltrosEnElOrdenDePhotinoYSinAuthorization() throws Exception {
        CALIDAD.responder("/calidad-faret/resumen", 200, "{\"ok\":true,\"data\":{\"inspeccionesHoy\":4,\"conDefectos\":1}}");
        MockHttpSession sesion = login("ana");
        JsonNode json = json(accion(sesion, "{\"action\":\"" + INSPECCIONES + "\",\"_modulo\":\"faret-inspecciones\"}")
                .andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.get("data").toString()).isEqualTo("{\"inspeccionesHoy\":4,\"conDefectos\":1}");
        accion(sesion, "{\"action\":\"" + INSPECCIONES + "\",\"nvFaret\":\" NV 55 \",\"presentaDefectos\":\"true\",\"maquina\":\"Línea 2\","
                + "\"operador\":\"Juan\",\"areaControl\":\"Corrugado\",\"fechaHasta\":\"2026-10-09\",\"fechaDesde\":\"2026-10-01\","
                + "\"_modulo\":\"faret-inspecciones\"}").andExpect(jsonPath("$.ok").value(true));
        accion(sesion, "{\"action\":\"" + INSPECCIONES + "\",\"presentaDefectos\":\"\",\"nvFaret\":\"   \",\"_modulo\":\"faret\"}").andExpect(jsonPath("$.ok").value(true));
        assertThat(CALIDAD.peticiones()).containsExactly(
                "GET /calidad-faret/resumen",
                "GET /calidad-faret/resumen?fechaDesde=2026-10-01&fechaHasta=2026-10-09&areaControl=Corrugado&operador=Juan&maquina=L%C3%ADnea%202"
                        + "&presentaDefectos=true&nvFaret=NV%2055",
                "GET /calidad-faret/resumen");
        assertThat(CALIDAD.authorizations()).containsOnlyNulls();
        assertThat(QC.lecturas()).isEmpty();
        assertThat(MC.peticiones()).isEmpty();
    }

    @Test
    void inspeccionesPresentaDefectosSoloTrueFalseUnoCeroYValidacionesComunes() throws Exception {
        CALIDAD.responder("/calidad-faret/resumen", 200, "{\"ok\":true,\"data\":{}}");
        MockHttpSession sesion = login("admin");
        for (String ok : List.of("true", "false", "1", "0")) {
            accion(sesion, "{\"action\":\"" + INSPECCIONES + "\",\"presentaDefectos\":\"" + ok + "\",\"_modulo\":\"faret-inspecciones\"}")
                    .andExpect(jsonPath("$.ok").value(true));
        }
        for (String malo : List.of("\"TRUE\"", "\"si\"", "\"2\"", "\"true; drop\"", "\"true&x=1\"", "\"yes\"")) {
            accion(sesion, "{\"action\":\"" + INSPECCIONES + "\",\"presentaDefectos\":" + malo + ",\"_modulo\":\"faret-inspecciones\"}")
                    .andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_PRESENTA_DEFECTOS));
        }
        // true/false JSON (booleano) y 1/0 numérico: bool es regla 2e; el número se trata como texto, igual que GetString.
        accion(sesion, "{\"action\":\"" + INSPECCIONES + "\",\"presentaDefectos\":true,\"_modulo\":\"faret-inspecciones\"}")
                .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
        accion(sesion, "{\"action\":\"" + INSPECCIONES + "\",\"presentaDefectos\":1,\"_modulo\":\"faret-inspecciones\"}").andExpect(jsonPath("$.ok").value(true));
        for (String clave : List.of("areaControl", "operador", "maquina", "nvFaret", "fechaDesde", "fechaHasta")) {
            accion(sesion, "{\"action\":\"" + INSPECCIONES + "\",\"" + clave + "\":{\"a\":1},\"_modulo\":\"faret-inspecciones\"}")
                    .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
            accion(sesion, "{\"action\":\"" + INSPECCIONES + "\",\"" + clave + "\":[1],\"_modulo\":\"faret-inspecciones\"}")
                    .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
        }
        accion(sesion, "{\"action\":\"" + INSPECCIONES + "\",\"fechaDesde\":\"2026-02-31\",\"_modulo\":\"faret-inspecciones\"}")
                .andExpect(jsonPath("$.error").value("La fecha fechaDesde no es válida (formato AAAA-MM-DD)."));
        accion(sesion, "{\"action\":\"" + INSPECCIONES + "\",\"operador\":\"" + "x".repeat(201) + "\",\"_modulo\":\"faret-inspecciones\"}")
                .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_LARGO));
        accion(sesion, "{\"action\":\"" + INSPECCIONES + "\",\"operador\":\"a\\nb\",\"_modulo\":\"faret-inspecciones\"}")
                .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
        // Solo llegaron a Calidad los 4 válidos de presentaDefectos + el numérico 1.
        assertThat(CALIDAD.peticiones()).hasSize(5);
    }

    @Test
    void maquinasResumenConYSinMaquinaYErroresDeCalidad() throws Exception {
        CALIDAD.responder("/calidad-faret/maquinas/resumen", 200, "{\"ok\":true,\"data\":{\"totalMaquinas\":2,\"maquinas\":[{\"maquina\":\"L1\",\"totalRegistros\":7}]}}");
        MockHttpSession sesion = login("clara");
        JsonNode json = json(accion(sesion, "{\"action\":\"" + MAQUINAS + "\",\"_modulo\":\"faret\"}").andExpect(status().isOk()).andReturn());
        assertThat(json.get("data").at("/maquinas/0/totalRegistros").asInt()).isEqualTo(7);
        accion(login("ana"), "{\"action\":\"" + MAQUINAS + "\",\"maquina\":\"  Línea 1/B  \",\"_modulo\":\"faret-maquinas\"}").andExpect(jsonPath("$.ok").value(true));
        // Solo "maquina" cuenta: lo demás del payload no llega a la URL.
        accion(login("ana"), "{\"action\":\"" + MAQUINAS + "\",\"cliente\":\"X\",\"fechaDesde\":\"2026-10-01\",\"_modulo\":\"faret-maquinas\"}")
                .andExpect(jsonPath("$.ok").value(true));
        accion(login("ana"), "{\"action\":\"" + MAQUINAS + "\",\"maquina\":true,\"_modulo\":\"faret-maquinas\"}")
                .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
        assertThat(CALIDAD.peticiones()).containsExactly("GET /calidad-faret/maquinas/resumen",
                "GET /calidad-faret/maquinas/resumen?maquina=L%C3%ADnea%201%2FB", "GET /calidad-faret/maquinas/resumen");

        CALIDAD.responder("/calidad-faret/maquinas/resumen", 200, "{\"ok\":false,\"message\":\"Sin base de datos\"}");
        accion(sesion, "{\"action\":\"" + MAQUINAS + "\",\"_modulo\":\"faret\"}").andExpect(jsonPath("$.error").value("Sin base de datos"));
        CALIDAD.responder("/calidad-faret/maquinas/resumen", 500, "{\"title\":\"x\"}");
        accion(sesion, "{\"action\":\"" + MAQUINAS + "\",\"_modulo\":\"faret\"}").andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret"));
        // 401 de Calidad: error normal, sesión intacta (la API no recibe ni valida ningún token).
        CALIDAD.responder("/calidad-faret/maquinas/resumen", 401, "{\"ok\":false,\"message\":\"No autorizado\"}");
        accion(sesion, "{\"action\":\"" + MAQUINAS + "\",\"_modulo\":\"faret\"}").andExpect(status().isOk()).andExpect(jsonPath("$.error").value("No autorizado"));
        assertThat(sesion.isInvalid()).isFalse();
        assertThat(CALIDAD.authorizations()).containsOnlyNulls();
    }

    @Test
    void siMejoraContinuaOCalidadNoEstanConfiguradasResponderComoPhotino() {
        java.time.Duration t = java.time.Duration.ofSeconds(1);
        FaretBridgeHandler sinUrls = new FaretBridgeHandler(new cl.faret.qccweb.upstream.FaretApiClient(QC.baseUrl(), true, t, t),
                new cl.faret.qccweb.upstream.FaretApiClient("", false, t, t), new cl.faret.qccweb.upstream.FaretApiClient(null, false, t, t), mapper);
        SessionUser u = usuario("FARET", "ADMIN");
        assertThat(sinUrls.ncList(mapper.createObjectNode(), u).error()).isEqualTo(FaretBridgeHandler.MENSAJE_MC_NO_CONFIGURADA);
        assertThat(sinUrls.inspeccionesResumen(mapper.createObjectNode(), u).error()).isEqualTo(FaretBridgeHandler.MENSAJE_CALIDAD_NO_CONFIGURADA);
        assertThat(sinUrls.maquinasResumen(mapper.createObjectNode(), u).error()).isEqualTo(FaretBridgeHandler.MENSAJE_CALIDAD_NO_CONFIGURADA);
        assertThat(FaretBridgeHandler.MENSAJE_MC_NO_CONFIGURADA).startsWith("API de Mejora Continua no configurada");
    }

    @Test
    void rolesEmpresaYModuloDeLasTresLecturasDeMcYCalidad() throws Exception {
        for (String a : List.of(NC_LIST, INSPECCIONES, MAQUINAS)) {
            for (String rol : List.of("ADMIN", "ADMIN_TI", "CALIDAD", "INSPECTOR", "CONSULTA")) {
                assertThat(policy.evaluar(a, usuario("FARET", rol))).as(a + " " + rol).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            }
            assertThat(policy.evaluar(a, usuario("FARET", "operador"))).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
            assertThat(policy.evaluar(a, usuario("INNPACK", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
            assertThat(PermisosModulo.esLectura(a)).isTrue();
        }
        MockHttpSession innpack = loginInnpack("operador1");
        for (String a : List.of(NC_LIST, INSPECCIONES, MAQUINAS)) {
            accion(innpack, "{\"action\":\"" + a + "\",\"_modulo\":\"faret\"}").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        }
        // CONSULTA no tiene faret-inspecciones: se rechaza antes de llamar a Calidad.
        accion(login("clara"), "{\"action\":\"" + INSPECCIONES + "\",\"_modulo\":\"faret-inspecciones\"}").andExpect(status().isForbidden());
        // Y sin sesión, nada.
        mockMvc.perform(post("/api/v1/bridge").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"" + NC_LIST + "\",\"_modulo\":\"faret\"}")).andExpect(status().isUnauthorized());
        assertThat(MC.peticiones()).isEmpty();
        assertThat(CALIDAD.peticiones()).isEmpty();
    }

    // ----------------------------------------------------------------------------- helpers

    private static SessionUser usuario(String empresa, String rol) {
        return new SessionUser(1, "u", "U", rol, empresa, "t", Instant.now(), Instant.now().plusSeconds(60));
    }

    private MockHttpSession login(String identificador) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/faret/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.77.0." + IP.getAndIncrement());
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
                            r.setRemoteAddr("10.77.1." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private ResultActions accion(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private JsonNode json(MvcResult res) throws Exception {
        return mapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Path crearWww() {
        try {
            Path dir = Files.createTempDirectory("qcc-web-fixture-6b");
            Files.writeString(dir.resolve("index.html"), "<!doctype html><title>fixture</title>");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
