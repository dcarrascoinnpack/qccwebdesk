package cl.faret.qccweb.upstream;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de la API "Programa de Producción" / Planificación FARET (qcc.web.planificacion.*), la misma que Photino
 * lee de config.json → PlanificacionApi. No tiene autenticación (confirmado en Photino): por eso no hay API key. Sin base
 * URL la web responde como Photino en un equipo sin Planificación configurada.
 *
 * @param baseUrl           p. ej. https://planificacion.faret.cl (vacío = no configurada)
 * @param connectTimeout    timeout de conexión
 * @param readTimeout       timeout de lectura (Photino: HttpClient.Timeout = 20 s)
 * @param maxBytesRespuesta tope del cuerpo leído (vista-planificacion trae el plan completo)
 */
@ConfigurationProperties(prefix = "qcc.web.planificacion")
public record PlanificacionProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        long maxBytesRespuesta) {

    public boolean configurada() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
