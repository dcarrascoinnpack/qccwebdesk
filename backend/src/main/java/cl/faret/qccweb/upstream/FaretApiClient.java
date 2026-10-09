package cl.faret.qccweb.upstream;

import cl.faret.qccweb.auth.SessionUser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cliente HTTP (solo GET) hacia una de las tres APIs FARET que usa Photino con FaretApiClient.cs: QualityControlFaret.Api
 * (JWT del usuario), MejoraContinua y Calidad (Photino NUNCA les hace SetToken: no llevan Authorization).
 *
 * Igual que InnpackApiClient, NO guarda estado de autenticación: con {@code conBearer} el Bearer sale en cada llamada del
 * SessionUser de la petición actual (nunca un token compartido ni de otro usuario); sin él, no se envía Authorization
 * aunque haya sesión. Sin redirecciones. Fallas locales (timeout/red) y respuestas de error sin cuerpo se devuelven con el
 * mismo cuerpo sintético {ok:false,error} que arma FaretApiClient.cs, para que FaretRespuestas aplique la misma lógica.
 */
public class FaretApiClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final boolean conBearer;

    /**
     * @param baseUrl   URL base (vacía = no configurada: {@link #configurada()} es false y no debe llamarse a {@link #get})
     * @param conBearer true = Authorization Bearer con el token upstream del usuario (QualityControlFaret.Api)
     */
    public FaretApiClient(String baseUrl, boolean conBearer, Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.conBearer = conBearer;
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** FaretApiClient.IsConfigured: hay base URL. */
    public boolean configurada() {
        return !baseUrl.isEmpty();
    }

    /**
     * GET. {@code path} empieza con "/" y ya viene escapada (como la arma Photino con Uri.EscapeDataString): se envía
     * como URI literal, sin expansión de plantillas ni re-codificación de "%".
     *
     * @param usuario sesión de la petición; solo se usa si {@code conBearer}
     * @throws UpstreamNoAutorizadoException solo con Bearer: la API rechazó el token de ESTE usuario (401)
     */
    public InnpackApiClient.Respuesta get(SessionUser usuario, String path) {
        ResponseEntity<String> r;
        try {
            r = restClient.method(HttpMethod.GET)
                    .uri(URI.create(baseUrl + path))
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (conBearer) {
                            h.setBearerAuth(usuario.upstreamToken());
                        }
                    })
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {})
                    .toEntity(String.class);
        } catch (RestClientException e) {
            return new InnpackApiClient.Respuesta(0,
                    errorLocal(esTimeout(e) ? "Timeout al conectar con la API Faret" : "Error de red al conectar con la API Faret"));
        }
        int status = r.getStatusCode().value();
        if (conBearer && status == 401) {
            throw new UpstreamNoAutorizadoException();
        }
        String body = r.getBody();
        if (status >= 400 && (body == null || body.isBlank())) {
            HttpStatus conocido = HttpStatus.resolve(status);
            body = errorLocal("HTTP " + status + ": " + (conocido != null ? conocido.getReasonPhrase() : ""));
        }
        return new InnpackApiClient.Respuesta(status, body);
    }

    private static String errorLocal(String mensaje) {
        return "{\"ok\":false,\"error\":\"" + mensaje + "\"}";
    }

    private static boolean esTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
