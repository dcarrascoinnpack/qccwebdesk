package cl.faret.qcc.laboratorio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public class RegistroEnsayoItemResponse {

    private final Long id;
    private final Long registroId;
    private final LocalDate fechaRegistro;
    private final LocalTime horaRegistro;
    private final String turno;
    private final String np;
    private final String usuarioNombre;
    private final String procesoNombre;
    private final String ensayoNombre;
    private final String materialNombre;
    private final BigDecimal valor;
    private final String observacion;
    private final String imagenUrl;

    public RegistroEnsayoItemResponse(Long id, Long registroId, LocalDate fechaRegistro, LocalTime horaRegistro,
            String turno, String np, String usuarioNombre, String procesoNombre, String ensayoNombre,
            String materialNombre, BigDecimal valor, String observacion, String imagenUrl) {
        this.id = id;
        this.registroId = registroId;
        this.fechaRegistro = fechaRegistro;
        this.horaRegistro = horaRegistro;
        this.turno = turno;
        this.np = np;
        this.usuarioNombre = usuarioNombre;
        this.procesoNombre = procesoNombre;
        this.ensayoNombre = ensayoNombre;
        this.materialNombre = materialNombre;
        this.valor = valor;
        this.observacion = observacion;
        this.imagenUrl = imagenUrl;
    }

    public Long getId() {
        return id;
    }

    public Long getRegistroId() {
        return registroId;
    }

    public LocalDate getFechaRegistro() {
        return fechaRegistro;
    }

    public LocalTime getHoraRegistro() {
        return horaRegistro;
    }

    public String getTurno() {
        return turno;
    }

    public String getNp() {
        return np;
    }

    public String getUsuarioNombre() {
        return usuarioNombre;
    }

    public String getProcesoNombre() {
        return procesoNombre;
    }

    public String getEnsayoNombre() {
        return ensayoNombre;
    }

    public String getMaterialNombre() {
        return materialNombre;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public String getObservacion() {
        return observacion;
    }

    public String getImagenUrl() {
        return imagenUrl;
    }
}
