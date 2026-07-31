package cl.faret.qcc.controldocumental.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "Documento")
@Table(name = "documentos")
public class Documento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "codigo_base")
    private String codigoBase;

    @Column(name = "tipo_documento")
    private String tipoDocumento;

    @Column(name = "area")
    private String area;

    @Column(name = "nombre")
    private String nombre;

    @Column(name = "alcance_empresa")
    private String alcanceEmpresa;

    @Column(name = "estado")
    private String estado;

    @Column(name = "responsable")
    private String responsable;

    @Column(name = "ubicacion")
    private String ubicacion;

    @Column(name = "observaciones")
    private String observaciones;

    @Column(name = "creado_por")
    private String creadoPor;

    @Column(name = "fecha_creacion", insertable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @Column(name = "actualizado_por")
    private String actualizadoPor;

    @Column(name = "fecha_actualizacion", insertable = false, updatable = false)
    private LocalDateTime fechaActualizacion;

    protected Documento() {
    }

    public Documento(String codigoBase, String tipoDocumento, String area, String nombre,
            String alcanceEmpresa, String estado, String responsable, String ubicacion,
            String observaciones, String creadoPor) {
        this.codigoBase = codigoBase;
        this.tipoDocumento = tipoDocumento;
        this.area = area;
        this.nombre = nombre;
        this.alcanceEmpresa = alcanceEmpresa;
        this.estado = estado;
        this.responsable = responsable;
        this.ubicacion = ubicacion;
        this.observaciones = observaciones;
        this.creadoPor = creadoPor;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCodigoBase() {
        return codigoBase;
    }

    public void setCodigoBase(String codigoBase) {
        this.codigoBase = codigoBase;
    }

    public String getTipoDocumento() {
        return tipoDocumento;
    }

    public void setTipoDocumento(String tipoDocumento) {
        this.tipoDocumento = tipoDocumento;
    }

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getAlcanceEmpresa() {
        return alcanceEmpresa;
    }

    public void setAlcanceEmpresa(String alcanceEmpresa) {
        this.alcanceEmpresa = alcanceEmpresa;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    public String getResponsable() {
        return responsable;
    }

    public void setResponsable(String responsable) {
        this.responsable = responsable;
    }

    public String getUbicacion() {
        return ubicacion;
    }

    public void setUbicacion(String ubicacion) {
        this.ubicacion = ubicacion;
    }

    public String getObservaciones() {
        return observaciones;
    }

    public void setObservaciones(String observaciones) {
        this.observaciones = observaciones;
    }

    public String getCreadoPor() {
        return creadoPor;
    }

    public LocalDateTime getFechaCreacion() {
        return fechaCreacion;
    }

    public String getActualizadoPor() {
        return actualizadoPor;
    }

    public void setActualizadoPor(String actualizadoPor) {
        this.actualizadoPor = actualizadoPor;
    }

    public LocalDateTime getFechaActualizacion() {
        return fechaActualizacion;
    }
}
