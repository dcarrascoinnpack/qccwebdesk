package cl.faret.qcc.noconformidades.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import cl.faret.qcc.noconformidades.entity.NcAccionCorrectiva;

public class AccionCorrectivaResponse {

    private final Long id;
    private final Long noConformidadId;
    private final Long analisisId;
    private final String descripcion;
    private final String responsable;
    private final LocalDate fechaLimite;
    private final String prioridad;
    private final String estado;
    private final String creadoPor;
    private final LocalDateTime creadoEn;
    private final String actualizadoPor;
    private final LocalDateTime actualizadoEn;

    public AccionCorrectivaResponse(NcAccionCorrectiva accion) {
        this.id = accion.getId();
        this.noConformidadId = accion.getNoConformidadId();
        this.analisisId = accion.getAnalisisId();
        this.descripcion = accion.getDescripcion();
        this.responsable = accion.getResponsable();
        this.fechaLimite = accion.getFechaLimite();
        this.prioridad = accion.getPrioridad();
        this.estado = accion.getEstado();
        this.creadoPor = accion.getCreadoPor();
        this.creadoEn = accion.getCreadoEn();
        this.actualizadoPor = accion.getActualizadoPor();
        this.actualizadoEn = accion.getActualizadoEn();
    }

    public Long getId() {
        return id;
    }

    public Long getNoConformidadId() {
        return noConformidadId;
    }

    public Long getAnalisisId() {
        return analisisId;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public String getResponsable() {
        return responsable;
    }

    public LocalDate getFechaLimite() {
        return fechaLimite;
    }

    public String getPrioridad() {
        return prioridad;
    }

    public String getEstado() {
        return estado;
    }

    public String getCreadoPor() {
        return creadoPor;
    }

    public LocalDateTime getCreadoEn() {
        return creadoEn;
    }

    public String getActualizadoPor() {
        return actualizadoPor;
    }

    public LocalDateTime getActualizadoEn() {
        return actualizadoEn;
    }
}
