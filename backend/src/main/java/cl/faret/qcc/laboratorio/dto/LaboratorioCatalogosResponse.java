package cl.faret.qcc.laboratorio.dto;

import java.util.List;

public class LaboratorioCatalogosResponse {

    private final List<CatalogoItemResponse> ensayos;
    private final List<CatalogoItemResponse> materiales;

    public LaboratorioCatalogosResponse(List<CatalogoItemResponse> ensayos, List<CatalogoItemResponse> materiales) {
        this.ensayos = ensayos;
        this.materiales = materiales;
    }

    public List<CatalogoItemResponse> getEnsayos() {
        return ensayos;
    }

    public List<CatalogoItemResponse> getMateriales() {
        return materiales;
    }
}
