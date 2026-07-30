package cl.faret.qcc.usuarios.dto;

import java.time.LocalDateTime;

import cl.faret.qcc.usuarios.entity.Usuario;

public class UsuarioResponse {

    private final Long id;
    private final String codigoUsuario;
    private final String nombreCompleto;
    private final String rol;
    private final Boolean activo;
    private final LocalDateTime creadoEn;

    public UsuarioResponse(Long id, String codigoUsuario, String nombreCompleto,
            String rol, Boolean activo, LocalDateTime creadoEn) {
        this.id = id;
        this.codigoUsuario = codigoUsuario;
        this.nombreCompleto = nombreCompleto;
        this.rol = rol;
        this.activo = activo;
        this.creadoEn = creadoEn;
    }

    public static UsuarioResponse from(Usuario usuario) {
        return new UsuarioResponse(
                usuario.getId(),
                usuario.getCodigoUsuario(),
                usuario.getNombreCompleto(),
                usuario.getRol(),
                usuario.getActivo(),
                usuario.getCreadoEn());
    }

    public Long getId() {
        return id;
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

    public Boolean getActivo() {
        return activo;
    }

    public LocalDateTime getCreadoEn() {
        return creadoEn;
    }
}
