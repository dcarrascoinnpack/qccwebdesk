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
 * Fase 6c — lecturas FARET restantes (Photino 1.8.15, 9e1b556, FaretHandler.cs), solo GET, payload PLANO. A: catálogos
 * (areas, operadores, maquinas) y catálogos PNC (QualityControlFaret.Api con Bearer). B: no conformidades (MejoraContinua, sin Authorization).
 * C: talleres externos / registros / importación / data.resumen (Bearer). D: Calidad (sin Authorization). TODAS las APIs son
 * fakes en localhost.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFase6cTest {

    private static final FakeFaretApi QC = new FakeFaretApi(Clock.systemUTC());
    private static final FakeInnpackApi INNPACK = new FakeInnpackApi(Clock.systemUTC());
    private static final FakeFaretSinAuthApi MC = new FakeFaretSinAuthApi("/mejora-continua");
    private static final FakeFaretSinAuthApi CALIDAD = new FakeFaretSinAuthApi("/calidad/api");
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveFaret6c#2026";
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

    // ----------------------------------------------------------------------------- A: catálogos

    private static final List<String> CATALOGOS_SIMPLES = List.of("areas");
    private static final List<String[]> PNC = List.of(
            new String[] {"clientes", "clientes"}, new String[] {"categoriasDefecto", "categorias-defecto"},
            new String[] {"tiposFalla", "tipos-falla"}, new String[] {"supervisores", "supervisores"},
            new String[] {"revisores", "revisores"}, new String[] {"familiasProducto", "familias-producto"},
            new String[] {"niveles", "niveles"}, new String[] {"impactos", "impactos"});

    @Test
    void catalogoAreasVaASuRutaConElBearerDeLaSesionYDesenvuelveElApiResponse() throws Exception {
        MockHttpSession sesion = login("ana");
        for (String c : CATALOGOS_SIMPLES) {
            // El payload (incluido un area/ruta/empresa del navegador) no llega a la URL.
            JsonNode json = json(accion(sesion, "{\"action\":\"faret.catalogos." + c + "\",\"_modulo\":\"faret-nc\",\"areaId\":5,\"ruta\":\"/x\",\"empresa\":\"INNPACK\"}")
                    .andExpect(status().isOk()).andReturn());
            assertThat(json.get("ok").asBoolean()).isTrue();
            assertThat(json.get("data").toString()).isEqualTo("{\"ruta\":\"/api/catalogos/" + c + "\",\"usuario\":20}");
        }
        assertThat(QC.lecturas()).containsExactly("GET /api/catalogos/areas");
        assertThat(QC.lecturasDeUsuario()).containsExactly(20);
        assertThat(MC.peticiones()).isEmpty();
        assertThat(CALIDAD.peticiones()).isEmpty();
    }

    @Test
    void operadoresYMaquinasLlevanAreaIdSoloSiEsEnteroPositivo() throws Exception {
        MockHttpSession sesion = login("admin");
        for (String c : List.of("operadores", "maquinas")) {
            for (String extra : List.of("\"areaId\":7", "\"areaId\":\" 8 \"", "\"areaId\":0", "\"areaId\":-3", "\"areaId\":\"abc\"", "\"areaId\":1.5",
                    "\"areaId\":null", "\"areaId\":99999999999", "\"x\":1")) {
                accion(sesion, "{\"action\":\"faret.catalogos." + c + "\",\"_modulo\":\"faret-nc\"," + extra + "}").andExpect(jsonPath("$.ok").value(true));
            }
        }
        List<String> esperadas = new java.util.ArrayList<>();
        for (String c : List.of("operadores", "maquinas")) {
            String ruta = "GET /api/catalogos/" + c;
            esperadas.add(ruta + "?areaId=7");
            esperadas.add(ruta + "?areaId=8");
            for (int i = 0; i < 7; i++) {
                esperadas.add(ruta);
            }
        }
        assertThat(QC.lecturas()).containsExactlyElementsOf(esperadas);
        // bool / objeto / array en areaId (regla 2e): rechazado sin llamar a la API.
        QC.limpiarLecturas();
        for (String valor : List.of("true", "false", "{\"a\":1}", "[1]", "[]")) {
            accion(sesion, "{\"action\":\"faret.catalogos.operadores\",\"_modulo\":\"faret-nc\",\"areaId\":" + valor + "}")
                    .andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
        }
        assertThat(QC.lecturas()).isEmpty();
    }

    @Test
    void losOchoCatalogosPncVanAlSegmentoGuionadoDeLaApi() throws Exception {
        MockHttpSession sesion = login("clara");
        for (String[] c : PNC) {
            JsonNode json = json(accion(sesion, "{\"action\":\"faret.pncCatalogos." + c[0] + ".list\",\"_modulo\":\"faret-nc\",\"id\":3,\"nombre\":\"x\"}")
                    .andExpect(status().isOk()).andReturn());
            assertThat(json.get("data").toString()).isEqualTo("{\"ruta\":\"/api/pnc-catalogos/" + c[1] + "\",\"usuario\":30}");
        }
        assertThat(QC.lecturas()).containsExactly(PNC.stream().map(c -> "GET /api/pnc-catalogos/" + c[1]).toArray(String[]::new));
        // Acciones que el frontend de Photino 9e1b556 ya no usa: no se habilitan (el contract check las marcaría SOLO_WEB).
        for (String a : List.of("faret.catalogos.inspectores", "faret.catalogos.defectos", "faret.health")) {
            accion(sesion, "{\"action\":\"" + a + "\",\"_modulo\":\"faret-nc\"}").andExpect(status().isForbidden());
        }
        // crear / desactivar siguen denegadas (son escrituras, fuera de esta fase).
        for (String a : List.of("faret.pncCatalogos.clientes.crear", "faret.pncCatalogos.clientes.desactivar", "faret.catalogos.areas.crear",
                "faret.catalogos.operadores.crear", "faret.catalogos.maquinas.crear")) {
            accion(sesion, "{\"action\":\"" + a + "\",\"_modulo\":\"faret-nc\"}").andExpect(status().isForbidden());
        }
        assertThat(QC.lecturas()).hasSize(PNC.size());
    }

    @Test
    void catalogosErroresDeLaApiYCuerposNoReconocidos() throws Exception {
        MockHttpSession sesion = login("admin");
        QC.respuestaLectura("/api/catalogos/areas", 403, "{\"success\":false,\"message\":\"Sin permiso para ver áreas\",\"data\":null}");
        accion(sesion, "{\"action\":\"faret.catalogos.areas\",\"_modulo\":\"faret-nc\"}").andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Sin permiso para ver áreas"));
        QC.respuestaLectura("/api/catalogos/maquinas", 500, "<html>boom</html>");
        MvcResult r = accion(sesion, "{\"action\":\"faret.catalogos.maquinas\",\"_modulo\":\"faret-nc\"}").andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret")).andReturn();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("boom");
        QC.respuestaLectura("/api/pnc-catalogos/niveles", 200, "{\"success\":true,\"data\":[{\"id\":1,\"nombre\":\"Alto\"}],\"errors\":null}");
        accion(sesion, "{\"action\":\"faret.pncCatalogos.niveles.list\",\"_modulo\":\"faret-nc\"}").andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data[0].nombre").value("Alto"));
    }

    @Test
    void faseARolesEmpresaYModulo() throws Exception {
        List<String> acciones = new java.util.ArrayList<>();
        CATALOGOS_SIMPLES.forEach(c -> acciones.add("faret.catalogos." + c));
        acciones.add("faret.catalogos.operadores");
        acciones.add("faret.catalogos.maquinas");
        PNC.forEach(c -> acciones.add("faret.pncCatalogos." + c[0] + ".list"));
        assertThat(acciones).hasSize(11);
        for (String a : acciones) {
            for (String rol : List.of("ADMIN", "ADMIN_TI", "CALIDAD", "INSPECTOR", "CONSULTA")) {
                assertThat(policy.evaluar(a, usuario("FARET", rol))).as(a + " " + rol).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            }
            assertThat(policy.evaluar(a, usuario("FARET", "operador"))).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
            assertThat(policy.evaluar(a, usuario("INNPACK", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
            assertThat(PermisosModulo.esLectura(a)).as(a).isTrue();
        }
        // Permisos por módulo de Photino: CALIDAD no tiene faret-data; sin módulo declarado tampoco.
        accion(login("ana"), "{\"action\":\"faret.catalogos.areas\",\"_modulo\":\"faret-data\"}").andExpect(status().isForbidden());
        accion(login("ana"), "{\"action\":\"faret.catalogos.areas\"}").andExpect(status().isForbidden());
        accion(login("clara"), "{\"action\":\"faret.catalogos.areas\",\"_modulo\":\"faret-nc\"}").andExpect(status().isOk());
        assertThat(QC.lecturas()).containsExactly("GET /api/catalogos/areas");
        QC.limpiarLecturas();
        accion(loginInnpack("operador1"), "{\"action\":\"faret.catalogos.areas\",\"_modulo\":\"faret-nc\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        assertThat(QC.lecturas()).isEmpty();
    }

    // ----------------------------------------------------------------------------- B: no conformidades (MejoraContinua)

    private static final String NC = "/api/no-conformidades";
    private static final List<String[]> NC_LECTURAS = List.of(
            new String[] {"faret.nc.get", ""}, new String[] {"faret.nc.seguimiento.list", "/seguimiento"},
            new String[] {"faret.nc.analisis.get", "/analisis"}, new String[] {"faret.nc.acciones.list", "/acciones"},
            new String[] {"faret.nc.adjuntos.list", "/adjuntos"});

    @Test
    void lasCincoLecturasDeUnaNcVanAMejoraContinuaConElIdSinAuthorizationYDevuelvenElJsonCrudo() throws Exception {
        MockHttpSession sesion = login("ana");
        for (String[] a : NC_LECTURAS) {
            MC.responder(NC + "/12" + a[1], 200, "{\"ruta\":\"" + a[1] + "\",\"id\":12}");
            // Todo lo demás del payload (incluido un data anidado con otro id) se ignora.
            JsonNode json = json(accion(sesion, "{\"action\":\"" + a[0] + "\",\"_modulo\":\"faret-nc\",\"id\":12,\"empresa\":\"INNPACK\",\"ruta\":\"/x\","
                    + "\"data\":{\"id\":99}}").andExpect(status().isOk()).andReturn());
            assertThat(json.get("ok").asBoolean()).isTrue();
            assertThat(json.get("data").toString()).isEqualTo("{\"ruta\":\"" + a[1] + "\",\"id\":12}");
        }
        // El id también puede venir como texto (TryGetInt de Photino).
        accion(sesion, "{\"action\":\"faret.nc.get\",\"_modulo\":\"faret-nc\",\"id\":\" 12 \"}").andExpect(jsonPath("$.ok").value(true));
        assertThat(MC.peticiones()).containsExactly("GET " + NC + "/12", "GET " + NC + "/12/seguimiento", "GET " + NC + "/12/analisis",
                "GET " + NC + "/12/acciones", "GET " + NC + "/12/adjuntos", "GET " + NC + "/12");
        // Photino nunca les manda Authorization; el gateway tampoco, aunque haya sesión FARET.
        assertThat(MC.authorizations()).hasSize(6).containsOnlyNulls();
        assertThat(QC.lecturas()).isEmpty();
        assertThat(CALIDAD.peticiones()).isEmpty();
    }

    @Test
    void elIdDeLaNcSeValidaAntesDeIrALaRuta() throws Exception {
        MockHttpSession sesion = login("admin");
        for (String[] a : NC_LECTURAS) {
            for (String id : List.of("", "\"id\":null,", "\"id\":0,", "\"id\":-1,", "\"id\":\"abc\",", "\"id\":\"\",", "\"id\":1.5,", "\"id\":99999999999,",
                    "\"id\":\"1/../../x\",", "\"id\":\"1?x=1\",", "\"id\":\"../1\",", "\"id\":\"12abc\",")) {
                accion(sesion, "{\"action\":\"" + a[0] + "\",\"_modulo\":\"faret-nc\"," + id + "\"x\":1}").andExpect(jsonPath("$.ok").value(false))
                        .andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
            }
            // Regla 2e: bool / objeto / array.
            for (String valor : List.of("true", "false", "{\"a\":1}", "[12]", "[]")) {
                accion(sesion, "{\"action\":\"" + a[0] + "\",\"_modulo\":\"faret-nc\",\"id\":" + valor + "}").andExpect(jsonPath("$.ok").value(false))
                        .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
            }
        }
        assertThat(MC.peticiones()).isEmpty();
    }

    @Test
    void erroresDeMejoraContinuaSeLeenComoExtractMcErrorMessageYUn401NoCierraLaSesion() throws Exception {
        MockHttpSession sesion = login("admin");
        MC.responder(NC + "/7", 404, "{\"mensaje\":\"No existe la no conformidad\",\"title\":\"Not Found\"}");
        accion(sesion, "{\"action\":\"faret.nc.get\",\"_modulo\":\"faret-nc\",\"id\":7}").andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("No existe la no conformidad"));
        MC.responder(NC + "/7/acciones", 500, "{\"type\":\"about:blank\",\"title\":\"Internal Server Error\",\"stack\":\"SqlException x\"}");
        MvcResult r = accion(sesion, "{\"action\":\"faret.nc.acciones.list\",\"_modulo\":\"faret-nc\",\"id\":7}").andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Internal Server Error")).andReturn();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SqlException");
        MC.responder(NC + "/7/seguimiento", 502, "<html>gateway</html>");
        accion(sesion, "{\"action\":\"faret.nc.seguimiento.list\",\"_modulo\":\"faret-nc\",\"id\":7}")
                .andExpect(jsonPath("$.error").value("Error al comunicarse con la API de Mejora Continua"));
        MC.responder(NC + "/7/adjuntos", 500, null);
        accion(sesion, "{\"action\":\"faret.nc.adjuntos.list\",\"_modulo\":\"faret-nc\",\"id\":7}")
                .andExpect(jsonPath("$.error").value("HTTP 500: Internal Server Error"));
        // Un 401 de MejoraContinua es un error normal: no invalida la sesión FARET del usuario.
        MC.responder(NC + "/8", 401, "{\"title\":\"Unauthorized\"}");
        accion(sesion, "{\"action\":\"faret.nc.get\",\"_modulo\":\"faret-nc\",\"id\":8}").andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
        assertThat(sesion.isInvalid()).isFalse();
        // Sin respuesta configurada el fake devuelve 404 ProblemDetails: título.
        accion(sesion, "{\"action\":\"faret.nc.get\",\"_modulo\":\"faret-nc\",\"id\":404}").andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void analisisSinAnalisisTodaviaEsOkNuloYCualquierOtroErrorSigueSiendoError() throws Exception {
        MockHttpSession sesion = login("admin");
        MC.responder(NC + "/5/analisis", 404, "{\"mensaje\":\"La no conformidad aún no tiene un análisis registrado.\"}");
        accion(sesion, "{\"action\":\"faret.nc.analisis.get\",\"_modulo\":\"faret-nc\",\"id\":5}").andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
        MC.responder(NC + "/6/analisis", 404, "{\"mensaje\":\"LA NC AÚN NO TIENE UN ANÁLISIS\"}");
        accion(sesion, "{\"action\":\"faret.nc.analisis.get\",\"_modulo\":\"faret-nc\",\"id\":6}").andExpect(jsonPath("$.ok").value(true));
        MC.responder(NC + "/9/analisis", 404, "{\"mensaje\":\"No conformidad inexistente\"}");
        accion(sesion, "{\"action\":\"faret.nc.analisis.get\",\"_modulo\":\"faret-nc\",\"id\":9}").andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("No conformidad inexistente"));
        MC.responder(NC + "/10/analisis", 200, "{\"id\":3,\"causaRaiz\":\"x\"}");
        accion(sesion, "{\"action\":\"faret.nc.analisis.get\",\"_modulo\":\"faret-nc\",\"id\":10}").andExpect(jsonPath("$.data.causaRaiz").value("x"));
    }

    @Test
    void faseBRolesEmpresaModuloYEscriturasSiguenDenegadas() throws Exception {
        for (String[] a : NC_LECTURAS) {
            for (String rol : List.of("ADMIN", "ADMIN_TI", "CALIDAD", "INSPECTOR", "CONSULTA")) {
                assertThat(policy.evaluar(a[0], usuario("FARET", rol))).as(a[0] + " " + rol).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            }
            assertThat(policy.evaluar(a[0], usuario("FARET", "operador"))).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
            assertThat(policy.evaluar(a[0], usuario("INNPACK", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
            assertThat(PermisosModulo.esLectura(a[0])).as(a[0]).isTrue();
        }
        MockHttpSession innpack = loginInnpack("operador1");
        accion(innpack, "{\"action\":\"faret.nc.get\",\"_modulo\":\"faret-nc\",\"id\":1}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        // Permisos por módulo: sin módulo declarado, o con uno inexistente, no entra.
        MockHttpSession ana = login("ana");
        accion(ana, "{\"action\":\"faret.nc.get\",\"id\":1}").andExpect(status().isForbidden());
        accion(ana, "{\"action\":\"faret.nc.get\",\"_modulo\":\"inventado\",\"id\":1}").andExpect(status().isForbidden());
        // adjuntos.abrir (fase E) y todas las escrituras de NC siguen denegadas.
        for (String a : List.of("faret.nc.adjuntos.abrir", "faret.nc.create", "faret.nc.crearRegistro", "faret.nc.actualizarRegistro", "faret.nc.update",
                "faret.nc.eliminarFila", "faret.nc.gestion.actualizar", "faret.nc.cerrar", "faret.nc.seguimiento.crear", "faret.nc.analisis.guardar",
                "faret.nc.acciones.crear", "faret.nc.acciones.actualizar", "faret.nc.adjuntos.subir", "faret.nc.adjuntos.eliminar")) {
            accion(ana, "{\"action\":\"" + a + "\",\"_modulo\":\"faret-nc\",\"id\":1}").andExpect(status().isForbidden());
        }
        assertThat(MC.peticiones()).isEmpty();
    }

    // ----------------------------------------------------------------------------- C: talleres, importación y data.resumen

    private static final String TALLERES = "/api/talleres-externos";

    @Test
    void talleresExternosListLlevaLosDoceFiltrosEnElOrdenDePhotinoYLaPaginacionAcotadaA500() throws Exception {
        MockHttpSession sesion = login("clara");
        accion(sesion, "{\"action\":\"faret.talleresExternos.list\",\"_modulo\":\"faret-talleres-externos\"}").andExpect(status().isOk());
        // Claves en orden "inverso": la query sale en el orden de BuildTalleresExternosFiltros + page, pageSize.
        accion(sesion, "{\"action\":\"faret.talleresExternos.list\",\"pageSize\":800,\"page\":2,\"fechaCompromisoHasta\":\"2026-12-31\",\"fechaCompromisoDesde\":\"2026-12-01\","
                + "\"fechaAsignacionHasta\":\"2026-11-30\",\"fechaAsignacionDesde\":\"2026-11-01\",\"estado\":\"EN PROCESO\",\"prioridad\":\"ALTA\","
                + "\"responsable\":\"Pérez\",\"proceso\":\"Pintura\",\"tallerExterno\":\"Taller 1\",\"cliente\":\" ACME \",\"producto\":\"Caja\","
                + "\"nv\":12345,\"_modulo\":\"faret-talleres-externos\",\"ruta\":\"/x\",\"data\":{\"nv\":\"otro\"}}").andExpect(status().isOk());
        accion(sesion, "{\"action\":\"faret.talleresExternos.list\",\"page\":1,\"pageSize\":200,\"_modulo\":\"faret-talleres-externos\"}").andExpect(status().isOk());
        // Valores de paginación que Photino ignora en silencio: no viajan.
        accion(sesion, "{\"action\":\"faret.talleresExternos.list\",\"page\":0,\"pageSize\":\"x\",\"_modulo\":\"faret-talleres-externos\"}").andExpect(status().isOk());
        assertThat(QC.lecturas()).containsExactly(
                "GET " + TALLERES,
                "GET " + TALLERES + "?nv=12345&producto=Caja&cliente=ACME&tallerExterno=Taller%201&proceso=Pintura&responsable=P%C3%A9rez&prioridad=ALTA"
                        + "&estado=EN%20PROCESO&fechaAsignacionDesde=2026-11-01&fechaAsignacionHasta=2026-11-30&fechaCompromisoDesde=2026-12-01"
                        + "&fechaCompromisoHasta=2026-12-31&page=2&pageSize=500",
                "GET " + TALLERES + "?page=1&pageSize=200",
                "GET " + TALLERES);
        assertThat(QC.lecturasDeUsuario()).containsOnly(30);
    }

    @Test
    void talleresExternosListRechazaFiltrosYPaginacionInvalidosSinLlamarALaApi() throws Exception {
        MockHttpSession sesion = login("admin");
        for (String clave : List.of("nv", "producto", "cliente", "tallerExterno", "proceso", "responsable", "prioridad", "estado", "fechaAsignacionDesde",
                "fechaCompromisoHasta", "page", "pageSize")) {
            for (String valor : List.of("true", "{\"a\":1}", "[\"x\"]")) {
                accion(sesion, "{\"action\":\"faret.talleresExternos.list\",\"_modulo\":\"faret-talleres-externos\",\"" + clave + "\":" + valor + "}")
                        .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
            }
        }
        accion(sesion, "{\"action\":\"faret.talleresExternos.list\",\"_modulo\":\"faret-talleres-externos\",\"fechaAsignacionDesde\":\"2026-02-30\"}")
                .andExpect(jsonPath("$.error").value("La fecha fechaAsignacionDesde no es válida (formato AAAA-MM-DD)."));
        accion(sesion, "{\"action\":\"faret.talleresExternos.list\",\"_modulo\":\"faret-talleres-externos\",\"producto\":\"" + "a".repeat(201) + "\"}")
                .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_LARGO));
        accion(sesion, "{\"action\":\"faret.talleresExternos.list\",\"_modulo\":\"faret-talleres-externos\",\"proceso\":\"a\\nb\"}")
                .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
        accion(sesion, "{\"action\":\"faret.talleresExternos.list\",\"_modulo\":\"faret-talleres-externos\",\"page\":1000001}")
                .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_PAGINACION));
        assertThat(QC.lecturas()).isEmpty();
    }

    @Test
    void talleresCatalogosImportacionListYDataResumenIgnoranElPayloadSalvoSusFiltros() throws Exception {
        MockHttpSession sesion = login("admin");
        JsonNode json = json(accion(sesion, "{\"action\":\"faret.talleresExternos.catalogos\",\"_modulo\":\"faret-talleres-externos\",\"id\":5,\"nombre\":\"x\"}")
                .andExpect(status().isOk()).andReturn());
        assertThat(json.get("data").toString()).isEqualTo("{\"ruta\":\"" + TALLERES + "/catalogos\",\"usuario\":10}");
        json = json(accion(sesion, "{\"action\":\"faret.importacion.list\",\"_modulo\":\"faret-importacion\",\"page\":3,\"loteId\":\"x\"}")
                .andExpect(status().isOk()).andReturn());
        assertThat(json.get("data").toString()).isEqualTo("{\"ruta\":\"/api/importaciones\",\"usuario\":10}");
        // data.resumen: BuildDataFiltros (cliente, tipoPnc, nivel, fechaDesde, fechaHasta) y SIN page/pageSize.
        accion(sesion, "{\"action\":\"faret.data.resumen\",\"_modulo\":\"faret-data\",\"page\":2,\"pageSize\":50,\"fechaHasta\":\"2026-10-31\",\"fechaDesde\":\"2026-10-01\","
                + "\"nivel\":\"Alto\",\"tipoPnc\":\"Rechazo\",\"cliente\":\"  Ñandú & Co  \",\"empresa\":\"INNPACK\"}").andExpect(jsonPath("$.ok").value(true));
        accion(sesion, "{\"action\":\"faret.data.resumen\",\"_modulo\":\"faret-data\"}").andExpect(jsonPath("$.ok").value(true));
        assertThat(QC.lecturas()).containsExactly("GET " + TALLERES + "/catalogos", "GET /api/importaciones",
                "GET /api/importaciones/pnc/resumen?cliente=%C3%91and%C3%BA%20%26%20Co&tipoPnc=Rechazo&nivel=Alto&fechaDesde=2026-10-01&fechaHasta=2026-10-31",
                "GET /api/importaciones/pnc/resumen");
        // Filtros inválidos de data.resumen.
        QC.limpiarLecturas();
        accion(sesion, "{\"action\":\"faret.data.resumen\",\"_modulo\":\"faret-data\",\"cliente\":true}")
                .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
        accion(sesion, "{\"action\":\"faret.data.resumen\",\"_modulo\":\"faret-data\",\"fechaDesde\":\"ayer\"}")
                .andExpect(jsonPath("$.error").value("La fecha fechaDesde no es válida (formato AAAA-MM-DD)."));
        assertThat(QC.lecturas()).isEmpty();
    }

    @Test
    void faseCErroresDeLaApiYUn401InvalidaSoloEsaSesion() throws Exception {
        MockHttpSession sesion = login("admin");
        QC.respuestaLectura("/api/importaciones", 400, "{\"success\":false,\"message\":\"Sin historial\",\"data\":null}");
        accion(sesion, "{\"action\":\"faret.importacion.list\",\"_modulo\":\"faret-importacion\"}").andExpect(jsonPath("$.error").value("Sin historial"));
        QC.respuestaLectura(TALLERES + "/catalogos", 500, "{\"title\":\"Internal Server Error\",\"detail\":\"SqlException\"}");
        MvcResult r = accion(sesion, "{\"action\":\"faret.talleresExternos.catalogos\",\"_modulo\":\"faret-talleres-externos\"}")
                .andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret")).andReturn();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SqlException");
        MockHttpSession otra = login("ana");
        QC.rechazarLecturas(true);
        accion(sesion, "{\"action\":\"faret.data.resumen\",\"_modulo\":\"faret-data\"}").andExpect(status().isUnauthorized());
        assertThat(sesion.isInvalid()).isTrue();
        assertThat(otra.isInvalid()).isFalse();
    }

    @Test
    void faseCRolesEmpresaModuloYAccionesNoHabilitadas() throws Exception {
        List<String> acciones = List.of("faret.talleresExternos.list", "faret.talleresExternos.catalogos", "faret.importacion.list", "faret.data.resumen");
        for (String a : acciones) {
            for (String rol : List.of("ADMIN", "ADMIN_TI", "CALIDAD", "INSPECTOR", "CONSULTA")) {
                assertThat(policy.evaluar(a, usuario("FARET", rol))).as(a + " " + rol).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            }
            assertThat(policy.evaluar(a, usuario("FARET", "operador"))).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
            assertThat(policy.evaluar(a, usuario("INNPACK", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
            assertThat(PermisosModulo.esLectura(a)).as(a).isTrue();
        }
        accion(loginInnpack("operador1"), "{\"action\":\"faret.importacion.list\",\"_modulo\":\"faret-importacion\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        // Permisos por módulo de Photino: faret-data y faret-importacion solo ADMIN (CALIDAD no entra); CONSULTA sí a talleres.
        MockHttpSession ana = login("ana");
        accion(ana, "{\"action\":\"faret.importacion.list\",\"_modulo\":\"faret-importacion\"}").andExpect(status().isForbidden());
        accion(ana, "{\"action\":\"faret.data.resumen\",\"_modulo\":\"faret-data\"}").andExpect(status().isForbidden());
        accion(login("clara"), "{\"action\":\"faret.talleresExternos.catalogos\",\"_modulo\":\"faret-talleres-externos\"}").andExpect(status().isOk());
        accion(login("clara"), "{\"action\":\"faret.talleresExternos.catalogos\"}").andExpect(status().isForbidden());
        assertThat(QC.lecturas()).containsExactly("GET " + TALLERES + "/catalogos");
        // No habilitadas: el frontend de Photino 9e1b556 no las usa (registros.*, talleresExternos.get) o son escrituras.
        for (String a : List.of("faret.registros.list", "faret.registros.get", "faret.talleresExternos.get", "faret.talleresExternos.create",
                "faret.talleresExternos.update", "faret.talleresExternos.lote", "faret.talleresExternos.eliminar", "faret.importacion.validar",
                "faret.importacion.confirmar", "faret.talleresExternos.catalogos.crearTaller")) {
            accion(login("admin"), "{\"action\":\"" + a + "\",\"_modulo\":\"faret-talleres-externos\",\"id\":1}").andExpect(status().isForbidden());
        }
        assertThat(QC.lecturas()).hasSize(1);
    }

    // ----------------------------------------------------------------------------- helpers

    private static SessionUser usuario(String empresa, String rol) {
        return new SessionUser(1, "u", "U", rol, empresa, "t", Instant.now(), Instant.now().plusSeconds(60));
    }

    private MockHttpSession login(String identificador) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/faret/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.78.0." + IP.getAndIncrement());
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
                            r.setRemoteAddr("10.78.1." + IP.getAndIncrement());
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
            Path dir = Files.createTempDirectory("qcc-web-fixture-6c");
            Files.writeString(dir.resolve("index.html"), "<!doctype html><title>fixture</title>");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
