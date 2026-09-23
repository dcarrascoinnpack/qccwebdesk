package cl.faret.qccweb.version;

import cl.faret.qccweb.config.GatewayWebProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GET /version — qué versión de Photino sirve la web y con qué gateway.
 * No expone secretos: solo versiones, commits y fechas. El estado de compatibilidad del
 * contrato de acciones queda "NO_EVALUADO" hasta la Fase 1d (contract check).
 */
@RestController
public class VersionController {

    private static final Logger LOGGER = LoggerFactory.getLogger(VersionController.class);

    private final Map<String, Object> version;

    public VersionController(
            GatewayWebProperties properties, ObjectProvider<BuildProperties> buildProperties, ObjectMapper mapper) {
        JsonNode manifest = leerManifest(properties.manifestFile(), mapper);
        BuildProperties build = buildProperties.getIfAvailable();

        Map<String, Object> photino = new LinkedHashMap<>();
        photino.put("version", texto(manifest, "photinoVersion"));
        photino.put("commit", texto(manifest, "photinoCommit"));
        photino.put("commitFecha", texto(manifest, "photinoCommitDate"));

        Map<String, Object> frontend = new LinkedHashMap<>();
        frontend.put("version", texto(manifest, "photinoVersion"));
        frontend.put("wwwSha256", texto(manifest, "wwwSha256"));
        frontend.put("shimSha256", texto(manifest, "shimSha256"));
        frontend.put("sincronizadoEn", texto(manifest, "syncedAt"));

        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("version", build != null ? build.getVersion() : null);
        gateway.put("buildTime", build != null && build.getTime() != null ? build.getTime().toString() : null);
        gateway.put("webRepoCommit", texto(manifest, "webRepoCommit"));

        Map<String, Object> compatibilidad = new LinkedHashMap<>();
        compatibilidad.put("estado", "NO_EVALUADO");
        compatibilidad.put("detalle", "Contract check Photino/Web pendiente (Fase 1d).");

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("photino", photino);
        respuesta.put("frontend", frontend);
        respuesta.put("gateway", gateway);
        respuesta.put("compatibilidad", compatibilidad);
        this.version = Map.copyOf(respuesta);
    }

    @GetMapping("/version")
    public ResponseEntity<Map<String, Object>> version() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(version);
    }

    private static JsonNode leerManifest(String manifestFile, ObjectMapper mapper) {
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

    private static String texto(JsonNode manifest, String campo) {
        if (manifest == null) {
            return null;
        }
        JsonNode valor = manifest.get(campo);
        return valor == null || valor.isNull() ? null : valor.asString();
    }
}
