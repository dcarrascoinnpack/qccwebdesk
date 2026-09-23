package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cl.faret.qccweb.auth.SessionUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ActionPolicyTest {

    private static final BridgeAction NOOP = (p, u) -> BridgeResult.ok(null);
    private static final ActionPolicy POLICY = new ActionPolicy(List.of(
            new ActionPolicy.Regla("inicio.getDashboard", Set.of("INNPACK"), Set.of("admin", "operador"), Map.of(), NOOP)));

    @Test
    void permiteAccionRegistradaParaEmpresaYRolCorrectos() {
        assertThat(POLICY.evaluar("inicio.getDashboard", usuario("INNPACK", "operador")))
                .isInstanceOf(ActionPolicy.Decision.Permitida.class);
        assertThat(POLICY.evaluar("inicio.getDashboard", usuario("INNPACK", "ADMIN")))
                .as("el rol se compara sin distinguir mayúsculas").isInstanceOf(ActionPolicy.Decision.Permitida.class);
    }

    @Test
    void niegaAccionNoRegistradaONula() {
        assertThat(POLICY.evaluar("usuarios.list", usuario("INNPACK", "admin")))
                .isEqualTo(new ActionPolicy.Decision.Denegada("ACCION_NO_REGISTRADA"));
        assertThat(POLICY.evaluar(null, usuario("INNPACK", "admin")))
                .isEqualTo(new ActionPolicy.Decision.Denegada("ACCION_NO_REGISTRADA"));
    }

    @Test
    void niegaSesionDeOtraEmpresa() {
        assertThat(POLICY.evaluar("inicio.getDashboard", usuario("FARET", "admin")))
                .isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
    }

    @Test
    void niegaRolNoListadoOVacio() {
        assertThat(POLICY.evaluar("inicio.getDashboard", usuario("INNPACK", "consulta")))
                .isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
        assertThat(POLICY.evaluar("inicio.getDashboard", usuario("INNPACK", null)))
                .isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
    }

    @Test
    void niegaSinUsuario() {
        assertThat(POLICY.evaluar("inicio.getDashboard", null)).isEqualTo(new ActionPolicy.Decision.Denegada("SIN_SESION"));
    }

    @Test
    void noPermiteRegistrarDosVecesLaMismaAccion() {
        ActionPolicy.Regla r = new ActionPolicy.Regla("a.b", Set.of("INNPACK"), Set.of("admin"), Map.of(), NOOP);
        assertThatThrownBy(() -> new ActionPolicy(List.of(r, r))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void identityOverrideReemplazaSoloLosCamposDeclaradosEnRaizYData() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode payload = (ObjectNode) mapper.readTree("{\"action\":\"x.y\",\"creadoPor\":\"Otro\",\"revisadoPor\":\"Catalogo\","
                + "\"data\":{\"usuarioId\":999,\"empresa\":\"FARET\",\"rol\":\"admin\",\"monto\":5}}");
        IdentityOverride.aplicar(payload, Map.of(
                "creadoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO,
                "usuarioId", IdentityOverride.Fuente.USUARIO_ID,
                "empresa", IdentityOverride.Fuente.EMPRESA), usuario("INNPACK", "operador"));

        assertThat(payload.get("creadoPor").asString()).isEqualTo("Operador Uno");
        assertThat(payload.at("/data/usuarioId").asInt()).isEqualTo(10);
        assertThat(payload.at("/data/empresa").asString()).isEqualTo("INNPACK");
        // No declarados: se respetan (son datos de negocio en algunas acciones de Photino).
        assertThat(payload.get("revisadoPor").asString()).isEqualTo("Catalogo");
        assertThat(payload.at("/data/rol").asString()).isEqualTo("admin");
        assertThat(payload.at("/data/monto").asInt()).isEqualTo(5);
    }

    @Test
    void identityOverrideSinCamposNoTocaElPayload() {
        ObjectNode payload = (ObjectNode) new ObjectMapper().readTree("{\"action\":\"x.y\",\"usuarioId\":999}");
        IdentityOverride.aplicar(payload, Map.of(), usuario("INNPACK", "operador"));
        assertThat(payload.get("usuarioId").asInt()).isEqualTo(999);
    }

    private static SessionUser usuario(String empresa, String rol) {
        return new SessionUser(10, "operador1", "Operador Uno", rol, empresa, "token", Instant.now(), Instant.now().plusSeconds(60));
    }
}
