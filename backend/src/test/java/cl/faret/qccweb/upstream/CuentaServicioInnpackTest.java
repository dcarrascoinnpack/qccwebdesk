package cl.faret.qccweb.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import cl.faret.qccweb.auth.InnpackAuthClient;
import cl.faret.qccweb.auth.InnpackAuthClient.Autenticado;
import cl.faret.qccweb.auth.InnpackAuthClient.Politica;
import cl.faret.qccweb.auth.InnpackAuthClient.Resultado;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/** Cuenta de servicio INNPACK de bajo privilegio: login perezoso, renovación, salvaguardas y secretos. */
class CuentaServicioInnpackTest {

    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

    /** Reloj manual y login falso que cuenta llamadas. */
    private static final class Entorno {
        Instant ahora = T0;
        final List<String> llamadas = new ArrayList<>();
        Resultado respuesta;
        final Clock reloj = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return ahora;
            }
        };
        final BiFunction<String, String, Resultado> login = (u, p) -> {
            llamadas.add(u);
            return respuesta;
        };

        CuentaServicioInnpack cuenta(String usuario, String password) {
            return new CuentaServicioInnpack(login, new InnpackServicioProperties(usuario, password, Duration.ofSeconds(60)), reloj);
        }
    }

    private static Autenticado ok(String rol, Instant expira) {
        return new Autenticado("TOKEN-SECRETO", 77, "SERVICIO_FARET_HIBRIDO", "Servicio Faret", rol, expira);
    }

    @Test
    void sinCredencialesNoHaceLoginNiDaSesion() {
        Entorno e = new Entorno();
        assertThat(e.cuenta("", "x").sesion()).isEmpty();
        assertThat(e.cuenta("svc", "").sesion()).isEmpty();
        assertThat(e.cuenta(null, null).sesion()).isEmpty();
        assertThat(e.llamadas).isEmpty();
    }

    @Test
    void haceLoginUnaVezYReutilizaElTokenHastaElMargenDeRenovacion() {
        Entorno e = new Entorno();
        e.respuesta = ok("operador", T0.plusSeconds(600));
        CuentaServicioInnpack cuenta = e.cuenta(" SERVICIO_FARET_HIBRIDO ", "clave");
        var s = cuenta.sesion().orElseThrow();
        assertThat(s.empresa()).isEqualTo("INNPACK");
        assertThat(s.rol()).isEqualTo("operador");
        assertThat(s.upstreamToken()).isEqualTo("TOKEN-SECRETO");
        assertThat(e.llamadas).containsExactly("SERVICIO_FARET_HIBRIDO"); // usuario recortado
        e.ahora = T0.plusSeconds(500); // aún faltan > 60 s
        assertThat(cuenta.sesion()).isPresent();
        assertThat(e.llamadas).hasSize(1);
        e.ahora = T0.plusSeconds(545); // faltan < 60 s: renueva
        e.respuesta = ok("operador", T0.plusSeconds(1200));
        assertThat(cuenta.sesion()).isPresent();
        assertThat(e.llamadas).hasSize(2);
    }

    @Test
    void invalidarFuerzaUnLoginNuevo() {
        Entorno e = new Entorno();
        e.respuesta = ok("operador", T0.plusSeconds(600));
        CuentaServicioInnpack cuenta = e.cuenta("svc", "clave");
        cuenta.sesion();
        cuenta.invalidar();
        cuenta.sesion();
        assertThat(e.llamadas).hasSize(2);
    }

    @Test
    void rechazaUnaCuentaConRolDeAdministracionOConCambioDeClavePendiente() {
        for (String rol : List.of("admin_ti", "ADMIN", "Admin_TI")) {
            Entorno e = new Entorno();
            e.respuesta = ok(rol, T0.plusSeconds(600));
            assertThat(e.cuenta("svc", "clave").sesion()).as(rol).isEmpty();
        }
        Entorno e = new Entorno();
        e.respuesta = new Autenticado("T", 1, "svc", "S", "operador", T0.plusSeconds(600), new Politica(true, "TEMPORAL", null, null));
        assertThat(e.cuenta("svc", "clave").sesion()).isEmpty();
    }

    @Test
    void trasUnFalloNoMartillaLaApiHastaPasadaLaEspera() {
        Entorno e = new Entorno();
        e.respuesta = new InnpackAuthClient.Rechazado("HTTP 401");
        CuentaServicioInnpack cuenta = e.cuenta("svc", "mala");
        assertThat(cuenta.sesion()).isEmpty();
        e.ahora = T0.plusSeconds(10);
        assertThat(cuenta.sesion()).isEmpty();
        assertThat(e.llamadas).hasSize(1); // dentro de la espera no reintenta
        e.ahora = T0.plus(CuentaServicioInnpack.ESPERA_TRAS_FALLO).plusSeconds(1);
        e.respuesta = ok("operador", e.ahora.plusSeconds(600));
        assertThat(cuenta.sesion()).isPresent();
        assertThat(e.llamadas).hasSize(2);
        e.respuesta = new InnpackAuthClient.NoDisponible("timeout");
        cuenta.invalidar();
        assertThat(cuenta.sesion()).isEmpty();
    }

    @Test
    void laClaveYElTokenNuncaSalenEnToStringNiEnLogs() {
        InnpackServicioProperties p = new InnpackServicioProperties("svc", "Secreta#123", null);
        assertThat(p.toString()).doesNotContain("Secreta#123").contains("***");
        Entorno e = new Entorno();
        e.respuesta = ok("operador", T0.plusSeconds(600));
        assertThat(e.cuenta("svc", "Secreta#123").sesion().orElseThrow().toString()).doesNotContain("TOKEN-SECRETO");
    }
}
