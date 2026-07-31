package cl.faret.qcc.registroscontrol.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Tabla puente registros_control <-> parametros_control_visual: cada fila es "este registro tuvo
// este defecto visual". Solo lectura para los modulos de reporte (se usa como proxy de "no
// conformidad detectada").
@Entity(name = "RegistroFallaVisualRegistroControl")
@Table(name = "registro_fallas_visuales")
public class RegistroFallaVisual {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "registro_id")
    private Long registroId;

    @Column(name = "parametro_id")
    private Long parametroId;

    protected RegistroFallaVisual() {
    }

    public RegistroFallaVisual(Long id, Long registroId, Long parametroId) {
        this.id = id;
        this.registroId = registroId;
        this.parametroId = parametroId;
    }

    public Long getId() {
        return id;
    }

    public Long getRegistroId() {
        return registroId;
    }

    public Long getParametroId() {
        return parametroId;
    }
}
