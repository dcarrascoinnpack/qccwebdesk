package cl.faret.qcc.dashboard.dto;

public class ControlesPorProcesoResponse {

    private final Long procesoId;
    private final String procesoNombre;
    private final long cantidad;

    public ControlesPorProcesoResponse(Long procesoId, String procesoNombre, long cantidad) {
        this.procesoId = procesoId;
        this.procesoNombre = procesoNombre;
        this.cantidad = cantidad;
    }

    public Long getProcesoId() {
        return procesoId;
    }

    public String getProcesoNombre() {
        return procesoNombre;
    }

    public long getCantidad() {
        return cantidad;
    }
}
