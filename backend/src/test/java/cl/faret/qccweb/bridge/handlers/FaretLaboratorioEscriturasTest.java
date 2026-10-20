package cl.faret.qccweb.bridge.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import cl.faret.qccweb.auth.AuthProperties;
import cl.faret.qccweb.auth.InnpackAuthClient.Autenticado;
import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.upstream.CuentaServicioInnpack;
import cl.faret.qccweb.upstream.InnpackApiClientServicio;
import cl.faret.qccweb.upstream.InnpackServicioProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Escrituras faretLab.*: lógica de muestraLab.*, contra api/faret-laboratorio, con la cuenta de servicio y la identidad de la sesión FARET. */
class FaretLaboratorioEscriturasTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> recibidos = new ArrayList<>();
    private int estado = 200;
    private HttpServer servidor;
    private final SessionUser ana = new SessionUser(
            5, "ana", "Ana Calidad", "calidad", "FARET", "TOKEN-FARET", Instant.now(), Instant.now().plusSeconds(3600));

    @BeforeEach
    void iniciar() throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", ex -> {
            String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            recibidos.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " " + ex.getRequestHeaders().getFirst("Authorization")
                    + " " + cuerpo);
            byte[] r = "{\"success\":true,\"message\":null,\"data\":{\"id\":42},\"errors\":null}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(estado, r.length);
            ex.getResponseBody().write(r);
            ex.close();
        });
        servidor.start();
    }

    @AfterEach
    void cerrar() {
        servidor.stop(0);
    }

    private FaretLaboratorioBridgeHandler handler(String usuarioServicio) {
        AuthProperties auth = new AuthProperties("http://127.0.0.1:" + servidor.getAddress().getPort(), null,
                Duration.ofSeconds(2), Duration.ofSeconds(5), null, null, null, null, false, null);
        CuentaServicioInnpack cuenta = new CuentaServicioInnpack((u, p) -> new Autenticado(
                "TOKEN-SERVICIO", 77, "SERVICIO_FARET_HIBRIDO", "Servicio", "operador", Instant.now().plusSeconds(3600)),
                new InnpackServicioProperties(usuarioServicio, "clave", Duration.ofSeconds(60)), Clock.systemUTC());
        var apiFaretLab = new InnpackApiClientServicio(auth, cuenta, "/api/muestra-laboratorio", "/api/faret-laboratorio");
        var escrituras = new MuestraLaboratorioBridgeHandler(apiFaretLab, null, null, null, mapper);
        return new FaretLaboratorioBridgeHandler(null, cuenta, mapper, escrituras);
    }

    @Test
    void crearVaAFaretLaboratorioConElTokenDeServicioYLaIdentidadDeLaSesionFaret() throws Exception {
        ObjectNode p = (ObjectNode) mapper.readTree(
                "{\"data\":{\"origen\":\"Pegado\",\"tipoMuestra\":\"CajaPegada\",\"np\":\"123\",\"cliente\":\"X\",\"usuarioId\":999}}");
        BridgeResult r = handler("svc").crear(p, ana);
        assertThat(r.error()).isEqualTo("Campo no permitido: usuarioId"); // la identidad nunca viene del navegador
        p = (ObjectNode) mapper.readTree("{\"data\":{\"origen\":\"Pegado\",\"tipoMuestra\":\"CajaPegada\",\"np\":\"123\",\"cliente\":\"X\"}}");
        r = handler("svc").crear(p, ana);
        assertThat(r.ok()).isTrue();
        assertThat(recibidos).hasSize(1);
        String s = recibidos.get(0);
        assertThat(s).startsWith("POST /api/faret-laboratorio Bearer TOKEN-SERVICIO ");
        assertThat(s).doesNotContain("TOKEN-FARET").contains("\"usuarioId\":5").contains("\"usuarioNombre\":\"Ana Calidad\"")
                .contains("\"np\":\"123\"");
        assertThat(FaretLaboratorioBridgeHandler.recurso("muestraLab:42:crear")).isEqualTo("faretLab:42:crear");
    }

    @Test
    void losTreceEnsayosVanASuRutaDeFaretLaboratorioConElAnalistaDeLaSesionFaret() throws Exception {
        var lab = handler("svc").escrituras();
        ObjectNode p = (ObjectNode) mapper.readTree("{\"data\":{\"muestraId\":7,\"metodo\":\"M\",\"observacion\":\"o\"}}");
        List<cl.faret.qccweb.bridge.BridgeAction> acciones = List.of(lab::phGuardar, lab::humedadGuardar, lab::gramajeGuardar,
                lab::cobbGuardar, lab::espesorGuardar, lab::rctGuardar, lab::fctGuardar, lab::ectGuardar, lab::bctMedidoGuardar,
                lab::bctTeoricoGuardar, lab::viscosidadGuardar, lab::solidosGuardar, lab::lugolGuardar);
        for (var accion : acciones) {
            assertThat(accion.ejecutar(p, ana).ok()).isTrue();
        }
        List<String> rutas = List.of("ph", "humedad", "gramaje", "cobb", "espesor", "rct", "fct", "ect", "bct-medido", "bct-teorico",
                "viscosidad", "solidos", "lugol");
        assertThat(recibidos).hasSize(13);
        for (int i = 0; i < rutas.size(); i++) {
            String s = recibidos.get(i);
            assertThat(s).as(rutas.get(i)).startsWith("POST /api/faret-laboratorio/" + rutas.get(i) + " Bearer TOKEN-SERVICIO ")
                    .contains("\"muestraId\":7").contains("\"analistaUsuarioId\":5").contains("\"analistaNombre\":\"Ana Calidad\"")
                    .doesNotContain("TOKEN-FARET");
        }
    }

    @Test
    void especificacionesAnulacionYNcVanASuRutaConElTokenDeServicio() throws Exception {
        var lab = handler("svc").escrituras();
        assertThat(lab.especificacionGuardar((ObjectNode) mapper.readTree(
                "{\"data\":{\"tipoMuestra\":\"Papel\",\"tipoEnsayo\":\"HUMEDAD\",\"limiteMin\":1,\"unidad\":\"%\"}}"), ana).ok()).isTrue();
        assertThat(lab.especificacionActivar((ObjectNode) mapper.readTree("{\"data\":{\"id\":9,\"activo\":true}}"), ana).ok()).isTrue();
        assertThat(lab.ensayoAnular((ObjectNode) mapper.readTree("{\"data\":{\"ensayoId\":4,\"motivo\":\"error\"}}"), ana).ok()).isTrue();
        assertThat(lab.ncCrear((ObjectNode) mapper.readTree("{\"data\":{\"muestraId\":7}}"), ana).ok()).isTrue();
        assertThat(recibidos).hasSize(4);
        assertThat(recibidos.get(0)).startsWith("POST /api/faret-laboratorio/especificaciones Bearer TOKEN-SERVICIO ");
        assertThat(recibidos.get(1)).startsWith("PATCH /api/faret-laboratorio/especificaciones/9/activo Bearer TOKEN-SERVICIO ")
                .endsWith("{\"activo\":true}");
        assertThat(recibidos.get(2)).startsWith("POST /api/faret-laboratorio/ensayos/4/anular Bearer TOKEN-SERVICIO ")
                .endsWith("{\"motivo\":\"error\"}");
        assertThat(recibidos.get(3)).startsWith("POST /api/faret-laboratorio/7/nc Bearer TOKEN-SERVICIO ")
                .endsWith("{\"usuarioNombre\":\"Ana Calidad\"}");
        assertThat(String.join("", recibidos)).doesNotContain("TOKEN-FARET");
    }

    @Test
    void sinCuentaDeServicioNoLlamaALaApiYDaErrorSinLanzar() throws Exception {
        ObjectNode p = (ObjectNode) mapper.readTree("{\"data\":{\"origen\":\"Pegado\",\"tipoMuestra\":\"CajaPegada\"}}");
        BridgeResult r = handler("").crear(p, ana); // no lanza UpstreamNoAutorizadoException: la sesión web no se cierra
        assertThat(r.ok()).isFalse();
        assertThat(recibidos).isEmpty();
    }

    @Test
    void un401DeLaApiNoLanzaYInvalidaElTokenDeServicio() throws Exception {
        estado = 401;
        ObjectNode p = (ObjectNode) mapper.readTree("{\"data\":{\"origen\":\"Pegado\",\"tipoMuestra\":\"CajaPegada\"}}");
        BridgeResult r = handler("svc").crear(p, ana);
        assertThat(r.ok()).isFalse();
        assertThat(recibidos).hasSize(1);
    }
}
