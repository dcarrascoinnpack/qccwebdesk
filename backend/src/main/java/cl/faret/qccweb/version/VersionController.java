package cl.faret.qccweb.version;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * GET /version — qué versión de Photino sirve la web, con qué gateway y cuán compatible es
 * (resultado del contract check, p. ej. "Photino 1.8.12 · Web compatible 1/236").
 * No expone secretos: solo versiones, commits, fechas y conteos.
 */
@RestController
public class VersionController {

    private final Map<String, Object> version;

    public VersionController(FrontendManifest manifest, ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();

        Map<String, Object> photino = new LinkedHashMap<>();
        photino.put("version", manifest.texto("photinoVersion"));
        photino.put("commit", manifest.texto("photinoCommit"));
        photino.put("commitFecha", manifest.texto("photinoCommitDate"));

        Map<String, Object> frontend = new LinkedHashMap<>();
        frontend.put("version", manifest.texto("photinoVersion"));
        frontend.put("wwwSha256", manifest.texto("wwwSha256"));
        frontend.put("shimSha256", manifest.texto("shimSha256"));
        frontend.put("sincronizadoEn", manifest.texto("syncedAt"));

        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("version", build != null ? build.getVersion() : null);
        gateway.put("buildTime", build != null && build.getTime() != null ? build.getTime().toString() : null);
        gateway.put("webRepoCommit", manifest.texto("webRepoCommit"));

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("photino", photino);
        respuesta.put("frontend", frontend);
        respuesta.put("gateway", gateway);
        respuesta.put("compatibilidad", compatibilidad(manifest.contrato()));
        this.version = Map.copyOf(respuesta);
    }

    @GetMapping("/version")
    public ResponseEntity<Map<String, Object>> version() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(version);
    }

    private static Map<String, Object> compatibilidad(JsonNode contrato) {
        Map<String, Object> c = new LinkedHashMap<>();
        if (contrato == null) {
            c.put("estado", "NO_EVALUADO");
            c.put("texto", "Contract check Photino/Web no ejecutado para este snapshot.");
            return c;
        }
        c.put("estado", FrontendManifest.texto(contrato, "estado"));
        c.put("texto", FrontendManifest.texto(contrato, "texto"));
        for (String campo : new String[] {"compatibles", "accionesFrontend", "pendientes", "revisar", "soloWeb"}) {
            JsonNode v = contrato.get(campo);
            c.put(campo, v != null && v.canConvertToInt() ? v.asInt() : null);
        }
        c.put("photinoCommitAnalizado", FrontendManifest.texto(contrato, "photinoCommit"));
        c.put("generado", FrontendManifest.texto(contrato, "generado"));
        return c;
    }
}
