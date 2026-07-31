package cl.faret.qcc.registroscontrol.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import cl.faret.qcc.registroscontrol.entity.ParametroControlVisual;
import cl.faret.qcc.registroscontrol.entity.RegistroAdjunto;
import cl.faret.qcc.registroscontrol.entity.RegistroFallaVisual;
import cl.faret.qcc.registroscontrol.repository.ParametroControlVisualRepository;
import cl.faret.qcc.registroscontrol.repository.RegistroAdjuntoRepository;
import cl.faret.qcc.registroscontrol.repository.RegistroFallaVisualRepository;

// Logica compartida por Dashboard y RegistrosControl (Laboratorio y MaquinasSeguimiento no la
// usan, confirmado en la investigacion): "imagen representativa" (primer adjunto por registro,
// equivalente al MIN(id) del Photino) y "tipo de defecto" (nombres de los parametros de control
// visual con falla en ese registro, equivalente al GROUP_CONCAT del Photino). Se resuelve en lote
// (batch) para evitar el patron N+1 de subconsultas correlacionadas del SQL original.
@Service
public class RegistroControlEnriquecimientoService {

    private final RegistroAdjuntoRepository registroAdjuntoRepository;
    private final RegistroFallaVisualRepository registroFallaVisualRepository;
    private final ParametroControlVisualRepository parametroControlVisualRepository;

    public RegistroControlEnriquecimientoService(
            RegistroAdjuntoRepository registroAdjuntoRepository,
            RegistroFallaVisualRepository registroFallaVisualRepository,
            ParametroControlVisualRepository parametroControlVisualRepository) {
        this.registroAdjuntoRepository = registroAdjuntoRepository;
        this.registroFallaVisualRepository = registroFallaVisualRepository;
        this.parametroControlVisualRepository = parametroControlVisualRepository;
    }

    public Map<Long, RegistroAdjunto> primerAdjuntoPorRegistro(List<Long> registroIds) {
        if (registroIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, RegistroAdjunto> resultado = new LinkedHashMap<>();
        for (RegistroAdjunto adjunto : registroAdjuntoRepository.findByRegistroIdInOrderByIdAsc(registroIds)) {
            resultado.putIfAbsent(adjunto.getRegistroId(), adjunto);
        }
        return resultado;
    }

    // Devuelve, por registro, la lista de nombres de parametros con falla (mismo contenido que el
    // GROUP_CONCAT del Photino, ya separado en lista en vez de string concatenado).
    public Map<Long, List<String>> tipoDefectoPorRegistro(List<Long> registroIds) {
        if (registroIds.isEmpty()) {
            return Map.of();
        }

        List<RegistroFallaVisual> fallas = registroFallaVisualRepository.findByRegistroIdIn(registroIds);
        if (fallas.isEmpty()) {
            return Map.of();
        }

        List<Long> parametroIds = fallas.stream().map(RegistroFallaVisual::getParametroId).distinct().toList();
        Map<Long, String> nombresPorParametro = parametroControlVisualRepository.findAllById(parametroIds).stream()
                .collect(Collectors.toMap(ParametroControlVisual::getId, ParametroControlVisual::getNombre));

        return fallas.stream()
                .collect(Collectors.groupingBy(
                        RegistroFallaVisual::getRegistroId,
                        LinkedHashMap::new,
                        Collectors.mapping(f -> nombresPorParametro.get(f.getParametroId()), Collectors.toList())));
    }
}
