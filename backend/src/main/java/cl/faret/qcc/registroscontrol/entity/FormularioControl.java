package cl.faret.qcc.registroscontrol.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Catalogo de solo lectura -- ningun modulo migrado hasta ahora crea/edita formularios.
@Entity(name = "FormularioControlRegistroControl")
@Table(name = "formularios_control")
public class FormularioControl {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "proceso_id")
    private Long procesoId;

    @Column(name = "maquina_id")
    private Long maquinaId;

    @Column(name = "nombre")
    private String nombre;

    @Column(name = "descripcion")
    private String descripcion;

    @Column(name = "activo")
    private boolean activo;

    protected FormularioControl() {
    }

    public FormularioControl(Long id, Long procesoId, Long maquinaId, String nombre, String descripcion,
            boolean activo) {
        this.id = id;
        this.procesoId = procesoId;
        this.maquinaId = maquinaId;
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.activo = activo;
    }

    public Long getId() {
        return id;
    }

    public Long getProcesoId() {
        return procesoId;
    }

    public Long getMaquinaId() {
        return maquinaId;
    }

    public String getNombre() {
        return nombre;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public boolean isActivo() {
        return activo;
    }
}
