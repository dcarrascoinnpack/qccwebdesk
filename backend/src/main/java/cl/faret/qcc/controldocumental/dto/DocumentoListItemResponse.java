package cl.faret.qcc.controldocumental.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import cl.faret.qcc.controldocumental.entity.Documento;
import cl.faret.qcc.controldocumental.entity.DocumentoVersion;

public class DocumentoListItemResponse {

    private final Long id;
    private final String codigoBase;
    private final String tipoDocumento;
    private final String area;
    private final String nombre;
    private final String alcanceEmpresa;
    private final String estado;
    private final String responsable;
    private final String ubicacion;
    private final String observaciones;
    private final String creadoPor;
    private final LocalDateTime fechaCreacion;
    private final String actualizadoPor;
    private final LocalDateTime fechaActualizacion;
    private final String versionVigente;
    private final LocalDate versionFechaActualizacion;
    private final LocalDate versionProximaRevision;

    public DocumentoListItemResponse(Documento documento, DocumentoVersion versionVigente) {
        this.id = documento.getId();
        this.codigoBase = documento.getCodigoBase();
        this.tipoDocumento = documento.getTipoDocumento();
        this.area = documento.getArea();
        this.nombre = documento.getNombre();
        this.alcanceEmpresa = documento.getAlcanceEmpresa();
        this.estado = documento.getEstado();
        this.responsable = documento.getResponsable();
        this.ubicacion = documento.getUbicacion();
        this.observaciones = documento.getObservaciones();
        this.creadoPor = documento.getCreadoPor();
        this.fechaCreacion = documento.getFechaCreacion();
        this.actualizadoPor = documento.getActualizadoPor();
        this.fechaActualizacion = documento.getFechaActualizacion();
        this.versionVigente = versionVigente != null ? versionVigente.getVersion() : null;
        this.versionFechaActualizacion = versionVigente != null ? versionVigente.getFechaActualizacion() : null;
        this.versionProximaRevision = versionVigente != null ? versionVigente.getProximaRevision() : null;
    }

    public Long getId() {
        return id;
    }

    public String getCodigoBase() {
        return codigoBase;
    }

    public String getTipoDocumento() {
        return tipoDocumento;
    }

    public String getArea() {
        return area;
    }

    public String getNombre() {
        return nombre;
    }

    public String getAlcanceEmpresa() {
        return alcanceEmpresa;
    }

    public String getEstado() {
        return estado;
    }

    public String getResponsable() {
        return responsable;
    }

    public String getUbicacion() {
        return ubicacion;
    }

    public String getObservaciones() {
        return observaciones;
    }

    public String getCreadoPor() {
        return creadoPor;
    }

    public LocalDateTime getFechaCreacion() {
        return fechaCreacion;
    }

    public String getActualizadoPor() {
        return actualizadoPor;
    }

    public LocalDateTime getFechaActualizacion() {
        return fechaActualizacion;
    }

    public String getVersionVigente() {
        return versionVigente;
    }

    public LocalDate getVersionFechaActualizacion() {
        return versionFechaActualizacion;
    }

    public LocalDate getVersionProximaRevision() {
        return versionProximaRevision;
    }
}
