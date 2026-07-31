package cl.faret.qcc.noconformidades.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public class ActualizarAccionRequest {

    @NotBlank
    private String descripcion;

    @NotBlank
    private String responsable;

    @NotNull
    private LocalDate fechaLimite;

    @Pattern(regexp = "ALTA|MEDIA|BAJA", message = "Prioridad inválida. Valores permitidos: ALTA, MEDIA, BAJA")
    private String prioridad;

    @NotBlank
    @Pattern(
            regexp = "PENDIENTE|EN_PROCESO|COMPLETADA|CANCELADA",
            message = "Estado inválido. Valores permitidos: PENDIENTE, EN_PROCESO, COMPLETADA, CANCELADA")
    private String estado;

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public String getResponsable() {
        return responsable;
    }

    public void setResponsable(String responsable) {
        this.responsable = responsable;
    }

    public LocalDate getFechaLimite() {
        return fechaLimite;
    }

    public void setFechaLimite(LocalDate fechaLimite) {
        this.fechaLimite = fechaLimite;
    }

    public String getPrioridad() {
        return prioridad;
    }

    public void setPrioridad(String prioridad) {
        this.prioridad = prioridad;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }
}
