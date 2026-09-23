package cl.faret.qccweb.version;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Impide arrancar el gateway con un snapshot cuyo contract check quedó BLOQUEANTE (acciones en
 * REVISAR o SOLO_WEB): así una versión web incompatible no puede llegar a deploy aunque alguien
 * empaquete igual. Solo afecta a la web; Photino no depende de esto.
 *
 * Desactivable solo para desarrollo local (QCC_WEB_CONTRACT_BLOQUEAR=false) al trabajar sobre una
 * acción que está en revisión.
 */
@Component
public class ContractGuard implements InitializingBean {

    private final FrontendManifest manifest;
    private final boolean bloquear;

    public ContractGuard(FrontendManifest manifest, @Value("${qcc.web.contract.bloquear-si-incompatible:true}") boolean bloquear) {
        this.manifest = manifest;
        this.bloquear = bloquear;
    }

    @Override
    public void afterPropertiesSet() {
        verificar(manifest.contrato(), bloquear);
    }

    static void verificar(JsonNode contrato, boolean bloquear) {
        if (!bloquear || contrato == null) {
            return;
        }
        JsonNode bloqueante = contrato.get("bloqueante");
        if (bloqueante != null && bloqueante.asBoolean(false)) {
            throw new IllegalStateException("El contract check Photino/Web del snapshot es BLOQUEANTE ("
                    + FrontendManifest.texto(contrato, "texto") + "). Revisa web-dist/contract/contract-report.md; "
                    + "el gateway no arranca con una versión web incompatible.");
        }
    }
}
