package cl.faret.qcc.registroscontrol.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Catalogo de solo lectura -- ningun modulo migrado hasta ahora crea/edita estados.
@Entity(name = "EstadoCatalogoRegistroControl")
@Table(name = "estados_catalogo")
public class EstadoCatalogo {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "nombre")
    private String nombre;

    @Column(name = "activo")
    private boolean activo;

    protected EstadoCatalogo() {
    }

    public EstadoCatalogo(Long id, String nombre, boolean activo) {
        this.id = id;
        this.nombre = nombre;
        this.activo = activo;
    }

    public Long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public boolean isActivo() {
        return activo;
    }
}
