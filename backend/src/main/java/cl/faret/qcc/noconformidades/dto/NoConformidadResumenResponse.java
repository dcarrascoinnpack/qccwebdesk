package cl.faret.qcc.noconformidades.dto;

public class NoConformidadResumenResponse {

    private final long total;
    private final long abiertas;
    private final long cerradas;
    private final long criticas;

    public NoConformidadResumenResponse(long total, long abiertas, long cerradas, long criticas) {
        this.total = total;
        this.abiertas = abiertas;
        this.cerradas = cerradas;
        this.criticas = criticas;
    }

    public long getTotal() {
        return total;
    }

    public long getAbiertas() {
        return abiertas;
    }

    public long getCerradas() {
        return cerradas;
    }

    public long getCriticas() {
        return criticas;
    }
}
