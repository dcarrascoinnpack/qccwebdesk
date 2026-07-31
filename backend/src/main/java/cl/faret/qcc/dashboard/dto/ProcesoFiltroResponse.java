package cl.faret.qcc.dashboard.dto;

public class ProcesoFiltroResponse {

    private final Long id;
    private final String nombre;

    public ProcesoFiltroResponse(Long id, String nombre) {
        this.id = id;
        this.nombre = nombre;
    }

    public Long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }
}
