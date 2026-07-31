package cl.faret.qcc.dashboard.dto;

// Reutilizado tanto para "cumplimiento por inspector" como para "desempeño individual": el
// Photino usa el mismo patron de calculo para ambos (investigacion confirmada), asi que se evita
// duplicar un DTO identico.
public class CumplimientoInspectorResponse {

    private final Long usuarioId;
    private final String usuarioNombre;
    private final long totalControles;
    private final double porcentajeCumplimiento;

    public CumplimientoInspectorResponse(
            Long usuarioId, String usuarioNombre, long totalControles, double porcentajeCumplimiento) {
        this.usuarioId = usuarioId;
        this.usuarioNombre = usuarioNombre;
        this.totalControles = totalControles;
        this.porcentajeCumplimiento = porcentajeCumplimiento;
    }

    public Long getUsuarioId() {
        return usuarioId;
    }

    public String getUsuarioNombre() {
        return usuarioNombre;
    }

    public long getTotalControles() {
        return totalControles;
    }

    public double getPorcentajeCumplimiento() {
        return porcentajeCumplimiento;
    }
}
