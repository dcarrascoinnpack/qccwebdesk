package cl.faret.qcc.dashboard.dto;

public class NoConformidadPorInspectorResponse {

    private final Long usuarioId;
    private final String usuarioNombre;
    private final long cantidadNoConformidades;

    public NoConformidadPorInspectorResponse(Long usuarioId, String usuarioNombre, long cantidadNoConformidades) {
        this.usuarioId = usuarioId;
        this.usuarioNombre = usuarioNombre;
        this.cantidadNoConformidades = cantidadNoConformidades;
    }

    public Long getUsuarioId() {
        return usuarioId;
    }

    public String getUsuarioNombre() {
        return usuarioNombre;
    }

    public long getCantidadNoConformidades() {
        return cantidadNoConformidades;
    }
}
