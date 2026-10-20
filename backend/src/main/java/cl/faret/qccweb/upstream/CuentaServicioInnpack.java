package cl.faret.qccweb.upstream;

import cl.faret.qccweb.auth.InnpackAuthClient;
import cl.faret.qccweb.auth.SessionUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sesión INNPACK de la cuenta de servicio de bajo privilegio (ver {@link InnpackServicioProperties}), para las acciones de una
 * sesión FARET que la API INNPACK atiende solo con JWT (faretLab.*, Despachos y Formularios FARET). Hace login perezoso contra
 * api/auth/login, guarda el token SOLO en memoria y lo renueva antes de que venza. Se usa únicamente para el upstream: la
 * identidad, el rol y la auditoría siguen siendo las del usuario FARET de la petición.
 *
 * Salvaguardas: nunca se usa una cuenta con rol admin / admin_ti (un error de configuración no debe dar al gateway privilegios de
 * administración sobre INNPACK) ni una con cambio de clave pendiente; tras un fallo de login no se reintenta durante
 * {@link #ESPERA_TRAS_FALLO} (no se martilla la API ni se bloquea la cuenta); la clave y el token nunca se loguean.
 */
public class CuentaServicioInnpack {

    public static final String EMPRESA = "INNPACK";
    static final Duration ESPERA_TRAS_FALLO = Duration.ofSeconds(30);
    private static final Duration MARGEN_POR_DEFECTO = Duration.ofSeconds(60);
    private static final Duration VIGENCIA_SIN_EXP = Duration.ofMinutes(30);
    private static final Set<String> ROLES_PROHIBIDOS = Set.of("admin", "admin_ti");
    private static final Logger LOG = LoggerFactory.getLogger(CuentaServicioInnpack.class);

    private final BiFunction<String, String, InnpackAuthClient.Resultado> login;
    private final InnpackServicioProperties propiedades;
    private final Clock reloj;
    private SessionUser actual;
    private Instant ultimoFallo;

    public CuentaServicioInnpack(
            BiFunction<String, String, InnpackAuthClient.Resultado> login, InnpackServicioProperties propiedades, Clock reloj) {
        this.login = login;
        this.propiedades = propiedades;
        this.reloj = reloj;
    }

    public boolean configurada() {
        return propiedades.configurada();
    }

    /** La sesión de servicio vigente (la renueva si hace falta); vacío si no está configurada, el login falló o la cuenta no es apta. */
    public synchronized Optional<SessionUser> sesion() {
        if (!propiedades.configurada()) {
            return Optional.empty();
        }
        Instant ahora = reloj.instant();
        Duration margen = propiedades.margenRenovacion() == null ? MARGEN_POR_DEFECTO : propiedades.margenRenovacion();
        if (actual != null && ahora.isBefore(actual.expiresAt().minus(margen))) {
            return Optional.of(actual);
        }
        actual = null;
        if (ultimoFallo != null && ahora.isBefore(ultimoFallo.plus(ESPERA_TRAS_FALLO))) {
            return Optional.empty();
        }
        InnpackAuthClient.Resultado resultado = login.apply(propiedades.usuario().trim(), propiedades.password());
        if (resultado instanceof InnpackAuthClient.Autenticado a) {
            String rol = a.rol() == null ? "" : a.rol().toLowerCase(Locale.ROOT);
            if (ROLES_PROHIBIDOS.contains(rol)) {
                return fallo(ahora, "la cuenta de servicio tiene rol de administración; se rechaza (use una cuenta de bajo privilegio)");
            }
            if (a.politica() != null && a.politica().debeCambiar()) {
                return fallo(ahora, "la cuenta de servicio tiene un cambio de clave pendiente");
            }
            Instant expira = a.tokenExpira() != null ? a.tokenExpira() : ahora.plus(VIGENCIA_SIN_EXP);
            actual = new SessionUser(a.userId(), a.codigoUsuario(), a.nombreCompleto(), a.rol(), EMPRESA, a.token(), ahora, expira);
            ultimoFallo = null;
            return Optional.of(actual);
        }
        return fallo(ahora, resultado instanceof InnpackAuthClient.Rechazado ? "credenciales rechazadas" : "API INNPACK no disponible");
    }

    /** La API respondió 401 con el token de servicio: se descarta para que el próximo uso haga login de nuevo. */
    public synchronized void invalidar() {
        actual = null;
    }

    private Optional<SessionUser> fallo(Instant ahora, String motivo) {
        ultimoFallo = ahora;
        LOG.warn("Cuenta de servicio INNPACK no disponible: {}", motivo);
        return Optional.empty();
    }
}
