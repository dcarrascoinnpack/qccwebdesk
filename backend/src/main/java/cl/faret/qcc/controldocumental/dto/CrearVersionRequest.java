package cl.faret.qcc.controldocumental.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CrearVersionRequest {

    @NotBlank
    private String version;

    @NotNull
    private LocalDate fechaActualizacion;

    private LocalDate proximaRevision;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public LocalDate getFechaActualizacion() {
        return fechaActualizacion;
    }

    public void setFechaActualizacion(LocalDate fechaActualizacion) {
        this.fechaActualizacion = fechaActualizacion;
    }

    public LocalDate getProximaRevision() {
        return proximaRevision;
    }

    public void setProximaRevision(LocalDate proximaRevision) {
        this.proximaRevision = proximaRevision;
    }
}
