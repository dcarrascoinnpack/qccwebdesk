package cl.faret.qcc.noconformidades.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.exception.ResourceNotFoundException;
import cl.faret.qcc.noconformidades.dto.CrearNoConformidadRequest;
import cl.faret.qcc.noconformidades.dto.CrearNoConformidadResponse;
import cl.faret.qcc.noconformidades.dto.FiltrosOpcionesResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadListResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadResumenResponse;
import cl.faret.qcc.noconformidades.entity.NoConformidad;
import cl.faret.qcc.noconformidades.repository.NoConformidadRepository;

@Service
public class NoConformidadService {

    private static final String ESTADO_INICIAL = "ABIERTA";
    private static final String ESTADO_GESTION_INICIAL = "PENDIENTE";
    private static final String ESTADO_GESTION_CERRADA = "CERRADA";
    private static final String SEVERIDAD_CRITICA = "ALTA";

    private final NoConformidadRepository noConformidadRepository;
    private final UsuarioRepository usuarioRepository;

    public NoConformidadService(
            NoConformidadRepository noConformidadRepository, UsuarioRepository usuarioRepository) {
        this.noConformidadRepository = noConformidadRepository;
        this.usuarioRepository = usuarioRepository;
    }

    public NoConformidadListResponse listar(
            String cliente, String tipoPnc, String nivel, String estadoGestion, String responsable,
            LocalDate fechaDesde, LocalDate fechaHasta, Integer page, Integer pageSize) {

        int paginaActual = page != null && page > 0 ? page : 1;
        int tamanoPagina = pageSize != null && pageSize > 0 ? pageSize : 50;

        Specification<NoConformidad> filtro =
                construirFiltro(cliente, tipoPnc, nivel, estadoGestion, responsable, fechaDesde, fechaHasta);
        Sort orden = Sort.by(Sort.Order.desc("fechaIngreso"), Sort.Order.desc("id"));
        Pageable pageable = PageRequest.of(paginaActual - 1, tamanoPagina, orden);

        Page<NoConformidad> resultado = noConformidadRepository.findAll(filtro, pageable);
        List<NoConformidadResponse> items =
                resultado.getContent().stream().map(NoConformidadResponse::new).toList();

        return new NoConformidadListResponse(items, resultado.getTotalElements(), paginaActual, tamanoPagina);
    }

    public NoConformidadResumenResponse resumen(
            String cliente, String tipoPnc, String nivel, String estadoGestion, String responsable,
            LocalDate fechaDesde, LocalDate fechaHasta) {

        Specification<NoConformidad> base =
                construirFiltro(cliente, tipoPnc, nivel, estadoGestion, responsable, fechaDesde, fechaHasta);

        long total = noConformidadRepository.count(base);
        long abiertas = noConformidadRepository.count(
                base.and((root, query, cb) -> cb.notEqual(root.get("estadoGestion"), ESTADO_GESTION_CERRADA)));
        long cerradas = noConformidadRepository.count(
                base.and((root, query, cb) -> cb.equal(root.get("estadoGestion"), ESTADO_GESTION_CERRADA)));
        long criticas = noConformidadRepository.count(
                base.and((root, query, cb) -> cb.equal(root.get("severidad"), SEVERIDAD_CRITICA)));

        return new NoConformidadResumenResponse(total, abiertas, cerradas, criticas);
    }

    public FiltrosOpcionesResponse filtrosOpciones() {
        return new FiltrosOpcionesResponse(
                noConformidadRepository.findDistinctClientes(),
                noConformidadRepository.findDistinctTiposPnc(),
                noConformidadRepository.findDistinctResponsables(),
                noConformidadRepository.findDistinctCategoriasDefecto(),
                noConformidadRepository.findDistinctAreas(),
                noConformidadRepository.findDistinctSupervisores(),
                noConformidadRepository.findDistinctRevisadoPor());
    }

    public NoConformidadResponse obtenerPorId(Long id) {
        NoConformidad noConformidad = noConformidadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No conformidad no encontrada"));
        return new NoConformidadResponse(noConformidad);
    }

