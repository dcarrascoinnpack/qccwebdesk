package cl.faret.qccweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ubicación del snapshot del frontend Photino generado por tools/sync-photino-www.ps1.
 *
 * @param wwwDir       carpeta con el www de Photino ya extraído de un commit (+ web-bridge.js)
 * @param manifestFile manifest JSON escrito por el mismo script (versión/commit de origen)
 */
@ConfigurationProperties(prefix = "qcc.web")
public record GatewayWebProperties(String wwwDir, String manifestFile) {}
