package cl.faret.qcc.usuarios.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Nombre de entidad JPA distinto de cl.faret.qcc.auth.entity.Usuario (ambas mapean la misma
// tabla "usuarios" pero son entidades independientes por modulo); sin esto Hibernate rechaza
// el arranque por nombre de entidad duplicado.
@Entity(name = "UsuarioGestion")
@Table(name = "usuarios")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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

    public Usuario(String codigoUsuario, String nombreCompleto, String passwordHash,
            String rol, Boolean activo) {
        this.codigoUsuario = codigoUsuario;
        this.nombreCompleto = nombreCompleto;
        this.passwordHash = passwordHash;
        this.rol = rol;
        this.activo = activo;
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

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
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
