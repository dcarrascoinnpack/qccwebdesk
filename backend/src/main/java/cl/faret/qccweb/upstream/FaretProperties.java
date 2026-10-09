package cl.faret.qccweb.upstream;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * URLs base de las APIs FARET que Photino consume por separado de QualityControlFaret.Api (esa ya vive en
 * qcc.web.auth.faret-api-base-url, Fase 6a): config.json → MejoraContinuaFaretApi y CalidadFaretApi. Ninguna es secreta.
 * Vacía = "no configurada" (Photino responde "API de Mejora Continua no configurada..." en un equipo sin esa sección).
 *
 * @param mejoraContinuaBaseUrl p. ej. https://api.faret.cl/mejora-continua (no conformidades; Photino no le manda token)
 * @param calidadBaseUrl        p. ej. https://api.faret.cl/calidad/api (backend Node de Calidad; sin autenticación)
 */
@ConfigurationProperties(prefix = "qcc.web.faret")
public record FaretProperties(String mejoraContinuaBaseUrl, String calidadBaseUrl) {}
