package cl.faret.qcc.auth.dto;

import cl.faret.qcc.auth.entity.Usuario;

public class LoginResponse {

    private final boolean success;
    private final String message;
    private final Long userId;
    private final String codigoUsuario;
    private final String nombreCompleto;
    private final String rol;
    private final String token;

    private LoginResponse(boolean success, String message, Long userId,
            String codigoUsuario, String nombreCompleto, String rol, String token) {
        this.success = success;
        this.message = message;
        this.userId = userId;
        this.codigoUsuario = codigoUsuario;
        this.nombreCompleto = nombreCompleto;
        this.rol = rol;
        this.token = token;
    }

    public static LoginResponse fallo(String message) {
        return new LoginResponse(false, message, null, null, null, null, null);
    }

    public static LoginResponse exito(Usuario usuario, String token) {
        return new LoginResponse(
                true,
                "Login correcto",
                usuario.getId(),
                usuario.getCodigoUsuario(),
                usuario.getNombreCompleto(),
                usuario.getRol(),
                token);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public Long getUserId() {
        return userId;
    }

    public String getCodigoUsuario() {
        return codigoUsuario;
    }

    public String getNombreCompleto() {
        return nombreCompleto;
    }

    public String getRol() {
        return rol;
    }

    public String getToken() {
        return token;
    }
}
