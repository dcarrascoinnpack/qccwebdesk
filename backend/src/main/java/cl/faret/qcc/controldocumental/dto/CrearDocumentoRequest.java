package cl.faret.qcc.controldocumental.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public class CrearDocumentoRequest {

    @NotBlank
    private String codigoBase;

    @NotBlank
    private String tipoDocumento;

    private String area;

    @NotBlank
    private String nombre;

    @Pattern(regexp = "INNPACK|FARET|AMBAS", message = "Alcance inválido. Valores permitidos: INNPACK, FARET, AMBAS")
    private String alcanceEmpresa;

    @Pattern(
            regexp = "VIGENTE|EN_REVISION|OBSOLETO",
            message = "Estado inválido. Valores permitidos: VIGENTE, EN_REVISION, OBSOLETO")
    private String estado;

    private String responsable;

    private String ubicacion;

    private String observaciones;

    @NotBlank
    private String version;

    @NotNull
    private LocalDate fechaActualizacion;

    private LocalDate proximaRevision;

    public String getCodigoBase() {
        return codigoBase;
    }

    public void setCodigoBase(String codigoBase) {
        this.codigoBase = codigoBase;
    }

    public String getTipoDocumento() {
        return tipoDocumento;
    }

    public void setTipoDocumento(String tipoDocumento) {
        this.tipoDocumento = tipoDocumento;
    }

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getAlcanceEmpresa() {
        return alcanceEmpresa;
    }

    public void setAlcanceEmpresa(String alcanceEmpresa) {
        this.alcanceEmpresa = alcanceEmpresa;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    public String getResponsable() {
        return responsable;
    }

    public void setResponsable(String responsable) {
        this.responsable = responsable;
    }

    public String getUbicacion() {
        return ubicacion;
    }

    public void setUbicacion(String ubicacion) {
        this.ubicacion = ubicacion;
    }

    public String getObservaciones() {
        return observaciones;
    }

    public void setObservaciones(String observaciones) {
        this.observaciones = observaciones;
    }

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
