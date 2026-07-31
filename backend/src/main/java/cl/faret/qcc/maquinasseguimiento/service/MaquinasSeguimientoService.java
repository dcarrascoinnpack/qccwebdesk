package cl.faret.qcc.maquinasseguimiento.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import cl.faret.qcc.auth.entity.Usuario;
import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.maquinasseguimiento.dto.MaquinaCatalogoResponse;
import cl.faret.qcc.maquinasseguimiento.dto.MaquinasSeguimientoResumenResponse;
import cl.faret.qcc.registroscontrol.dto.RegistroControlItemResponse;
import cl.faret.qcc.registroscontrol.entity.EstadoCatalogo;
import cl.faret.qcc.registroscontrol.entity.FormularioControl;
import cl.faret.qcc.registroscontrol.entity.Maquina;
import cl.faret.qcc.registroscontrol.entity.Proceso;
import cl.faret.qcc.registroscontrol.entity.RegistroControl;
import cl.faret.qcc.registroscontrol.repository.EstadoCatalogoRepository;
import cl.faret.qcc.registroscontrol.repository.FormularioControlRepository;
import cl.faret.qcc.registroscontrol.repository.MaquinaRepository;
import cl.faret.qcc.registroscontrol.repository.ProcesoRepository;
import cl.faret.qcc.registroscontrol.repository.RegistroControlRepository;
import cl.faret.qcc.registroscontrol.service.TurnoCalculator;

// Reemplaza MaquinasSeguimientoHandler.cs / MaquinasSeguimientoRepository.cs.
//
// Decisiones de fidelidad ya acordadas:
// - Usa la columna `np` real (el Photino mostraba `codigo_producto` bajo la etiqueta "NP" -- bug
//   confirmado, se unifica con el resto de los modulos del Cluster B).
// - El "rechazados" se resuelve contra `estado_id` (buscando una sola vez el id del estado
//   "Rechazado"), no comparando `LOWER(nombre)='rechazado'` fila por fila como el original.
// Nota: a diferencia de RegistrosControl/Dashboard, ninguna consulta de este modulo filtra por
// `eliminado` (asi esta en el Photino, confirmado en la investigacion).
@Service
public class MaquinasSeguimientoService {

    private static final String ESTADO_RECHAZADO = "Rechazado";
    private static final int LIMITE_REGISTROS_DEFECTO = 300;

    private final MaquinaRepository maquinaRepository;
    private final ProcesoRepository procesoRepository;
    private final RegistroControlRepository registroControlRepository;
    private final EstadoCatalogoRepository estadoCatalogoRepository;
    private final FormularioControlRepository formularioControlRepository;
    private final UsuarioRepository usuarioRepository;

    public MaquinasSeguimientoService(
            MaquinaRepository maquinaRepository,
            ProcesoRepository procesoRepository,
            RegistroControlRepository registroControlRepository,
            EstadoCatalogoRepository estadoCatalogoRepository,
            FormularioControlRepository formularioControlRepository,
            UsuarioRepository usuarioRepository) {
        this.maquinaRepository = maquinaRepository;
        this.procesoRepository = procesoRepository;
        this.registroControlRepository = registroControlRepository;
        this.estadoCatalogoRepository = estadoCatalogoRepository;
        this.formularioControlRepository = formularioControlRepository;
        this.usuarioRepository = usuarioRepository;
    }

