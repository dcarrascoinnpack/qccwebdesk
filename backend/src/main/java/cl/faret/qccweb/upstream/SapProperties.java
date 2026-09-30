package cl.faret.qccweb.upstream;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de apisapfaret (qcc.web.sap.*), la API de consultas SAP que Photino usa vía SapRecepcionApiClient
 * (config.json → SapRecepcionApi). Se autentica con una API key (header X-Api-Key), no con el JWT del usuario: la key
 * vive SOLO en el servidor (variable de entorno QCC_SAP_API_KEY), nunca en el repo ni en el navegador. apisapfaret
 * admite una key por consumidor (Security:ApiKeys): lo recomendable es una propia para la web. Sin base URL la web
 * responde como Photino en un equipo sin SAP configurado.
 *
 * @param baseUrl           p. ej. https://api.faret.cl/apifaret (vacío = no configurada)
 * @param apiKey            valor del header X-Api-Key (secreto; nunca se loguea)
 * @param connectTimeout    timeout de conexión
 * @param readTimeout       timeout de lectura (Photino: HttpClient.Timeout = 25 s)
 * @param maxBytesRespuesta tope del cuerpo leído de cada respuesta
 */
@ConfigurationProperties(prefix = "qcc.web.sap")
public record SapProperties(
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
        return "SapProperties[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null || apiKey.isEmpty() ? "-" : "***")
                + ", readTimeout=" + readTimeout + "]";
    }
}
