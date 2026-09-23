package cl.faret.qccweb.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting del login, en memoria (una sola instancia del gateway; sin dependencias nuevas).
 *
 * - Por IP: máximo N intentos (de cualquier usuario) por ventana deslizante.
 * - Por usuario+IP: tras K fallos consecutivos, bloqueo exponencial (base, 2x base, 4x base...)
 *   con tope. Un login correcto reinicia el contador.
 *
 * Un intento bloqueado NO se reenvía a la API INNPACK.
 */
public final class LoginRateLimiter {

    /** Resultado de la verificación previa al intento. */
    public record Decision(boolean permitido, String motivo, Duration reintentarEn) {
        static final Decision OK = new Decision(true, null, Duration.ZERO);
    }

    private static final int MAX_ENTRADAS = 20_000;

    private final AuthProperties.RateLimit config;
    private final Clock clock;
    private final Map<String, Deque<Instant>> intentosPorIp = new ConcurrentHashMap<>();
    private final Map<String, EstadoUsuario> estadoPorUsuarioIp = new ConcurrentHashMap<>();

    private static final class EstadoUsuario {
        int fallosConsecutivos;
        Instant bloqueadoHasta = Instant.EPOCH;
        Instant ultimoFallo = Instant.EPOCH;
    }

    public LoginRateLimiter(AuthProperties.RateLimit config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    /** Verifica y, si está permitido, registra el intento en la ventana de la IP. */
    public Decision verificarYRegistrar(String ip, String usuario) {
        Instant ahora = clock.instant();
        limpiarSiEsNecesario(ahora);

        EstadoUsuario estado = estadoPorUsuarioIp.get(clave(ip, usuario));
        if (estado != null) {
            synchronized (estado) {
                if (ahora.isBefore(estado.bloqueadoHasta)) {
                    return new Decision(false, "USUARIO_IP_BLOQUEADO", Duration.between(ahora, estado.bloqueadoHasta));
                }
            }
        }

        Deque<Instant> ventana = intentosPorIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (ventana) {
            Instant desde = ahora.minus(config.ventanaIp());
            while (!ventana.isEmpty() && !ventana.peekFirst().isAfter(desde)) {
                ventana.pollFirst();
            }
            if (ventana.size() >= config.maxIntentosPorIp()) {
                Duration espera = Duration.between(ahora, ventana.peekFirst().plus(config.ventanaIp()));
                return new Decision(false, "LIMITE_IP", espera);
            }
            ventana.addLast(ahora);
        }
        return Decision.OK;
    }

    public void registrarFallo(String ip, String usuario) {
        Instant ahora = clock.instant();
        EstadoUsuario estado = estadoPorUsuarioIp.computeIfAbsent(clave(ip, usuario), k -> new EstadoUsuario());
        synchronized (estado) {
            estado.fallosConsecutivos++;
            estado.ultimoFallo = ahora;
            int exceso = estado.fallosConsecutivos - config.fallosAntesDeBloqueo();
            if (exceso >= 0) {
                Duration bloqueo = config.bloqueoBase().multipliedBy(1L << Math.min(exceso, 20));
                if (bloqueo.compareTo(config.bloqueoMaximo()) > 0) {
                    bloqueo = config.bloqueoMaximo();
                }
                estado.bloqueadoHasta = ahora.plus(bloqueo);
            }
        }
    }

    public void registrarExito(String ip, String usuario) {
        estadoPorUsuarioIp.remove(clave(ip, usuario));
    }

    private static String clave(String ip, String usuario) {
        return ip + "|" + (usuario == null ? "" : usuario.trim().toLowerCase(Locale.ROOT));
    }

    /** Evita crecimiento sin límite de memoria (p. ej. ataque con miles de usuarios/IPs distintos). */
    private void limpiarSiEsNecesario(Instant ahora) {
        if (intentosPorIp.size() + estadoPorUsuarioIp.size() < MAX_ENTRADAS) {
            return;
        }
        Instant desdeIp = ahora.minus(config.ventanaIp());
        intentosPorIp.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty() || !e.getValue().peekLast().isAfter(desdeIp);
            }
        });
        Instant desdeUsuario = ahora.minus(config.bloqueoMaximo());
        estadoPorUsuarioIp.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return ahora.isAfter(e.getValue().bloqueadoHasta) && e.getValue().ultimoFallo.isBefore(desdeUsuario);
            }
        });
    }
}
