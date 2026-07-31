package cl.faret.qcc.registroscontrol.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Solo lectura para los modulos de reporte (Dashboard/RegistrosControl/MaquinasSeguimiento):
// ninguno de ellos sube archivos, solo muestran el primer adjunto de cada registro como
// "imagen representativa".
@Entity(name = "RegistroAdjuntoRegistroControl")
@Table(name = "registro_adjuntos")
public class RegistroAdjunto {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "registro_id")
    private Long registroId;

    @Column(name = "ruta_archivo")
    private String rutaArchivo;

    protected RegistroAdjunto() {
    }

    public RegistroAdjunto(Long id, Long registroId, String rutaArchivo) {
        this.id = id;
        this.registroId = registroId;
        this.rutaArchivo = rutaArchivo;
    }

    public Long getId() {
        return id;
    }

    public Long getRegistroId() {
        return registroId;
    }

    public String getRutaArchivo() {
        return rutaArchivo;
    }
}
