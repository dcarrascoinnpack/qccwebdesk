package cl.faret.qccweb.upstream;

import cl.faret.qccweb.auth.AuthProperties;
import cl.faret.qccweb.auth.SessionUser;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Cliente INNPACK para las acciones de una sesión FARET que la API atiende solo con JWT (faretLab.*): reemplaza el Bearer del
 * usuario FARET por el de la cuenta de servicio de bajo privilegio ({@link CuentaServicioInnpack}) y traduce el prefijo de ruta
 * del handler que se reutiliza (p. ej. /api/muestra-laboratorio → /api/faret-laboratorio, mismas rutas y DTOs). La identidad
 * que el handler pone en el cuerpo (usuarioId, analistaNombre...) sigue siendo la de la sesión FARET.
 *
 * Sin cuenta de servicio, o ante un 401 de su token, devuelve status 0 (error genérico) en vez de lanzar
 * {@link UpstreamNoAutorizadoException}: el bridge cerraría la sesión web del usuario FARET, que no tiene la culpa. No es un bean
 * (el InnpackApiClient inyectable debe seguir siendo uno solo).
 */
public class InnpackApiClientServicio extends InnpackApiClient {

    private static final Respuesta NO_DISPONIBLE = new Respuesta(0, null);

    private final CuentaServicioInnpack cuentaServicio;
    private final String prefijoDelHandler;
    private final String prefijoDeLaApi;

    public InnpackApiClientServicio(
            AuthProperties properties, CuentaServicioInnpack cuentaServicio, String prefijoDelHandler, String prefijoDeLaApi) {
        super(properties);
        this.cuentaServicio = cuentaServicio;
        this.prefijoDelHandler = prefijoDelHandler;
        this.prefijoDeLaApi = prefijoDeLaApi;
    }

    @Override
    public Respuesta get(SessionUser usuario, String path) {
        return conServicio(servicio -> super.get(servicio, traducir(path)));
    }

    @Override
    public Respuesta postJson(SessionUser usuario, String path, JsonNode cuerpo) {
        return conServicio(servicio -> super.postJson(servicio, traducir(path), cuerpo));
    }

    @Override
    public Respuesta putJson(SessionUser usuario, String path, JsonNode cuerpo) {
        return conServicio(servicio -> super.putJson(servicio, traducir(path), cuerpo));
    }

    @Override
    public Respuesta patchJson(SessionUser usuario, String path, JsonNode cuerpo) {
        return conServicio(servicio -> super.patchJson(servicio, traducir(path), cuerpo));
    }

    @Override
    public Respuesta delete(SessionUser usuario, String path) {
        return conServicio(servicio -> super.delete(servicio, traducir(path)));
    }

    private String traducir(String path) {
        return path.startsWith(prefijoDelHandler) ? prefijoDeLaApi + path.substring(prefijoDelHandler.length()) : path;
    }

    private Respuesta conServicio(java.util.function.Function<SessionUser, Respuesta> llamada) {
        Optional<SessionUser> servicio = cuentaServicio.sesion();
        if (servicio.isEmpty()) {
            return NO_DISPONIBLE;
        }
        try {
            return llamada.apply(servicio.get());
        } catch (UpstreamNoAutorizadoException e) {
            cuentaServicio.invalidar();
            return NO_DISPONIBLE;
        }
    }
}
