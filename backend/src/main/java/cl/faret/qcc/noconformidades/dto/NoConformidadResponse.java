package cl.faret.qcc.noconformidades.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import cl.faret.qcc.noconformidades.entity.NoConformidad;

public class NoConformidadResponse {

    private final Long id;
    private final String codigo;
    private final String tipo;
    private final String origen;
    private final String titulo;
    private final String descripcion;
    private final String severidad;
    private final String proceso;
    private final String norma;
    private final String reportadoPor;
    private final LocalDate fechaDeteccion;
    private final String estado;
    private final String responsable;
    private final String estadoGestion;
    private final LocalDate fechaCompromiso;
    private final String cerradoPor;
    private final String comentarioCierre;
    private final LocalDateTime fechaCierre;
    private final String tipoPnc;
    private final LocalDate fechaIngreso;
    private final LocalDate fechaSalida;
    private final String npNv;
    private final String cliente;
    private final String codigoProducto;
    private final String producto;
    private final BigDecimal cantRequerida;
    private final BigDecimal cantRechazada;
    private final BigDecimal cantRecuperada;
    private final BigDecimal pncReal;
    private final BigDecimal pctRecuperacion;
    private final LocalDate fechaFabricacion;
    private final String descripcionDefecto;
    private final String categoriaDefecto;
    private final String nivel;
    private final String tipoFalla;
    private final String area;
    private final String maquina;
    private final String operador;
    private final String supervisor;
    private final String revisadoPor;
    private final String impacto;
    private final String observacion;
    private final String causaRaiz;
    private final String accionesCorrectivas;
    private final String verificacionSeguimiento;
    private final String creadoPor;
    private final LocalDateTime fechaCreacion;
    private final String actualizadoPor;
    private final LocalDateTime fechaActualizacion;

    public NoConformidadResponse(NoConformidad n) {
        this.id = n.getId();
        this.codigo = n.getCodigo();
        this.tipo = n.getTipo();
        this.origen = n.getOrigen();
        this.titulo = n.getTitulo();
        this.descripcion = n.getDescripcion();
        this.severidad = n.getSeveridad();
        this.proceso = n.getProceso();
        this.norma = n.getNorma();
        this.reportadoPor = n.getReportadoPor();
        this.fechaDeteccion = n.getFechaDeteccion();
        this.estado = n.getEstado();
        this.responsable = n.getResponsable();
        this.estadoGestion = n.getEstadoGestion();
        this.fechaCompromiso = n.getFechaCompromiso();
        this.cerradoPor = n.getCerradoPor();
        this.comentarioCierre = n.getComentarioCierre();
        this.fechaCierre = n.getFechaCierre();
        this.tipoPnc = n.getTipoPnc();
        this.fechaIngreso = n.getFechaIngreso();
        this.fechaSalida = n.getFechaSalida();
        this.npNv = n.getNpNv();
        this.cliente = n.getCliente();
        this.codigoProducto = n.getCodigoProducto();
        this.producto = n.getProducto();
        this.cantRequerida = n.getCantRequerida();
        this.cantRechazada = n.getCantRechazada();
        this.cantRecuperada = n.getCantRecuperada();
        this.pncReal = n.getPncReal();
        this.pctRecuperacion = n.getPctRecuperacion();
        this.fechaFabricacion = n.getFechaFabricacion();
        this.descripcionDefecto = n.getDescripcionDefecto();
        this.categoriaDefecto = n.getCategoriaDefecto();
        this.nivel = n.getNivel();
        this.tipoFalla = n.getTipoFalla();
        this.area = n.getArea();
        this.maquina = n.getMaquina();
        this.operador = n.getOperador();
        this.supervisor = n.getSupervisor();
        this.revisadoPor = n.getRevisadoPor();
        this.impacto = n.getImpacto();
        this.observacion = n.getObservacion();
        this.causaRaiz = n.getCausaRaiz();
        this.accionesCorrectivas = n.getAccionesCorrectivas();
        this.verificacionSeguimiento = n.getVerificacionSeguimiento();
        this.creadoPor = n.getCreadoPor();
        this.fechaCreacion = n.getFechaCreacion();
        this.actualizadoPor = n.getActualizadoPor();
        this.fechaActualizacion = n.getFechaActualizacion();
    }

    public Long getId() {
        return id;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getTipo() {
        return tipo;
    }

    public String getOrigen() {
        return origen;
    }

    public String getTitulo() {
        return titulo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public String getSeveridad() {
        return severidad;
    }

    public String getProceso() {
        return proceso;
    }

    public String getNorma() {
        return norma;
    }

    public String getReportadoPor() {
        return reportadoPor;
    }

    public LocalDate getFechaDeteccion() {
        return fechaDeteccion;
    }

    public String getEstado() {
        return estado;
    }

    public String getResponsable() {
        return responsable;
    }

    public String getEstadoGestion() {
        return estadoGestion;
    }

    public LocalDate getFechaCompromiso() {
        return fechaCompromiso;
    }

    public String getCerradoPor() {
        return cerradoPor;
    }

    public String getComentarioCierre() {
        return comentarioCierre;
    }

    public LocalDateTime getFechaCierre() {
        return fechaCierre;
    }

    public String getTipoPnc() {
        return tipoPnc;
    }

    public LocalDate getFechaIngreso() {
        return fechaIngreso;
    }

    public LocalDate getFechaSalida() {
        return fechaSalida;
    }

    public String getNpNv() {
        return npNv;
    }

    public String getCliente() {
        return cliente;
    }

    public String getCodigoProducto() {
        return codigoProducto;
    }

    public String getProducto() {
        return producto;
    }

    public BigDecimal getCantRequerida() {
        return cantRequerida;
    }

    public BigDecimal getCantRechazada() {
        return cantRechazada;
    }

    public BigDecimal getCantRecuperada() {
        return cantRecuperada;
    }

    public BigDecimal getPncReal() {
        return pncReal;
    }

    public BigDecimal getPctRecuperacion() {
        return pctRecuperacion;
    }

    public LocalDate getFechaFabricacion() {
        return fechaFabricacion;
    }

    public String getDescripcionDefecto() {
        return descripcionDefecto;
    }

    public String getCategoriaDefecto() {
        return categoriaDefecto;
    }

    public String getNivel() {
        return nivel;
    }

    public String getTipoFalla() {
        return tipoFalla;
    }

    public String getArea() {
        return area;
    }

    public String getMaquina() {
        return maquina;
    }

    public String getOperador() {
        return operador;
    }

    public String getSupervisor() {
        return supervisor;
    }

    public String getRevisadoPor() {
        return revisadoPor;
    }

    public String getImpacto() {
        return impacto;
    }

    public String getObservacion() {
        return observacion;
    }

    public String getCausaRaiz() {
        return causaRaiz;
    }

    public String getAccionesCorrectivas() {
        return accionesCorrectivas;
    }

    public String getVerificacionSeguimiento() {
        return verificacionSeguimiento;
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
}
