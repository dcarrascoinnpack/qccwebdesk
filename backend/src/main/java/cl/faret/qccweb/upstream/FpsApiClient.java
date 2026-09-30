package cl.faret.qccweb.upstream;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cliente hacia fps-api (Fase 3v): solo GET, autenticado con el header x-api-key como el FpsApiClient de Photino.
 * La key es del servidor (FpsProperties), nunca del usuario ni del navegador, y no se loguea. Sin redirecciones y con
 * tope de tamaño de respuesta. No lanza por 401: fps-api responde 401 a una key inválida, que para el usuario es un
 * error de la integración (como en Photino), no una sesión vencida.
 */
public class FpsApiClient {

    private final RestClient restClient;
    private final FpsProperties properties;

    public FpsApiClient(FpsProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public boolean configurada() {
        return properties.configurada();
    }

    /**
     * @param pathYQuery ruta relativa + query YA escapadas (se envía como URI literal)
     * @return status 0 = no se pudo conectar, no está configurada o la respuesta excede el tope
     */
    public InnpackApiClient.Respuesta get(String pathYQuery) {
        if (!configurada()) {
            return new InnpackApiClient.Respuesta(0, null);
        }
        String url = properties.baseUrl().replaceAll("/+$", "") + "/" + pathYQuery.replaceAll("^/+", "");
        try {
            return restClient.get()
                    .uri(URI.create(url))
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (properties.apiKey() != null && !properties.apiKey().isEmpty()) {
                            h.set("x-api-key", properties.apiKey());
                        }
                    })
                    .exchange((req, res) -> {
                        String cuerpo = leerAcotado(res.getBody(), properties.maxBytesRespuesta());
                        return cuerpo == null
                                ? new InnpackApiClient.Respuesta(0, null)
                                : new InnpackApiClient.Respuesta(res.getStatusCode().value(), cuerpo);
                    });
        } catch (RestClientException | IllegalArgumentException e) {
            return new InnpackApiClient.Respuesta(0, null);
        }
    }

    /** Lee hasta max bytes; null si el cuerpo es mayor (no se carga entero en memoria). */
    private static String leerAcotado(InputStream in, long max) throws IOException {
        byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
        return bytes.length > max ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
