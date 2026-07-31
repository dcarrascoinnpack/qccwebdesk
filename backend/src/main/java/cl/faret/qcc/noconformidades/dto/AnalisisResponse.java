package cl.faret.qcc.noconformidades.dto;

import java.time.LocalDateTime;

import cl.faret.qcc.noconformidades.entity.NcAnalisis;

public class AnalisisResponse {

    private final Long id;
    private final Long noConformidadId;
    private final String metodologia;
    private final String problemaDetectado;
    private final String porque1;
    private final String porque2;
    private final String porque3;
    private final String porque4;
    private final String porque5;
    private final String causaRaiz;
    private final String conclusion;
    private final String creadoPor;
    private final LocalDateTime creadoEn;
    private final String actualizadoPor;
    private final LocalDateTime actualizadoEn;

    public AnalisisResponse(NcAnalisis analisis) {
        this.id = analisis.getId();
        this.noConformidadId = analisis.getNoConformidadId();
        this.metodologia = analisis.getMetodologia();
        this.problemaDetectado = analisis.getProblemaDetectado();
        this.porque1 = analisis.getPorque1();
        this.porque2 = analisis.getPorque2();
        this.porque3 = analisis.getPorque3();
        this.porque4 = analisis.getPorque4();
        this.porque5 = analisis.getPorque5();
        this.causaRaiz = analisis.getCausaRaiz();
        this.conclusion = analisis.getConclusion();
        this.creadoPor = analisis.getCreadoPor();
        this.creadoEn = analisis.getCreadoEn();
        this.actualizadoPor = analisis.getActualizadoPor();
        this.actualizadoEn = analisis.getActualizadoEn();
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

    public String getProblemaDetectado() {
        return problemaDetectado;
    }

    public String getPorque1() {
        return porque1;
    }

    public String getPorque2() {
        return porque2;
    }

    public String getPorque3() {
        return porque3;
    }

    public String getPorque4() {
        return porque4;
    }

    public String getPorque5() {
        return porque5;
    }

    public String getCausaRaiz() {
        return causaRaiz;
    }

    public String getConclusion() {
        return conclusion;
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

    public LocalDateTime getActualizadoEn() {
        return actualizadoEn;
    }
}
