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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gestion de Usuarios FARET, escrituras (Photino 1.8.23, FaretHandler.HandleUsuarios*): create, cambiarRol, resetPassword, activar,
 * desactivar, eliminarDefinitivo y permisos.{guardar,restablecer} contra QualityControlFaret.Api con el Bearer de la sesion.
 * Solo ADMIN_TI de la sesion FARET, lista cerrada de claves, claves nunca registradas, una cuenta no actua sobre si misma.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFaseFaretUsuariosEscTest {

    private static final FakeFaretApi QC = new FakeFaretApi(Clock.systemUTC());
    private static final FakeInnpackApi INNPACK = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveFaretUsr#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final List<String> ACCIONES = List.of("create", "cambiarRol", "resetPassword", "activar", "desactivar",
            "eliminarDefinitivo", "permisos.guardar", "permisos.restablecer");

    static {
        QC.agregar(new FakeFaretApi.Usuario(10, "admin", "admin@faret.cl", PASS, "Admin Faret", List.of("ADMIN")));
        QC.agregar(new FakeFaretApi.Usuario(20, "ana", "ana@faret.cl", PASS, "Ana Calidad", List.of("CALIDAD")));
        QC.agregar(new FakeFaretApi.Usuario(50, "tecnico", "tecnico@faret.cl", PASS, "Tecnico TI", List.of("ADMIN_TI")));
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

    @Test
    void cadaAccionVaASuRutaConElCuerpoExactoYElBearerDeLaSesion(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("tecnico");
        enviar(s, "create", Map.of("nombre", " Nuevo Uno ", "username", " 11.111.111-1 ", "rol", "calidad", "password", "Temporal#2026"))
                .andExpect(jsonPath("$.data.message").value("Usuario creado correctamente"));
        enviar(s, "cambiarRol", Map.of("id", "20", "rol", " inspector ")).andExpect(jsonPath("$.data.message").value("Rol actualizado correctamente"));
        enviar(s, "resetPassword", Map.of("id", 20, "password", "Otra#2026x")).andExpect(jsonPath("$.data.message").value("Contraseña actualizada correctamente"));
        enviar(s, "activar", Map.of("id", 20)).andExpect(jsonPath("$.data.message").value("Usuario activado correctamente"));
        enviar(s, "desactivar", Map.of("id", 20)).andExpect(jsonPath("$.data.message").value("Usuario desactivado correctamente"));
        enviar(s, "eliminarDefinitivo", Map.of("id", 20)).andExpect(jsonPath("$.data.message").value("Usuario eliminado definitivamente"));
        enviar(s, "permisos.guardar", Map.of("id", 20, "permisos", List.of(Map.of("modulo", "faret-nc", "nivel", "VER", "extra", 1),
                nivelNulo("faret-talleres-externos"))))
                .andExpect(jsonPath("$.data.message").value("Permisos guardados correctamente"));
        enviar(s, "permisos.restablecer", Map.of("id", 20)).andExpect(jsonPath("$.data.message").value("Permisos restablecidos al comportamiento por rol"));

        List<Escritura> e = QC.escrituras();
        assertThat(e).extracting(Escritura::metodo).containsExactly("POST", "PUT", "PUT", "PUT", "DELETE", "DELETE", "PUT", "DELETE");
        assertThat(e).extracting(Escritura::uri).containsExactly("/api/usuarios", "/api/usuarios/20/roles", "/api/usuarios/20/password",
                "/api/usuarios/20/activar", "/api/usuarios/20", "/api/usuarios/20/definitivo", "/api/usuarios/20/permisos",
                "/api/usuarios/20/permisos");
        assertThat(e).extracting(Escritura::sub).containsOnly(50);
        assertThat(mapper.readTree(e.get(0).cuerpo())).isEqualTo(mapper.readTree("{\"nombre\":\"Nuevo Uno\",\"username\":\"11.111.111-1\","
                + "\"password\":\"Temporal#2026\",\"rol\":\"CALIDAD\",\"roles\":[\"CALIDAD\"]}"));
        assertThat(mapper.readTree(e.get(1).cuerpo())).isEqualTo(mapper.readTree("{\"roles\":[\"INSPECTOR\"]}"));
        assertThat(mapper.readTree(e.get(2).cuerpo())).isEqualTo(mapper.readTree("{\"password\":\"Otra#2026x\"}"));
        assertThat(mapper.readTree(e.get(3).cuerpo())).isEqualTo(mapper.readTree("{}"));
        assertThat(mapper.readTree(e.get(6).cuerpo())).isEqualTo(mapper.readTree(
                "{\"permisos\":[{\"modulo\":\"faret-nc\",\"nivel\":\"VER\"},{\"modulo\":\"faret-talleres-externos\",\"nivel\":null}]}"));
        assertThat(salida.getAll()).contains("faret:usuarios:20:cambiarRol", "faret:usuarios:crear:11.111.111-1")
                .doesNotContain("Temporal#2026").doesNotContain("Otra#2026x");
    }

    @Test
    void validaAntesDeLlamarALaApi() throws Exception {
        MockHttpSession s = login("tecnico");
        String ok = "Temporal#2026";
        enviar(s, "create", Map.of("username", "u", "rol", "CALIDAD", "password", ok)).andExpect(jsonPath("$.error").value("Falta el nombre"));
        enviar(s, "create", Map.of("nombre", "N", "rol", "CALIDAD", "password", ok)).andExpect(jsonPath("$.error").value("Falta el RUT/usuario"));
        enviar(s, "create", Map.of("nombre", "N", "username", "u", "password", ok)).andExpect(jsonPath("$.error").value("Falta el rol"));
        enviar(s, "create", Map.of("nombre", "N", "username", "u", "rol", "CALIDAD")).andExpect(jsonPath("$.error").value("Falta la clave temporal"));
        enviar(s, "create", Map.of("nombre", "N", "username", "u", "rol", "CALIDAD", "password", "   ")).andExpect(jsonPath("$.error").value("Falta la clave temporal"));
        enviar(s, "create", Map.of("nombre", "N", "username", "u", "rol", "SUPERUSUARIO", "password", ok)).andExpect(jsonPath("$.error").value("Rol inválido"));
        enviar(s, "create", Map.of("nombre", "N", "username", "u", "rol", "CALIDAD", "password", ok, "id", 3))
                .andExpect(jsonPath("$.error").value("Campo no permitido: id"));
        enviar(s, "create", Map.of("nombre", "N\nX", "username", "u", "rol", "CALIDAD", "password", ok)).andExpect(jsonPath("$.error").exists());
        enviar(s, "create", Map.of("nombre", "n".repeat(151), "username", "u", "rol", "CALIDAD", "password", ok)).andExpect(jsonPath("$.error").exists());
        enviar(s, "create", Map.of("nombre", "N", "username", "u".repeat(51), "rol", "CALIDAD", "password", ok)).andExpect(jsonPath("$.error").exists());
        enviar(s, "create", Map.of("nombre", "N", "username", "u", "rol", "CALIDAD", "password", "p".repeat(257))).andExpect(jsonPath("$.error").exists());
        enviar(s, "create", Map.of("nombre", 5, "username", "u", "rol", "CALIDAD", "password", ok)).andExpect(jsonPath("$.error").value("Falta el nombre"));

        for (String accion : List.of("cambiarRol", "resetPassword", "activar", "desactivar", "eliminarDefinitivo", "permisos.restablecer")) {
            for (Object id : new Object[] {0, -1, "x", "1.5", true, List.of(1)}) {
                enviar(s, accion, Map.of("id", id, "rol", "CALIDAD", "password", "x")).andExpect(jsonPath("$.ok").value(false));
            }
            enviar(s, accion, Map.of()).andExpect(jsonPath("$.error").value("Falta el id del usuario"));
        }
        enviar(s, "activar", Map.of("id", 20, "rol", "ADMIN")).andExpect(jsonPath("$.error").value("Campo no permitido: rol"));
        enviar(s, "cambiarRol", Map.of("id", 20)).andExpect(jsonPath("$.error").value("Falta el rol"));
        enviar(s, "cambiarRol", Map.of("id", 20, "rol", "ROOT")).andExpect(jsonPath("$.error").value("Rol inválido"));
        enviar(s, "resetPassword", Map.of("id", 20)).andExpect(jsonPath("$.error").value("Falta la clave temporal"));
        enviar(s, "resetPassword", Map.of("id", 20, "password", "p".repeat(257))).andExpect(jsonPath("$.error").exists());
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void permisosGuardarValidaModulosYNiveles() throws Exception {
        MockHttpSession s = login("tecnico");
        String invalido = "Los permisos recibidos no son válidos";
        List<Object> casos = List.of(
                List.of(Map.of("modulo", "faret-usuarios", "nivel", "EDITAR")),
                List.of(Map.of("modulo", "usuarios", "nivel", "EDITAR")),
                List.of(Map.of("modulo", "dashboard", "nivel", "VER")),
                List.of(Map.of("modulo", "nada", "nivel", "VER")),
                List.of(Map.of("modulo", "faret-nc", "nivel", "ADMIN")),
                List.of(Map.of("modulo", "faret-nc", "nivel", "ver")),
                List.of(Map.of("modulo", "faret-nc", "nivel", 5)),
                List.of(Map.of("nivel", "VER")),
                List.of(5),
                List.of(Map.of("modulo", "faret-nc", "nivel", "VER"), Map.of("modulo", "faret-nc", "nivel", "EDITAR")));
        for (Object permisos : casos) {
            enviar(s, "permisos.guardar", Map.of("id", 20, "permisos", permisos)).andExpect(jsonPath("$.error").value(invalido));
        }
        enviar(s, "permisos.guardar", Map.of("id", 20)).andExpect(jsonPath("$.error").value("No se recibieron permisos"));
        enviar(s, "permisos.guardar", Map.of("id", 20, "permisos", Map.of())).andExpect(jsonPath("$.error").value("No se recibieron permisos"));
        enviar(s, "permisos.guardar", Map.of("id", 0, "permisos", List.of())).andExpect(jsonPath("$.error").value("Falta el id del usuario"));
        enviar(s, "permisos.guardar", Map.of("id", 20, "permisos", List.of(), "rol", "ADMIN")).andExpect(jsonPath("$.error").value("Campo no permitido: rol"));
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void nadieActuaSobreSuPropiaCuentaYLosErroresDeLaApiPasan() throws Exception {
        MockHttpSession s = login("tecnico");
        String propio = "No puedes aplicar esta acción sobre tu propio usuario";
        enviar(s, "cambiarRol", Map.of("id", 50, "rol", "CALIDAD")).andExpect(jsonPath("$.error").value(propio));
        enviar(s, "desactivar", Map.of("id", 50)).andExpect(jsonPath("$.error").value(propio));
        enviar(s, "eliminarDefinitivo", Map.of("id", 50)).andExpect(jsonPath("$.error").value(propio));
        assertThat(QC.escrituras()).isEmpty();
        // Restablecer la clave, activar y los permisos propios no se bloquean (como Photino).
        enviar(s, "resetPassword", Map.of("id", 50, "password", "Temporal#2026")).andExpect(jsonPath("$.ok").value(true));

        QC.respuestaEscritura("DELETE /api/usuarios/9/definitivo", 400,
                "{\"success\":false,\"message\":\"El usuario tiene historial y no puede eliminarse\",\"data\":null,\"errors\":null}");
        enviar(s, "eliminarDefinitivo", Map.of("id", 9)).andExpect(jsonPath("$.error").value("El usuario tiene historial y no puede eliminarse"));
        QC.respuestaEscritura("PUT /api/usuarios/9/activar", 500, "no es json");
        enviar(s, "activar", Map.of("id", 9)).andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret"));
    }

    @Test
    void soloAdminTiDeLaSesionFaret() throws Exception {
        for (String usuario : new String[] {"admin", "ana"}) {
            MockHttpSession s = login(usuario);
            for (String a : ACCIONES) {
                enviar(s, a, Map.of("id", 20, "rol", "CALIDAD", "password", "x", "nombre", "n", "username", "u", "permisos", List.of()))
                        .andExpect(status().isForbidden());
            }
        }
        assertThat(QC.escrituras()).isEmpty();
    }

    private static Map<String, Object> nivelNulo(String modulo) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("modulo", modulo);
        m.put("nivel", null);
        return m;
    }

    private ResultActions enviar(MockHttpSession sesion, String accion, Map<String, Object> campos) throws Exception {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", "faret.usuarios." + accion);
        p.put("_modulo", "faret-usuarios");
        campos.forEach((k, v) -> p.set(k, mapper.valueToTree(v)));
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(p.toString()));
    }

    private MockHttpSession login(String identificador) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/faret/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.82.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identificador\":\"" + identificador + "\",\"password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private static Path crearWww() {
        try {
            Path dir = Files.createTempDirectory("qcc-web-fixture-fusr");
            Files.writeString(dir.resolve("index.html"), "<!doctype html><title>fixture</title>", StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
