package cl.faret.qccweb.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import cl.faret.qccweb.bridge.BridgeResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Paridad con TryUnwrapApiResponse y ExtractMcErrorMessage de FaretHandler.cs (Photino 1.8.15, 9e1b556). */
class FaretRespuestasTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void successTrueDesenvuelveData() {
        BridgeResult r = desenvolver(200, "{\"success\":true,\"message\":null,\"data\":{\"items\":[1,2],\"totalCount\":2},\"errors\":null}");
        assertThat(r.ok()).isTrue();
        assertThat(((JsonNode) r.data()).toString()).isEqualTo("{\"items\":[1,2],\"totalCount\":2}");
        assertThat(desenvolver(200, "{\"success\":true,\"data\":null}").data()).isNull();
    }

    @Test
    void varianteOkDeCalidadDesenvuelveData() {
        BridgeResult r = desenvolver(200, "{\"ok\":true,\"data\":{\"totalMaquinas\":3}}");
        assertThat(r.ok()).isTrue();
        assertThat(((JsonNode) r.data()).toString()).isEqualTo("{\"totalMaquinas\":3}");
        assertThat(desenvolver(200, "{\"ok\":false,\"message\":\"Sin datos\"}").error()).isEqualTo("Sin datos");
        // La variante ok=false no lee "error" (TryUnwrapApiResponse solo mira message).
        assertThat(desenvolver(200, "{\"ok\":false,\"error\":\"x\"}").error()).isEqualTo(FaretRespuestas.ERROR_FARET);
    }

    @Test
    void successFalseMuestraElMensajeDeLaApi() {
        assertThat(desenvolver(400, "{\"success\":false,\"message\":\"Fecha inválida\"}").error()).isEqualTo("Fecha inválida");
        assertThat(desenvolver(400, "{\"success\":false,\"message\":null}").error()).isEqualTo(FaretRespuestas.ERROR_FARET);
        assertThat(desenvolver(400, "{\"success\":false}").error()).isEqualTo(FaretRespuestas.ERROR_FARET);
    }

    @Test
    void varianteErrorLocalYRespuestasNoReconocidasDanElGenerico() {
        assertThat(desenvolver(0, "{\"ok\":false,\"error\":\"Timeout al conectar con la API Faret\"}").error())
                .isEqualTo(FaretRespuestas.ERROR_FARET);
        assertThat(desenvolver(500, "{\"error\":\"Boom\"}").error()).isEqualTo("Boom");
        for (String body : new String[] {null, "", "no-json", "[]", "{\"title\":\"Internal Server Error\"}", "{\"success\":\"si\"}",
                "{\"success\":true}"}) {
            BridgeResult r = desenvolver(200, body);
            assertThat(r.ok()).as(String.valueOf(body)).isFalse();
            assertThat(r.error()).as(String.valueOf(body)).isEqualTo(FaretRespuestas.ERROR_FARET);
        }
    }

    @Test
    void unStatusNo2xxNuncaEsExitoAunqueElCuerpoDigaSuccessTrue() {
        BridgeResult r = desenvolver(500, "{\"success\":true,\"data\":{\"a\":1}}");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).isEqualTo(FaretRespuestas.ERROR_FARET);
    }

    @Test
    void mejoraContinuaDevuelveElJsonCrudoEnExito() {
        BridgeResult r = crudo(200, "[{\"id\":1,\"codigo\":\"NC-1\"}]");
        assertThat(r.ok()).isTrue();
        assertThat(((JsonNode) r.data()).toString()).isEqualTo("[{\"id\":1,\"codigo\":\"NC-1\"}]");
        assertThat(crudo(200, "[]").ok()).isTrue();
        assertThat(crudo(200, "no-json").error()).isEqualTo(FaretRespuestas.ERROR_MC);
    }

    @Test
    void mejoraContinuaExtraeElErrorEnElMismoOrdenQuePhotino() {
        assertThat(crudo(400, "{\"mensaje\":\"M\",\"error\":\"E\",\"title\":\"T\",\"detail\":\"D\"}").error()).isEqualTo("M");
        assertThat(crudo(400, "{\"error\":\"E\",\"title\":\"T\",\"detail\":\"D\"}").error()).isEqualTo("E");
        assertThat(crudo(500, "{\"title\":\"Internal Server Error\",\"status\":500,\"detail\":\"D\"}").error()).isEqualTo("Internal Server Error");
        assertThat(crudo(500, "{\"detail\":\"D\"}").error()).isEqualTo("D");
        // Solo cuentan los strings: un mensaje no-string se salta al siguiente.
        assertThat(crudo(400, "{\"mensaje\":5,\"error\":\"E\"}").error()).isEqualTo("E");
        for (String body : new String[] {null, "", "no-json", "[]", "{}", "{\"mensaje\":null}"}) {
            assertThat(crudo(502, body).error()).as(String.valueOf(body)).isEqualTo(FaretRespuestas.ERROR_MC);
        }
    }

    private BridgeResult desenvolver(int status, String body) {
        return FaretRespuestas.desenvolver(new InnpackApiClient.Respuesta(status, body), mapper);
    }

    private BridgeResult crudo(int status, String body) {
        return FaretRespuestas.crudoMc(new InnpackApiClient.Respuesta(status, body), mapper);
    }
}
