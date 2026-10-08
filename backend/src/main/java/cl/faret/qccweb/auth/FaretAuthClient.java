package cl.faret.qccweb.auth;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
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
 * Login FARET delegado a QualityControlFaret.Api (Fase 6a): POST api/Auth/login, el mismo endpoint y la misma
 * fuente maestra que usa Photino (FaretAuthApiService.LoginAsync + MessageRouter.CargarPermisosFaretAsync).
 * Respuesta real de la API: { success, message, data: { token, expiresAt, usuario: { id, nombre, username, correo,
 * roles: ["ADMIN"] } } }. Como Photino, el rol de la sesión es el PRIMER rol de la lista.
 *
 * Mismas reglas que InnpackAuthClient: la contraseña solo existe durante la llamada; los mensajes de la API no se
 * devuelven al navegador (solo a la auditoría). Devuelve los mismos tipos de resultado (Autenticado/Rechazado/
 * NoDisponible) para que AuthController trate ambas empresas igual.
 */
public class FaretAuthClient {

    private final RestClient restClient;
    private final ObjectMapper mapper;

    public FaretAuthClient(AuthProperties properties, ObjectMapper mapper) {
        if (properties.faretApiBaseUrl() == null || properties.faretApiBaseUrl().isBlank()) {
            throw new IllegalStateException("Falta configurar qcc.web.auth.faret-api-base-url (QCC_FARET_API_BASE_URL).");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.faretApiBaseUrl().replaceAll("/+$", ""))
                .requestFactory(factory)
                .build();
        this.mapper = mapper;
    }

    /**
     * @param identificador correo o username (la API acepta ambos en el mismo campo, como Photino)
     */
    public InnpackAuthClient.Resultado login(String identificador, String password) {
        ResponseEntity<String> respuesta;
        // Mismo orden que LoginRequest de Photino (identificador, password).
        Map<String, String> peticion = new LinkedHashMap<>();
        peticion.put("identificador", identificador);
        peticion.put("password", password);
        byte[] cuerpo = mapper.writeValueAsBytes(peticion);
        try {
            respuesta = restClient.post()
                    .uri("/api/Auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .contentLength(cuerpo.length)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(cuerpo)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {})
                    .toEntity(String.class);
        } catch (RestClientException e) {
            return new InnpackAuthClient.NoDisponible(e.getClass().getSimpleName());
        } finally {
            Arrays.fill(cuerpo, (byte) 0);
        }

        int status = respuesta.getStatusCode().value();
        JsonNode body = leerJson(respuesta.getBody());
        JsonNode success = campo(body, "success");

        // FaretAuthApiService: !ok o success=false → credenciales rechazadas (mensaje de la API solo a auditoría).
        if (status == 401 || status == 400 || (success != null && success.isBoolean() && !success.asBoolean())) {
            String mensaje = texto(campo(body, "message"));
            return new InnpackAuthClient.Rechazado("HTTP " + status + ": " + (mensaje == null ? texto(campo(body, "error")) : mensaje));
        }
        if (status != 200 || body == null) {
            return new InnpackAuthClient.NoDisponible("HTTP " + status);
        }

        JsonNode data = campo(body, "data");
        String token = texto(campo(data, "token"));
        if (token == null || token.isBlank()) {
            return new InnpackAuthClient.NoDisponible("Token no recibido en la respuesta");
        }
        JsonNode usuario = campo(data, "usuario");
        JsonNode id = campo(usuario, "id");
        String nombre = texto(campo(usuario, "nombre"));
        String username = texto(campo(usuario, "username"));
        String rol = primerRol(campo(usuario, "roles"));
        return new InnpackAuthClient.Autenticado(
                token,
                id != null && id.canConvertToInt() ? id.asInt() : 0,
                username == null || username.isBlank() ? identificador : username,
                nombre == null ? "" : nombre,
                rol,
                expiracionJwt(token));
    }

    /** Primer elemento string del arreglo de roles (igual que el foreach + break de Photino); "" si no hay. */
    private static String primerRol(JsonNode roles) {
        if (roles == null || !roles.isArray()) {
            return "";
        }
        for (JsonNode r : roles) {
            return r.isString() ? r.asString() : "";
        }
        return "";
    }

    /**
     * Permisos personalizados del usuario FARET: GET api/auth/mis-permisos con SU token. Mismo contrato y mismo
     * criterio de parseo que la API INNPACK (PermisosService.TryParsearPermisos de Photino).
     *
     * @return módulo → nivel, o null si no se pudieron cargar (Photino anula el login en ese caso)
     */
    public Map<String, String> misPermisos(String token) {
        ResponseEntity<String> respuesta;
        try {
            respuesta = restClient.get()
                    .uri("/api/auth/mis-permisos")
                    .header("Authorization", "Bearer " + token)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {})
                    .toEntity(String.class);
        } catch (RestClientException e) {
            return null;
        }
        if (respuesta.getStatusCode().value() != 200) {
            return null;
        }
        JsonNode data = campo(leerJson(respuesta.getBody()), "data");
        if (data == null || !data.isArray()) {
            return null;
        }
        Map<String, String> permisos = new LinkedHashMap<>();
        for (JsonNode fila : data) {
            JsonNode modulo = campo(fila, "modulo");
            JsonNode nivel = campo(fila, "nivel");
            if (modulo == null || !modulo.isString() || modulo.asString().isBlank()
                    || nivel == null || !nivel.isString() || nivel.asString().isBlank()) {
                continue;
            }
            String n = nivel.asString().toUpperCase(Locale.ROOT);
            if (n.equals("SIN_ACCESO") || n.equals("VER") || n.equals("EDITAR")) {
                permisos.put(modulo.asString(), n);
            }
        }
        return permisos;
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
