package cl.faret.qccweb.version;

import cl.faret.qccweb.config.GatewayWebProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * web-manifest.json escrito por tools/sync-photino-www.ps1: commit/versión de Photino del snapshot
 * servido y, desde la Fase 1d, el resultado del contract check ("contrato"). Se lee una sola vez.
 */
@Component
public class FrontendManifest {

    private static final Logger LOGGER = LoggerFactory.getLogger(FrontendManifest.class);

    private final JsonNode raiz;

    public FrontendManifest(GatewayWebProperties properties, ObjectMapper mapper) {
        this.raiz = leer(properties.manifestFile(), mapper);
    }

    public String texto(String campo) {
        return texto(raiz, campo);
    }

    /** Bloque "contrato" del manifest, o null si el contract check no se ejecutó. */
    public JsonNode contrato() {
        JsonNode c = raiz == null ? null : raiz.get("contrato");
        return c != null && c.isObject() ? c : null;
    }

    static String texto(JsonNode nodo, String campo) {
        if (nodo == null) {
            return null;
        }
        JsonNode valor = nodo.get(campo);
        return valor == null || valor.isNull() ? null : valor.asString();
    }

    private static JsonNode leer(String manifestFile, ObjectMapper mapper) {
        if (manifestFile == null || manifestFile.isBlank()) {
            return null;
        }
        Path path = Path.of(manifestFile).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            LOGGER.warn("No se encontró el manifest del frontend en {}; /version mostrará valores nulos.", path);
            return null;
        }
        try {
            return mapper.readTree(Files.readString(path));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("No se pudo leer el manifest del frontend {}: {}", path, e.getMessage());
            return null;
        }
    }
}
