package cl.faret.qccweb.bridge;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Límite de ESCRITURAS por usuario de sesión (ventana deslizante, en memoria; una sola instancia del
 * gateway, sin dependencias nuevas). Frena ráfagas (doble clic repetido, scripts desde DevTools) antes
 * de tocar la API INNPACK. Las lecturas no pasan por aquí.
 */
public final class EscrituraRateLimiter {

    private static final int MAX_ENTRADAS = 20_000;

    private final int maxPorVentana;
    private final Duration ventana;
    private final Clock clock;
    private final Map<Integer, Deque<Instant>> porUsuario = new ConcurrentHashMap<>();

    public EscrituraRateLimiter(int maxPorVentana, Duration ventana, Clock clock) {
        this.maxPorVentana = maxPorVentana;
        this.ventana = ventana;
        this.clock = clock;
    }

    /** true (y registra la escritura) si el usuario no superó el máximo en la ventana. */
    public boolean permitir(int userId) {
        Instant ahora = clock.instant();
        if (porUsuario.size() > MAX_ENTRADAS) {
            porUsuario.clear();
        }
        Deque<Instant> registro = porUsuario.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (registro) {
            Instant desde = ahora.minus(ventana);
            while (!registro.isEmpty() && !registro.peekFirst().isAfter(desde)) {
                registro.pollFirst();
            }
            if (registro.size() >= maxPorVentana) {
                return false;
            }
            registro.addLast(ahora);
            return true;
        }
    }
}
