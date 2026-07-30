package cl.faret.qcc.noconformidades.dto;

import java.util.List;

public class FiltrosOpcionesResponse {

    private final List<String> clientes;
    private final List<String> tiposPnc;
    private final List<String> responsables;
    private final List<String> categoriasDefecto;
    private final List<String> areas;
    private final List<String> supervisores;
    private final List<String> revisadoPor;

    public FiltrosOpcionesResponse(List<String> clientes, List<String> tiposPnc, List<String> responsables,
            List<String> categoriasDefecto, List<String> areas, List<String> supervisores,
            List<String> revisadoPor) {
        this.clientes = clientes;
        this.tiposPnc = tiposPnc;
        this.responsables = responsables;
        this.categoriasDefecto = categoriasDefecto;
        this.areas = areas;
        this.supervisores = supervisores;
        this.revisadoPor = revisadoPor;
    }

    public List<String> getClientes() {
        return clientes;
    }

    public List<String> getTiposPnc() {
        return tiposPnc;
    }

    public List<String> getResponsables() {
        return responsables;
    }

    public List<String> getCategoriasDefecto() {
        return categoriasDefecto;
    }

    public List<String> getAreas() {
        return areas;
    }

    public List<String> getSupervisores() {
        return supervisores;
    }

    public List<String> getRevisadoPor() {
        return revisadoPor;
    }
}
