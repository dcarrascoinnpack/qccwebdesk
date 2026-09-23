package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.AuthController;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Export de la ActionPolicy REAL (el mismo bean que usa el gateway) para el contract check:
 * escribe target/contract/web-actions.json, que consume tools/contract/photino_contract.py.
 * Se ejecuta con: mvnw -Dtest=ActionPolicyExportTest test
 */
@SpringBootTest(properties = "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME)
class ActionPolicyExportTest {

    static final Path SALIDA = Path.of("target", "contract", "web-actions.json");
    private static final Path WWW = crearWww();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.www-dir", WWW::toString);
        registry.add("qcc.web.manifest-file", () -> WWW.resolve("no-existe.json").toString());
        registry.add("qcc.web.auth.innpack-api-base-url", () -> "http://127.0.0.1:9");
    }

    @Autowired
    private ActionPolicy policy;

    @Test
    void exportaLaPoliticaRealAWebActionsJson() throws IOException {
        List<Map<String, Object>> acciones = new ArrayList<>();
        for (Map<String, Object> regla : policy.describir()) {
            Map<String, Object> d = new LinkedHashMap<>(regla);
            d.put("via", "bridge");
            acciones.add(d);
        }
        new TreeMap<>(AuthController.ACCIONES_PHOTINO).forEach((accion, endpoint) -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("accion", accion);
            d.put("via", "AuthController " + endpoint);
            acciones.add(d);
        });

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("descripcion", "Acciones de Photino habilitadas en QCC Web (ActionPolicy + AuthController). "
                + "Generado por ActionPolicyExportTest.");
        export.put("acciones", acciones);

        ObjectMapper mapper = new ObjectMapper().rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();
        Files.createDirectories(SALIDA.getParent());
        Files.writeString(SALIDA, mapper.writeValueAsString(export) + "\n", StandardCharsets.UTF_8);

        JsonNode leido = mapper.readTree(Files.readString(SALIDA));
        List<String> nombres = new ArrayList<>();
        leido.get("acciones").forEach(a -> nombres.add(a.get("accion").asString()));
        assertThat(nombres).containsExactlyInAnyOrderElementsOf(esperadas());

        JsonNode dashboard = buscar(leido, "inicio.getDashboard");
        assertThat(dashboard.get("via").asString()).isEqualTo("bridge");
        assertThat(dashboard.get("empresas").toString()).isEqualTo("[\"INNPACK\"]");
        assertThat(dashboard.get("roles").toString()).isEqualTo("[\"admin\",\"admin_ti\",\"operador\"]");
        assertThat(dashboard.get("identidad").size()).isZero();
        assertThat(Files.readString(SALIDA)).doesNotContainIgnoringCase("token").doesNotContainIgnoringCase("password");

        assertThat(buscar(leido, "auth.login").get("via").asString()).isEqualTo("AuthController POST /api/v1/auth/login");
        assertThat(buscar(leido, "auth.me").get("via").asString()).isEqualTo("AuthController GET /api/v1/auth/session");
    }

    /** Todo lo habilitado en la web: reglas del bridge + acciones de autenticación. */
    private List<String> esperadas() {
        List<String> esperadas = new ArrayList<>(policy.accionesRegistradas());
        esperadas.addAll(AuthController.ACCIONES_PHOTINO.keySet());
        return esperadas;
    }

    private static JsonNode buscar(JsonNode export, String accion) {
        for (JsonNode a : export.get("acciones")) {
            if (a.get("accion").asString().equals(accion)) {
                return a;
            }
        }
        throw new AssertionError("No exportada: " + accion);
    }

    @Test
    void describirRefleja1a1LasReglasRegistradas() {
        List<Map<String, Object>> descripcion = policy.describir();
        assertThat(descripcion).extracting(d -> d.get("accion")).containsExactlyElementsOf(policy.accionesRegistradas());
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-export");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
