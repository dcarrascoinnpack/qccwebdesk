package cl.faret.qcc.dashboard.dto;

public class UsuarioFiltroResponse {

    private final Long id;
    private final String nombreCompleto;

    public UsuarioFiltroResponse(Long id, String nombreCompleto) {
        this.id = id;
        this.nombreCompleto = nombreCompleto;
    }

    public Long getId() {
        return id;
    }

    public String getNombreCompleto() {
        return nombreCompleto;
    }
}
