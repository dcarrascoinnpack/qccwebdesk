package cl.faret.qccweb.auth;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Autenticación web INNPACK (Fase 1b). Traduce el contrato de Photino:
 * auth.login → POST /login · auth.me → GET /session · auth.logout → POST /logout.
 * Las respuestas mantienen la forma normalizada de Photino { ok, success, data, error }.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String EMPRESA_INNPACK = "INNPACK";
    static final String MENSAJE_CREDENCIALES = "Usuario o contraseña incorrectos.";
    static final String MENSAJE_REQUERIDOS = "Completa todos los campos";
    static final String MENSAJE_BLOQUEO = "Demasiados intentos. Espera unos minutos e inténtalo nuevamente.";
    static final String MENSAJE_NO_DISPONIBLE = "Servicio de autenticación no disponible. Intenta más tarde.";
    private static final int MAX_LARGO_USUARIO = 100;
    private static final int MAX_LARGO_PASSWORD = 256;

    /**
     * Cuerpo de login. Acepta también los nombres PascalCase que envía Photino. Cualquier otro campo
     * (rol, empresa, userId...) se ignora: la identidad la decide la API, no el navegador.
     */
    public record LoginRequest(
            @JsonAlias("CodigoUsuario") String codigoUsuario,
            @JsonAlias("Password") String password) {
        @Override
        public String toString() {
            return "LoginRequest[codigoUsuario=" + codigoUsuario + ", password=***]";
        }
    }

    private final InnpackAuthClient authClient;
    private final LoginRateLimiter rateLimiter;
    private final ClientIpResolver ipResolver;
    private final AuditLogger audit;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final AuthProperties properties;
    private final Clock clock;

    public AuthController(
            InnpackAuthClient authClient,
            LoginRateLimiter rateLimiter,
            ClientIpResolver ipResolver,
            AuditLogger audit,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            AuthProperties properties,
            Clock clock) {
        this.authClient = authClient;
        this.rateLimiter = rateLimiter;
        this.ipResolver = ipResolver;
        this.audit = audit;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody(required = false) LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        long inicio = System.nanoTime();
        String ip = ipResolver.resolver(request);
        String usuario = body == null || body.codigoUsuario() == null ? "" : body.codigoUsuario().trim();
        String password = body == null ? null : body.password();

        if (usuario.isEmpty() || password == null || password.isEmpty()
                || usuario.length() > MAX_LARGO_USUARIO || password.length() > MAX_LARGO_PASSWORD) {
            return error(HttpStatus.BAD_REQUEST, MENSAJE_REQUERIDOS);
        }

        LoginRateLimiter.Decision decision = rateLimiter.verificarYRegistrar(ip, usuario);
        if (!decision.permitido()) {
            audit.loginBloqueado(usuario, ip, decision.motivo());
            long segundos = Math.max(1, decision.reintentarEn().toSeconds());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(segundos))
                    .body(respuesta(false, null, MENSAJE_BLOQUEO));
        }

        InnpackAuthClient.Resultado resultado = authClient.login(usuario, password);
        password = null; // la contraseña no se usa más allá de esta llamada

        ResponseEntity<Map<String, Object>> salida;
        if (resultado instanceof InnpackAuthClient.Autenticado ok) {
            rateLimiter.registrarExito(ip, usuario);
            HttpSession sesion = crearSesion(ok, request, response);
            audit.loginOk(ok.codigoUsuario(), ip, EMPRESA_INNPACK, sesion.getId());
            salida = ResponseEntity.ok(respuesta(true, datosLogin(ok), null));
        } else if (resultado instanceof InnpackAuthClient.Rechazado rechazado) {
            rateLimiter.registrarFallo(ip, usuario);
            audit.loginFallido(usuario, ip, rechazado.motivoInterno());
            salida = error(HttpStatus.UNAUTHORIZED, MENSAJE_CREDENCIALES);
        } else {
            InnpackAuthClient.NoDisponible caido = (InnpackAuthClient.NoDisponible) resultado;
            audit.loginErrorUpstream(usuario, ip, caido.detalleInterno());
            return error(HttpStatus.SERVICE_UNAVAILABLE, MENSAJE_NO_DISPONIBLE);
        }
        esperarDuracionMinima(inicio);
        return salida;
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession sesion = request.getSession(false);
        if (sesion != null) {
            SessionUser usuario = usuarioDe(SecurityContextHolder.getContext().getAuthentication());
            audit.logout(usuario != null ? usuario.codigoUsuario() : null, ipResolver.resolver(request), sesion.getId());
            sesion.invalidate();
        }
        SecurityContextHolder.clearContext();
        csrfTokenRepository.saveToken(null, request, response);
        return ResponseEntity.ok(respuesta(true, Map.of("message", "Sesión cerrada correctamente"), null));
    }

    /** Equivalente web de auth.me. Nunca devuelve el token upstream. */
    @GetMapping("/session")
    public ResponseEntity<Map<String, Object>> session() {
        SessionUser usuario = usuarioDe(SecurityContextHolder.getContext().getAuthentication());
        if (usuario == null) {
            return ResponseEntity.ok(respuesta(false, null, "No hay sesión activa"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("Id", usuario.userId());
        data.put("CodigoUsuario", usuario.codigoUsuario());
        data.put("NombreCompleto", usuario.nombreCompleto());
        data.put("Rol", usuario.rol());
        data.put("Activo", true);
        data.put("Empresa", usuario.empresa());
        return ResponseEntity.ok(respuesta(true, data, null));
    }

    /**
     * Sesión nueva en cada login: se invalida cualquier sesión previa del navegador (protección
     * contra session fixation) y se rota el token CSRF.
     */
    private HttpSession crearSesion(InnpackAuthClient.Autenticado ok, HttpServletRequest request, HttpServletResponse response) {
        HttpSession anterior = request.getSession(false);
        if (anterior != null) {
            anterior.invalidate();
        }
        HttpSession sesion = request.getSession(true);
        sesion.setMaxInactiveInterval((int) properties.sessionIdleTimeout().toSeconds());

        Instant ahora = clock.instant();
        Instant tope = ahora.plus(properties.sessionMaxDuration());
        Instant expira = ok.tokenExpira() != null && ok.tokenExpira().isBefore(tope) ? ok.tokenExpira() : tope;
        SessionUser usuario = new SessionUser(
                ok.userId(), ok.codigoUsuario(), ok.nombreCompleto(), ok.rol(), EMPRESA_INNPACK, ok.token(), ahora, expira);

        List<SimpleGrantedAuthority> permisos = List.of(
                new SimpleGrantedAuthority("ROLE_" + ok.rol().toUpperCase(Locale.ROOT)),
                new SimpleGrantedAuthority("EMPRESA_" + EMPRESA_INNPACK));
        SecurityContext contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(usuario, null, permisos));
        SecurityContextHolder.setContext(contexto);
        securityContextRepository.saveContext(contexto, request, response);

        csrfTokenRepository.saveToken(null, request, response);
        return sesion;
    }

    private Map<String, Object> datosLogin(InnpackAuthClient.Autenticado ok) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("Success", true);
        data.put("Message", "Login correcto");
        data.put("UserId", ok.userId());
        data.put("CodigoUsuario", ok.codigoUsuario());
        data.put("NombreCompleto", ok.nombreCompleto());
        data.put("Rol", ok.rol());
        return data;
    }

    /** Iguala el tiempo de respuesta de éxito/fallo para no revelar si el usuario existe. */
    private void esperarDuracionMinima(long inicioNanos) {
        Duration restante = properties.loginMinDuration().minusNanos(System.nanoTime() - inicioNanos);
        if (!restante.isNegative() && !restante.isZero()) {
            try {
                Thread.sleep(restante.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static SessionUser usuarioDe(Authentication auth) {
        return auth != null && auth.getPrincipal() instanceof SessionUser u ? u : null;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String mensaje) {
        return ResponseEntity.status(status).body(respuesta(false, null, mensaje));
    }

    static Map<String, Object> respuesta(boolean ok, Object data, String error) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", ok);
        r.put("success", ok);
        r.put("data", data);
        r.put("error", error);
        return r;
    }
}
