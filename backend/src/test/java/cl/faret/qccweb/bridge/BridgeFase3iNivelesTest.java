package cl.faret.qccweb.bridge;

import java.util.List;

/**
 * Fase 3i — noConformidades.catalogos.niveles.crear (largo 20) SOLO admin_ti, más estricto que Photino (operador/admin):
 * el nivel define la severidad y el filtro Nivel tiene opciones fijas. Contrato común en {@link CatalogoCrearBase}.
 */
class BridgeFase3iNivelesTest extends CatalogoCrearBase {

    BridgeFase3iNivelesTest() {
        super("niveles", 20, List.of("admin_ti"));
    }
}
