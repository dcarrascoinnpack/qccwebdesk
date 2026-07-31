package cl.faret.qcc.registroscontrol.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Catalogo de tipos de defecto visual, solo lectura para los modulos de reporte.
@Entity(name = "ParametroControlVisualRegistroControl")
@Table(name = "parametros_control_visual")
public class ParametroControlVisual {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "proceso_id")
    private Long procesoId;

    @Column(name = "nombre")
    private String nombre;

    @Column(name = "criticidad")
    private String criticidad;

    @Column(name = "activo")
    private boolean activo;

    protected ParametroControlVisual() {
    }

    public ParametroControlVisual(Long id, Long procesoId, String nombre, String criticidad, boolean activo) {
        this.id = id;
        this.procesoId = procesoId;
        this.nombre = nombre;
        this.criticidad = criticidad;
        this.activo = activo;
    }

    public Long getId() {
        return id;
    }

    public Long getProcesoId() {
        return procesoId;
    }

    public String getNombre() {
        return nombre;
    }

    public String getCriticidad() {
        return criticidad;
    }

    public boolean isActivo() {
        return activo;
    }
}
