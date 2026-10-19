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
            String token, int userId, String codigoUsuario, String nombreCompleto, String rol, Instant tokenExpira,
            Politica politica)
            implements Resultado {
        public Autenticado(String token, int userId, String codigoUsuario, String nombreCompleto, String rol, Instant tokenExpira) {
            this(token, userId, codigoUsuario, nombreCompleto, rol, tokenExpira, Politica.NINGUNA);
        }

        @Override
        public String toString() {
            return "Autenticado[userId=" + userId + ", codigoUsuario=" + codigoUsuario + ", rol=" + rol + "]";
        }
    }

    /**
     * Política de contraseñas (API, 15-10-2026): {@code debeCambiar} = clave temporal, nunca cambiada o vencida (el frontend
     * muestra la pantalla de cambio antes de cualquier módulo); {@code dias} = días para el vencimiento (aviso si &lt;= 7).
     */
    public record Politica(boolean debeCambiar, String motivo, Integer dias, String venceEl) {
        public static final Politica NINGUNA = new Politica(false, null, null, null);
        static final int MAX_TEXTO = 300;
    }

    /** Respuesta de GET/POST cambiar-password. */
    public sealed interface Cambio {}

    /** Clave cambiada: la API emite un token nuevo (sin "cambio pendiente") que reemplaza al de la sesión. */
    public record CambioOk(String token, Instant tokenExpira, Politica politica) implements Cambio {
        @Override
        public String toString() {
            return "CambioOk[politica=" + politica + "]";
        }
    }

    /** La API rechazó el cambio (clave actual incorrecta, nueva inválida...). {@code mensaje} es apto para mostrar al usuario. */
    public record CambioRechazado(String mensaje) implements Cambio {}

    /** La API no respondió o respondió algo inválido: el resultado del cambio es desconocido. */
    public record CambioNoDisponible(String detalleInterno) implements Cambio {}

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
        // soportaCambioPassword: como Photino >= 1.8.21, esta web sabe mostrar el cambio de clave, así que la API puede exigirlo.
        Map<String, Object> peticion = new java.util.LinkedHashMap<>();
        peticion.put("codigoUsuario", codigoUsuario);
        peticion.put("password", password);
        peticion.put("soportaCambioPassword", true);
        byte[] cuerpo = mapper.writeValueAsBytes(peticion);
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
                token, userId.asInt(), codigo, texto(campo(data, "nombreCompleto")), rol, expiracionJwt(token), politicaDe(data));
    }

    /**
     * Cambio de clave propio: POST api/auth/cambiar-password con el token DEL usuario (AuthService.CambiarPasswordAsync de
     * Photino 1.8.23). Las claves solo existen durante la llamada: no se guardan ni se loguean.
     */
    public Cambio cambiarPassword(String token, String passwordActual, String passwordNueva) {
        byte[] cuerpo = mapper.writeValueAsBytes(cuerpoCambio(passwordActual, passwordNueva));
        ResponseEntity<String> respuesta;
        try {
            respuesta = restClient.post()
                    .uri("/api/auth/cambiar-password")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .contentLength(cuerpo.length)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(cuerpo)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {})
                    .toEntity(String.class);
        } catch (RestClientException e) {
            return new CambioNoDisponible(e.getClass().getSimpleName());
        } finally {
            Arrays.fill(cuerpo, (byte) 0);
        }
        return interpretarCambio(respuesta.getStatusCode().value(), respuesta.getBody(), mapper, this::expiracionJwt);
    }

    static Map<String, String> cuerpoCambio(String passwordActual, String passwordNueva) {
        Map<String, String> peticion = new java.util.LinkedHashMap<>();
        peticion.put("passwordActual", passwordActual);
        peticion.put("passwordNueva", passwordNueva);
        return peticion;
    }

    /**
     * Interpreta la respuesta de cambiar-password de cualquiera de las dos APIs ({success, message, data:{token, ...}}).
     * 400/401/403/409/422 o success=false → rechazo con el mensaje de la API (el usuario ya está autenticado: no hay
     * enumeración de usuarios); un éxito sin token o cualquier otro estado → resultado desconocido.
     */
    static Cambio interpretarCambio(int status, String cuerpo, ObjectMapper mapper,
            java.util.function.Function<String, Instant> expiracion) {
        JsonNode body = null;
        if (cuerpo != null && !cuerpo.isBlank()) {
            try {
                body = mapper.readTree(cuerpo);
            } catch (RuntimeException e) {
                body = null;
            }
        }
        JsonNode success = campo(body, "success");
        boolean rechazo = status == 400 || status == 401 || status == 403 || status == 409 || status == 422
                || (success != null && success.isBoolean() && !success.asBoolean());
        if (rechazo) {
            String mensaje = texto(campo(body, "message"));
            if (mensaje == null) {
                mensaje = texto(campo(body, "error"));
            }
            return new CambioRechazado(mensajeSeguro(mensaje));
        }
        if (status != 200 || body == null) {
            return new CambioNoDisponible("HTTP " + status);
        }
        JsonNode data = campo(body, "data");
        String token = texto(campo(data, "token"));
        if (token == null || token.isBlank()) {
            return new CambioNoDisponible("Token no recibido tras el cambio de clave");
        }
        return new CambioOk(token, expiracion.apply(token), politicaDe(data));
    }

    /** Mensaje de la API apto para mostrar: una línea, sin controles ni marcado, acotado. */
    static String mensajeSeguro(String mensaje) {
        String limpio = mensaje == null ? "" : mensaje.replaceAll("[\\p{Cntrl}<>]", " ").replaceAll("\\s+", " ").strip();
        if (limpio.isEmpty()) {
            return "No se pudo cambiar la contraseña";
        }
        return limpio.length() > Politica.MAX_TEXTO ? limpio.substring(0, Politica.MAX_TEXTO) : limpio;
    }

    /** Campos de política del data de login / cambio de clave; ausentes = sin política (API antigua). */
    static Politica politicaDe(JsonNode data) {
        JsonNode debe = campo(data, "debeCambiarPassword");
        JsonNode motivo = campo(data, "motivoCambioPassword");
        JsonNode dias = campo(data, "diasParaVencer");
        JsonNode vence = campo(data, "passwordVenceEl");
        return new Politica(
                debe != null && debe.isBoolean() && debe.asBoolean(),
                motivo != null && motivo.isString() ? mensajeSeguroOpcional(motivo.asString()) : null,
                dias != null && dias.isIntegralNumber() && dias.canConvertToInt() ? dias.asInt() : null,
                vence != null && vence.isString() ? mensajeSeguroOpcional(vence.asString()) : null);
    }

    private static String mensajeSeguroOpcional(String texto) {
        String limpio = texto.replaceAll("[\\p{Cntrl}<>]", " ").replaceAll("\\s+", " ").strip();
        if (limpio.isEmpty()) {
            return null;
        }
        return limpio.length() > Politica.MAX_TEXTO ? limpio.substring(0, Politica.MAX_TEXTO) : limpio;
    }

    /**
     * Permisos personalizados del usuario recién autenticado: GET api/auth/mis-permisos con SU token (la API toma el
     * id del token). Mismo criterio que PermisosService.TryParsearPermisos de Photino 1.8.14: data debe ser un arreglo;
     * se ignoran filas sin módulo/nivel y niveles desconocidos; el nivel se normaliza a mayúsculas.
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
        Map<String, String> permisos = new java.util.LinkedHashMap<>();
        for (JsonNode fila : data) {
            JsonNode modulo = campo(fila, "modulo");
            JsonNode nivel = campo(fila, "nivel");
            if (modulo == null || !modulo.isString() || modulo.asString().isBlank()
                    || nivel == null || !nivel.isString() || nivel.asString().isBlank()) {
                continue;
            }
            String n = nivel.asString().toUpperCase(java.util.Locale.ROOT);
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
