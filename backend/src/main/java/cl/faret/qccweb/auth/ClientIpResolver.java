package cl.faret.qccweb.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;

/**
 * IP real del cliente para rate limiting y auditoría. Solo confía en X-Forwarded-For si la conexión
 * viene de un proxy configurado (IIS); si no, cualquiera podría falsificar su IP con esa cabecera.
 */
public final class ClientIpResolver {

    private final Set<String> proxiesConfiables;

    public ClientIpResolver(List<String> proxiesConfiables) {
        this.proxiesConfiables = Set.copyOf(proxiesConfiables);
    }

    public String resolver(HttpServletRequest request) {
        String remoto = request.getRemoteAddr();
        if (!proxiesConfiables.contains(remoto)) {
            return remoto;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) {
            return remoto;
        }
        // Recorre de derecha a izquierda: el primer salto que no sea un proxy confiable es el cliente.
        String[] saltos = xff.split(",");
        for (int i = saltos.length - 1; i >= 0; i--) {
            String ip = saltos[i].trim();
            if (!ip.isEmpty() && !proxiesConfiables.contains(ip)) {
                return ip;
            }
        }
        return remoto;
    }
}
