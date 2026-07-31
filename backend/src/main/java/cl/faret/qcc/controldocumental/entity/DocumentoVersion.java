package cl.faret.qcc.controldocumental.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "DocumentoVersion")
@Table(name = "documento_versiones")
public class DocumentoVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "documento_id")
    private Long documentoId;

    @Column(name = "version")
    private String version;

    @Column(name = "fecha_actualizacion")
    private LocalDate fechaActualizacion;

    @Column(name = "proxima_revision")
    private LocalDate proximaRevision;

    @Column(name = "es_version_vigente")
    private boolean esVersionVigente;

    @Column(name = "creado_por")
    private String creadoPor;

    @Column(name = "fecha_creacion", insertable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    protected DocumentoVersion() {
    }

    public DocumentoVersion(Long documentoId, String version, LocalDate fechaActualizacion,
            LocalDate proximaRevision, String creadoPor) {
        this.documentoId = documentoId;
        this.version = version;
        this.fechaActualizacion = fechaActualizacion;
        this.proximaRevision = proximaRevision;
        this.esVersionVigente = true;
        this.creadoPor = creadoPor;
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

    public void setEsVersionVigente(boolean esVersionVigente) {
        this.esVersionVigente = esVersionVigente;
    }

    public String getCreadoPor() {
        return creadoPor;
    }

    public LocalDateTime getFechaCreacion() {
        return fechaCreacion;
    }
}