    public MaquinasSeguimientoResumenResponse resumen(Long maquinaId, boolean sinLimite) {
        long totalMaquinas = maquinaRepository.countByActivoTrue();
        long maquinasConRegistros = registroControlRepository.countDistinctMaquinaId();

        List<Maquina> maquinas = maquinaRepository.findByActivoTrueOrderByNombreAsc();
        Map<Long, String> nombresProcesos = procesoRepository
                .findAllById(maquinas.stream().map(Maquina::getProcesoId).distinct().toList()).stream()
                .collect(Collectors.toMap(Proceso::getId, Proceso::getNombre));
        List<MaquinaCatalogoResponse> catalogo = maquinas.stream()
                .map(m -> new MaquinaCatalogoResponse(m.getId(), m.getNombre(), nombresProcesos.get(m.getProcesoId())))
                .toList();

        if (maquinaId == null || maquinaId <= 0) {
            return new MaquinasSeguimientoResumenResponse(
                    totalMaquinas, maquinasConRegistros, catalogo, null, null, List.of());
        }

        long registrosMaquina = registroControlRepository.countByMaquinaId(maquinaId);
        Long idEstadoRechazado = estadoCatalogoRepository.findByNombreIgnoreCase(ESTADO_RECHAZADO)
                .map(EstadoCatalogo::getId)
                .orElse(null);
        long rechazosMaquina = idEstadoRechazado != null
                ? registroControlRepository.countByMaquinaIdAndEstadoId(maquinaId, idEstadoRechazado)
                : 0;

        List<RegistroControl> registros =
                registroControlRepository.findByMaquinaIdOrderByFechaRegistroDescIdDesc(maquinaId);
        List<RegistroControl> limitados = sinLimite
                ? registros
                : registros.stream().limit(LIMITE_REGISTROS_DEFECTO).toList();

        List<RegistroControlItemResponse> items = mapearItems(limitados);

        return new MaquinasSeguimientoResumenResponse(
                totalMaquinas, maquinasConRegistros, catalogo, registrosMaquina, rechazosMaquina, items);
    }

    private List<RegistroControlItemResponse> mapearItems(List<RegistroControl> registros) {
        if (registros.isEmpty()) {
            return List.of();
        }

        Map<Long, String> nombresUsuarios = usuarioRepository
                .findByIdIn(registros.stream().map(RegistroControl::getUsuarioId).distinct().toList()).stream()
                .collect(Collectors.toMap(Usuario::getId, Usuario::getNombreCompleto));
        Map<Long, String> nombresProcesos = procesoRepository
                .findAllById(registros.stream().map(RegistroControl::getProcesoId).distinct().toList()).stream()
                .collect(Collectors.toMap(Proceso::getId, Proceso::getNombre));
        Map<Long, String> nombresMaquinas = maquinaRepository
                .findAllById(registros.stream().map(RegistroControl::getMaquinaId).distinct().toList()).stream()
                .collect(Collectors.toMap(Maquina::getId, Maquina::getNombre));
        Map<Long, String> nombresFormularios = formularioControlRepository
                .findAllById(registros.stream()
                        .map(RegistroControl::getFormularioId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(FormularioControl::getId, FormularioControl::getNombre));
        Map<Long, String> nombresEstados = estadoCatalogoRepository
                .findAllById(registros.stream().map(RegistroControl::getEstadoId).distinct().toList()).stream()
                .collect(Collectors.toMap(EstadoCatalogo::getId, EstadoCatalogo::getNombre));

        return registros.stream()
                .map(r -> new RegistroControlItemResponse(
                        r.getId(),
                        r.getFechaRegistro(),
                        r.getHoraRegistro(),
                        TurnoCalculator.calcular(r.getHoraRegistro()),
                        r.getUsuarioId(),
                        nombresUsuarios.get(r.getUsuarioId()),
                        r.getProcesoId(),
                        nombresProcesos.get(r.getProcesoId()),
                        r.getMaquinaId(),
                        nombresMaquinas.get(r.getMaquinaId()),
                        r.getFormularioId(),
                        r.getFormularioId() != null ? nombresFormularios.get(r.getFormularioId()) : null,
                        r.getNp(),
                        r.getEstadoId(),
                        nombresEstados.get(r.getEstadoId()),
                        r.getObservacion(),
                        r.getTipoMerma(),
                        r.getCantidadMerma(),
                        r.getEstadoValidacion(),
                        r.getFechaValidacion(),
                        r.getUsuarioValidacion(),
                        r.getCreadoEn(),
                        null,
                        List.of()))
                .toList();
    }
}
