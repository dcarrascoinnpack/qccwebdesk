package cl.faret.qcc.controldocumental.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import cl.faret.qcc.controldocumental.entity.DocumentoVersion;

public class VersionResponse {

    private final Long id;
    private final Long documentoId;
    private final String version;
    private final LocalDate fechaActualizacion;
    private final LocalDate proximaRevision;
    private final boolean esVersionVigente;
    private final String creadoPor;
    private final LocalDateTime fechaCreacion;

    public VersionResponse(DocumentoVersion version) {
        this.id = version.getId();
        this.documentoId = version.getDocumentoId();
        this.version = version.getVersion();
        this.fechaActualizacion = version.getFechaActualizacion();
        this.proximaRevision = version.getProximaRevision();
        this.esVersionVigente = version.isEsVersionVigente();
        this.creadoPor = version.getCreadoPor();
        this.fechaCreacion = version.getFechaCreacion();
    }

    public Long getId() {
        return id;
    }

    public Long getDocumentoId() {
        return documentoId;
    }

    public String getVersion() {
        return version;
    }

    public LocalDate getFechaActualizacion() {
        return fechaActualizacion;
    }

    public LocalDate getProximaRevision() {
        return proximaRevision;
    }

    public boolean isEsVersionVigente() {
        return esVersionVigente;
    }

    public String getCreadoPor() {
        return creadoPor;
    }

    public LocalDateTime getFechaCreacion() {
        return fechaCreacion;
    }
}
