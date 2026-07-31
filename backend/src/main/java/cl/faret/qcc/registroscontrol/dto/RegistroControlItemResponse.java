package cl.faret.qcc.registroscontrol.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public class RegistroControlItemResponse {

    private final Long id;
    private final LocalDate fechaRegistro;
    private final LocalTime horaRegistro;
    private final String turno;
    private final Long usuarioId;
    private final String usuarioNombre;
    private final Long procesoId;
    private final String procesoNombre;
    private final Long maquinaId;
    private final String maquinaNombre;
    private final Long formularioId;
    private final String formularioNombre;
    private final String np;
    private final Long estadoId;
    private final String estadoNombre;
    private final String observacion;
    private final String tipoMerma;
    private final BigDecimal cantidadMerma;
    private final String estadoValidacion;
    private final LocalDateTime fechaValidacion;
    private final String usuarioValidacion;
    private final LocalDateTime creadoEn;
    private final String imagenUrl;
    private final List<String> tipoDefecto;

    public RegistroControlItemResponse(Long id, LocalDate fechaRegistro, LocalTime horaRegistro, String turno,
            Long usuarioId, String usuarioNombre, Long procesoId, String procesoNombre, Long maquinaId,
            String maquinaNombre, Long formularioId, String formularioNombre, String np, Long estadoId,
            String estadoNombre, String observacion, String tipoMerma, BigDecimal cantidadMerma,
            String estadoValidacion, LocalDateTime fechaValidacion, String usuarioValidacion,
            LocalDateTime creadoEn, String imagenUrl, List<String> tipoDefecto) {
        this.id = id;
        this.fechaRegistro = fechaRegistro;
        this.horaRegistro = horaRegistro;
        this.turno = turno;
        this.usuarioId = usuarioId;
        this.usuarioNombre = usuarioNombre;
        this.procesoId = procesoId;
        this.procesoNombre = procesoNombre;
        this.maquinaId = maquinaId;
        this.maquinaNombre = maquinaNombre;
        this.formularioId = formularioId;
        this.formularioNombre = formularioNombre;
        this.np = np;
        this.estadoId = estadoId;
        this.estadoNombre = estadoNombre;
        this.observacion = observacion;
        this.tipoMerma = tipoMerma;
        this.cantidadMerma = cantidadMerma;
        this.estadoValidacion = estadoValidacion;
        this.fechaValidacion = fechaValidacion;
        this.usuarioValidacion = usuarioValidacion;
        this.creadoEn = creadoEn;
        this.imagenUrl = imagenUrl;
        this.tipoDefecto = tipoDefecto;
    }

    public Long getId() {
        return id;
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

    public Long getUsuarioId() {
        return usuarioId;
    }

    public String getUsuarioNombre() {
        return usuarioNombre;
    }

    public Long getProcesoId() {
        return procesoId;
    }

    public String getProcesoNombre() {
        return procesoNombre;
    }

    public Long getMaquinaId() {
        return maquinaId;
    }

    public String getMaquinaNombre() {
        return maquinaNombre;
    }

    public Long getFormularioId() {
        return formularioId;
    }

    public String getFormularioNombre() {
        return formularioNombre;
    }

    public String getNp() {
        return np;
    }

    public Long getEstadoId() {
        return estadoId;
    }

    public String getEstadoNombre() {
        return estadoNombre;
    }

    public String getObservacion() {
        return observacion;
    }

    public String getTipoMerma() {
        return tipoMerma;
    }

    public BigDecimal getCantidadMerma() {
        return cantidadMerma;
    }

    public String getEstadoValidacion() {
        return estadoValidacion;
    }

    public LocalDateTime getFechaValidacion() {
        return fechaValidacion;
    }

    public String getUsuarioValidacion() {
        return usuarioValidacion;
    }

    public LocalDateTime getCreadoEn() {
        return creadoEn;
    }

    public String getImagenUrl() {
        return imagenUrl;
    }

    public List<String> getTipoDefecto() {
        return tipoDefecto;
    }
}
