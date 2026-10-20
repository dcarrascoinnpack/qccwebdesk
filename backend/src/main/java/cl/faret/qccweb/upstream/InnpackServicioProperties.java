package cl.faret.qccweb.upstream;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cuenta de servicio de BAJO privilegio con la que el gateway llama a la API INNPACK en nombre de una sesión FARET, que no tiene
 * token INNPACK (módulos faretLab.*, Despachos y Formularios FARET). Es la misma idea que la sección InnpackApiServiceAccount de
 * Photino (config.json → usuario SERVICIO_FARET_HIBRIDO, rol operador, sin privilegios de administración). Las credenciales viven
 * SOLO en el servidor (QCC_INNPACK_SERVICIO_USUARIO / QCC_INNPACK_SERVICIO_PASSWORD), nunca en el repo ni en el navegador. Sin
 * credenciales las acciones que la necesitan responden como un equipo sin cuenta de servicio configurada.
 *
 * @param usuario          codigoUsuario de la cuenta de servicio (vacío = no configurada)
 * @param password         clave de la cuenta (secreto; nunca se loguea)
 * @param margenRenovacion se renueva el token cuando faltan menos que esto para su vencimiento (por defecto 60 s)
 */
@ConfigurationProperties(prefix = "qcc.web.innpack-servicio")
public record InnpackServicioProperties(String usuario, String password, Duration margenRenovacion) {

    public boolean configurada() {
        return usuario != null && !usuario.isBlank() && password != null && !password.isEmpty();
    }

    /** Nunca incluir la clave. */
    @Override
    public String toString() {
        return "InnpackServicioProperties[usuario=" + (usuario == null || usuario.isBlank() ? "-" : usuario) + ", password="
                + (password == null || password.isEmpty() ? "-" : "***") + "]";
    }
}
