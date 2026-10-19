package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
 * Gestion de Usuarios INNPACK, escrituras (Photino 1.8.23): create, resetPassword, activo, cambiarRol, eliminarDefinitivo y
 * permisos.{get,guardar,restablecer}. Solo admin_ti, lista cerrada de claves, contrasenas nunca registradas, una cuenta no
 * puede desactivarse / cambiarse el rol / eliminarse a si misma. API SIMULADA.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFaseUsuariosEscTest {

    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveUsuarios#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        API.agregar(new FakeInnpackApi.Usuario(40, "ti1", PASS, "TI Uno", "admin_ti", true));
        API.agregar(new FakeInnpackApi.Usuario(20, "admin1", PASS, "Admin Uno", "admin", true));
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
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

    private static String accion(String accion, String data) {
        return "{\"action\":\"usuarios." + accion + "\",\"_modulo\":\"usuarios\",\"data\":" + data + "}";
    }

    @Test
    void createReenviaElCuerpoExactoYNuncaRegistraLaContrasena(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("ti1");
        JsonNode json = json(enviar(s, accion("create", "{\"codigoUsuario\":\" nuevo1 \",\"nombreCompleto\":\" Nuevo Uno \","
                + "\"password\":\" Temporal#2026 \",\"rol\":\"operador\",\"activo\":false}")).andExpect(status().isOk()).andReturn());
        assertThat(json.get("data")).isEqualTo(mapper.readTree("{\"message\":\"Usuario creado correctamente\",\"id\":77}"));
        assertThat(API.cuerposUsuarios()).containsExactly("POST /api/usuarios|{\"codigoUsuario\":\"nuevo1\",\"nombreCompleto\":\"Nuevo Uno\","
                + "\"password\":\"Temporal#2026\",\"rol\":\"operador\",\"activo\":false}");
        // activo por defecto true.
        enviar(s, accion("create", "{\"codigoUsuario\":\"n2\",\"nombreCompleto\":\"N\",\"password\":\"Otra#2026x\",\"rol\":\"consulta\"}"));
        assertThat(API.cuerposUsuarios().get(1)).endsWith("\"activo\":true}");
        assertThat(salida.getAll()).contains("usuarios:crear:nuevo1").doesNotContain("Temporal#2026").doesNotContain("Otra#2026x");
    }

    @Test
    void createValidaAntesDeLlamarALaApi() throws Exception {
        MockHttpSession s = login("ti1");
        String ok = "\"codigoUsuario\":\"u\",\"nombreCompleto\":\"N\",\"password\":\"Clave#2026\",\"rol\":\"operador\"";
        enviar(s, accion("create", "{\"nombreCompleto\":\"N\",\"password\":\"x\",\"rol\":\"r\"}")).andExpect(jsonPath("$.error").value("El código de usuario es obligatorio"));
        enviar(s, accion("create", "{\"codigoUsuario\":\"u\",\"password\":\"x\",\"rol\":\"r\"}")).andExpect(jsonPath("$.error").value("El nombre completo es obligatorio"));
        enviar(s, accion("create", "{\"codigoUsuario\":\"u\",\"nombreCompleto\":\"N\",\"rol\":\"r\"}")).andExpect(jsonPath("$.error").value("La contraseña es obligatoria"));
        enviar(s, accion("create", "{\"codigoUsuario\":\"u\",\"nombreCompleto\":\"N\",\"password\":\"x\"}")).andExpect(jsonPath("$.error").value("El rol es obligatorio"));
        enviar(s, accion("create", "{\"codigoUsuario\":\"  \",\"nombreCompleto\":\"N\",\"password\":\"x\",\"rol\":\"r\"}")).andExpect(jsonPath("$.error").value("El código de usuario es obligatorio"));
        String nombre = "\"nombreCompleto\":\"N\"";
        String clave = "\"password\":\"Clave#2026\"";
        String rol = "\"rol\":\"operador\"";
        for (String malo : new String[] {
                "\"codigoUsuario\":5," + nombre + "," + clave + "," + rol,
                "\"codigoUsuario\":\"" + "u".repeat(51) + "\"," + nombre + "," + clave + "," + rol,
                "\"codigoUsuario\":\"a\\nb\"," + nombre + "," + clave + "," + rol,
                "\"codigoUsuario\":\"u\",\"nombreCompleto\":[1]," + clave + "," + rol,
                "\"codigoUsuario\":\"u\"," + nombre + "," + clave + ",\"rol\":\"" + "r".repeat(31) + "\"",
                "\"codigoUsuario\":\"u\"," + nombre + ",\"password\":\"" + "p".repeat(257) + "\"," + rol,
                ok + ",\"activo\":{}",
                ok + ",\"passwordHash\":\"x\"",
                ok + ",\"id\":3"}) {
            enviar(s, accion("create", "{" + malo + "}")).andExpect(jsonPath("$.error").exists());
        }
        enviar(s, accion("create", "[]")).andExpect(jsonPath("$.error").value("Datos inválidos"));
        assertThat(API.cuerposUsuarios()).isEmpty();
        enviar(s, accion("create", "{\"codigoUsuario\":\"dup\",\"nombreCompleto\":\"N\",\"password\":\"x\",\"rol\":\"r\"}"))
                .andExpect(jsonPath("$.error").value("El código de usuario ya existe"));
    }

    @Test
    void resetPasswordYSusValidaciones(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("ti1");
        enviar(s, accion("resetPassword", "{\"id\":\"10\",\"nuevaPassword\":\" Temporal#9 \"}"))
                .andExpect(jsonPath("$.data.message").value("Contraseña actualizada correctamente"));
        assertThat(API.cuerposUsuarios()).containsExactly("PUT /api/usuarios/10/password|{\"nuevaPassword\":\"Temporal#9\"}");
        assertThat(salida.getAll()).doesNotContain("Temporal#9");
        for (String id : new String[] {"0", "-1", "\"x\"", "null", "1.5"}) {
            enviar(s, accion("resetPassword", "{\"id\":" + id + ",\"nuevaPassword\":\"x\"}")).andExpect(jsonPath("$.error").value("Id inválido"));
        }
        enviar(s, accion("resetPassword", "{\"id\":10,\"nuevaPassword\":\"  \"}")).andExpect(jsonPath("$.error").value("La nueva contraseña es obligatoria"));
        enviar(s, accion("resetPassword", "{\"id\":10,\"nuevaPassword\":12}")).andExpect(jsonPath("$.error").value("Datos inválidos"));
        enviar(s, accion("resetPassword", "{\"id\":10,\"nuevaPassword\":\"x\",\"rol\":\"admin\"}")).andExpect(jsonPath("$.error").value("Datos inválidos"));
        enviar(s, accion("resetPassword", "{\"id\":404,\"nuevaPassword\":\"x\"}")).andExpect(jsonPath("$.error").value("Usuario no encontrado"));
        assertThat(API.cuerposUsuarios()).hasSize(2);
    }

    @Test
    void activoCambiarRolYEliminarNoPermitenActuarSobreUnoMismo() throws Exception {
        MockHttpSession s = login("ti1");
        enviar(s, accion("activo", "{\"id\":10,\"activo\":false}")).andExpect(jsonPath("$.data.message").value("Usuario desactivado correctamente"));
        enviar(s, accion("activo", "{\"id\":10}")).andExpect(jsonPath("$.data.message").value("Usuario activado correctamente"));
        enviar(s, accion("activo", "{\"id\":40,\"activo\":true}")).andExpect(jsonPath("$.ok").value(true));
        assertThat(API.cuerposUsuarios()).containsExactly("PUT /api/usuarios/10/activo|{\"activo\":false}",
                "PUT /api/usuarios/10/activo|{\"activo\":true}", "PUT /api/usuarios/40/activo|{\"activo\":true}");
        API.reiniciarDashboard();
        String propio = "No puedes aplicar esta acción sobre tu propio usuario";
        enviar(s, accion("activo", "{\"id\":40,\"activo\":false}")).andExpect(jsonPath("$.error").value(propio));
        enviar(s, accion("cambiarRol", "{\"id\":40,\"rol\":\"operador\"}")).andExpect(jsonPath("$.error").value(propio));
        enviar(s, accion("eliminarDefinitivo", "{\"id\":40}")).andExpect(jsonPath("$.error").value(propio));
        assertThat(API.cuerposUsuarios()).isEmpty();

        enviar(s, accion("cambiarRol", "{\"id\":10,\"rol\":\" admin \"}")).andExpect(jsonPath("$.data.message").value("Rol actualizado correctamente"));
        enviar(s, accion("cambiarRol", "{\"id\":10,\"rol\":\"\"}")).andExpect(jsonPath("$.error").value("El rol es obligatorio"));
        enviar(s, accion("cambiarRol", "{\"id\":0,\"rol\":\"admin\"}")).andExpect(jsonPath("$.error").value("Id inválido"));
        enviar(s, accion("cambiarRol", "{\"id\":10,\"rol\":7}")).andExpect(jsonPath("$.error").value("Datos inválidos"));
        enviar(s, accion("eliminarDefinitivo", "{\"id\":10}")).andExpect(jsonPath("$.data.message").value("Usuario eliminado definitivamente"));
        enviar(s, accion("eliminarDefinitivo", "{\"id\":409}")).andExpect(jsonPath("$.error").value("El usuario tiene historial y no puede eliminarse"));
        enviar(s, accion("eliminarDefinitivo", "{\"id\":10,\"x\":1}")).andExpect(jsonPath("$.error").value("Datos inválidos"));
        assertThat(API.cuerposUsuarios()).containsExactly("PUT /api/usuarios/10/rol|{\"rol\":\"admin\"}",
                "DELETE /api/usuarios/10/definitivo|", "DELETE /api/usuarios/409/definitivo|");
    }

    @Test
    void permisosGetArmaLaMatrizSoloConCamposConocidos() throws Exception {
        MockHttpSession s = login("ti1");
        JsonNode d = json(enviar(s, accion("permisos.get", "{\"id\":10,\"rol\":\"operador\"}")).andExpect(status().isOk()).andReturn()).get("data");
        assertThat(d.get("esAdminTi").asBoolean()).isFalse();
        JsonNode dashboard = null;
        for (JsonNode fila : d.get("modulos")) {
            if (fila.get("modulo").asString().equals("dashboard")) {
                dashboard = fila;
            }
            assertThat(fila.get("modulo").asString()).isNotEqualTo("usuarios").doesNotStartWith("faret");
        }
        assertThat(dashboard).isNotNull();
        assertThat(dashboard.get("personalizado").asString()).isEqualTo("VER");
        assertThat(dashboard.get("modificadoPor").asString()).isEqualTo("ti1");
        assertThat(dashboard.get("porRol").asString()).isEqualTo("EDITAR");
        assertThat(d.toString()).doesNotContain("no-debe-llegar");
        assertThat(json(enviar(s, accion("permisos.get", "{\"id\":10,\"rol\":\"ADMIN_TI\"}")).andReturn()).at("/data/esAdminTi").asBoolean()).isTrue();
        enviar(s, accion("permisos.get", "{\"id\":0,\"rol\":\"x\"}")).andExpect(jsonPath("$.error").value("Id inválido"));
        enviar(s, accion("permisos.get", "{\"id\":10,\"rol\":5}")).andExpect(jsonPath("$.error").value("Datos inválidos"));
    }

    @Test
    void permisosGuardarValidaModulosYNiveles() throws Exception {
        MockHttpSession s = login("ti1");
        enviar(s, accion("permisos.guardar", "{\"id\":10,\"permisos\":[{\"modulo\":\"dashboard\",\"nivel\":\"VER\"},"
                + "{\"modulo\":\"trazabilidad\",\"nivel\":null},{\"modulo\":\"formularios\",\"nivel\":\"SIN_ACCESO\",\"extra\":1}]}"))
                .andExpect(jsonPath("$.data.message").value("Permisos guardados correctamente"));
        assertThat(API.cuerposUsuarios()).containsExactly("PUT /api/usuarios/10/permisos|{\"permisos\":[{\"modulo\":\"dashboard\",\"nivel\":\"VER\"},"
                + "{\"modulo\":\"trazabilidad\",\"nivel\":null},{\"modulo\":\"formularios\",\"nivel\":\"SIN_ACCESO\"}]}");
        String invalido = "Los permisos recibidos no son válidos";
        for (String p : new String[] {"[{\"modulo\":\"usuarios\",\"nivel\":\"EDITAR\"}]", "[{\"modulo\":\"faret\",\"nivel\":\"EDITAR\"}]",
                "[{\"modulo\":\"nada\",\"nivel\":\"VER\"}]", "[{\"modulo\":\"dashboard\",\"nivel\":\"ADMIN\"}]", "[{\"modulo\":\"dashboard\",\"nivel\":\"ver\"}]",
                "[{\"modulo\":\"dashboard\",\"nivel\":5}]", "[{\"nivel\":\"VER\"}]", "[5]", "[{\"modulo\":\"dashboard\",\"nivel\":\"VER\"},{\"modulo\":\"dashboard\",\"nivel\":\"EDITAR\"}]"}) {
            enviar(s, accion("permisos.guardar", "{\"id\":10,\"permisos\":" + p + "}")).andExpect(jsonPath("$.error").value(invalido));
        }
        enviar(s, accion("permisos.guardar", "{\"id\":10}")).andExpect(jsonPath("$.error").value("No se recibieron permisos"));
        enviar(s, accion("permisos.guardar", "{\"id\":10,\"permisos\":{}}")).andExpect(jsonPath("$.error").value("No se recibieron permisos"));
        enviar(s, accion("permisos.guardar", "{\"id\":0,\"permisos\":[]}")).andExpect(jsonPath("$.error").value("Id inválido"));
        assertThat(API.cuerposUsuarios()).hasSize(1);
        enviar(s, accion("permisos.restablecer", "{\"id\":10}")).andExpect(jsonPath("$.data.message").value("Permisos restablecidos al comportamiento por rol"));
        assertThat(API.cuerposUsuarios().get(1)).isEqualTo("DELETE /api/usuarios/10/permisos|");
    }

    @Test
    void soloAdminTi() throws Exception {
        for (String usuario : new String[] {"admin1", "operador1"}) {
            MockHttpSession s = login(usuario);
            for (String a : new String[] {"create", "resetPassword", "activo", "cambiarRol", "eliminarDefinitivo", "permisos.get",
                    "permisos.guardar", "permisos.restablecer"}) {
                enviar(s, accion(a, "{\"id\":10,\"rol\":\"x\",\"nuevaPassword\":\"x\",\"permisos\":[]}")).andExpect(status().isForbidden());
            }
        }
        assertThat(API.cuerposUsuarios()).isEmpty();
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.41.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private ResultActions enviar(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private JsonNode json(MvcResult res) throws Exception {
        return mapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-usuarios-esc");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
