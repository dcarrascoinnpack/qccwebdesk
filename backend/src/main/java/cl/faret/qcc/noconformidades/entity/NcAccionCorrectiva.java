package cl.faret.qcc.noconformidades.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "NcAccionCorrectiva")
@Table(name = "nc_acciones_correctivas")
public class NcAccionCorrectiva {

    private static final String ESTADO_INICIAL = "PENDIENTE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "no_conformidad_id")
    private Long noConformidadId;

    @Column(name = "analisis_id")
    private Long analisisId;

    @Column(name = "descripcion")
    private String descripcion;

    @Column(name = "responsable")
    private String responsable;

    @Column(name = "fecha_limite")
    private LocalDate fechaLimite;

    @Column(name = "prioridad")
    private String prioridad;

    @Column(name = "estado")
    private String estado;

    @Column(name = "creado_por")
    private String creadoPor;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private LocalDateTime creadoEn;

    @Column(name = "actualizado_por")
    private String actualizadoPor;

    @Column(name = "actualizado_en", insertable = false, updatable = false)
    private LocalDateTime actualizadoEn;

    protected NcAccionCorrectiva() {
    }

    public NcAccionCorrectiva(Long noConformidadId, Long analisisId, String descripcion,
            String responsable, LocalDate fechaLimite, String prioridad, String creadoPor) {
        this.noConformidadId = noConformidadId;
        this.analisisId = analisisId;
        this.descripcion = descripcion;
        this.responsable = responsable;
        this.fechaLimite = fechaLimite;
        this.prioridad = prioridad;
        this.estado = ESTADO_INICIAL;
        this.creadoPor = creadoPor;
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

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public String getResponsable() {
        return responsable;
    }

    public void setResponsable(String responsable) {
        this.responsable = responsable;
    }

    public LocalDate getFechaLimite() {
        return fechaLimite;
    }

    public void setFechaLimite(LocalDate fechaLimite) {
        this.fechaLimite = fechaLimite;
    }

    public String getPrioridad() {
        return prioridad;
    }

    public void setPrioridad(String prioridad) {
        this.prioridad = prioridad;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
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

    public void setActualizadoPor(String actualizadoPor) {
        this.actualizadoPor = actualizadoPor;
    }

    public LocalDateTime getActualizadoEn() {
        return actualizadoEn;
    }
}
