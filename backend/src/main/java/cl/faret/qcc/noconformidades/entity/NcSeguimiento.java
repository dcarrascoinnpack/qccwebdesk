package cl.faret.qcc.noconformidades.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "NcSeguimiento")
@Table(name = "nc_seguimiento")
public class NcSeguimiento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "no_conformidad_id")
    private Long noConformidadId;

    @Column(name = "comentario")
    private String comentario;

    @Column(name = "autor")
    private String autor;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private LocalDateTime creadoEn;

    protected NcSeguimiento() {
    }

    public NcSeguimiento(Long noConformidadId, String comentario, String autor) {
        this.noConformidadId = noConformidadId;
        this.comentario = comentario;
        this.autor = autor;
    }

    public Long getId() {
        return id;
    }

    public Long getNoConformidadId() {
        return noConformidadId;
    }

    public String getComentario() {
        return comentario;
    }

    public String getAutor() {
        return autor;
    }

    public LocalDateTime getCreadoEn() {
        return creadoEn;
    }
}
