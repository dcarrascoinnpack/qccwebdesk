package cl.faret.qccweb.bridge;

import cl.faret.qccweb.auth.SessionUser;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Política explícita de acciones del bridge — deny-by-default.
 *
 * Una acción solo se ejecuta si está registrada aquí (nombre exacto de Photino), la empresa de la
 * sesión está permitida y el rol de la sesión está en la lista explícita de roles. Empresa y rol
 * salen SIEMPRE del SessionUser, nunca del payload. Cualquier otra acción se rechaza.
 */
public final class ActionPolicy {

    /**
     * @param accion    nombre exacto de la acción de Photino (p. ej. "inicio.getDashboard")
     * @param empresas  empresas de sesión permitidas ("INNPACK" / "FARET")
     * @param roles     roles de sesión permitidos (lista explícita; un rol nuevo no entra solo)
     * @param identidad campos del payload que son identidad del actor y se sobrescriben con la sesión
     * @param handler   implementación
     */
    public record Regla(
            String accion,
            Set<String> empresas,
            Set<String> roles,
            Map<String, IdentityOverride.Fuente> identidad,
            BridgeAction handler) {

        public Regla {
            empresas = Set.copyOf(empresas);
            roles = roles.stream().map(r -> r.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
            identidad = Map.copyOf(identidad);
        }
    }

    /** Resultado de evaluar una acción para un usuario. */
    public sealed interface Decision {
        record Permitida(Regla regla) implements Decision {}

        record Denegada(String motivo) implements Decision {}
    }

    private final Map<String, Regla> reglas;

    public ActionPolicy(List<Regla> reglas) {
        Map<String, Regla> mapa = new LinkedHashMap<>();
        for (Regla r : reglas) {
            if (mapa.put(r.accion(), r) != null) {
                throw new IllegalStateException("Acción registrada dos veces en ActionPolicy: " + r.accion());
            }
        }
        this.reglas = Collections.unmodifiableMap(mapa);
    }

    public Decision evaluar(String accion, SessionUser usuario) {
        Regla regla = accion == null ? null : reglas.get(accion);
        if (regla == null) {
            return new Decision.Denegada("ACCION_NO_REGISTRADA");
        }
        if (usuario == null) {
            return new Decision.Denegada("SIN_SESION");
        }
        if (!regla.empresas().contains(usuario.empresa())) {
            return new Decision.Denegada("EMPRESA_NO_PERMITIDA");
        }
        String rol = usuario.rol() == null ? "" : usuario.rol().toLowerCase(Locale.ROOT);
        if (!regla.roles().contains(rol)) {
            return new Decision.Denegada("ROL_NO_PERMITIDO");
        }
        return new Decision.Permitida(regla);
    }

    /** Acciones habilitadas en la web (insumo del contract check de la Fase 1d). */
    public Set<String> accionesRegistradas() {
        return reglas.keySet();
    }
}
