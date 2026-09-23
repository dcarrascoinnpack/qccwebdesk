package cl.faret.qccweb.upstream;

/** La API respondió 401 para el token del usuario: la sesión web de ESE usuario debe invalidarse. */
public class UpstreamNoAutorizadoException extends RuntimeException {

    public UpstreamNoAutorizadoException() {
        super("La API upstream rechazó el token de la sesión", null, false, false);
    }
}
