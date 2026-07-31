package cl.faret.qcc.registroscontrol.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Tabla central compartida por los modulos Dashboard, RegistrosControl, Laboratorio y
// MaquinasSeguimiento. Solo mapea las columnas que esos 4 modulos realmente leen (confirmado
// contra INFORMATION_SCHEMA en produccion) -- la tabla real tiene columnas adicionales
// (tipo_onda_id, requiere_ensayo_laboratorio, merma_insumos_desponche_bobinas,
// merma_proceso_monotapas) que ningun modulo migrado hasta ahora utiliza.
@Entity(name = "RegistroControl")
@Table(name = "registros_control")
public class RegistroControl {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "usuario_id")
    private Long usuarioId;

    @Column(name = "proceso_id")
    private Long procesoId;

    @Column(name = "maquina_id")
    private Long maquinaId;

    @Column(name = "formulario_id")
    private Long formularioId;

    @Column(name = "area")
    private String area;

    @Column(name = "np")
    private String np;

    @Column(name = "codigo_producto")
    private String codigoProducto;

    @Column(name = "descripcion_producto")
    private String descripcionProducto;

    @Column(name = "turno")
    private String turno;

    @Column(name = "estado_id")
    private Long estadoId;

    @Column(name = "observacion")
    private String observacion;

    @Column(name = "requiere_merma")
    private boolean requiereMerma;

    @Column(name = "tipo_merma")
    private String tipoMerma;

    @Column(name = "cantidad_merma")
    private BigDecimal cantidadMerma;

    @Column(name = "fecha_registro")
    private LocalDate fechaRegistro;

    @Column(name = "hora_registro")
    private LocalTime horaRegistro;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private LocalDateTime creadoEn;

    @Column(name = "estado_validacion")
    private String estadoValidacion;

    @Column(name = "fecha_validacion")
    private LocalDateTime fechaValidacion;

    @Column(name = "usuario_validacion")
    private String usuarioValidacion;

    @Column(name = "eliminado")
    private boolean eliminado;

    protected RegistroControl() {
    }

    // Ningun modulo migrado hasta ahora inserta filas en registros_control (las crea la app movil,
    // fuera del alcance de esta migracion) -- este constructor existe solo para poder construir
    // fixtures de prueba sin depender de JPA/reflexion.
    public RegistroControl(Long id, Long usuarioId, Long procesoId, Long maquinaId, Long formularioId,
            String area, String np, String codigoProducto, String descripcionProducto, String turno,
            Long estadoId, String observacion, boolean requiereMerma, String tipoMerma,
            BigDecimal cantidadMerma, LocalDate fechaRegistro, LocalTime horaRegistro,
            String estadoValidacion, LocalDateTime fechaValidacion, String usuarioValidacion,
            boolean eliminado) {
        this.id = id;
        this.usuarioId = usuarioId;
        this.procesoId = procesoId;
        this.maquinaId = maquinaId;
        this.formularioId = formularioId;
        this.area = area;
        this.np = np;
        this.codigoProducto = codigoProducto;
        this.descripcionProducto = descripcionProducto;
        this.turno = turno;
        this.estadoId = estadoId;
        this.observacion = observacion;
        this.requiereMerma = requiereMerma;
        this.tipoMerma = tipoMerma;
        this.cantidadMerma = cantidadMerma;
        this.fechaRegistro = fechaRegistro;
        this.horaRegistro = horaRegistro;
        this.estadoValidacion = estadoValidacion;
        this.fechaValidacion = fechaValidacion;
        this.usuarioValidacion = usuarioValidacion;
        this.eliminado = eliminado;
    }

    public Long getId() {
        return id;
    }

    public Long getUsuarioId() {
        return usuarioId;
    }

    public Long getProcesoId() {
        return procesoId;
    }

    public Long getMaquinaId() {
        return maquinaId;
    }

    public Long getFormularioId() {
        return formularioId;
    }

    public String getArea() {
        return area;
    }

    public String getNp() {
        return np;
    }

    public String getCodigoProducto() {
        return codigoProducto;
    }

    public String getDescripcionProducto() {
        return descripcionProducto;
    }

    public String getTurno() {
        return turno;
    }

    public Long getEstadoId() {
        return estadoId;
    }

    public String getObservacion() {
        return observacion;
    }

    public boolean isRequiereMerma() {
        return requiereMerma;
    }

    public String getTipoMerma() {
        return tipoMerma;
    }

    public BigDecimal getCantidadMerma() {
        return cantidadMerma;
    }

    public LocalDate getFechaRegistro() {
        return fechaRegistro;
    }

    public LocalTime getHoraRegistro() {
        return horaRegistro;
    }

    public LocalDateTime getCreadoEn() {
        return creadoEn;
    }

    public String getEstadoValidacion() {
        return estadoValidacion;
    }

    public void setEstadoValidacion(String estadoValidacion) {
        this.estadoValidacion = estadoValidacion;
    }

    public LocalDateTime getFechaValidacion() {
        return fechaValidacion;
    }

    public void setFechaValidacion(LocalDateTime fechaValidacion) {
        this.fechaValidacion = fechaValidacion;
    }

    public String getUsuarioValidacion() {
        return usuarioValidacion;
    }

    public void setUsuarioValidacion(String usuarioValidacion) {
        this.usuarioValidacion = usuarioValidacion;
    }

    public boolean isEliminado() {
        return eliminado;
    }

    public void setEliminado(boolean eliminado) {
        this.eliminado = eliminado;
    }
}
