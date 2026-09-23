package cl.faret.qccweb.auth;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Login delegado a la API INNPACK (POST api/auth/login): la misma fuente maestra y el mismo
 * endpoint que usa Photino (AuthService.LoginAsync). La contraseña solo existe durante esta
 * llamada: no se guarda, no se loguea y no forma parte de ningún objeto con toString.
 *
 * Los mensajes de la API ("Usuario no existe", "Contraseña incorrecta"...) NO se devuelven al
 * navegador (permitirían enumerar usuarios); solo se exponen para la auditoría interna.
 */
public class InnpackAuthClient {

    /** Resultado del intento de login contra la API. */
    public sealed interface Resultado {}

    /** Credenciales válidas. {@code tokenExpira} es el exp del JWT (null si no se pudo leer). */
    public record Autenticado(
            String token, int userId, String codigoUsuario, String nombreCompleto, String rol, Instant tokenExpira)
            implements Resultado {
        @Override
        public String toString() {
            return "Autenticado[userId=" + userId + ", codigoUsuario=" + codigoUsuario + ", rol=" + rol + "]";
        }
    }

    /** La API rechazó las credenciales (401/400). {@code motivoInterno} solo va a la auditoría. */
    public record Rechazado(String motivoInterno) implements Resultado {}

    /** La API no respondió o respondió algo inválido: no cuenta como intento fallido del usuario. */
    public record NoDisponible(String detalleInterno) implements Resultado {}

    private final RestClient restClient;
    private final ObjectMapper mapper;

    public InnpackAuthClient(AuthProperties properties, ObjectMapper mapper) {
        if (properties.innpackApiBaseUrl() == null || properties.innpackApiBaseUrl().isBlank()) {
            throw new IllegalStateException("Falta configurar qcc.web.auth.innpack-api-base-url (QCC_INNPACK_API_BASE_URL).");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.innpackApiBaseUrl().replaceAll("/+$", ""))
                .requestFactory(factory)
                .build();
        this.mapper = mapper;
    }

    public Resultado login(String codigoUsuario, String password) {
        ResponseEntity<String> respuesta;
        // Cuerpo serializado a bytes con largo explícito (sin transfer-encoding chunked) y borrado de
        // memoria apenas se envía, para que la contraseña no quede en un buffer reutilizable.
        byte[] cuerpo = mapper.writeValueAsBytes(Map.of("codigoUsuario", codigoUsuario, "password", password));
        try {
            respuesta = restClient.post()
                    .uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .contentLength(cuerpo.length)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(cuerpo)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {})
                    .toEntity(String.class);
        } catch (RestClientException e) {
            return new NoDisponible(e.getClass().getSimpleName());
        } finally {
            Arrays.fill(cuerpo, (byte) 0);
        }

        int status = respuesta.getStatusCode().value();
        JsonNode body = leerJson(respuesta.getBody());

        if (status == 401 || status == 400) {
            return new Rechazado("HTTP " + status + ": " + texto(campo(body, "message")));
        }
        if (status != 200 || body == null) {
            return new NoDisponible("HTTP " + status);
        }

        JsonNode data = campo(body, "data");
        String token = texto(campo(data, "token"));
        JsonNode userId = campo(data, "userId");
        String codigo = texto(campo(data, "codigoUsuario"));
        String rol = texto(campo(data, "rol"));
        if (token == null || token.isBlank() || userId == null || !userId.canConvertToInt()
                || codigo == null || rol == null) {
            return new NoDisponible("Respuesta de login incompleta");
        }
        return new Autenticado(
                token, userId.asInt(), codigo, texto(campo(data, "nombreCompleto")), rol, expiracionJwt(token));
    }

    /** Lee el claim exp del JWT (sin verificar firma: solo se usa para acotar la sesión web). */
    Instant expiracionJwt(String token) {
        try {
            String[] partes = token.split("\\.");
            if (partes.length < 2) {
                return null;
            }
            byte[] payload = Base64.getUrlDecoder().decode(partes[1]);
            JsonNode exp = mapper.readTree(new String(payload, StandardCharsets.UTF_8)).get("exp");
            return exp != null && exp.canConvertToLong() ? Instant.ofEpochSecond(exp.asLong()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private JsonNode leerJson(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(texto);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Busca un campo sin distinguir mayúsculas (la API .NET usa camelCase, Photino lee sin distinguir). */
    private static JsonNode campo(JsonNode nodo, String nombre) {
        if (nodo == null || !nodo.isObject()) {
            return null;
        }
        Iterator<Map.Entry<String, JsonNode>> it = nodo.properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().equalsIgnoreCase(nombre)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String texto(JsonNode nodo) {
        return nodo == null || nodo.isNull() ? null : nodo.asString();
    }
}
