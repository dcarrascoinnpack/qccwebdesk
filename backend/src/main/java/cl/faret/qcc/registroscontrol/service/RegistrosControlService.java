package cl.faret.qcc.registroscontrol.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import cl.faret.qcc.auth.entity.Usuario;
import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.registroscontrol.dto.RegistroControlItemResponse;
import cl.faret.qcc.registroscontrol.dto.RegistroControlListResponse;
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

// Reemplaza RegistrosControlHandler.cs / RegistrosControlRepository.cs. Sin RBAC (mismo criterio
// que No Conformidades / Control Documental, confirmado por la investigacion: ninguno de los 4
// modulos del Cluster B requiere sesion).
@Service
public class RegistrosControlService {

    private static final int PAGE_SIZE_DEFAULT = 20;

    private final RegistroControlRepository registroControlRepository;
    private final UsuarioRepository usuarioRepository;
    private final ProcesoRepository procesoRepository;
    private final MaquinaRepository maquinaRepository;
    private final FormularioControlRepository formularioControlRepository;
    private final EstadoCatalogoRepository estadoCatalogoRepository;
    private final RegistroControlEnriquecimientoService enriquecimientoService;

    public RegistrosControlService(
            RegistroControlRepository registroControlRepository,
            UsuarioRepository usuarioRepository,
            ProcesoRepository procesoRepository,
            MaquinaRepository maquinaRepository,
            FormularioControlRepository formularioControlRepository,
            EstadoCatalogoRepository estadoCatalogoRepository,
            RegistroControlEnriquecimientoService enriquecimientoService) {
        this.registroControlRepository = registroControlRepository;
        this.usuarioRepository = usuarioRepository;
        this.procesoRepository = procesoRepository;
        this.maquinaRepository = maquinaRepository;
        this.formularioControlRepository = formularioControlRepository;
        this.estadoCatalogoRepository = estadoCatalogoRepository;
        this.enriquecimientoService = enriquecimientoService;
    }

    // Replica el comportamiento especial de RegistrosControlService.cs: si viene filtro `np`, se
    // ignora la paginacion y se trae todo en una sola "pagina" (comentario original: "la respuesta
    // debe reflejar 'todo en una sola pagina'").
    public RegistroControlListResponse listar(
            Long id, LocalDate fechaDesde, LocalDate fechaHasta, String np, String turno,
            Long estadoId, Integer page, Integer limit) {
        return listar(id, fechaDesde, fechaHasta, np, turno, estadoId, page, limit, null);
    }

    // Sobrecarga con filtro de `area`, usada por Dashboard/RegistrosProduccion para su listado de
    // "ultimos registros" (RegistrosControl -- el listado plano -- no filtra por area en el
    // original, por eso el metodo de 8 argumentos sigue sin este filtro).
    public RegistroControlListResponse listar(
            Long id, LocalDate fechaDesde, LocalDate fechaHasta, String np, String turno,
            Long estadoId, Integer page, Integer limit, List<String> areas) {

        Specification<RegistroControl> filtro = RegistroControlFiltros.construir(
                id, fechaDesde, fechaHasta, np, turno, estadoId, null, null, areas);
        Sort orden = Sort.by(Sort.Order.desc("fechaRegistro"), Sort.Order.desc("id"));

        List<RegistroControl> registros;
        long total;
        int paginaRespuesta;
        int tamanoRespuesta;

        if (StringUtils.hasText(np)) {
            registros = registroControlRepository.findAll(filtro, orden);
            total = registros.size();
            paginaRespuesta = 1;
            tamanoRespuesta = (int) total;
        } else {
            int paginaActual = page != null && page > 0 ? page : 1;
            int tamanoPagina = limit != null && limit > 0 ? limit : PAGE_SIZE_DEFAULT;
            Pageable pageable = PageRequest.of(paginaActual - 1, tamanoPagina, orden);
            Page<RegistroControl> resultado = registroControlRepository.findAll(filtro, pageable);
            registros = resultado.getContent();
            total = resultado.getTotalElements();
            paginaRespuesta = paginaActual;
            tamanoRespuesta = tamanoPagina;
        }

        return new RegistroControlListResponse(mapearItems(registros), total, paginaRespuesta, tamanoRespuesta);
    }

    private List<RegistroControlItemResponse> mapearItems(List<RegistroControl> registros) {
        if (registros.isEmpty()) {
            return List.of();
        }

        List<Long> ids = registros.stream().map(RegistroControl::getId).toList();
        Map<Long, String> usuarios = usuarioRepository
                .findByIdIn(registros.stream().map(RegistroControl::getUsuarioId).distinct().toList()).stream()
                .collect(Collectors.toMap(Usuario::getId, Usuario::getNombreCompleto));
        Map<Long, String> procesos = procesoRepository
                .findAllById(registros.stream().map(RegistroControl::getProcesoId).distinct().toList()).stream()
                .collect(Collectors.toMap(Proceso::getId, Proceso::getNombre));
        Map<Long, String> maquinas = maquinaRepository
                .findAllById(registros.stream().map(RegistroControl::getMaquinaId).distinct().toList()).stream()
                .collect(Collectors.toMap(Maquina::getId, Maquina::getNombre));
        Map<Long, String> formularios = formularioControlRepository
                .findAllById(registros.stream()
                        .map(RegistroControl::getFormularioId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(FormularioControl::getId, FormularioControl::getNombre));
        Map<Long, String> estados = estadoCatalogoRepository
                .findAllById(registros.stream().map(RegistroControl::getEstadoId).distinct().toList()).stream()
                .collect(Collectors.toMap(EstadoCatalogo::getId, EstadoCatalogo::getNombre));

        var adjuntos = enriquecimientoService.primerAdjuntoPorRegistro(ids);
        var tiposDefecto = enriquecimientoService.tipoDefectoPorRegistro(ids);

        return registros.stream()
                .map(r -> new RegistroControlItemResponse(
                        r.getId(),
                        r.getFechaRegistro(),
                        r.getHoraRegistro(),
                        TurnoCalculator.calcular(r.getHoraRegistro()),
                        r.getUsuarioId(),
                        usuarios.get(r.getUsuarioId()),
                        r.getProcesoId(),
                        procesos.get(r.getProcesoId()),
                        r.getMaquinaId(),
                        maquinas.get(r.getMaquinaId()),
                        r.getFormularioId(),
                        r.getFormularioId() != null ? formularios.get(r.getFormularioId()) : null,
                        r.getNp(),
                        r.getEstadoId(),
                        estados.get(r.getEstadoId()),
                        r.getObservacion(),
                        r.getTipoMerma(),
                        r.getCantidadMerma(),
                        r.getEstadoValidacion(),
                        r.getFechaValidacion(),
                        r.getUsuarioValidacion(),
                        r.getCreadoEn(),
                        adjuntos.containsKey(r.getId()) ? adjuntos.get(r.getId()).getRutaArchivo() : null,
                        tiposDefecto.getOrDefault(r.getId(), List.of())))
                .toList();
    }
}
