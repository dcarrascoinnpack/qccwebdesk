package cl.faret.qcc.laboratorio.dto;

import java.time.LocalDate;
import java.util.List;

public class LaboratorioResumenResponse {

    private final long ensayosHoy;
    private final long ensayosPeriodo;
    private final long tiposEnsayo;
    private final long materialesAnalizados;
    private final boolean mostrandoHistorico;
    private final LocalDate fechaUltimoRegistro;
    private final List<RegistroEnsayoItemResponse> registros;

    public LaboratorioResumenResponse(long ensayosHoy, long ensayosPeriodo, long tiposEnsayo,
            long materialesAnalizados, boolean mostrandoHistorico, LocalDate fechaUltimoRegistro,
            List<RegistroEnsayoItemResponse> registros) {
        this.ensayosHoy = ensayosHoy;
        this.ensayosPeriodo = ensayosPeriodo;
        this.tiposEnsayo = tiposEnsayo;
        this.materialesAnalizados = materialesAnalizados;
        this.mostrandoHistorico = mostrandoHistorico;
        this.fechaUltimoRegistro = fechaUltimoRegistro;
        this.registros = registros;
    }

    public long getEnsayosHoy() {
        return ensayosHoy;
    }

    public long getEnsayosPeriodo() {
        return ensayosPeriodo;
    }

    public long getTiposEnsayo() {
        return tiposEnsayo;
    }

    public long getMaterialesAnalizados() {
        return materialesAnalizados;
    }

    public boolean isMostrandoHistorico() {
        return mostrandoHistorico;
    }

    public LocalDate getFechaUltimoRegistro() {
        return fechaUltimoRegistro;
    }

    public List<RegistroEnsayoItemResponse> getRegistros() {
        return registros;
    }
}
