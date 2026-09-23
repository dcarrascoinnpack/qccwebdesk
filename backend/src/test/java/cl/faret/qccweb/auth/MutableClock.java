package cl.faret.qccweb.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Reloj controlable para probar expiraciones y backoff sin esperar tiempo real. */
public final class MutableClock extends Clock {

    private volatile Instant ahora;

    public MutableClock(Instant inicio) {
        this.ahora = inicio;
    }

    public void fijar(Instant instante) {
        this.ahora = instante;
    }

    public void avanzar(Duration duracion) {
        this.ahora = ahora.plus(duracion);
    }

    @Override
    public Instant instant() {
        return ahora;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
