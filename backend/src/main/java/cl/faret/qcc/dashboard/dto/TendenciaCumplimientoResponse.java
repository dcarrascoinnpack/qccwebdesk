package cl.faret.qcc.dashboard.dto;

import java.time.LocalDate;

public class TendenciaCumplimientoResponse {

    private final LocalDate fecha;
    private final double porcentajeCumplimiento;

    public TendenciaCumplimientoResponse(LocalDate fecha, double porcentajeCumplimiento) {
        this.fecha = fecha;
        this.porcentajeCumplimiento = porcentajeCumplimiento;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public double getPorcentajeCumplimiento() {
        return porcentajeCumplimiento;
    }
}
