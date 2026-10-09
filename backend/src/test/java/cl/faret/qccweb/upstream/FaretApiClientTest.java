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
import tools.jackson.databind.ObjectMapper;
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

    // ------------------------------------------------------------------------------------------- F0: escrituras

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void escrituraConBearerLlevaElTokenDelUsuarioElCuerpoJsonYPropaga201() throws Exception {
        FaretApiClient cliente = new FaretApiClient(QC.baseUrl(), true, CONEXION, LECTURA);
        InnpackApiClient.Respuesta r = cliente.postJson(usuario(7), "/api/pnc-catalogos/clientes",
                MAPPER.readTree("{\"nombre\":\"Viña Ñandú\"}"));
        assertThat(r.status()).isEqualTo(201);
        assertThat(QC.escrituras()).hasSize(1);
        FakeFaretApi.Escritura e = QC.escrituras().get(0);
        assertThat(e.metodo()).isEqualTo("POST");
        assertThat(e.uri()).isEqualTo("/api/pnc-catalogos/clientes");
        assertThat(e.sub()).isEqualTo(7);
        assertThat(e.contentType()).startsWith("application/json");
        assertThat(MAPPER.readTree(e.cuerpo()).get("nombre").asString()).isEqualTo("Viña Ñandú");
        assertThat(QC.lecturas()).isEmpty();
    }

    @Test
    void putPatchYDeleteUsanSuMetodoSinCuerpoEnDelete() throws Exception {
        FaretApiClient cliente = new FaretApiClient(QC.baseUrl(), true, CONEXION, LECTURA);
        assertThat(cliente.putJson(usuario(7), "/api/catalogos/areas/3", MAPPER.readTree("{\"x\":1}")).status()).isEqualTo(201);
        assertThat(cliente.patchJson(usuario(7), "/api/catalogos/areas/3", MAPPER.readTree("{\"y\":2}")).status()).isEqualTo(201);
        assertThat(cliente.delete(usuario(9), "/api/pnc-catalogos/clientes/3").status()).isEqualTo(201);
        assertThat(QC.escrituras()).extracting(FakeFaretApi.Escritura::metodo).containsExactly("PUT", "PATCH", "DELETE");
        assertThat(QC.escrituras()).extracting(FakeFaretApi.Escritura::sub).containsExactly(7, 7, 9);
        assertThat(QC.escrituras().get(2).cuerpo()).isEmpty();
    }

    @Test
    void escrituraConBearerPropagaElConflicto409SinReintentarYUn401InvalidaLaSesion() throws Exception {
        FaretApiClient cliente = new FaretApiClient(QC.baseUrl(), true, CONEXION, LECTURA);
        QC.respuestaEscritura("POST /api/catalogos/areas", 409, "{\"success\":false,\"message\":\"Ya existe\",\"data\":null}");
        InnpackApiClient.Respuesta r = cliente.postJson(usuario(7), "/api/catalogos/areas", MAPPER.readTree("{}"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("Ya existe");
        assertThat(QC.escrituras()).hasSize(1); // sin reintento

        SessionUser malFirmado = new SessionUser(5, "x", "X", "CALIDAD", "FARET", "e30.e30.firma-falsa", Instant.now(),
                Instant.now().plusSeconds(60));
        assertThatThrownBy(() -> cliente.postJson(malFirmado, "/api/catalogos/areas", MAPPER.readTree("{}")))
                .isInstanceOf(UpstreamNoAutorizadoException.class);
        QC.rechazarLecturas(true);
        assertThatThrownBy(() -> cliente.delete(usuario(7), "/api/catalogos/areas/3")).isInstanceOf(UpstreamNoAutorizadoException.class);
    }

    @Test
    void escrituraConBearerSinTokenUpstreamNoLlamaALaApiYInvalidaLaSesion() throws Exception {
        FaretApiClient cliente = new FaretApiClient(QC.baseUrl(), true, CONEXION, LECTURA);
        SessionUser sinToken = new SessionUser(5, "x", "X", "CALIDAD", "FARET", null, Instant.now(), Instant.now().plusSeconds(60));
        assertThatThrownBy(() -> cliente.postJson(sinToken, "/api/catalogos/areas", MAPPER.readTree("{}")))
                .isInstanceOf(UpstreamNoAutorizadoException.class);
        assertThatThrownBy(() -> cliente.delete(null, "/api/catalogos/areas/1")).isInstanceOf(UpstreamNoAutorizadoException.class);
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void escrituraSinBearerNuncaEnviaAuthorizationYUn401NoInvalidaNada() throws Exception {
        FaretApiClient cliente = new FaretApiClient(MC.baseUrl(), false, CONEXION, LECTURA);
        MC.responder("/api/no-conformidades", 201, "{\"id\":5}");
        MC.responder("/api/no-conformidades/1", 401, "{\"title\":\"Unauthorized\"}");
        assertThat(cliente.postJson(usuario(7), "/api/no-conformidades", MAPPER.readTree("{\"a\":1}")).status()).isEqualTo(201);
        assertThat(cliente.patchJson(usuario(7), "/api/no-conformidades/1", MAPPER.readTree("{\"a\":2}")).status()).isEqualTo(401);
        assertThat(cliente.delete(null, "/api/no-conformidades/1").status()).isEqualTo(401);
        assertThat(MC.authorizations()).containsExactly(null, null, null);
        assertThat(MC.peticiones()).containsExactly("POST /api/no-conformidades", "PATCH /api/no-conformidades/1",
                "DELETE /api/no-conformidades/1");
        assertThat(MC.cuerpos().get(0)).isEqualTo("{\"a\":1}");
    }

    @Test
    void escrituraConRedCaidaDevuelveStatusCeroSinReintentarYErrorSinCuerpoSeSintetiza() throws Exception {
        FaretApiClient caido = new FaretApiClient("http://127.0.0.1:9", false, CONEXION, LECTURA);
        InnpackApiClient.Respuesta red = caido.postJson(null, "/api/no-conformidades", MAPPER.readTree("{}"));
        assertThat(red.status()).isZero();
        assertThat(red.body()).isEqualTo("{\"ok\":false,\"error\":\"Error de red al conectar con la API Faret\"}");

        FaretApiClient cliente = new FaretApiClient(MC.baseUrl(), false, CONEXION, LECTURA);
        MC.responder("/api/no-conformidades", 409, null);
        InnpackApiClient.Respuesta conflicto = cliente.postJson(null, "/api/no-conformidades", MAPPER.readTree("{}"));
        assertThat(conflicto.status()).isEqualTo(409);
        assertThat(conflicto.body()).isEqualTo("{\"ok\":false,\"error\":\"HTTP 409: Conflict\"}");
        // El mensaje que llega al navegador es el genérico de Faret (FaretRespuestas), nunca el detalle interno.
        assertThat(FaretRespuestas.desenvolver(conflicto, MAPPER).error()).isEqualTo(FaretRespuestas.ERROR_FARET);
    }
}
