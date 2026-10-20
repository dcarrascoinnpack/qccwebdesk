package cl.faret.qccweb.bridge.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.faret.qccweb.auth.InnpackAuthClient;
import cl.faret.qccweb.auth.InnpackAuthClient.Autenticado;
import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.CuentaServicioInnpack;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.InnpackApiClient.Respuesta;
import cl.faret.qccweb.upstream.InnpackServicioProperties;
import cl.faret.qccweb.upstream.UpstreamNoAutorizadoException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** faretLab.list / detalle / especificacion.list: lecturas con la cuenta de servicio de bajo privilegio (no el usuario FARET). */
class FaretLaboratorioBridgeHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final InnpackApiClient api = mock(InnpackApiClient.class);
    private final AtomicInteger logins = new AtomicInteger();
    private final SessionUser faret = new SessionUser(
            5, "ana", "Ana Calidad", "calidad", "FARET", "TOKEN-FARET", Instant.now(), Instant.now().plusSeconds(3600));

    private FaretLaboratorioBridgeHandler handler(String usuario, String password) {
        InnpackAuthClient.Resultado login = new Autenticado(
                "TOKEN-SERVICIO", 77, "SERVICIO_FARET_HIBRIDO", "Servicio", "operador", Instant.now().plusSeconds(3600));
        CuentaServicioInnpack cuenta = new CuentaServicioInnpack((u, p) -> {
            logins.incrementAndGet();
            return login;
        }, new InnpackServicioProperties(usuario, password, Duration.ofSeconds(60)), Clock.systemUTC());
        return new FaretLaboratorioBridgeHandler(api, cuenta, mapper);
    }

    private ObjectNode payload(String json) throws Exception {
        return (ObjectNode) mapper.readTree(json);
    }

    private static Respuesta ok(String data) {
        return new Respuesta(200, "{\"success\":true,\"message\":null,\"data\":" + data + ",\"errors\":null}");
    }

    @Test
    void listUsaElTokenDeLaCuentaDeServicioYSoloLosTresFiltrosNoVacios() throws Exception {
        when(api.get(any(), anyString())).thenReturn(ok("[{\"id\":1}]"));
        BridgeResult r = handler("svc", "clave").list(
                payload("{\"data\":{\"estado\":\"Finalizado\",\"tipoMuestra\":\"  \",\"np\":\"A B&1\",\"cliente\":\"x\"}}"), faret);
        assertThat(r.ok()).isTrue();
        assertThat(r.data().toString()).isEqualTo("[{\"id\":1}]");
        ArgumentCaptor<SessionUser> quien = ArgumentCaptor.forClass(SessionUser.class);
        ArgumentCaptor<String> ruta = ArgumentCaptor.forClass(String.class);
        verify(api).get(quien.capture(), ruta.capture());
        assertThat(quien.getValue().upstreamToken()).isEqualTo("TOKEN-SERVICIO"); // nunca el token FARET
        assertThat(ruta.getValue()).startsWith("/api/faret-laboratorio?estado=Finalizado&np=A").doesNotContain("cliente")
                .doesNotContain("tipoMuestra").doesNotContain("A B&1");
    }

    @Test
    void listSinFiltrosYFiltrosInvalidos() throws Exception {
        when(api.get(any(), anyString())).thenReturn(ok("[]"));
        FaretLaboratorioBridgeHandler h = handler("svc", "clave");
        assertThat(h.list(payload("{}"), faret).ok()).isTrue();
        verify(api).get(any(), eq("/api/faret-laboratorio"));
        for (String malo : new String[] {"true", "{\"a\":1}", "[1]"}) {
            BridgeResult r = h.list(payload("{\"data\":{\"estado\":" + malo + "}}"), faret);
            assertThat(r.error()).isEqualTo("Parámetro de filtro inválido.");
        }
    }

    @Test
    void detalleYEspecificacionesUsanSuRutaYElIdInvalidoVaComoCero() throws Exception {
        when(api.get(any(), anyString())).thenReturn(ok("{}"));
        FaretLaboratorioBridgeHandler h = handler("svc", "clave");
        h.detalle(payload("{\"data\":{\"id\":\"12\"}}"), faret);
        h.detalle(payload("{\"data\":{\"id\":\"abc\"}}"), faret);
        h.detalle(payload("{}"), faret);
        h.especificacionList(payload("{\"data\":{}}"), faret);
        verify(api).get(any(), eq("/api/faret-laboratorio/12"));
        verify(api, org.mockito.Mockito.times(2)).get(any(), eq("/api/faret-laboratorio/0"));
        verify(api).get(any(), eq("/api/faret-laboratorio/especificaciones"));
    }

    @Test
    void sinCuentaDeServicioNoLlamaALaApi() throws Exception {
        BridgeResult r = handler("", "").list(payload("{}"), faret);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).isEqualTo(FaretLaboratorioBridgeHandler.MENSAJE_NO_DISPONIBLE);
        verify(api, never()).get(any(), anyString());
        assertThat(logins.get()).isZero();
    }

    @Test
    void un401DelTokenDeServicioLoInvalidaSinCerrarLaSesionDelUsuario() throws Exception {
        when(api.get(any(), anyString())).thenThrow(new UpstreamNoAutorizadoException()).thenReturn(ok("[]"));
        FaretLaboratorioBridgeHandler h = handler("svc", "clave");
        BridgeResult primera = h.list(payload("{}"), faret); // no propaga la excepción: el bridge cerraría la sesión web
        assertThat(primera.error()).isEqualTo(FaretLaboratorioBridgeHandler.MENSAJE_NO_DISPONIBLE);
        assertThat(h.list(payload("{}"), faret).ok()).isTrue();
        assertThat(logins.get()).isEqualTo(2); // el segundo uso volvió a hacer login
    }

    @Test
    void unErrorDeLaApiSeReenviaComoEnMuestraLab() throws Exception {
        when(api.get(any(), anyString()))
                .thenReturn(new Respuesta(404, "{\"success\":false,\"message\":\"Muestra no encontrada\",\"data\":null}"));
        assertThat(handler("svc", "clave").detalle(payload("{\"data\":{\"id\":9}}"), faret).error()).isEqualTo("Muestra no encontrada");
    }
}
