package cl.faret.qcc.noconformidades.dto;

import java.time.LocalDateTime;

import cl.faret.qcc.noconformidades.entity.NcSeguimiento;

public class SeguimientoResponse {

    private final Long id;
    private final Long noConformidadId;
    private final String comentario;
    private final String autor;
    private final LocalDateTime creadoEn;

    public SeguimientoResponse(NcSeguimiento seguimiento) {
        this.id = seguimiento.getId();
        this.noConformidadId = seguimiento.getNoConformidadId();
        this.comentario = seguimiento.getComentario();
        this.autor = seguimiento.getAutor();
        this.creadoEn = seguimiento.getCreadoEn();
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
