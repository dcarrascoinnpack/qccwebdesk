package cl.faret.qccweb.upstream;

import cl.faret.qccweb.auth.AuthProperties;
import cl.faret.qccweb.auth.SessionUser;
import java.net.URI;
import java.net.http.HttpClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Cliente HTTP hacia la API INNPACK para las acciones del bridge.
 *
 * A diferencia del InnpackApiClient de Photino (un token en DefaultRequestHeaders compartido por
 * todo el proceso), este cliente NO guarda estado de autenticación: el Bearer se toma en cada
 * llamada del SessionUser de la petición actual. Nunca hay cuenta de servicio ni token de otro
 * usuario involucrado.
 */
public class InnpackApiClient {

    /** Respuesta cruda de la API. status 0 = no se pudo conectar (timeout / red). */
    public record Respuesta(int status, String body) {}

    private final RestClient restClient;
    private final String baseUrl;

    public InnpackApiClient(AuthProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        this.baseUrl = properties.innpackApiBaseUrl().replaceAll("/+$", "");
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * GET autenticado con el JWT del usuario de la sesión.
     *
     * @param path ruta + query YA escapadas (como las arma Photino con Uri.EscapeDataString). Se
     *             envía como URI literal: sin expansión de plantillas ni re-codificación de "%".
     * @throws UpstreamNoAutorizadoException si la API responde 401 (token vencido/revocado)
     */
    public Respuesta get(SessionUser usuario, String path) {
        ResponseEntity<String> r;
        try {
            r = restClient.get()
                    .uri(URI.create(baseUrl + path))
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> h.setBearerAuth(usuario.upstreamToken()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {})
                    .toEntity(String.class);
        } catch (RestClientException e) {
            return new Respuesta(0, null);
        }
        if (r.getStatusCode().value() == 401) {
            throw new UpstreamNoAutorizadoException();
        }
        return new Respuesta(r.getStatusCode().value(), r.getBody());
    }

    /**
     * POST JSON autenticado con el JWT del usuario de la sesión (escrituras). Sin reintentos: un
     * timeout devuelve status 0 y el llamador responde error, así nunca se duplica una escritura en
     * silencio. El cuerpo lo arma SIEMPRE el gateway (nunca el payload del navegador tal cual).
     *
     * @throws UpstreamNoAutorizadoException si la API responde 401 (token vencido/revocado)
     */
    public Respuesta postJson(SessionUser usuario, String path, JsonNode cuerpo) {
        return enviarJson(HttpMethod.POST, usuario, path, cuerpo);
    }

    /** PUT JSON autenticado (escrituras que sobrescriben). Mismas garantías que postJson: sin reintentos. */
    public Respuesta putJson(SessionUser usuario, String path, JsonNode cuerpo) {
        return enviarJson(HttpMethod.PUT, usuario, path, cuerpo);
    }

    /** PATCH JSON autenticado (actualizaciones parciales, p. ej. gestión de NC). Mismas garantías: sin reintentos. */
    public Respuesta patchJson(SessionUser usuario, String path, JsonNode cuerpo) {
        return enviarJson(HttpMethod.PATCH, usuario, path, cuerpo);
    }

    private Respuesta enviarJson(HttpMethod metodo, SessionUser usuario, String path, JsonNode cuerpo) {
        ResponseEntity<String> r;
        try {
            r = restClient.method(metodo)
                    .uri(URI.create(baseUrl + path))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> h.setBearerAuth(usuario.upstreamToken()))
                    .body(cuerpo.toString())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {})
                    .toEntity(String.class);
        } catch (RestClientException e) {
            return new Respuesta(0, null);
        }
        if (r.getStatusCode().value() == 401) {
            throw new UpstreamNoAutorizadoException();
        }
        return new Respuesta(r.getStatusCode().value(), r.getBody());
    }
}
