package cl.faret.qccweb.bridge;

import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Huellas de lo que ESTA sesión leyó por última vez de un recurso (p. ej. el análisis de una NC), guardadas
 * en la sesión HTTP server-side. Permite detectar "lost updates" en escrituras que sobrescriben sin que la
 * API tenga versión/ETag y sin cambiar el contrato de Photino: al guardar, el gateway relee el recurso y
 * compara con lo que el usuario tenía abierto.
 *
 * Límites (documentados): cubre solo a usuarios de la web; varias pestañas de la MISMA sesión comparten la
 * huella; queda una ventana mínima entre la relectura y la escritura (la API no ofrece operación atómica).
 */
public final class LecturasDeSesion {

    private static final String ATRIBUTO = "qcc.lecturas";
    private static final int MAX_ENTRADAS = 500;

    private LecturasDeSesion() {}

    public static void registrar(String recurso, String huella) {
        Map<String, String> mapa = mapa(true);
        if (mapa == null) {
            return;
        }
        if (mapa.size() >= MAX_ENTRADAS && !mapa.containsKey(recurso)) {
            mapa.clear();
        }
        mapa.put(recurso, huella);
    }

    /** Huella registrada para el recurso, o null si esta sesión no lo leyó. */
    public static String huella(String recurso) {
        Map<String, String> mapa = mapa(false);
        return mapa == null ? null : mapa.get(recurso);
    }

    public static void olvidar(String recurso) {
        Map<String, String> mapa = mapa(false);
        if (mapa != null) {
            mapa.remove(recurso);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> mapa(boolean crear) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) {
            return null;
        }
        HttpSession sesion = sra.getRequest().getSession(false);
        if (sesion == null) {
            return null;
        }
        synchronized (sesion) {
            Object actual = sesion.getAttribute(ATRIBUTO);
            if (actual instanceof Map<?, ?> m) {
                return (Map<String, String>) m;
            }
            if (!crear) {
                return null;
            }
            Map<String, String> nuevo = new ConcurrentHashMap<>();
            sesion.setAttribute(ATRIBUTO, nuevo);
            return nuevo;
        }
    }
}
