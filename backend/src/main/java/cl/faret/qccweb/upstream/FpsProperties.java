package cl.faret.qccweb.upstream;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de fps-api (qcc.web.fps.*), la API de integración con FPS/SAP que Photino usa vía FpsApiClient
 * (config.json → FpsApi). Se autentica con una API key compartida (header x-api-key), no con el JWT del usuario:
 * la key vive SOLO en el servidor (variable de entorno QCC_FPS_API_KEY), nunca en el repo ni en el navegador.
 * Sin base URL la web responde como Photino en un equipo sin fps-api configurada.
 *
 * @param baseUrl           p. ej. https://api.faret.cl/fps/api/produccion (vacío = no configurada)
 * @param apiKey            valor del header x-api-key (secreto; nunca se loguea)
 * @param connectTimeout    timeout de conexión
 * @param readTimeout       timeout de lectura (Photino: HttpClient.Timeout = 20 s)
 * @param maxBytesRespuesta tope del cuerpo leído de cada respuesta
 */
@ConfigurationProperties(prefix = "qcc.web.fps")
public record FpsProperties(
        String baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration readTimeout,
        long maxBytesRespuesta) {

    public boolean configurada() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /** Nunca incluir la API key. */
    @Override
    public String toString() {
        return "FpsProperties[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null || apiKey.isEmpty() ? "-" : "***")
                + ", readTimeout=" + readTimeout + "]";
    }
}
