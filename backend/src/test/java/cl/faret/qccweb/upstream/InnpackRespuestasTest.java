package cl.faret.qccweb.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import cl.faret.qccweb.bridge.BridgeResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Paridad con HomeHandler.Forward / TryUnwrapApiResponse de Photino. */
class InnpackRespuestasTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exitoDevuelveDataTalCual() {
        BridgeResult r = reenviar(200, "{\"success\":true,\"message\":null,\"data\":{\"a\":1,\"b\":[1,2]},\"errors\":null}");
        assertThat(r.ok()).isTrue();
        assertThat(((JsonNode) r.data()).toString()).isEqualTo("{\"a\":1,\"b\":[1,2]}");
    }

    @Test
    void exitoSinDataDevuelveNull() {
        BridgeResult r = reenviar(200, "{\"success\":true}");
        assertThat(r.ok()).isTrue();
        assertThat(r.data()).isNull();
    }

    @Test
    void successFalseMuestraElMensajeDeLaApi() {
        assertThat(reenviar(400, "{\"success\":false,\"message\":\"Parámetros inválidos\"}").error())
                .isEqualTo("Parámetros inválidos");
    }

    @Test
    void respuestasNoReconocidasDanMensajeGenerico() {
        for (String body : new String[] {null, "", "no-json", "[]", "{\"title\":\"Internal Server Error\",\"detail\":\"SqlException\"}"}) {
            BridgeResult r = reenviar(500, body);
            assertThat(r.ok()).isFalse();
            assertThat(r.error()).isEqualTo(InnpackRespuestas.ERROR_GENERICO);
        }
        assertThat(InnpackRespuestas.reenviar(new InnpackApiClient.Respuesta(0, null), mapper).error())
                .isEqualTo(InnpackRespuestas.ERROR_GENERICO);
    }

    @Test
    void successTrueConHttpDeErrorEsError() {
        assertThat(reenviar(500, "{\"success\":true,\"data\":{}}").ok()).isFalse();
    }

    private BridgeResult reenviar(int status, String body) {
        return InnpackRespuestas.reenviar(new InnpackApiClient.Respuesta(status, body), mapper);
    }
}