    // Codigo correlativo NC-{anio}-{id:00000}: se inserta con un codigo temporal unico y, ya con el
    // id autogenerado, se actualiza al codigo final -- todo en una sola transaccion, para que un
    // error en cualquier punto revierta ambos pasos y no persista ningun codigo temporal.
    @Transactional
    public CrearNoConformidadResponse crear(CrearNoConformidadRequest request) {
        String creadoPor = resolverUsuarioActual();
        BigDecimal pctRecuperacion =
                calcularPctRecuperacion(request.getCantRechazada(), request.getCantRecuperada());

        NoConformidad noConformidad = new NoConformidad(
                codigoTemporal(),
                request.getTipo(),
                request.getOrigen(),
                request.getTitulo(),
                request.getDescripcion(),
                request.getSeveridad(),
                request.getProceso(),
                request.getNorma(),
                request.getReportadoPor(),
                request.getFechaDeteccion(),
                ESTADO_INICIAL,
                ESTADO_GESTION_INICIAL,
                request.getTipoPnc(),
                request.getFechaIngreso(),
                request.getFechaSalida(),
                request.getNpNv(),
                request.getCliente(),
                request.getCodigoProducto(),
                request.getProducto(),
                request.getCantRequerida(),
                request.getCantRechazada(),
                request.getCantRecuperada(),
                request.getPncReal(),
                pctRecuperacion,
                request.getFechaFabricacion(),
                request.getDescripcionDefecto(),
                request.getCategoriaDefecto(),
                request.getNivel(),
                request.getTipoFalla(),
                request.getArea(),
                request.getMaquina(),
                request.getOperador(),
                request.getSupervisor(),
                request.getRevisadoPor(),
                request.getImpacto(),
                request.getObservacion(),
                request.getCausaRaiz(),
                request.getAccionesCorrectivas(),
                request.getVerificacionSeguimiento(),
                creadoPor);

        NoConformidad guardada = noConformidadRepository.save(noConformidad);

        String codigoFinal = "NC-" + Year.now().getValue() + "-" + String.format("%05d", guardada.getId());
        guardada.setCodigo(codigoFinal);
        noConformidadRepository.save(guardada);

        return new CrearNoConformidadResponse(guardada.getId(), codigoFinal);
    }

    private String codigoTemporal() {
        return "TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private BigDecimal calcularPctRecuperacion(BigDecimal cantRechazada, BigDecimal cantRecuperada) {
        if (cantRechazada == null || cantRecuperada == null || cantRechazada.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return cantRecuperada
                .multiply(BigDecimal.valueOf(100))
                .divide(cantRechazada, 2, RoundingMode.HALF_EVEN);
    }

    private String resolverUsuarioActual() {
        String codigoUsuario = SecurityContextHolder.getContext().getAuthentication().getName();
        return usuarioRepository.findByCodigoUsuarioAndActivoTrue(codigoUsuario)
                .map(usuario -> usuario.getNombreCompleto())
                .orElse(codigoUsuario);
    }

    private Specification<NoConformidad> construirFiltro(
            String cliente, String tipoPnc, String nivel, String estadoGestion, String responsable,
            LocalDate fechaDesde, LocalDate fechaHasta) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(cliente)) {
                predicates.add(cb.like(root.get("cliente"), "%" + cliente + "%"));
            }
            if (StringUtils.hasText(tipoPnc)) {
                predicates.add(cb.like(root.get("tipoPnc"), "%" + tipoPnc + "%"));
            }
            if (StringUtils.hasText(nivel)) {
                predicates.add(cb.equal(root.get("nivel"), nivel));
            }
            if (StringUtils.hasText(estadoGestion)) {
                predicates.add(cb.equal(root.get("estadoGestion"), estadoGestion));
            }
            if (StringUtils.hasText(responsable)) {
                predicates.add(cb.like(root.get("responsable"), "%" + responsable + "%"));
            }
            if (fechaDesde != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("fechaIngreso"), fechaDesde));
            }
            if (fechaHasta != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("fechaIngreso"), fechaHasta));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
