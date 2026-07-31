package cl.faret.qcc.maquinasseguimiento.dto;

public class MaquinaCatalogoResponse {

    private final Long id;
    private final String nombre;
    private final String procesoNombre;

    public MaquinaCatalogoResponse(Long id, String nombre, String procesoNombre) {
        this.id = id;
        this.nombre = nombre;
        this.procesoNombre = procesoNombre;
    }

    public Long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getProcesoNombre() {
        return procesoNombre;
    }
}
