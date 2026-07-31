package cl.faret.qcc.noconformidades.dto;

import jakarta.validation.constraints.NotBlank;

public class CrearSeguimientoRequest {

    @NotBlank
    private String comentario;

    public String getComentario() {
        return comentario;
    }

    public void setComentario(String comentario) {
        this.comentario = comentario;
    }
}
