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
