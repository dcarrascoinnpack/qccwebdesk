package cl.faret.qcc.maquinasseguimiento.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.faret.qcc.maquinasseguimiento.dto.MaquinasSeguimientoResumenResponse;
import cl.faret.qcc.maquinasseguimiento.service.MaquinasSeguimientoService;

@RestController
@RequestMapping("/api/v1/maquinas-seguimiento")
public class MaquinasSeguimientoController {

    private final MaquinasSeguimientoService maquinasSeguimientoService;

    public MaquinasSeguimientoController(MaquinasSeguimientoService maquinasSeguimientoService) {
        this.maquinasSeguimientoService = maquinasSeguimientoService;
    }

    @GetMapping("/resumen")
    public ResponseEntity<MaquinasSeguimientoResumenResponse> resumen(
            @RequestParam(required = false) Long maquinaId,
            @RequestParam(required = false, defaultValue = "false") boolean sinLimite) {

        return ResponseEntity.ok(maquinasSeguimientoService.resumen(maquinaId, sinLimite));
    }
}
