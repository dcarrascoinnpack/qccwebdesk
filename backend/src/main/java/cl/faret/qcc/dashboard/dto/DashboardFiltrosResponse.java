package cl.faret.qcc.dashboard.dto;

import java.util.List;

public class DashboardFiltrosResponse {

    private final List<UsuarioFiltroResponse> usuarios;
    private final List<ProcesoFiltroResponse> procesos;

    public DashboardFiltrosResponse(List<UsuarioFiltroResponse> usuarios, List<ProcesoFiltroResponse> procesos) {
        this.usuarios = usuarios;
        this.procesos = procesos;
    }

    public List<UsuarioFiltroResponse> getUsuarios() {
        return usuarios;
    }

    public List<ProcesoFiltroResponse> getProcesos() {
        return procesos;
    }
}
