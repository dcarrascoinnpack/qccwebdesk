package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import cl.faret.qccweb.auth.SessionUser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
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

/**
 * Fase 3u — permisos por módulo igual que Photino 1.8.15 (9e1b556 desde la Fase 5a-1, PermisosService): al iniciar sesión se leen los
 * permisos personalizados (GET api/auth/mis-permisos con el token del usuario; si fallan, no se entra), cada acción
 * viaja con el módulo abierto (`_modulo`) y el gateway aplica SIN_ACCESO / VER / EDITAR con los mensajes de Photino.
 * Seguridad transparente de la web: las lecturas también deben pertenecer al módulo declarado. API SIMULADA.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase3uTest {

    private static final String SIN_ACCESO = "No tienes acceso a este módulo.";
    private static final String SOLO_VISTA = "Solo vista: no tienes permiso para modificar datos en este módulo.";
    private static final String NO_RECONOCIDO = "Acción rechazada: módulo de origen no reconocido.";
    private static final String NO_PERMITIDA = "Acción no permitida desde este módulo.";
    private static final String PERMISOS_NO_CARGADOS = "No se pudieron cargar los permisos del usuario. Intenta nuevamente.";
    private static final List<String> MODULOS_INNPACK = List.of("inicio", "dashboard", "registros-produccion", "no-conformidades",
            "nc-internas", "control-documental", "maquinas-seguimiento", "registros-control", "talleres-externos",
            "producto-terminado", "certificados-liberacion", "despachos-diarios", "recepcion-calidad", "muestra-laboratorio",
            "trazabilidad", "formularios", "usuarios");
    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClavePermisos#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(20, "admin1", PASS, "Admin Uno", "admin", true));
        API.agregar(new FakeInnpackApi.Usuario(25, "adminti1", PASS, "Admin TI", "admin_ti", true));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.innpack-api-base-url", API::baseUrl);
        registry.add("qcc.web.www-dir", WWW::toString);
        registry.add("qcc.web.manifest-file", () -> WWW.resolve("no-existe.json").toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reiniciar() {
        API.reiniciarDashboard();
    }

    @AfterAll
    static void cerrar() {
        API.close();
    }

    // ------------------------------------------------------------------ login y permisos.mios

    @Test
    void loginLeeLosPermisosConElTokenDelPropioUsuario() throws Exception {
        login("operador1");
        login("admin1");
        assertThat(API.misPermisosConsultados()).containsExactly(10, 20);
    }

    @Test
    void siMisPermisosFallaElLoginSeAnulaSinSesion(CapturedOutput salida) throws Exception {
        API.misPermisosCaido(10);
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).with(r -> {
                            r.setRemoteAddr("10.33.1." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"CodigoUsuario\":\"operador1\",\"Password\":\"" + PASS + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(PERMISOS_NO_CARGADOS))
                .andReturn();
        MockHttpSession sesion = (MockHttpSession) res.getRequest().getSession(false);
        if (sesion != null) {
            // Sin usuario autenticado: el bridge responde 401.
            accion(sesion, "{\"action\":\"inicio.getDashboard\",\"_modulo\":\"inicio\"}").andExpect(status().isUnauthorized());
        }
        assertThat(salida.getAll()).contains("evento=LOGIN_ERROR_UPSTREAM usuario=operador1").doesNotContain("evento=LOGIN_OK usuario=operador1");
        assertThat(salida.getAll()).doesNotContain(FakeInnpackApi.firmaDeToken(10), PASS);
    }

    @Test
    void permisosMiosSinPersonalizarEsLaReglaPorRol() throws Exception {
        Map<String, String> operador = mios(login("operador1"));
        assertThat(operador.keySet()).containsExactlyElementsOf(MODULOS_INNPACK);
        MODULOS_INNPACK.forEach(m -> assertThat(operador.get(m)).as(m).isEqualTo(m.equals("usuarios") ? "SIN_ACCESO" : "EDITAR"));
        assertThat(mios(login("admin1")).get("usuarios")).isEqualTo("SIN_ACCESO");
        Map<String, String> adminTi = mios(login("adminti1"));
        assertThat(adminTi.values()).containsOnly("EDITAR");
    }

    @Test
    void permisosPersonalizadosMandanSobreElRolInicioNuncaBajaDeVerYAdminTiNoSePersonaliza() throws Exception {
        String personalizados = "[{\"modulo\":\"dashboard\",\"nivel\":\"ver\"},{\"modulo\":\"recepcion-calidad\",\"nivel\":\"SIN_ACCESO\"},"
                + "{\"modulo\":\"inicio\",\"nivel\":\"SIN_ACCESO\"},{\"modulo\":\"usuarios\",\"nivel\":\"EDITAR\"},"
                + "{\"modulo\":\"trazabilidad\",\"nivel\":\"TOTAL\"},{\"modulo\":\"\",\"nivel\":\"VER\"},{\"nivel\":\"VER\"}]";
        API.permisos(10, personalizados);
        API.permisos(25, personalizados);
        Map<String, String> operador = mios(login("operador1"));
        assertThat(operador.get("dashboard")).isEqualTo("VER");
        assertThat(operador.get("recepcion-calidad")).isEqualTo("SIN_ACCESO");
        assertThat(operador.get("inicio")).isEqualTo("VER");
        assertThat(operador.get("usuarios")).isEqualTo("SIN_ACCESO");
        assertThat(operador.get("trazabilidad")).isEqualTo("EDITAR");
        assertThat(operador.get("no-conformidades")).isEqualTo("EDITAR");
        assertThat(mios(login("adminti1")).values()).containsOnly("EDITAR");
    }

    // ------------------------------------------------------------------ SIN_ACCESO / VER / EDITAR

    @Test
    void sinAccesoRechazaTodoSinLlamarALaApi() throws Exception {
        API.permisos(10, "[{\"modulo\":\"recepcion-calidad\",\"nivel\":\"SIN_ACCESO\"}]");
        MockHttpSession s = login("operador1");
        rechazo(s, "{\"action\":\"recepcion.list\",\"data\":{},\"_modulo\":\"recepcion-calidad\"}", SIN_ACCESO);
        rechazo(s, "{\"action\":\"recepcion.estado.actualizar\",\"data\":{\"loteId\":1,\"estado\":\"NoConforme\"},"
                + "\"_modulo\":\"recepcion-calidad\"}", SIN_ACCESO);
        assertThat(API.peticionesRecepcion()).isEmpty();
        // Los demás módulos siguen con la regla por rol.
        accion(s, "{\"action\":\"noConformidades.list\",\"data\":{},\"_modulo\":\"no-conformidades\"}").andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void soloVistaPermiteLeerYRechazaEscribir() throws Exception {
        API.permisos(10, "[{\"modulo\":\"recepcion-calidad\",\"nivel\":\"VER\"}]");
        MockHttpSession s = login("operador1");
        accion(s, "{\"action\":\"recepcion.list\",\"data\":{},\"_modulo\":\"recepcion-calidad\"}").andExpect(jsonPath("$.ok").value(true));
        accion(s, "{\"action\":\"recepcion.detalle\",\"data\":{\"id\":1},\"_modulo\":\"recepcion-calidad\"}")
                .andExpect(jsonPath("$.ok").value(true));
        rechazo(s, "{\"action\":\"recepcion.estado.actualizar\",\"data\":{\"loteId\":1,\"estado\":\"NoConforme\"},"
                + "\"_modulo\":\"recepcion-calidad\"}", SOLO_VISTA);
        assertThat(API.estadosRecibidos()).isEmpty();
    }

    @Test
    void editarPermiteEscribirYLaWebQuitaElModuloAntesDelHandler() throws Exception {
        MockHttpSession s = login("operador1");
        accion(s, "{\"action\":\"recepcion.detalle\",\"data\":{\"id\":1},\"_modulo\":\"recepcion-calidad\"}")
                .andExpect(jsonPath("$.ok").value(true));
        accion(s, "{\"action\":\"recepcion.estado.actualizar\",\"data\":{\"loteId\":1,\"estado\":\"NoConforme\"},"
                + "\"_modulo\":\"recepcion-calidad\"}").andExpect(jsonPath("$.ok").value(true));
        assertThat(mapper.readTree(API.estadosRecibidos().get(0).cuerpo())).isEqualTo(mapper.readTree("{\"estado\":\"NoConforme\"}"));
    }

    // ------------------------------------------------------------------ módulo de origen

    @Test
    void moduloAusenteNuloDesconocidoONoTextoSeRechaza() throws Exception {
        MockHttpSession s = login("operador1");
        for (String modulo : new String[] {"null", "\"\"", "\"no-existe\"", "\"Inicio\"", "5", "[\"inicio\"]", "{\"a\":1}"}) {
            rechazo(s, "{\"action\":\"inicio.getDashboard\",\"_modulo\":" + modulo + "}", NO_RECONOCIDO);
        }
        assertThat(API.llamadasDashboard()).isZero();
        // Módulo de la otra empresa (Faret): sin acceso para una sesión INNPACK.
        rechazo(s, "{\"action\":\"noConformidades.list\",\"data\":{},\"_modulo\":\"faret-nc-internas\"}", SIN_ACCESO);
    }

    @Test
    void unModuloConAccesoNoSirveParaLeerOEscribirDatosDeOtro(CapturedOutput salida) throws Exception {
        API.permisos(10, "[{\"modulo\":\"recepcion-calidad\",\"nivel\":\"SIN_ACCESO\"},{\"modulo\":\"nc-internas\",\"nivel\":\"VER\"}]");
        MockHttpSession s = login("operador1");
        // Lectura de Recepción declarando Inicio (EDITAR): rechazada (defensa propia de la web).
        rechazo(s, "{\"action\":\"recepcion.list\",\"data\":{},\"_modulo\":\"inicio\"}", NO_PERMITIDA);
        rechazo(s, "{\"action\":\"recepcion.foto.abrir\",\"data\":{\"loteId\":1},\"_modulo\":\"dashboard\"}", NO_PERMITIDA);
        // Escritura de Recepción declarando No Conformidades (EDITAR): rechazada (regla de Photino).
        rechazo(s, "{\"action\":\"recepcion.estado.actualizar\",\"data\":{\"loteId\":1,\"estado\":\"NoConforme\"},"
                + "\"_modulo\":\"no-conformidades\"}", NO_PERMITIDA);
        assertThat(API.peticionesRecepcion()).isEmpty();
        // Escritura de NC desde NC Internas en VER: solo vista.
        rechazo(s, "{\"action\":\"noConformidades.seguimiento.crear\",\"id\":501,\"comentario\":\"x\",\"_modulo\":\"nc-internas\"}",
                SOLO_VISTA);
        assertThat(API.seguimientosRecibidos()).isEmpty();
        assertThat(salida.getAll()).contains("evento=ACCION_DENEGADA usuario=operador1 empresa=INNPACK accion=recepcion.list "
                + "motivo=PERMISO_MODULO:inicio");
    }

    @Test
    void lecturasCruzadasQueHaceLaPantallaDeNoConformidadesSiguenPermitidas() throws Exception {
        MockHttpSession s = login("operador1");
        accion(s, "{\"action\":\"dashboard.obtenerFiltros\",\"_modulo\":\"no-conformidades\"}").andExpect(jsonPath("$.ok").value(true));
        accion(s, "{\"action\":\"maquinasSeguimiento.obtenerResumen\",\"_modulo\":\"no-conformidades\"}")
                .andExpect(jsonPath("$.ok").value(true));
        accion(s, "{\"action\":\"noConformidades.list\",\"data\":{\"ambito\":\"INTERNA\"},\"_modulo\":\"nc-internas\"}")
                .andExpect(jsonPath("$.ok").value(true));
        // permisos.mios se pide al iniciar sesión, desde la pantalla de login (sin módulo del sidebar).
        accion(s, "{\"action\":\"permisos.mios\",\"_modulo\":\"auth\"}").andExpect(jsonPath("$.ok").value(true));
        accion(s, "{\"action\":\"permisos.mios\",\"_modulo\":null}").andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void liberacionCalidadRespondeComoPhotinoSinFpsApiSinAvisoNiLlamadas() throws Exception {
        MockHttpSession s = login("operador1");
        // 200 + ok:false (no 403): la celda muestra "No disponible" y el shim no muestra el aviso de acción no disponible.
        accion(s, "{\"action\":\"liberacionCalidad.inspectores\",\"data\":{\"nps\":[\"123\"]},\"_modulo\":\"no-conformidades\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("fps-api no está configurada en este equipo."));
        rechazo(s, "{\"action\":\"liberacionCalidad.inspectores\",\"data\":{},\"_modulo\":\"recepcion-calidad\"}", NO_PERMITIDA);
        assertThat(API.authorizationRecibidos()).isEmpty();
    }

    // ------------------------------------------------------------------ Gestión de Usuarios

    @Test
    void usuariosListSoloAdminTiComoPhotino() throws Exception {
        accion(login("admin1"), "{\"action\":\"usuarios.list\",\"data\":{},\"_modulo\":\"usuarios\"}").andExpect(status().isForbidden());
        accion(login("operador1"), "{\"action\":\"usuarios.list\",\"data\":{},\"_modulo\":\"usuarios\"}").andExpect(status().isForbidden());
        accion(login("adminti1"), "{\"action\":\"usuarios.list\",\"data\":{},\"_modulo\":\"usuarios\"}").andExpect(jsonPath("$.ok").value(true));
        assertThat(API.peticionesUsuarios()).hasSize(1);
    }

    @Test
    void reglaPuraComoPhotino() {
        SessionUser operador = usuario("operador", Map.of("dashboard", "VER"));
        assertThat(PermisosModulo.validar("dashboard.obtenerResumen", "dashboard", operador)).isNull();
        assertThat(PermisosModulo.validar("dashboard.validarRegistro", "dashboard", operador)).isEqualTo(SOLO_VISTA);
        assertThat(PermisosModulo.validar("registrosProduccion.validarRegistro", "registros-produccion", operador)).isNull();
        assertThat(PermisosModulo.validar("usuarios.list", "usuarios", operador))
                .isEqualTo("Acceso no autorizado: solo ADMIN_TI puede administrar usuarios.");
        assertThat(PermisosModulo.validar("usuarios.list", "usuarios", usuario("ADMIN_TI", Map.of()))).isNull();
        assertThat(PermisosModulo.validar("permisos.mios", null, operador)).isNull();
        assertThat(PermisosModulo.validar("inicio.getDashboard", "inicio", null)).isEqualTo("No hay una sesión activa. Vuelve a iniciar sesión.");
        // Criterio cerrado de Photino: lo que no es lectura conocida es escritura.
        assertThat(PermisosModulo.esLectura("noConformidades.catalogos.clientes.list")).isTrue();
        assertThat(PermisosModulo.esLectura("certificadosLiberacion.calidadPdf.descargar")).isTrue();
        assertThat(PermisosModulo.esLectura("recepcion.sap.consultar")).isTrue();
        assertThat(PermisosModulo.esLectura("noConformidades.catalogos.clientes.crear")).isFalse();
        assertThat(PermisosModulo.esLectura("inicio.frecuencias.actualizar")).isFalse();
        // Photino 1.8.15 (9e1b556, Fase 5a-1): Formularios y Despachos Diarios (INNPACK y Faret, sin escrituras);
        // formularios.abrirPdf es lectura (AccionesLectura); CONSULTA de Faret también entra a faret-despachos-diarios.
        assertThat(PermisosModulo.esLectura("formularios.abrirPdf")).isTrue();
        assertThat(PermisosModulo.validar("formularios.list", "formularios", operador)).isNull();
        assertThat(PermisosModulo.validar("despachosDiarios.resumen", "despachos-diarios", operador)).isNull();
        assertThat(PermisosModulo.validar("formularios.list", "despachos-diarios", operador)).isEqualTo(NO_PERMITIDA);
        assertThat(PermisosModulo.validar("formularios.guardar", "formularios", operador)).isEqualTo(NO_PERMITIDA);
        SessionUser consultaFaret = new SessionUser(2, "c", "C", "CONSULTA", "FARET", "t", Instant.now(),
                Instant.now().plusSeconds(60), Map.of());
        assertThat(PermisosModulo.nivelEfectivo("faret-despachos-diarios", consultaFaret)).isEqualTo("EDITAR");
        assertThat(PermisosModulo.nivelEfectivo("faret-formularios", consultaFaret)).isEqualTo("SIN_ACCESO");
        assertThat(PermisosModulo.nivelEfectivo("faret-certificados-liberacion", consultaFaret)).isEqualTo("SIN_ACCESO");
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, String> mios(MockHttpSession s) throws Exception {
        JsonNode data = json(accion(s, "{\"action\":\"permisos.mios\",\"_modulo\":\"auth\"}").andExpect(status().isOk()).andReturn())
                .get("data");
        Map<String, String> niveles = new LinkedHashMap<>();
        data.properties().forEach(e -> niveles.put(e.getKey(), e.getValue().asString()));
        return niveles;
    }

    private void rechazo(MockHttpSession s, String cuerpo, String mensaje) throws Exception {
        accion(s, cuerpo).andExpect(status().isForbidden()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(mensaje));
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.33.0." + IP.getAndIncrement());
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

    private static SessionUser usuario(String rol, Map<String, String> permisos) {
        return new SessionUser(1, "u", "U", rol, "INNPACK", "t", Instant.now(), Instant.now().plusSeconds(60), permisos);
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-3u");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
