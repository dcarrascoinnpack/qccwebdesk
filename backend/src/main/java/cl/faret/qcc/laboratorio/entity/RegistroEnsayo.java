package cl.faret.qcc.laboratorio.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Solo lectura -- el modulo Laboratorio (LaboratorioHandler.cs) solo tiene una accion de solo
// lectura, ningun modulo migrado hasta ahora crea/edita ensayos de laboratorio.
@Entity(name = "RegistroEnsayo")
@Table(name = "registro_ensayos")
public class RegistroEnsayo {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "registro_id")
    private Long registroId;

    @Column(name = "ensayo_id")
    private Long ensayoId;

    @Column(name = "material_id")
    private Long materialId;

    @Column(name = "valor")
    private BigDecimal valor;

    @Column(name = "observacion")
    private String observacion;

    protected RegistroEnsayo() {
    }

    public RegistroEnsayo(Long id, Long registroId, Long ensayoId, Long materialId, BigDecimal valor,
            String observacion) {
        this.id = id;
        this.registroId = registroId;
        this.ensayoId = ensayoId;
        this.materialId = materialId;
        this.valor = valor;
        this.observacion = observacion;
    }

    public Long getId() {
        return id;
    }

    public Long getRegistroId() {
        return registroId;
    }

    public Long getEnsayoId() {
        return ensayoId;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public String getObservacion() {
        return observacion;
    }
}
