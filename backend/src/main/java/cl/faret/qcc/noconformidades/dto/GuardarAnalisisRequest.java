package cl.faret.qcc.noconformidades.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class GuardarAnalisisRequest {

    @NotBlank
    @Pattern(
            regexp = "CINCO_PORQUES|ISHIKAWA|MIXTA",
            message = "Metodología inválida. Valores permitidos: CINCO_PORQUES, ISHIKAWA, MIXTA")
    private String metodologia;

    @NotBlank
    private String problemaDetectado;

    private String porque1;
    private String porque2;
    private String porque3;
    private String porque4;
    private String porque5;
    private String causaRaiz;
    private String conclusion;

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
}
