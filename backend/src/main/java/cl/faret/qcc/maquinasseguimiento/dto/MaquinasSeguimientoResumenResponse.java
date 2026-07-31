package cl.faret.qcc.maquinasseguimiento.dto;

import java.util.List;

import cl.faret.qcc.registroscontrol.dto.RegistroControlItemResponse;

public class MaquinasSeguimientoResumenResponse {

    private final long totalMaquinas;
    private final long maquinasConRegistros;
    private final List<MaquinaCatalogoResponse> maquinas;
    private final Long registrosMaquinaSeleccionada;
    private final Long rechazosMaquinaSeleccionada;
    private final List<RegistroControlItemResponse> registros;

    public MaquinasSeguimientoResumenResponse(long totalMaquinas, long maquinasConRegistros,
            List<MaquinaCatalogoResponse> maquinas, Long registrosMaquinaSeleccionada,
            Long rechazosMaquinaSeleccionada, List<RegistroControlItemResponse> registros) {
        this.totalMaquinas = totalMaquinas;
        this.maquinasConRegistros = maquinasConRegistros;
        this.maquinas = maquinas;
        this.registrosMaquinaSeleccionada = registrosMaquinaSeleccionada;
        this.rechazosMaquinaSeleccionada = rechazosMaquinaSeleccionada;
        this.registros = registros;
    }

    public long getTotalMaquinas() {
        return totalMaquinas;
    }

    public long getMaquinasConRegistros() {
        return maquinasConRegistros;
    }

    public List<MaquinaCatalogoResponse> getMaquinas() {
        return maquinas;
    }

    public Long getRegistrosMaquinaSeleccionada() {
        return registrosMaquinaSeleccionada;
    }

    public Long getRechazosMaquinaSeleccionada() {
        return rechazosMaquinaSeleccionada;
    }

    public List<RegistroControlItemResponse> getRegistros() {
        return registros;
    }
}
