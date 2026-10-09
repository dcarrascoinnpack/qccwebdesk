package cl.faret.qccweb.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cl.faret.qccweb.auth.FakeFaretApi;
import cl.faret.qccweb.auth.FakeFaretSinAuthApi;
import cl.faret.qccweb.auth.SessionUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fase 6b-1 — FaretApiClient: Bearer solo hacia QualityControlFaret.Api (con el token del usuario de la petición),
 * NUNCA hacia MejoraContinua/Calidad; 401 invalida la sesión solo con Bearer; fallas locales con el cuerpo {ok,error}
 * de Photino. Todo contra fakes en localhost.
 */
class FaretApiClientTest {

    private static final FakeFaretApi QC = new FakeFaretApi(Clock.systemUTC());
    private static final FakeFaretSinAuthApi MC = new FakeFaretSinAuthApi("/mejora-continua");
    private static final Duration CONEXION = Duration.ofSeconds(2);
    private static final Duration LECTURA = Duration.ofSeconds(5);

    @AfterAll
    static void cerrar() {
        QC.close();
        MC.close();
    }

    @BeforeEach
    void reiniciar() {
        QC.limpiarLecturas();
        MC.limpiar();
    }

    private static SessionUser usuario(int id) {
        // Token con la forma que valida FakeFaretApi: header.payload(sub).firma-del-usuario
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + id + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String token = "e30." + payload + "." + FakeFaretApi.firmaDeToken(id);
        return new SessionUser(id, "u" + id, "U " + id, "CALIDAD", "FARET", token, Instant.now(), Instant.now().plusSeconds(3600));
    }

    @Test
    void conBearerEnviaElTokenDelUsuarioDeLaPeticionYNingunOtro() {
        FaretApiClient cliente = new FaretApiClient(QC.baseUrl(), true, CONEXION, LECTURA);
        InnpackApiClient.Respuesta a = cliente.get(usuario(7), "/api/importaciones/pnc?cliente=ACME%20SA");
        InnpackApiClient.Respuesta b = cliente.get(usuario(9), "/api/talleres-externos/resumen");
        assertThat(a.status()).isEqualTo(200);
        assertThat(b.status()).isEqualTo(200);
        assertThat(QC.lecturasDeUsuario()).containsExactly(7, 9);
        // La ruta sale tal cual (el %20 no se re-codifica).
        assertThat(QC.lecturas()).containsExactly("GET /api/importaciones/pnc?cliente=ACME%20SA", "GET /api/talleres-externos/resumen");
    }

    @Test
    void conBearerUn401LanzaLaExcepcionDeSesionInvalidada() {
        FaretApiClient cliente = new FaretApiClient(QC.baseUrl(), true, CONEXION, LECTURA);
        SessionUser malFirmado = new SessionUser(5, "x", "X", "CALIDAD", "FARET", "e30.e30.firma-falsa", Instant.now(),
                Instant.now().plusSeconds(60));
        assertThatThrownBy(() -> cliente.get(malFirmado, "/api/importaciones/pnc")).isInstanceOf(UpstreamNoAutorizadoException.class);
    }

    @Test
    void sinBearerNuncaSeEnviaAuthorizationAunqueHayaSesionYUn401NoInvalidaNada() {
        FaretApiClient cliente = new FaretApiClient(MC.baseUrl(), false, CONEXION, LECTURA);
        MC.responder("/api/no-conformidades", 200, "[]");
        MC.responder("/api/no-conformidades/1/acciones", 401, "{\"title\":\"Unauthorized\"}");
        InnpackApiClient.Respuesta ok = cliente.get(usuario(7), "/api/no-conformidades");
        InnpackApiClient.Respuesta noAut = cliente.get(usuario(7), "/api/no-conformidades/1/acciones");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(noAut.status()).isEqualTo(401);
        assertThat(MC.authorizations()).containsExactly((String) null, (String) null);
        assertThat(MC.peticiones()).containsExactly("GET /api/no-conformidades", "GET /api/no-conformidades/1/acciones");
        // Sin sesión (null) tampoco hace falta usuario.
        assertThat(cliente.get(null, "/api/no-conformidades").status()).isEqualTo(200);
    }

    @Test
    void errorSinCuerpoSeSintetizaComoPhotinoYLaRedCaidaDevuelveStatusCero() {
        FaretApiClient cliente = new FaretApiClient(MC.baseUrl(), false, CONEXION, LECTURA);
        MC.responder("/api/no-conformidades", 500, null);
        InnpackApiClient.Respuesta vacia = cliente.get(null, "/api/no-conformidades");
        assertThat(vacia.status()).isEqualTo(500);
        assertThat(vacia.body()).isEqualTo("{\"ok\":false,\"error\":\"HTTP 500: Internal Server Error\"}");

        FaretApiClient caido = new FaretApiClient("http://127.0.0.1:9", false, CONEXION, LECTURA);
        InnpackApiClient.Respuesta red = caido.get(null, "/api/no-conformidades");
        assertThat(red.status()).isZero();
        assertThat(red.body()).isEqualTo("{\"ok\":false,\"error\":\"Error de red al conectar con la API Faret\"}");
    }

    @Test
    void configuradaDependeDeLaBaseUrlYSeIgnoranBarrasFinales() {
        assertThat(new FaretApiClient("", false, CONEXION, LECTURA).configurada()).isFalse();
        assertThat(new FaretApiClient("   ", false, CONEXION, LECTURA).configurada()).isFalse();
        assertThat(new FaretApiClient(null, false, CONEXION, LECTURA).configurada()).isFalse();
        FaretApiClient conBarras = new FaretApiClient(MC.baseUrl() + "//", false, CONEXION, LECTURA);
        MC.responder("/api/no-conformidades", 200, "[]");
        assertThat(conBarras.configurada()).isTrue();
        assertThat(conBarras.get(null, "/api/no-conformidades").status()).isEqualTo(200);
        assertThat(List.of(MC.peticiones().get(0))).containsExactly("GET /api/no-conformidades");
    }
}
