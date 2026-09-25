package cl.faret.qccweb.version;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * (resultado del contract check, p. ej. "Photino 1.8.12 · Web compatible 1/236"), contra qué commit
 * Photino quedó validada por última vez y qué acciones están en cada estado.
 * No expone secretos: solo versiones, commits, fechas, conteos y nombres de acciones.
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
        // Último commit Photino completamente validado (contrato sin bloqueos) que sirve esta web.
        JsonNode validado = manifest.objeto("photinoValidado");
        photino.put("validado", validado == null ? null : Map.of(
                "version", String.valueOf(FrontendManifest.texto(validado, "version")),
                "commit", String.valueOf(FrontendManifest.texto(validado, "commit"))));

        Map<String, Object> frontend = new LinkedHashMap<>();
        frontend.put("version", manifest.texto("photinoVersion"));
        frontend.put("wwwSha256", manifest.texto("wwwSha256"));
        frontend.put("shimSha256", manifest.texto("shimSha256"));
        frontend.put("webDistSha256", manifest.texto("webDistSha256"));
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
        respuesta.put("acciones", acciones(manifest.objeto("acciones")));
        this.version = Map.copyOf(respuesta);
    }

    @GetMapping("/version")
    public ResponseEntity<Map<String, Object>> version() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(version);
    }

    /** COMPATIBLE / PENDIENTE / REVISAR / SOLO_WEB → nombres de acciones (vacío si no hay contrato). */
    private static Map<String, List<String>> acciones(JsonNode acciones) {
        Map<String, List<String>> a = new LinkedHashMap<>();
        for (String estado : new String[] {"COMPATIBLE", "PENDIENTE", "REVISAR", "SOLO_WEB"}) {
            List<String> nombres = new ArrayList<>();
            JsonNode lista = acciones == null ? null : acciones.get(estado);
            if (lista != null && lista.isArray()) {
                lista.forEach(n -> nombres.add(n.asString()));
            } else if (lista != null && lista.isString()) {
                nombres.add(lista.asString()); // ConvertTo-Json de PowerShell aplana listas de 1 elemento
            }
            a.put(estado, List.copyOf(nombres));
        }
        return a;
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
