package cl.faret.qcc.registroscontrol.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Catalogo de solo lectura -- ningun modulo migrado hasta ahora crea/edita maquinas.
@Entity(name = "MaquinaRegistroControl")
@Table(name = "maquinas")
public class Maquina {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "proceso_id")
    private Long procesoId;

    @Column(name = "nombre")
    private String nombre;

    @Column(name = "codigo_qr")
    private String codigoQr;

    @Column(name = "activo")
    private boolean activo;

    protected Maquina() {
    }

    public Maquina(Long id, Long procesoId, String nombre, String codigoQr, boolean activo) {
        this.id = id;
        this.procesoId = procesoId;
        this.nombre = nombre;
        this.codigoQr = codigoQr;
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

    public String getCodigoQr() {
        return codigoQr;
    }

    public boolean isActivo() {
        return activo;
    }
}
