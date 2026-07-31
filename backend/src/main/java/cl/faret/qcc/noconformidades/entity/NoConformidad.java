package cl.faret.qcc.noconformidades.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "NoConformidadInnpack")
@Table(name = "no_conformidades")
public class NoConformidad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "codigo")
    private String codigo;

    @Column(name = "tipo")
    private String tipo;

    @Column(name = "origen")
    private String origen;

    @Column(name = "titulo")
    private String titulo;

    @Column(name = "descripcion")
    private String descripcion;

    @Column(name = "severidad")
    private String severidad;

    @Column(name = "proceso")
    private String proceso;

    @Column(name = "norma")
    private String norma;

    @Column(name = "reportado_por")
    private String reportadoPor;

    @Column(name = "fecha_deteccion")
    private LocalDate fechaDeteccion;

    @Column(name = "estado")
    private String estado;

    @Column(name = "responsable")
    private String responsable;

    @Column(name = "estado_gestion")
    private String estadoGestion;

    @Column(name = "fecha_compromiso")
    private LocalDate fechaCompromiso;

    @Column(name = "cerrado_por")
    private String cerradoPor;

    @Column(name = "comentario_cierre")
    private String comentarioCierre;

    @Column(name = "fecha_cierre")
    private LocalDateTime fechaCierre;

    @Column(name = "tipo_pnc")
    private String tipoPnc;

    @Column(name = "fecha_ingreso")
    private LocalDate fechaIngreso;

    @Column(name = "fecha_salida")
    private LocalDate fechaSalida;

    @Column(name = "np_nv")
    private String npNv;

    @Column(name = "cliente")
    private String cliente;

    @Column(name = "codigo_producto")
    private String codigoProducto;

    @Column(name = "producto")
    private String producto;

    @Column(name = "cant_requerida")
    private BigDecimal cantRequerida;

    @Column(name = "cant_rechazada")
    private BigDecimal cantRechazada;

    @Column(name = "cant_recuperada")
    private BigDecimal cantRecuperada;

    @Column(name = "pnc_real")
    private BigDecimal pncReal;

    @Column(name = "pct_recuperacion")
    private BigDecimal pctRecuperacion;

    @Column(name = "fecha_fabricacion")
    private LocalDate fechaFabricacion;

    @Column(name = "descripcion_defecto")
    private String descripcionDefecto;

    @Column(name = "categoria_defecto")
    private String categoriaDefecto;

    @Column(name = "nivel")
    private String nivel;

    @Column(name = "tipo_falla")
    private String tipoFalla;

    @Column(name = "area")
    private String area;

    @Column(name = "maquina")
    private String maquina;

    @Column(name = "operador")
    private String operador;

    @Column(name = "supervisor")
    private String supervisor;

    @Column(name = "revisado_por")
    private String revisadoPor;

    @Column(name = "impacto")
    private String impacto;

    @Column(name = "observacion")
    private String observacion;

    @Column(name = "causa_raiz")
    private String causaRaiz;

    @Column(name = "acciones_correctivas")
    private String accionesCorrectivas;

    @Column(name = "verificacion_seguimiento")
    private String verificacionSeguimiento;

    @Column(name = "creado_por")
    private String creadoPor;

    @Column(name = "fecha_creacion")
    private LocalDateTime fechaCreacion;

    @Column(name = "actualizado_por")
    private String actualizadoPor;

    @Column(name = "fecha_actualizacion")
    private LocalDateTime fechaActualizacion;

    protected NoConformidad() {
    }

    public NoConformidad(String codigo, String tipo, String origen, String titulo, String descripcion,
            String severidad, String proceso, String norma, String reportadoPor, LocalDate fechaDeteccion,
            String estado, String estadoGestion, String tipoPnc, LocalDate fechaIngreso, LocalDate fechaSalida,
            String npNv, String cliente, String codigoProducto, String producto, BigDecimal cantRequerida,
            BigDecimal cantRechazada, BigDecimal cantRecuperada, BigDecimal pncReal, BigDecimal pctRecuperacion,
            LocalDate fechaFabricacion, String descripcionDefecto, String categoriaDefecto, String nivel,
            String tipoFalla, String area, String maquina, String operador, String supervisor,
            String revisadoPor, String impacto, String observacion, String causaRaiz,
            String accionesCorrectivas, String verificacionSeguimiento, String creadoPor) {
        this.codigo = codigo;
        this.tipo = tipo;
        this.origen = origen;
        this.titulo = titulo;
        this.descripcion = descripcion;
        this.severidad = severidad;
        this.proceso = proceso;
        this.norma = norma;
        this.reportadoPor = reportadoPor;
        this.fechaDeteccion = fechaDeteccion;
        this.estado = estado;
        this.estadoGestion = estadoGestion;
        this.tipoPnc = tipoPnc;
        this.fechaIngreso = fechaIngreso;
        this.fechaSalida = fechaSalida;
        this.npNv = npNv;
        this.cliente = cliente;
        this.codigoProducto = codigoProducto;
        this.producto = producto;
        this.cantRequerida = cantRequerida;
        this.cantRechazada = cantRechazada;
        this.cantRecuperada = cantRecuperada;
        this.pncReal = pncReal;
        this.pctRecuperacion = pctRecuperacion;
        this.fechaFabricacion = fechaFabricacion;
        this.descripcionDefecto = descripcionDefecto;
        this.categoriaDefecto = categoriaDefecto;
        this.nivel = nivel;
        this.tipoFalla = tipoFalla;
        this.area = area;
        this.maquina = maquina;
        this.operador = operador;
        this.supervisor = supervisor;
        this.revisadoPor = revisadoPor;
        this.impacto = impacto;
        this.observacion = observacion;
        this.causaRaiz = causaRaiz;
        this.accionesCorrectivas = accionesCorrectivas;
        this.verificacionSeguimiento = verificacionSeguimiento;
        this.creadoPor = creadoPor;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCodigo() {
        return codigo;
    }

    public void setCodigo(String codigo) {
        this.codigo = codigo;
    }

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

    public String getEstado() {
        return estado;
    }

    public String getResponsable() {
        return responsable;
    }

    public void setResponsable(String responsable) {
        this.responsable = responsable;
    }

    public String getEstadoGestion() {
        return estadoGestion;
    }

    public void setEstadoGestion(String estadoGestion) {
        this.estadoGestion = estadoGestion;
    }

    public LocalDate getFechaCompromiso() {
        return fechaCompromiso;
    }

    public void setFechaCompromiso(LocalDate fechaCompromiso) {
        this.fechaCompromiso = fechaCompromiso;
    }

    public String getCerradoPor() {
        return cerradoPor;
    }

    public void setCerradoPor(String cerradoPor) {
        this.cerradoPor = cerradoPor;
    }

    public String getComentarioCierre() {
        return comentarioCierre;
    }

    public void setComentarioCierre(String comentarioCierre) {
        this.comentarioCierre = comentarioCierre;
    }

    public LocalDateTime getFechaCierre() {
        return fechaCierre;
    }

    public void setFechaCierre(LocalDateTime fechaCierre) {
        this.fechaCierre = fechaCierre;
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

    public BigDecimal getPctRecuperacion() {
        return pctRecuperacion;
    }

    public void setPctRecuperacion(BigDecimal pctRecuperacion) {
        this.pctRecuperacion = pctRecuperacion;
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

    public String getCreadoPor() {
        return creadoPor;
    }

    public LocalDateTime getFechaCreacion() {
        return fechaCreacion;
    }

    public String getActualizadoPor() {
        return actualizadoPor;
    }

    public void setActualizadoPor(String actualizadoPor) {
        this.actualizadoPor = actualizadoPor;
    }

    public LocalDateTime getFechaActualizacion() {
        return fechaActualizacion;
    }
}
