package cl.faret.qcc.laboratorio.dto;

public class CatalogoItemResponse {

    private final Long id;
    private final String nombre;

    public CatalogoItemResponse(Long id, String nombre) {
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
