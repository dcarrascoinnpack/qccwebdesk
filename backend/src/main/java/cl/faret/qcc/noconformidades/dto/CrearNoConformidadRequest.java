package cl.faret.qcc.noconformidades.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public class CrearNoConformidadRequest {

    @NotBlank
    private String tipo;

    @NotBlank
    private String origen;

    @NotBlank
    private String titulo;

    @NotBlank
    private String descripcion;

    @NotBlank
    private String severidad;

    @NotBlank
    private String proceso;

    private String norma;

    private String reportadoPor;

    @NotNull
    private LocalDate fechaDeteccion;

    private String tipoPnc;

    private LocalDate fechaIngreso;

    private LocalDate fechaSalida;

    @NotBlank
    private String npNv;

    @NotBlank
    private String cliente;

    private String codigoProducto;

    @NotBlank
    private String producto;

    @NotNull
    private BigDecimal cantRequerida;

    @NotNull
    private BigDecimal cantRechazada;

    private BigDecimal cantRecuperada;

    private BigDecimal pncReal;

    private LocalDate fechaFabricacion;

    @NotBlank
    private String descripcionDefecto;

    @NotBlank
    private String categoriaDefecto;

    @NotBlank
    @Pattern(regexp = "Crítico|Mayor|Menor", message = "Nivel inválido. Valores permitidos: Crítico, Mayor, Menor")
    private String nivel;

    private String tipoFalla;

    private String area;

    private String maquina;

    private String operador;

    private String supervisor;

    private String revisadoPor;

    private String impacto;

    private String observacion;

    private String causaRaiz;

    private String accionesCorrectivas;

    private String verificacionSeguimiento;

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public String getOrigen() {
        return origen;
    }

    public void setOrigen(String origen) {
        this.origen = origen;
    }

    public String getTitulo() {
        return titulo;
    }

    public void setTitulo(String titulo) {
        this.titulo = titulo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public String getSeveridad() {
        return severidad;
    }

    public void setSeveridad(String severidad) {
        this.severidad = severidad;
    }

    public String getProceso() {
        return proceso;
    }

    public void setProceso(String proceso) {
        this.proceso = proceso;
    }

    public String getNorma() {
        return norma;
    }

    public void setNorma(String norma) {
        this.norma = norma;
    }

    public String getReportadoPor() {
        return reportadoPor;
    }

    public void setReportadoPor(String reportadoPor) {
        this.reportadoPor = reportadoPor;
    }

    public LocalDate getFechaDeteccion() {
        return fechaDeteccion;
    }

    public void setFechaDeteccion(LocalDate fechaDeteccion) {
        this.fechaDeteccion = fechaDeteccion;
    }

    public String getTipoPnc() {
        return tipoPnc;
    }

    public void setTipoPnc(String tipoPnc) {
        this.tipoPnc = tipoPnc;
    }

    public LocalDate getFechaIngreso() {
        return fechaIngreso;
    }

    public void setFechaIngreso(LocalDate fechaIngreso) {
        this.fechaIngreso = fechaIngreso;
    }

    public LocalDate getFechaSalida() {
        return fechaSalida;
    }

    public void setFechaSalida(LocalDate fechaSalida) {
        this.fechaSalida = fechaSalida;
    }

    public String getNpNv() {
        return npNv;
    }

    public void setNpNv(String npNv) {
        this.npNv = npNv;
    }

    public String getCliente() {
        return cliente;
    }

    public void setCliente(String cliente) {
        this.cliente = cliente;
    }

    public String getCodigoProducto() {
        return codigoProducto;
    }

    public void setCodigoProducto(String codigoProducto) {
        this.codigoProducto = codigoProducto;
    }

    public String getProducto() {
        return producto;
    }

    public void setProducto(String producto) {
        this.producto = producto;
    }

    public BigDecimal getCantRequerida() {
        return cantRequerida;
    }

    public void setCantRequerida(BigDecimal cantRequerida) {
        this.cantRequerida = cantRequerida;
    }

    public BigDecimal getCantRechazada() {
        return cantRechazada;
    }

    public void setCantRechazada(BigDecimal cantRechazada) {
        this.cantRechazada = cantRechazada;
    }

    public BigDecimal getCantRecuperada() {
        return cantRecuperada;
    }

    public void setCantRecuperada(BigDecimal cantRecuperada) {
        this.cantRecuperada = cantRecuperada;
    }

    public BigDecimal getPncReal() {
        return pncReal;
    }

    public void setPncReal(BigDecimal pncReal) {
        this.pncReal = pncReal;
    }

    public LocalDate getFechaFabricacion() {
        return fechaFabricacion;
    }

    public void setFechaFabricacion(LocalDate fechaFabricacion) {
        this.fechaFabricacion = fechaFabricacion;
    }

    public String getDescripcionDefecto() {
        return descripcionDefecto;
    }

    public void setDescripcionDefecto(String descripcionDefecto) {
        this.descripcionDefecto = descripcionDefecto;
    }

    public String getCategoriaDefecto() {
        return categoriaDefecto;
    }

    public void setCategoriaDefecto(String categoriaDefecto) {
        this.categoriaDefecto = categoriaDefecto;
    }

    public String getNivel() {
        return nivel;
    }

    public void setNivel(String nivel) {
        this.nivel = nivel;
    }

    public String getTipoFalla() {
        return tipoFalla;
    }

    public void setTipoFalla(String tipoFalla) {
        this.tipoFalla = tipoFalla;
    }

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
    }

    public String getMaquina() {
        return maquina;
    }

    public void setMaquina(String maquina) {
        this.maquina = maquina;
    }

    public String getOperador() {
        return operador;
    }

    public void setOperador(String operador) {
        this.operador = operador;
    }

    public String getSupervisor() {
        return supervisor;
    }

    public void setSupervisor(String supervisor) {
        this.supervisor = supervisor;
    }

    public String getRevisadoPor() {
        return revisadoPor;
    }

    public void setRevisadoPor(String revisadoPor) {
        this.revisadoPor = revisadoPor;
    }

    public String getImpacto() {
        return impacto;
    }

    public void setImpacto(String impacto) {
        this.impacto = impacto;
    }

    public String getObservacion() {
        return observacion;
    }

    public void setObservacion(String observacion) {
        this.observacion = observacion;
    }

    public String getCausaRaiz() {
        return causaRaiz;
    }

    public void setCausaRaiz(String causaRaiz) {
        this.causaRaiz = causaRaiz;
    }

    public String getAccionesCorrectivas() {
        return accionesCorrectivas;
    }

    public void setAccionesCorrectivas(String accionesCorrectivas) {
        this.accionesCorrectivas = accionesCorrectivas;
    }

    public String getVerificacionSeguimiento() {
        return verificacionSeguimiento;
    }

    public void setVerificacionSeguimiento(String verificacionSeguimiento) {
        this.verificacionSeguimiento = verificacionSeguimiento;
    }
}
