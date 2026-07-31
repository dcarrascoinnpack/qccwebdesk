package cl.faret.qcc.laboratorio.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Catalogo de solo lectura.
@Entity(name = "EnsayoLaboratorio")
@Table(name = "ensayos_laboratorio")
public class EnsayoLaboratorio {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "proceso_id")
    private Long procesoId;

    @Column(name = "nombre")
    private String nombre;

    @Column(name = "unidad_medida")
    private String unidadMedida;

    @Column(name = "activo")
    private boolean activo;

    protected EnsayoLaboratorio() {
    }

    public EnsayoLaboratorio(Long id, Long procesoId, String nombre, String unidadMedida, boolean activo) {
        this.id = id;
        this.procesoId = procesoId;
        this.nombre = nombre;
        this.unidadMedida = unidadMedida;
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

    public String getUnidadMedida() {
        return unidadMedida;
    }

    public boolean isActivo() {
        return activo;
    }
}
