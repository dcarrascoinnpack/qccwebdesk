package cl.faret.qcc.noconformidades.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "NcAnalisis")
@Table(name = "nc_analisis")
public class NcAnalisis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "no_conformidad_id")
    private Long noConformidadId;

    @Column(name = "metodologia")
    private String metodologia;

    @Column(name = "problema_detectado")
    private String problemaDetectado;

    @Column(name = "porque1")
    private String porque1;

    @Column(name = "porque2")
    private String porque2;

    @Column(name = "porque3")
    private String porque3;

    @Column(name = "porque4")
    private String porque4;

    @Column(name = "porque5")
    private String porque5;

    @Column(name = "causa_raiz")
    private String causaRaiz;

    @Column(name = "conclusion")
    private String conclusion;

    @Column(name = "creado_por")
    private String creadoPor;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private LocalDateTime creadoEn;

    @Column(name = "actualizado_por")
    private String actualizadoPor;

    @Column(name = "actualizado_en", insertable = false, updatable = false)
    private LocalDateTime actualizadoEn;

    protected NcAnalisis() {
    }

    public NcAnalisis(Long noConformidadId, String metodologia, String problemaDetectado,
            String porque1, String porque2, String porque3, String porque4, String porque5,
            String causaRaiz, String conclusion, String creadoPor) {
        this.noConformidadId = noConformidadId;
        this.metodologia = metodologia;
        this.problemaDetectado = problemaDetectado;
        this.porque1 = porque1;
        this.porque2 = porque2;
        this.porque3 = porque3;
        this.porque4 = porque4;
        this.porque5 = porque5;
        this.causaRaiz = causaRaiz;
        this.conclusion = conclusion;
        this.creadoPor = creadoPor;
    }

    public Long getId() {
        return id;
    }

    public Long getNoConformidadId() {
        return noConformidadId;
    }

    public String getMetodologia() {
        return metodologia;
    }

    public void setMetodologia(String metodologia) {
        this.metodologia = metodologia;
    }

    public String getProblemaDetectado() {
        return problemaDetectado;
    }

    public void setProblemaDetectado(String problemaDetectado) {
        this.problemaDetectado = problemaDetectado;
    }

    public String getPorque1() {
        return porque1;
    }

    public void setPorque1(String porque1) {
        this.porque1 = porque1;
    }

    public String getPorque2() {
        return porque2;
    }

    public void setPorque2(String porque2) {
        this.porque2 = porque2;
    }

    public String getPorque3() {
        return porque3;
    }

    public void setPorque3(String porque3) {
        this.porque3 = porque3;
    }

    public String getPorque4() {
        return porque4;
    }

    public void setPorque4(String porque4) {
        this.porque4 = porque4;
    }

    public String getPorque5() {
        return porque5;
    }

    public void setPorque5(String porque5) {
        this.porque5 = porque5;
    }

    public String getCausaRaiz() {
        return causaRaiz;
    }

    public void setCausaRaiz(String causaRaiz) {
        this.causaRaiz = causaRaiz;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public String getCreadoPor() {
        return creadoPor;
    }

    public LocalDateTime getCreadoEn() {
        return creadoEn;
    }

    public String getActualizadoPor() {
        return actualizadoPor;
    }

    public void setActualizadoPor(String actualizadoPor) {
        this.actualizadoPor = actualizadoPor;
    }

    public LocalDateTime getActualizadoEn() {
        return actualizadoEn;
    }
}
