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
 * C: talleres externos / registros / importación / data.resumen (Bearer). D: Calidad (sin Authorization). E: faret.nc.adjuntos.abrir
 * (MejoraContinua). F: faret.usuarios.list / permisos.get (solo admin_ti, Bearer). TODAS las APIs son fakes en localhost.
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
        QC.agregar(new FakeFaretApi.Usuario(50, "tecnico", "tecnico@faret.cl", PASS, "Tecnico TI", List.of("ADMIN_TI")));
        INNPACK.agregar(new FakeInnpackApi.Usuario(41, "adminti1", PASS, "Admin TI Innpack", "admin_ti", true));
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
        // desactivar sigue denegada (el frontend de Photino 9e1b556 no la usa); crear se habilitó en la Fase F1 (BridgeFaseF1Test).
        for (String a : List.of("faret.pncCatalogos.clientes.desactivar", "faret.pncCatalogos.niveles.desactivar", "faret.catalogos.areas.desactivar",
                "faret.catalogos.areas.editar", "faret.catalogos.operadores.eliminar")) {
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
        // Todas las escrituras de NC siguen denegadas.
        for (String a : List.of("faret.nc.create", "faret.nc.crearRegistro", "faret.nc.actualizarRegistro", "faret.nc.update",
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

    // ----------------------------------------------------------------------------- D: Calidad (inspecciones y pallets)

    private static final String INSP = "/calidad-faret/registros";
    private static final String PALLET = "/calidad-faret-pallet/registros";

    @Test
    void inspeccionesListLlevaLosSieteFiltrosEnElOrdenDePhotinoYPaginacionSinAuthorization() throws Exception {
        MC.limpiar();
        CALIDAD.responder(INSP, 200, "{\"ok\":true,\"data\":{\"items\":[{\"id\":1}],\"total\":1}}");
        MockHttpSession sesion = login("ana");
        JsonNode json = json(accion(sesion, "{\"action\":\"faret.inspecciones.list\",\"_modulo\":\"faret-inspecciones\",\"page\":2,\"pageSize\":500}")
                .andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.at("/data/items/0/id").asInt()).isEqualTo(1);
        accion(sesion, "{\"action\":\"faret.inspecciones.list\",\"_modulo\":\"faret-inspecciones\",\"pageSize\":50,\"page\":1,\"nvFaret\":\"NV 1/2\","
                + "\"presentaDefectos\":\"true\",\"maquina\":\"M-1\",\"operador\":\" Ñuñoa \",\"areaControl\":\"Prensa\",\"fechaHasta\":\"2026-10-31\","
                + "\"fechaDesde\":\"2026-10-01\",\"empresa\":\"INNPACK\",\"data\":{\"maquina\":\"otra\"}}").andExpect(status().isOk());
        accion(sesion, "{\"action\":\"faret.inspecciones.list\",\"_modulo\":\"faret-inspecciones\"}").andExpect(status().isOk());
        assertThat(CALIDAD.peticiones()).containsExactly(
                "GET " + INSP + "?page=2&pageSize=200",
                "GET " + INSP + "?fechaDesde=2026-10-01&fechaHasta=2026-10-31&areaControl=Prensa&operador=%C3%91u%C3%B1oa&maquina=M-1"
                        + "&presentaDefectos=true&nvFaret=NV%201%2F2&page=1&pageSize=50",
                "GET " + INSP);
        assertThat(CALIDAD.authorizations()).hasSize(3).containsOnlyNulls();
        assertThat(QC.lecturas()).isEmpty();
        assertThat(MC.peticiones()).isEmpty();
    }

    @Test
    void inspeccionesPalletListLlevaLosSeisFiltrosPropiosDelPallet() throws Exception {
        CALIDAD.responder(PALLET, 200, "{\"ok\":true,\"data\":{\"items\":[],\"total\":0}}");
        MockHttpSession sesion = login("ana");
        accion(sesion, "{\"action\":\"faret.inspeccionesPallet.list\",\"_modulo\":\"faret-inspecciones-pallet\",\"page\":1,\"pageSize\":10,\"operador\":\"Ana\","
                + "\"maquina\":\"M2\",\"numeroPallet\":4567,\"tipoMaterial\":\"Cartón\",\"fechaHasta\":\"2026-10-09\",\"fechaDesde\":\"2026-10-01\","
                + "\"areaControl\":\"no va\",\"nvFaret\":\"no va\",\"presentaDefectos\":\"true\"}").andExpect(jsonPath("$.ok").value(true));
        accion(sesion, "{\"action\":\"faret.inspeccionesPallet.list\",\"_modulo\":\"faret-inspecciones-pallet\",\"pageSize\":201}").andExpect(jsonPath("$.ok").value(true));
        assertThat(CALIDAD.peticiones()).containsExactly(
                "GET " + PALLET + "?fechaDesde=2026-10-01&fechaHasta=2026-10-09&tipoMaterial=Cart%C3%B3n&numeroPallet=4567&maquina=M2&operador=Ana&page=1&pageSize=10",
                "GET " + PALLET + "?pageSize=200");
        assertThat(CALIDAD.authorizations()).containsOnlyNulls();
    }

    @Test
    void filtrosInvalidosDeCalidadSeRechazanSinLlamarALaApi() throws Exception {
        MockHttpSession sesion = login("admin");
        for (String accion : List.of("faret.inspecciones.list", "faret.inspeccionesPallet.list")) {
            String modulo = accion.contains("Pallet") ? "faret-inspecciones-pallet" : "faret-inspecciones";
            String base = "{\"action\":\"" + accion + "\",\"_modulo\":\"" + modulo + "\",";
            for (String clave : List.of("fechaDesde", "operador", "maquina", "page", "pageSize")) {
                for (String valor : List.of("true", "{\"a\":1}", "[1]")) {
                    accion(sesion, base + "\"" + clave + "\":" + valor + "}").andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
                }
            }
            accion(sesion, base + "\"fechaHasta\":\"2026-13-40\"}").andExpect(jsonPath("$.error").value("La fecha fechaHasta no es válida (formato AAAA-MM-DD)."));
            accion(sesion, base + "\"operador\":\"" + "a".repeat(201) + "\"}").andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_LARGO));
            accion(sesion, base + "\"maquina\":\"a\\u0000b\"}").andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
            accion(sesion, base + "\"page\":1000001}").andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_PAGINACION));
        }
        accion(sesion, "{\"action\":\"faret.inspecciones.list\",\"_modulo\":\"faret-inspecciones\",\"presentaDefectos\":\"quizas\"}")
                .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_PRESENTA_DEFECTOS));
        assertThat(CALIDAD.peticiones()).isEmpty();
    }

    @Test
    void adjuntosDeInspeccionYDetalleDePalletValidanElIdAntesDeIrALaRuta() throws Exception {
        CALIDAD.responder(INSP + "/31/adjuntos", 200, "{\"ok\":true,\"data\":[{\"id\":9,\"nombre\":\"f.jpg\"}]}");
        CALIDAD.responder(PALLET + "/32", 200, "{\"ok\":true,\"data\":{\"id\":32,\"numeroPallet\":\"P-1\"}}");
        MockHttpSession sesion = login("admin");
        accion(sesion, "{\"action\":\"faret.inspecciones.adjuntos\",\"_modulo\":\"faret-inspecciones\",\"id\":31,\"data\":{\"id\":99}}")
                .andExpect(jsonPath("$.data[0].nombre").value("f.jpg"));
        accion(sesion, "{\"action\":\"faret.inspeccionesPallet.get\",\"_modulo\":\"faret-inspecciones-pallet\",\"id\":\"32\"}")
                .andExpect(jsonPath("$.data.numeroPallet").value("P-1"));
        assertThat(CALIDAD.peticiones()).containsExactly("GET " + INSP + "/31/adjuntos", "GET " + PALLET + "/32");
        assertThat(CALIDAD.authorizations()).containsOnlyNulls();
        CALIDAD.limpiar();
        for (String a : List.of("faret.inspecciones.adjuntos", "faret.inspeccionesPallet.get")) {
            String modulo = a.contains("Pallet") ? "faret-inspecciones-pallet" : "faret-inspecciones";
            for (String id : List.of("", "\"id\":null,", "\"id\":0,", "\"id\":-5,", "\"id\":\"abc\",", "\"id\":1.5,", "\"id\":99999999999,",
                    "\"id\":\"1/../../x\",", "\"id\":\"1?a=b\",", "\"id\":\"\",")) {
                accion(sesion, "{\"action\":\"" + a + "\",\"_modulo\":\"" + modulo + "\"," + id + "\"x\":1}").andExpect(jsonPath("$.ok").value(false))
                        .andExpect(jsonPath("$.error").value("id es requerido"));
            }
            for (String valor : List.of("true", "{\"a\":1}", "[31]")) {
                accion(sesion, "{\"action\":\"" + a + "\",\"_modulo\":\"" + modulo + "\",\"id\":" + valor + "}")
                        .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
            }
        }
        assertThat(CALIDAD.peticiones()).isEmpty();
    }

    @Test
    void erroresDeCalidadSeDesenvuelvenComoTryUnwrapApiResponseYUn401NoCierraLaSesion() throws Exception {
        MockHttpSession sesion = login("admin");
        CALIDAD.responder(INSP, 500, "{\"ok\":false,\"message\":\"Error al listar\"}");
        accion(sesion, "{\"action\":\"faret.inspecciones.list\",\"_modulo\":\"faret-inspecciones\"}").andExpect(jsonPath("$.error").value("Error al listar"));
        CALIDAD.responder(PALLET, 502, "<html>bad gateway</html>");
        MvcResult r = accion(sesion, "{\"action\":\"faret.inspeccionesPallet.list\",\"_modulo\":\"faret-inspecciones-pallet\"}")
                .andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret")).andReturn();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("bad gateway");
        CALIDAD.responder(INSP + "/5/adjuntos", 401, "{\"ok\":false,\"message\":\"No autorizado\"}");
        accion(sesion, "{\"action\":\"faret.inspecciones.adjuntos\",\"_modulo\":\"faret-inspecciones\",\"id\":5}").andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value("No autorizado"));
        assertThat(sesion.isInvalid()).isFalse();
        // Sin respuesta configurada el fake devuelve 404 sin forma ApiResponse: genérico.
        accion(sesion, "{\"action\":\"faret.inspeccionesPallet.get\",\"_modulo\":\"faret-inspecciones-pallet\",\"id\":404}")
                .andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret"));
    }

    @Test
    void faseDRolesEmpresaModuloYAccionesNoHabilitadas() throws Exception {
        List<String> acciones = List.of("faret.inspecciones.list", "faret.inspecciones.adjuntos", "faret.inspeccionesPallet.list", "faret.inspeccionesPallet.get");
        for (String a : acciones) {
            for (String rol : List.of("ADMIN", "ADMIN_TI", "CALIDAD", "INSPECTOR", "CONSULTA")) {
                assertThat(policy.evaluar(a, usuario("FARET", rol))).as(a + " " + rol).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            }
            assertThat(policy.evaluar(a, usuario("FARET", "operador"))).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
            assertThat(policy.evaluar(a, usuario("INNPACK", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
            assertThat(PermisosModulo.esLectura(a)).as(a).isTrue();
        }
        accion(loginInnpack("operador1"), "{\"action\":\"faret.inspecciones.list\",\"_modulo\":\"faret-inspecciones\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        // Permisos por módulo: CONSULTA no tiene faret-inspecciones; sin módulo declarado nadie entra.
        accion(login("clara"), "{\"action\":\"faret.inspecciones.list\",\"_modulo\":\"faret-inspecciones\"}").andExpect(status().isForbidden());
        accion(login("clara"), "{\"action\":\"faret.inspeccionesPallet.list\",\"_modulo\":\"faret-inspecciones-pallet\"}").andExpect(status().isForbidden());
        accion(login("ana"), "{\"action\":\"faret.inspecciones.list\"}").andExpect(status().isForbidden());
        // Eliminar sigue denegada.
        for (String a : List.of("faret.inspecciones.eliminar", "faret.inspeccionesPallet.eliminar")) {
            accion(login("admin"), "{\"action\":\"" + a + "\",\"_modulo\":\"faret-inspecciones\",\"id\":1}").andExpect(status().isForbidden());
        }
        assertThat(CALIDAD.peticiones()).isEmpty();
    }

    // ----------------------------------------------------------------------------- E: faret.nc.adjuntos.abrir

    private static final String PNG_B64 = java.util.Base64.getEncoder().encodeToString(
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52});
    private static final String PDF_B64 = java.util.Base64.getEncoder().encodeToString("%PDF-1.4\n%fake\n".getBytes(StandardCharsets.US_ASCII));
    private static final String HTML_B64 = java.util.Base64.getEncoder().encodeToString("<html><script>x()</script></html>".getBytes(StandardCharsets.US_ASCII));
    private static final String ABRIR = "{\"action\":\"faret.nc.adjuntos.abrir\",\"_modulo\":\"faret-nc\"";

    @Test
    void adjuntosAbrirDevuelveSoloLosCuatroCamposValidadosConElTipoRealPorFirmaYSinAuthorization() throws Exception {
        MockHttpSession sesion = login("ana");
        // La API declara un MIME peligroso y un nombre con ruta/etiquetas: sale el tipo real y el nombre saneado; nada más pasa.
        MC.responder(NC + "/12/adjuntos/3", 200, "{\"id\":3,\"tipo\":\"FOTO\",\"nombreArchivo\":\"..\\\\x\\\\<img onerror=1>foto.png\","
                + "\"tipoMime\":\"text/html\",\"contenidoBase64\":\"" + PNG_B64 + "\",\"subidoPor\":\"Ana\",\"rutaInterna\":\"C:\\\\secreto\"}");
        JsonNode json = json(accion(sesion, ABRIR + ",\"id\":12,\"adjuntoId\":3,\"data\":{\"id\":99},\"ruta\":\"/x\"}").andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        JsonNode data = json.get("data");
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("id", "nombreArchivo", "tipoMime", "contenidoBase64");
        assertThat(data.get("id").asInt()).isEqualTo(3);
        assertThat(data.get("tipoMime").asString()).isEqualTo("image/png");
        assertThat(data.get("contenidoBase64").asString()).isEqualTo(PNG_B64);
        assertThat(data.get("nombreArchivo").asString()).doesNotContain("<").doesNotContain(">").doesNotContain("\\").doesNotContain("/").endsWith("foto.png");
        // Un PDF con id como texto.
        MC.responder(NC + "/12/adjuntos/4", 200, "{\"id\":4,\"nombreArchivo\":\"analisis.pdf\",\"tipoMime\":\"application/pdf\",\"contenidoBase64\":\"" + PDF_B64 + "\"}");
        JsonNode pdf = json(accion(sesion, ABRIR + ",\"id\":\" 12 \",\"adjuntoId\":\"4\"}").andExpect(status().isOk()).andReturn());
        assertThat(pdf.at("/data/tipoMime").asString()).isEqualTo("application/pdf");
        assertThat(pdf.at("/data/nombreArchivo").asString()).isEqualTo("analisis.pdf");
        assertThat(MC.peticiones()).containsExactly("GET " + NC + "/12/adjuntos/3", "GET " + NC + "/12/adjuntos/4");
        assertThat(MC.authorizations()).hasSize(2).containsOnlyNulls();
        assertThat(QC.lecturas()).isEmpty();
        assertThat(CALIDAD.peticiones()).isEmpty();
    }

    @Test
    void adjuntosAbrirRechazaContenidoNoSeguroVacioOGrandeSinReflejarLaRespuesta() throws Exception {
        MockHttpSession sesion = login("admin");
        String grande = "A".repeat(NC_MAX_BASE64 + 4);
        List<String[]> casos = List.of(
                new String[] {"1", "{\"nombreArchivo\":\"a.png\",\"tipoMime\":\"image/png\",\"contenidoBase64\":\"" + HTML_B64 + "\"}", "El adjunto no es válido."},
                new String[] {"2", "{\"nombreArchivo\":\"a.png\",\"tipoMime\":\"image/png\",\"contenidoBase64\":\"" + PNG_B64 + "\\\"onerror=\\\"x()\"}", "El adjunto no es válido."},
                new String[] {"3", "{\"nombreArchivo\":\"a.png\",\"tipoMime\":\"image/png\",\"contenidoBase64\":\"" + PNG_B64 + "=\\u0020x\"}", "El adjunto no es válido."},
                new String[] {"4", "{\"nombreArchivo\":\"a.png\",\"contenidoBase64\":\"\"}", "El adjunto no trae contenido"},
                new String[] {"5", "{\"nombreArchivo\":\"a.png\"}", "El adjunto no trae contenido"},
                new String[] {"6", "{\"contenidoBase64\":123}", "El adjunto no es válido."},
                new String[] {"7", "{\"nombreArchivo\":\"a.png\",\"contenidoBase64\":\"" + grande + "\"}", "El adjunto excede el tamaño máximo permitido."},
                new String[] {"8", "[1,2]", "El adjunto no trae contenido"},
                new String[] {"9", "\"texto\"", "El adjunto no trae contenido"});
        for (String[] c : casos) {
            MC.responder(NC + "/12/adjuntos/" + c[0], 200, c[1]);
            MvcResult r = accion(sesion, ABRIR + ",\"id\":12,\"adjuntoId\":" + c[0] + "}").andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(c[2])).andReturn();
            assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("onerror").doesNotContain("script");
        }
    }

    private static final int NC_MAX_BASE64 = ((10 * 1024 * 1024 + 2) / 3) * 4;

    @Test
    void adjuntosAbrirValidaAmbosIdsAntesDeIrALaRuta() throws Exception {
        MockHttpSession sesion = login("admin");
        for (String id : List.of("", "\"id\":null,", "\"id\":0,", "\"id\":-1,", "\"id\":\"abc\",", "\"id\":1.5,", "\"id\":99999999999,", "\"id\":\"1/../x\",", "\"id\":\"1?x\",")) {
            accion(sesion, "{\"action\":\"faret.nc.adjuntos.abrir\",\"_modulo\":\"faret-nc\"," + id + "\"adjuntoId\":3}").andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Falta el id de la no conformidad"));
        }
        for (String adj : List.of("", "\"adjuntoId\":null,", "\"adjuntoId\":0,", "\"adjuntoId\":-5,", "\"adjuntoId\":\"x\",", "\"adjuntoId\":2.5,",
                "\"adjuntoId\":99999999999,", "\"adjuntoId\":\"3/../../x\",", "\"adjuntoId\":\"3#\",")) {
            accion(sesion, "{\"action\":\"faret.nc.adjuntos.abrir\",\"_modulo\":\"faret-nc\",\"id\":12," + adj + "\"x\":1}").andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Falta el id del adjunto"));
        }
        for (String valor : List.of("true", "{\"a\":1}", "[3]", "[]")) {
            accion(sesion, ABRIR + ",\"id\":" + valor + ",\"adjuntoId\":3}").andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
            accion(sesion, ABRIR + ",\"id\":12,\"adjuntoId\":" + valor + "}").andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
        }
        assertThat(MC.peticiones()).isEmpty();
    }

    @Test
    void adjuntosAbrirErroresDeLaApiSeLeenComoMcYUn401NoCierraLaSesion() throws Exception {
        MockHttpSession sesion = login("admin");
        MC.responder(NC + "/7/adjuntos/1", 404, "{\"mensaje\":\"Adjunto no encontrado\",\"stack\":\"SqlException x\"}");
        MvcResult r = accion(sesion, ABRIR + ",\"id\":7,\"adjuntoId\":1}").andExpect(jsonPath("$.error").value("Adjunto no encontrado")).andReturn();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SqlException");
        MC.responder(NC + "/7/adjuntos/2", 502, "<html>gateway</html>");
        accion(sesion, ABRIR + ",\"id\":7,\"adjuntoId\":2}").andExpect(jsonPath("$.error").value("Error al comunicarse con la API de Mejora Continua"));
        MC.responder(NC + "/7/adjuntos/3", 401, "{\"title\":\"Unauthorized\"}");
        accion(sesion, ABRIR + ",\"id\":7,\"adjuntoId\":3}").andExpect(status().isOk()).andExpect(jsonPath("$.error").value("Unauthorized"));
        assertThat(sesion.isInvalid()).isFalse();
        MC.responder(NC + "/7/adjuntos/4", 200, null);
        accion(sesion, ABRIR + ",\"id\":7,\"adjuntoId\":4}").andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void faseERolesEmpresaModuloYEscriturasDeAdjuntosSiguenDenegadas() throws Exception {
        for (String rol : List.of("ADMIN", "ADMIN_TI", "CALIDAD", "INSPECTOR", "CONSULTA")) {
            assertThat(policy.evaluar("faret.nc.adjuntos.abrir", usuario("FARET", rol))).as(rol).isInstanceOf(ActionPolicy.Decision.Permitida.class);
        }
        assertThat(policy.evaluar("faret.nc.adjuntos.abrir", usuario("FARET", "operador"))).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
        assertThat(policy.evaluar("faret.nc.adjuntos.abrir", usuario("INNPACK", "admin"))).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
        assertThat(PermisosModulo.esLectura("faret.nc.adjuntos.abrir")).isTrue();
        accion(loginInnpack("operador1"), ABRIR + ",\"id\":1,\"adjuntoId\":1}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        accion(login("ana"), "{\"action\":\"faret.nc.adjuntos.abrir\",\"id\":1,\"adjuntoId\":1}").andExpect(status().isForbidden());
        accion(login("ana"), "{\"action\":\"faret.nc.adjuntos.abrir\",\"_modulo\":\"faret-data\",\"id\":1,\"adjuntoId\":1}").andExpect(status().isForbidden());
        for (String a : List.of("faret.nc.adjuntos.subir", "faret.nc.adjuntos.eliminar")) {
            accion(login("admin"), "{\"action\":\"" + a + "\",\"_modulo\":\"faret-nc\",\"id\":1,\"adjuntoId\":1}").andExpect(status().isForbidden());
        }
        assertThat(MC.peticiones()).isEmpty();
    }

    // ----------------------------------------------------------------------------- F: Gestión de Usuarios FARET (lectura)

    private static final String USUARIOS = "/api/usuarios";
    private static final List<String> USUARIOS_LECTURAS = List.of("faret.usuarios.list", "faret.usuarios.permisos.get");

    @Test
    void usuariosListSoloReproyectaLosCamposDelDtoYNuncaDejaPasarUnHash() throws Exception {
        MockHttpSession sesion = login("tecnico");
        QC.respuestaLectura(USUARIOS, 200, "{\"success\":true,\"data\":[{\"id\":10,\"nombre\":\"Admin <b>Faret</b>\",\"correo\":\"a@faret.cl\",\"username\":\"adm\\\"in\","
                + "\"rol\":\"ADMIN\",\"activo\":true,\"roles\":[\"ADMIN\"],\"createdAt\":\"2026-01-02T03:04:05\",\"passwordHash\":\"$2a$10$secreto\","
                + "\"password\":\"x\",\"token\":\"t\",\"intentos\":3},{\"id\":20,\"nombre\":\"Ana\",\"correo\":null,\"username\":\"ana\",\"rol\":\"CALIDAD\",\"activo\":false,\"roles\":[]}],\"errors\":null}");
        MvcResult r = accion(sesion, "{\"action\":\"faret.usuarios.list\",\"_modulo\":\"faret-usuarios\",\"empresa\":\"INNPACK\",\"ruta\":\"/x\"}")
                .andExpect(status().isOk()).andReturn();
        JsonNode data = json(r).get("data");
        assertThat(data).hasSize(2);
        assertThat(data.get(0).propertyNames()).containsExactlyInAnyOrder("id", "nombre", "correo", "username", "rol", "activo", "roles", "createdAt");
        assertThat(data.get(0).get("nombre").asString()).isEqualTo("Admin &lt;b&gt;Faret&lt;/b&gt;");
        assertThat(data.get(0).get("username").asString()).isEqualTo("adm&quot;in");
        assertThat(data.get(1).get("correo").isNull()).isTrue();
        assertThat(data.get(1).get("activo").asBoolean()).isFalse();
        String crudo = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(crudo).doesNotContain("secreto").doesNotContain("passwordHash").doesNotContain("password").doesNotContain("intentos");
        assertThat(QC.lecturas()).containsExactly("GET /api/usuarios");
        assertThat(QC.lecturasDeUsuario()).containsExactly(50);
        assertThat(MC.peticiones()).isEmpty();
        assertThat(CALIDAD.peticiones()).isEmpty();
    }

    @Test
    void usuariosListSoloActivosComoPhotinoYErroresDeLaApi() throws Exception {
        MockHttpSession sesion = login("tecnico");
        QC.respuestaLectura(USUARIOS, 200, "{\"success\":true,\"data\":[],\"errors\":null}");
        for (String extra : List.of("\"soloActivos\":true", "\"soloActivos\":\"True\"", "\"soloActivos\":false", "\"soloActivos\":\" false \"", "\"soloActivos\":\"si\"",
                "\"soloActivos\":1", "\"soloActivos\":null", "\"x\":1")) {
            accion(sesion, "{\"action\":\"faret.usuarios.list\",\"_modulo\":\"faret-usuarios\"," + extra + "}").andExpect(jsonPath("$.ok").value(true));
        }
        assertThat(QC.lecturas()).containsExactly("GET /api/usuarios?soloActivos=true", "GET /api/usuarios?soloActivos=true", "GET /api/usuarios?soloActivos=false",
                "GET /api/usuarios?soloActivos=false", "GET /api/usuarios", "GET /api/usuarios", "GET /api/usuarios", "GET /api/usuarios");
        QC.limpiarLecturas();
        for (String valor : List.of("{\"a\":1}", "[true]", "[]")) {
            accion(sesion, "{\"action\":\"faret.usuarios.list\",\"_modulo\":\"faret-usuarios\",\"soloActivos\":" + valor + "}")
                    .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
        }
        assertThat(QC.lecturas()).isEmpty();
        // El fake por defecto responde un objeto (no un arreglo): respuesta inválida; errores de la API como en el resto de FARET.
        accion(sesion, "{\"action\":\"faret.usuarios.list\",\"_modulo\":\"faret-usuarios\"}").andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret"));
        QC.respuestaLectura(USUARIOS, 403, "{\"success\":false,\"message\":\"Solo ADMIN_TI\",\"data\":null}");
        accion(sesion, "{\"action\":\"faret.usuarios.list\",\"_modulo\":\"faret-usuarios\"}").andExpect(jsonPath("$.error").value("Solo ADMIN_TI"));
        QC.respuestaLectura(USUARIOS, 500, "<html>boom</html>");
        MvcResult r = accion(sesion, "{\"action\":\"faret.usuarios.list\",\"_modulo\":\"faret-usuarios\"}")
                .andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret")).andReturn();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("boom");
        QC.respuestaLectura(USUARIOS, 200, "{\"success\":true,\"data\":[1,2],\"errors\":null}");
        accion(sesion, "{\"action\":\"faret.usuarios.list\",\"_modulo\":\"faret-usuarios\"}").andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret"));
    }

    @Test
    void permisosGetArmaLaMatrizDePhotinoSinCamposExtraDeLaApi() throws Exception {
        MockHttpSession sesion = login("tecnico");
        QC.respuestaLectura(USUARIOS + "/20/permisos", 200, "{\"success\":true,\"data\":[{\"modulo\":\"faret-data\",\"nivel\":\"ver\",\"modificadoPor\":\"Tecnico TI\","
                + "\"fechaModificacion\":\"2026-02-03T10:00:00\",\"usuarioId\":20,\"hash\":\"zzz\"},{\"modulo\":\"faret-nc\",\"nivel\":null},{\"modulo\":\"inexistente\",\"nivel\":\"EDITAR\"},"
                + "{\"modulo\":\"faret-usuarios\",\"nivel\":\"EDITAR\"}],\"errors\":null}");
        MvcResult res = accion(sesion, "{\"action\":\"faret.usuarios.permisos.get\",\"_modulo\":\"faret-usuarios\",\"id\":20,\"rol\":\"consulta\",\"empresa\":\"INNPACK\"}")
                .andExpect(status().isOk()).andReturn();
        JsonNode data = json(res).get("data");
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("esAdminTi", "modulos");
        assertThat(data.get("esAdminTi").asBoolean()).isFalse();
        JsonNode modulos = data.get("modulos");
        // Todos los módulos FARET menos Gestión de Usuarios (17), en el orden de Photino.
        assertThat(modulos).hasSize(17);
        assertThat(modulos.get(0).get("modulo").asString()).isEqualTo("faret");
        assertThat(modulos.get(1).get("modulo").asString()).isEqualTo("faret-inspecciones");
        java.util.Map<String, JsonNode> porModulo = new java.util.HashMap<>();
        modulos.forEach(m -> porModulo.put(m.get("modulo").asString(), m));
        assertThat(porModulo).doesNotContainKeys("faret-usuarios", "inexistente", "usuarios");
        assertThat(modulos.get(0).propertyNames()).containsExactlyInAnyOrder("modulo", "porRol", "personalizado", "modificadoPor", "fechaModificacion", "minimoVer");
        // CONSULTA: solo faret, talleres, nc, nc internas y despachos en EDITAR; data/importación SIN_ACCESO; Inicio (faret) con minimoVer.
        assertThat(porModulo.get("faret").get("porRol").asString()).isEqualTo("EDITAR");
        assertThat(porModulo.get("faret").get("minimoVer").asBoolean()).isTrue();
        assertThat(porModulo.get("faret-inspecciones").get("porRol").asString()).isEqualTo("SIN_ACCESO");
        assertThat(porModulo.get("faret-inspecciones").get("minimoVer").asBoolean()).isFalse();
        assertThat(porModulo.get("faret-nc").get("porRol").asString()).isEqualTo("EDITAR");
        assertThat(porModulo.get("faret-nc").get("personalizado").isNull()).isTrue();
        assertThat(porModulo.get("faret-data").get("porRol").asString()).isEqualTo("SIN_ACCESO");
        assertThat(porModulo.get("faret-data").get("personalizado").asString()).isEqualTo("VER");
        assertThat(porModulo.get("faret-data").get("modificadoPor").asString()).isEqualTo("Tecnico TI");
        assertThat(porModulo.get("faret-data").get("fechaModificacion").asString()).isEqualTo("2026-02-03T10:00:00");
        String crudo = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(crudo).doesNotContain("zzz").doesNotContain("hash").doesNotContain("usuarioId");
        assertThat(QC.lecturas()).containsExactly("GET /api/usuarios/20/permisos");
        assertThat(QC.lecturasDeUsuario()).containsExactly(50);
        // Otros roles: ADMIN ve data/importación; ADMIN_TI queda bloqueado en EDITAR; sin rol, la regla general.
        for (String[] c : List.of(new String[] {"ADMIN", "EDITAR", "false"}, new String[] {"admin_ti", "EDITAR", "true"}, new String[] {"CALIDAD", "SIN_ACCESO", "false"},
                new String[] {"", "SIN_ACCESO", "false"})) {
            JsonNode d = json(accion(sesion, "{\"action\":\"faret.usuarios.permisos.get\",\"_modulo\":\"faret-usuarios\",\"id\":21,\"rol\":\"" + c[0] + "\"}").andReturn()).get("data");
            assertThat(d.get("esAdminTi").asBoolean()).as(c[0]).isEqualTo(Boolean.parseBoolean(c[2]));
            for (JsonNode m : d.get("modulos")) {
                if (m.get("modulo").asString().equals("faret-data")) {
                    assertThat(m.get("porRol").asString()).as(c[0]).isEqualTo(c[1]);
                }
            }
        }
    }

    @Test
    void permisosGetValidaIdYRolAntesDeLlamarALaApiYTraduceSusErrores() throws Exception {
        MockHttpSession sesion = login("tecnico");
        for (String id : List.of("", "\"id\":null,", "\"id\":0,", "\"id\":-2,", "\"id\":\"abc\",", "\"id\":1.5,", "\"id\":99999999999,", "\"id\":\"1/../x\",", "\"id\":\"1?x=1\",")) {
            accion(sesion, "{\"action\":\"faret.usuarios.permisos.get\",\"_modulo\":\"faret-usuarios\"," + id + "\"rol\":\"ADMIN\"}").andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("Falta el id del usuario"));
        }
        for (String valor : List.of("true", "{\"a\":1}", "[5]", "[]")) {
            accion(sesion, "{\"action\":\"faret.usuarios.permisos.get\",\"_modulo\":\"faret-usuarios\",\"id\":" + valor + "}")
                    .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
        }
        for (String rol : List.of("true", "5", "{\"a\":1}", "[]", "\"root\"", "\"ADMIN;DROP\"", "\"<b>\"")) {
            accion(sesion, "{\"action\":\"faret.usuarios.permisos.get\",\"_modulo\":\"faret-usuarios\",\"id\":5,\"rol\":" + rol + "}")
                    .andExpect(jsonPath("$.error").value(FaretBridgeHandler.MENSAJE_FILTRO_INVALIDO));
        }
        assertThat(QC.lecturas()).isEmpty();
        QC.respuestaLectura(USUARIOS + "/9/permisos", 404, "{\"success\":false,\"message\":\"Usuario no encontrado\",\"data\":null}");
        accion(sesion, "{\"action\":\"faret.usuarios.permisos.get\",\"_modulo\":\"faret-usuarios\",\"id\":9}").andExpect(jsonPath("$.error").value("Usuario no encontrado"));
        // Sin permisos personalizados (data nulo o vacío) la matriz se arma igual, solo con el nivel por rol.
        QC.respuestaLectura(USUARIOS + "/10/permisos", 200, "{\"success\":true,\"data\":null,\"errors\":null}");
        accion(sesion, "{\"action\":\"faret.usuarios.permisos.get\",\"_modulo\":\"faret-usuarios\",\"id\":10,\"rol\":\"ADMIN\"}")
                .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data.modulos.length()").value(17));
    }

    @Test
    void faseFSoloAdminTiDeLaSesionFaretYLasEscriturasSiguenDenegadas() throws Exception {
        for (String a : USUARIOS_LECTURAS) {
            assertThat(policy.evaluar(a, usuario("FARET", "ADMIN_TI"))).as(a).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            for (String rol : List.of("ADMIN", "CALIDAD", "INSPECTOR", "CONSULTA", "operador")) {
                assertThat(policy.evaluar(a, usuario("FARET", rol))).as(a + " " + rol).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
            }
            assertThat(policy.evaluar(a, usuario("INNPACK", "admin_ti"))).as(a).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
            assertThat(PermisosModulo.esLectura(a)).as(a).isTrue();
        }
        // Rol cruzado por HTTP: ningún rol FARET que no sea admin_ti llega a la API, ni siquiera declarando faret-usuarios.
        for (String u : List.of("admin", "ana", "clara")) {
            for (String a : USUARIOS_LECTURAS) {
                accion(login(u), "{\"action\":\"" + a + "\",\"_modulo\":\"faret-usuarios\",\"id\":20,\"rol\":\"ADMIN\"}").andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.ok").value(false));
            }
        }
        // Un admin_ti de INNPACK no alcanza las acciones FARET (ni con módulo FARET declarado).
        for (String a : USUARIOS_LECTURAS) {
            accion(loginInnpack("adminti1"), "{\"action\":\"" + a + "\",\"_modulo\":\"faret-usuarios\",\"id\":20}").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        }
        assertThat(QC.lecturas()).isEmpty();
        // Sin módulo de origen, o con uno desconocido, el admin_ti tampoco entra.
        MockHttpSession tecnico = login("tecnico");
        accion(tecnico, "{\"action\":\"faret.usuarios.list\"}").andExpect(status().isForbidden());
        accion(tecnico, "{\"action\":\"faret.usuarios.list\",\"_modulo\":\"inventado\"}").andExpect(status().isForbidden());
        assertThat(QC.lecturas()).isEmpty();
        // Escrituras de usuarios siguen denegadas incluso para admin_ti.
        for (String a : List.of("faret.usuarios.create", "faret.usuarios.cambiarRol", "faret.usuarios.toggleActivo", "faret.usuarios.eliminarDefinitivo",
                "faret.usuarios.resetPassword", "faret.usuarios.permisos.guardar", "faret.usuarios.permisos.restablecer")) {
            accion(tecnico, "{\"action\":\"" + a + "\",\"_modulo\":\"faret-usuarios\",\"id\":20}").andExpect(status().isForbidden());
        }
        assertThat(QC.lecturas()).isEmpty();
        // Y un admin_ti con sesión FARET sí lee.
        accion(tecnico, "{\"action\":\"faret.usuarios.list\",\"_modulo\":\"faret-usuarios\"}").andExpect(status().isOk());
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
