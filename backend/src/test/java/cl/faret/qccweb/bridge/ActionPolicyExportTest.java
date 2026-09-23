package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import cl.faret.qccweb.QccWebGatewayApplication;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("descripcion", "Acciones habilitadas en QCC Web (ActionPolicy). Generado por ActionPolicyExportTest.");
        export.put("acciones", policy.describir());

        ObjectMapper mapper = new ObjectMapper().rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();
        Files.createDirectories(SALIDA.getParent());
        Files.writeString(SALIDA, mapper.writeValueAsString(export) + "\n", StandardCharsets.UTF_8);

        JsonNode leido = mapper.readTree(Files.readString(SALIDA));
        assertThat(leido.get("acciones")).hasSize(1);
        JsonNode dashboard = leido.get("acciones").get(0);
        assertThat(dashboard.get("accion").asString()).isEqualTo("inicio.getDashboard");
        assertThat(dashboard.get("empresas").toString()).isEqualTo("[\"INNPACK\"]");
        assertThat(dashboard.get("roles").toString()).isEqualTo("[\"admin\",\"admin_ti\",\"operador\"]");
        assertThat(dashboard.get("identidad").size()).isZero();
        assertThat(Files.readString(SALIDA)).doesNotContainIgnoringCase("token").doesNotContainIgnoringCase("password");
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
