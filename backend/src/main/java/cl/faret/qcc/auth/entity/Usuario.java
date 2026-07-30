package cl.faret.qcc.auth.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "usuarios")
public class Usuario {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "codigo_usuario")
    private String codigoUsuario;

    @Column(name = "nombre_completo")
    private String nombreCompleto;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "rol")
    private String rol;

    @Column(name = "activo")
    private Boolean activo;

    @Column(name = "creado_en")
    private LocalDateTime creadoEn;

    protected Usuario() {
    }

    public Usuario(Long id, String codigoUsuario, String nombreCompleto, String passwordHash,
            String rol, Boolean activo, LocalDateTime creadoEn) {
        this.id = id;
        this.codigoUsuario = codigoUsuario;
        this.nombreCompleto = nombreCompleto;
        this.passwordHash = passwordHash;
        this.rol = rol;
        this.activo = activo;
        this.creadoEn = creadoEn;
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

    public String getPasswordHash() {
        return passwordHash;
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
