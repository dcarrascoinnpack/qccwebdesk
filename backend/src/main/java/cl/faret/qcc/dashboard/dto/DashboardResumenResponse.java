package cl.faret.qcc.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

import cl.faret.qcc.registroscontrol.dto.RegistroControlItemResponse;

public class DashboardResumenResponse {

    private final long controlesHoy;
    private final long controlesPeriodo;
    private final long noConformidadesDetectadas;
    private final BigDecimal mermaHoy;
    private final long registrosConObservacionHoy;
    private final List<CumplimientoInspectorResponse> cumplimientoPorInspector;
    private final List<NoConformidadPorInspectorResponse> noConformidadesPorInspector;
    private final List<ControlesPorProcesoResponse> controlesPorProceso;
    private final List<TendenciaCumplimientoResponse> tendenciaCumplimiento;
    private final List<CumplimientoInspectorResponse> desempenoIndividual;
    private final List<RegistroControlItemResponse> ultimosRegistros;

    public DashboardResumenResponse(
            long controlesHoy, long controlesPeriodo, long noConformidadesDetectadas, BigDecimal mermaHoy,
            long registrosConObservacionHoy, List<CumplimientoInspectorResponse> cumplimientoPorInspector,
            List<NoConformidadPorInspectorResponse> noConformidadesPorInspector,
            List<ControlesPorProcesoResponse> controlesPorProceso,
            List<TendenciaCumplimientoResponse> tendenciaCumplimiento,
            List<CumplimientoInspectorResponse> desempenoIndividual,
            List<RegistroControlItemResponse> ultimosRegistros) {
        this.controlesHoy = controlesHoy;
        this.controlesPeriodo = controlesPeriodo;
        this.noConformidadesDetectadas = noConformidadesDetectadas;
        this.mermaHoy = mermaHoy;
        this.registrosConObservacionHoy = registrosConObservacionHoy;
        this.cumplimientoPorInspector = cumplimientoPorInspector;
        this.noConformidadesPorInspector = noConformidadesPorInspector;
        this.controlesPorProceso = controlesPorProceso;
        this.tendenciaCumplimiento = tendenciaCumplimiento;
        this.desempenoIndividual = desempenoIndividual;
        this.ultimosRegistros = ultimosRegistros;
    }

    public long getControlesHoy() {
        return controlesHoy;
    }

    public long getControlesPeriodo() {
        return controlesPeriodo;
    }

    public long getNoConformidadesDetectadas() {
        return noConformidadesDetectadas;
    }

    public BigDecimal getMermaHoy() {
        return mermaHoy;
    }

    public long getRegistrosConObservacionHoy() {
        return registrosConObservacionHoy;
    }

    public List<CumplimientoInspectorResponse> getCumplimientoPorInspector() {
        return cumplimientoPorInspector;
    }

    public List<NoConformidadPorInspectorResponse> getNoConformidadesPorInspector() {
        return noConformidadesPorInspector;
    }

    public List<ControlesPorProcesoResponse> getControlesPorProceso() {
        return controlesPorProceso;
    }

    public List<TendenciaCumplimientoResponse> getTendenciaCumplimiento() {
        return tendenciaCumplimiento;
    }

    public List<CumplimientoInspectorResponse> getDesempenoIndividual() {
        return desempenoIndividual;
    }

    public List<RegistroControlItemResponse> getUltimosRegistros() {
        return ultimosRegistros;
    }
}
