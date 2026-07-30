package cl.faret.qcc.noconformidades.dto;

public class CrearNoConformidadResponse {

    private final Long id;
    private final String codigo;

    public CrearNoConformidadResponse(Long id, String codigo) {
        this.id = id;
        this.codigo = codigo;
    }

    public Long getId() {
        return id;
    }

    public String getCodigo() {
        return codigo;
    }
}
