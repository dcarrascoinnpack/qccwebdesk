package cl.faret.qcc.noconformidades.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import cl.faret.qcc.noconformidades.dto.AccionCorrectivaResponse;
import cl.faret.qcc.noconformidades.dto.ActualizarAccionRequest;
import cl.faret.qcc.noconformidades.dto.ActualizarGestionRequest;
import cl.faret.qcc.noconformidades.dto.AnalisisResponse;
import cl.faret.qcc.noconformidades.dto.CerrarNoConformidadRequest;
import cl.faret.qcc.noconformidades.dto.CrearAccionRequest;
import cl.faret.qcc.noconformidades.dto.CrearNoConformidadRequest;
import cl.faret.qcc.noconformidades.dto.CrearNoConformidadResponse;
import cl.faret.qcc.noconformidades.dto.CrearSeguimientoRequest;
import cl.faret.qcc.noconformidades.dto.FiltrosOpcionesResponse;
import cl.faret.qcc.noconformidades.dto.GuardarAnalisisRequest;
import cl.faret.qcc.noconformidades.dto.NoConformidadIdResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadListResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadResumenResponse;
import cl.faret.qcc.noconformidades.dto.SeguimientoResponse;
import cl.faret.qcc.noconformidades.entity.NcAccionCorrectiva;
import cl.faret.qcc.noconformidades.entity.NcAnalisis;
import cl.faret.qcc.noconformidades.entity.NcSeguimiento;
import cl.faret.qcc.noconformidades.entity.NoConformidad;
import cl.faret.qcc.noconformidades.exception.ValidacionNoConformidadException;
import cl.faret.qcc.noconformidades.repository.NcAccionCorrectivaRepository;
import cl.faret.qcc.noconformidades.repository.NcAnalisisRepository;
import cl.faret.qcc.noconformidades.repository.NcSeguimientoRepository;
import cl.faret.qcc.noconformidades.repository.NoConformidadRepository;

@Service
public class NoConformidadService {

    private static final String ESTADO_INICIAL = "ABIERTA";
    private static final String ESTADO_GESTION_INICIAL = "PENDIENTE";
    private static final String ESTADO_GESTION_CERRADA = "CERRADA";
    private static final String SEVERIDAD_CRITICA = "ALTA";

    private static final List<String> NIVELES_VALIDOS = List.of("Crítico", "Mayor", "Menor");
    private static final List<String> ESTADOS_GESTION_VALIDOS =
            List.of("PENDIENTE", "ASIGNADA", "EN_GESTION", "CERRADA");

    // Replica CamposEditables de NoConformidadesRepository.cs: columnas que noConformidades.update
    // puede tocar (excluye codigo/estado/gestion/cierre/auditoria, que son operaciones aparte).
    private static final Set<String> CAMPOS_EDITABLES_TEXTO = Set.of(
            "tipo", "origen", "titulo", "descripcion", "severidad", "proceso", "norma", "reportadoPor",
            "tipoPnc", "npNv", "cliente", "codigoProducto", "producto", "descripcionDefecto",
            "categoriaDefecto", "nivel", "tipoFalla", "area", "maquina", "operador", "supervisor",
            "revisadoPor", "impacto", "observacion", "causaRaiz", "accionesCorrectivas",
            "verificacionSeguimiento");
    private static final Set<String> CAMPOS_EDITABLES_FECHA =
            Set.of("fechaDeteccion", "fechaIngreso", "fechaSalida", "fechaFabricacion");
    private static final Set<String> CAMPOS_EDITABLES_DECIMAL =
            Set.of("cantRequerida", "cantRechazada", "cantRecuperada", "pncReal");
    private static final Set<String> CAMPOS_EDITABLES_TODOS = new LinkedHashSet<>();

    static {
        CAMPOS_EDITABLES_TODOS.addAll(CAMPOS_EDITABLES_TEXTO);
        CAMPOS_EDITABLES_TODOS.addAll(CAMPOS_EDITABLES_FECHA);
        CAMPOS_EDITABLES_TODOS.addAll(CAMPOS_EDITABLES_DECIMAL);
    }

    private final NoConformidadRepository noConformidadRepository;
    private final UsuarioRepository usuarioRepository;
    private final NcSeguimientoRepository ncSeguimientoRepository;
    private final NcAnalisisRepository ncAnalisisRepository;
    private final NcAccionCorrectivaRepository ncAccionCorrectivaRepository;

    public NoConformidadService(
            NoConformidadRepository noConformidadRepository,
            UsuarioRepository usuarioRepository,
            NcSeguimientoRepository ncSeguimientoRepository,
            NcAnalisisRepository ncAnalisisRepository,
            NcAccionCorrectivaRepository ncAccionCorrectivaRepository) {
        this.noConformidadRepository = noConformidadRepository;
        this.usuarioRepository = usuarioRepository;
        this.ncSeguimientoRepository = ncSeguimientoRepository;
        this.ncAnalisisRepository = ncAnalisisRepository;
        this.ncAccionCorrectivaRepository = ncAccionCorrectivaRepository;
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

    // Actualizacion parcial: replica LeerCamposEditables/Actualizar del Photino. Solo se tocan las
    // claves realmente presentes en el JSON recibido -- por eso el parametro es un Map crudo y no
    // un DTO tipado: un DTO normal no distingue "clave ausente" de "clave enviada en null", y esa
    // distincion es la que define si una columna se sobrescribe o no.
    @Transactional
    public NoConformidadIdResponse actualizar(Long id, Map<String, Object> camposRecibidos) {
        NoConformidad noConformidad = noConformidadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No conformidad no encontrada"));

        Set<String> camposPresentes = new LinkedHashSet<>(camposRecibidos.keySet());
        camposPresentes.retainAll(CAMPOS_EDITABLES_TODOS);
        if (camposPresentes.isEmpty()) {
            throw new ValidacionNoConformidadException("No se recibió ningún campo para actualizar");
        }

        if (camposPresentes.contains("nivel")) {
            String nivel = aTextoONulo(camposRecibidos.get("nivel"));
            if (nivel != null && !NIVELES_VALIDOS.contains(nivel)) {
                throw new ValidacionNoConformidadException(
                        "Nivel inválido. Valores permitidos: " + String.join(", ", NIVELES_VALIDOS));
            }
        }

        for (String campo : camposPresentes) {
            aplicarCampoEditable(noConformidad, campo, camposRecibidos.get(campo));
        }

        noConformidad.setPctRecuperacion(
                calcularPctRecuperacion(noConformidad.getCantRechazada(), noConformidad.getCantRecuperada()));
        noConformidad.setActualizadoPor(resolverUsuarioActual());

        noConformidadRepository.save(noConformidad);
        return new NoConformidadIdResponse(id);
    }

    // Replica ActualizarGestion del Photino: sobrescritura total e incondicional de las 3 columnas
    // de gestion (decision de fidelidad ya acordada), a diferencia de la actualizacion parcial de
    // arriba.
    @Transactional
    public NoConformidadIdResponse actualizarGestion(Long id, ActualizarGestionRequest request) {
        NoConformidad noConformidad = noConformidadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No conformidad no encontrada"));

        String estadoGestion = request.getEstadoGestion();
        if (StringUtils.hasText(estadoGestion) && !ESTADOS_GESTION_VALIDOS.contains(estadoGestion)) {
            throw new ValidacionNoConformidadException("Estado de gestión inválido. Valores permitidos: "
                    + String.join(", ", ESTADOS_GESTION_VALIDOS));
        }

        noConformidad.setResponsable(request.getResponsable());
        noConformidad.setEstadoGestion(estadoGestion);
        noConformidad.setFechaCompromiso(request.getFechaCompromiso());
        noConformidad.setActualizadoPor(resolverUsuarioActual());

        noConformidadRepository.save(noConformidad);
        return new NoConformidadIdResponse(id);
    }

    // Replica Cerrar del Photino: sin guardas de transicion (se puede cerrar una NC ya cerrada, sin
    // acciones correctivas), decision de fidelidad ya acordada. cerradoPor se resuelve desde el JWT
    // (mismo criterio que creadoPor en crear()), no se confia en el valor que envie el cliente.
    @Transactional
    public NoConformidadIdResponse cerrar(Long id, CerrarNoConformidadRequest request) {
        NoConformidad noConformidad = noConformidadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No conformidad no encontrada"));

        noConformidad.setEstadoGestion(ESTADO_GESTION_CERRADA);
        noConformidad.setCerradoPor(resolverUsuarioActual());
        noConformidad.setComentarioCierre(request.getComentarioCierre());
        noConformidad.setFechaCierre(LocalDateTime.now());

        noConformidadRepository.save(noConformidad);
        return new NoConformidadIdResponse(id);
    }

    // Replica ListarSeguimiento: no valida que la NC exista (si el id no existe, simplemente
    // devuelve una lista vacia), igual que el Photino.
    public List<SeguimientoResponse> listarSeguimiento(Long id) {
        return ncSeguimientoRepository.findByNoConformidadIdOrderByCreadoEnDescIdDesc(id).stream()
                .map(SeguimientoResponse::new)
                .toList();
    }

    // A diferencia del Photino (que deja fallar la FK con un error crudo de MySQL si el id no
    // existe), aca se valida antes y se devuelve 404 -- decision de fidelidad ya acordada, mismo
    // resultado final (la operacion falla) sin exponer errores de SQL.
    @Transactional
    public NoConformidadIdResponse crearSeguimiento(Long id, CrearSeguimientoRequest request) {
        if (!noConformidadRepository.existsById(id)) {
            throw new ResourceNotFoundException("No conformidad no encontrada");
        }

        NcSeguimiento seguimiento = new NcSeguimiento(id, request.getComentario(), resolverUsuarioActual());
        ncSeguimientoRepository.save(seguimiento);

        return new NoConformidadIdResponse(id);
    }

    // Replica ObtenerAnalisis: no valida que la NC exista, si no hay analisis registrado
    // simplemente devuelve null (no es un error, es el estado "sin analisis todavia").
    public AnalisisResponse obtenerAnalisis(Long id) {
        return ncAnalisisRepository.findTopByNoConformidadIdOrderByIdDesc(id)
                .map(AnalisisResponse::new)
                .orElse(null);
    }

    // Replica GuardarAnalisis: upsert sobre el analisis mas reciente de la NC (en la practica solo
    // existe uno por NC). A diferencia del Photino (que deja fallar la FK con un error crudo si el
    // id no existe), aca se valida antes y se devuelve 404 -- mismo criterio ya aplicado en
    // crearSeguimiento.
    @Transactional
    public NoConformidadIdResponse guardarAnalisis(Long id, GuardarAnalisisRequest request) {
        if (!noConformidadRepository.existsById(id)) {
            throw new ResourceNotFoundException("No conformidad no encontrada");
        }

        String usuario = resolverUsuarioActual();
        Optional<NcAnalisis> existente = ncAnalisisRepository.findTopByNoConformidadIdOrderByIdDesc(id);

        NcAnalisis analisis;
        if (existente.isPresent()) {
            analisis = existente.get();
            analisis.setMetodologia(request.getMetodologia());
            analisis.setProblemaDetectado(request.getProblemaDetectado());
            analisis.setPorque1(request.getPorque1());
            analisis.setPorque2(request.getPorque2());
            analisis.setPorque3(request.getPorque3());
            analisis.setPorque4(request.getPorque4());
            analisis.setPorque5(request.getPorque5());
            analisis.setCausaRaiz(request.getCausaRaiz());
            analisis.setConclusion(request.getConclusion());
            analisis.setActualizadoPor(usuario);
        } else {
            analisis = new NcAnalisis(
                    id,
                    request.getMetodologia(),
                    request.getProblemaDetectado(),
                    request.getPorque1(),
                    request.getPorque2(),
                    request.getPorque3(),
                    request.getPorque4(),
                    request.getPorque5(),
                    request.getCausaRaiz(),
                    request.getConclusion(),
                    usuario);
        }

        NcAnalisis guardado = ncAnalisisRepository.save(analisis);
        return new NoConformidadIdResponse(guardado.getId());
    }

    // Replica ListarAcciones: sin validar que la NC exista, orden ORDER BY id DESC.
    public List<AccionCorrectivaResponse> listarAcciones(Long id) {
        return ncAccionCorrectivaRepository.findByNoConformidadIdOrderByIdDesc(id).stream()
                .map(AccionCorrectivaResponse::new)
                .toList();
    }

    // Replica CrearAccion. creadoPor se resuelve desde el JWT (mismo criterio que en crear() y
    // crearSeguimiento()); se valida que la NC exista antes de insertar (mismo criterio que
    // guardarAnalisis/crearSeguimiento). A diferencia del Photino (que no devuelve el id de la
    // accion creada), aca se devuelve -- vacio del original, no una decision de negocio.
    @Transactional
    public NoConformidadIdResponse crearAccion(Long id, CrearAccionRequest request) {
        if (!noConformidadRepository.existsById(id)) {
            throw new ResourceNotFoundException("No conformidad no encontrada");
        }

        NcAccionCorrectiva accion = new NcAccionCorrectiva(
                id,
                request.getAnalisisId(),
                request.getDescripcion(),
                request.getResponsable(),
                request.getFechaLimite(),
                request.getPrioridad(),
                resolverUsuarioActual());

        NcAccionCorrectiva guardada = ncAccionCorrectivaRepository.save(accion);
        return new NoConformidadIdResponse(guardada.getId());
    }

    // Replica ActualizarAccion: sin guardas de transicion de estado (decision de fidelidad ya
    // acordada para el modulo), sin relacionar accionId con el id de la NC (igual que el original).
    // A diferencia del Photino (que actualiza sin verificar que la accion exista, dejando un
    // UPDATE de 0 filas pasar como exito silencioso), aca se valida antes y se devuelve 404.
    @Transactional
    public NoConformidadIdResponse actualizarAccion(Long accionId, ActualizarAccionRequest request) {
        NcAccionCorrectiva accion = ncAccionCorrectivaRepository.findById(accionId)
                .orElseThrow(() -> new ResourceNotFoundException("Acción correctiva no encontrada"));

        accion.setDescripcion(request.getDescripcion());
        accion.setResponsable(request.getResponsable());
        accion.setFechaLimite(request.getFechaLimite());
        accion.setPrioridad(request.getPrioridad());
        accion.setEstado(request.getEstado());
        accion.setActualizadoPor(resolverUsuarioActual());

        ncAccionCorrectivaRepository.save(accion);
        return new NoConformidadIdResponse(accionId);
    }

    private void aplicarCampoEditable(NoConformidad noConformidad, String campo, Object valor) {
        if (CAMPOS_EDITABLES_DECIMAL.contains(campo)) {
            BigDecimal decimal = aDecimalONulo(valor);
            switch (campo) {
                case "cantRequerida" -> noConformidad.setCantRequerida(decimal);
                case "cantRechazada" -> noConformidad.setCantRechazada(decimal);
                case "cantRecuperada" -> noConformidad.setCantRecuperada(decimal);
                case "pncReal" -> noConformidad.setPncReal(decimal);
                default -> throw new IllegalStateException("Campo decimal no mapeado: " + campo);
            }
            return;
        }

        if (CAMPOS_EDITABLES_FECHA.contains(campo)) {
            LocalDate fecha = aFechaONula(valor);
            switch (campo) {
                case "fechaDeteccion" -> noConformidad.setFechaDeteccion(fecha);
                case "fechaIngreso" -> noConformidad.setFechaIngreso(fecha);
                case "fechaSalida" -> noConformidad.setFechaSalida(fecha);
                case "fechaFabricacion" -> noConformidad.setFechaFabricacion(fecha);
                default -> throw new IllegalStateException("Campo fecha no mapeado: " + campo);
            }
            return;
        }

        String texto = aTextoONulo(valor);
        switch (campo) {
            case "tipo" -> noConformidad.setTipo(texto);
            case "origen" -> noConformidad.setOrigen(texto);
            case "titulo" -> noConformidad.setTitulo(texto);
            case "descripcion" -> noConformidad.setDescripcion(texto);
            case "severidad" -> noConformidad.setSeveridad(texto);
            case "proceso" -> noConformidad.setProceso(texto);
            case "norma" -> noConformidad.setNorma(texto);
            case "reportadoPor" -> noConformidad.setReportadoPor(texto);
            case "tipoPnc" -> noConformidad.setTipoPnc(texto);
            case "npNv" -> noConformidad.setNpNv(texto);
            case "cliente" -> noConformidad.setCliente(texto);
            case "codigoProducto" -> noConformidad.setCodigoProducto(texto);
            case "producto" -> noConformidad.setProducto(texto);
            case "descripcionDefecto" -> noConformidad.setDescripcionDefecto(texto);
            case "categoriaDefecto" -> noConformidad.setCategoriaDefecto(texto);
            case "nivel" -> noConformidad.setNivel(texto);
            case "tipoFalla" -> noConformidad.setTipoFalla(texto);
            case "area" -> noConformidad.setArea(texto);
            case "maquina" -> noConformidad.setMaquina(texto);
            case "operador" -> noConformidad.setOperador(texto);
            case "supervisor" -> noConformidad.setSupervisor(texto);
            case "revisadoPor" -> noConformidad.setRevisadoPor(texto);
            case "impacto" -> noConformidad.setImpacto(texto);
            case "observacion" -> noConformidad.setObservacion(texto);
            case "causaRaiz" -> noConformidad.setCausaRaiz(texto);
            case "accionesCorrectivas" -> noConformidad.setAccionesCorrectivas(texto);
            case "verificacionSeguimiento" -> noConformidad.setVerificacionSeguimiento(texto);
            default -> throw new IllegalStateException("Campo editable no mapeado: " + campo);
        }
    }

    private String aTextoONulo(Object valor) {
        if (valor == null) {
            return null;
        }
        String texto = valor.toString();
        return StringUtils.hasText(texto) ? texto : null;
    }

    private BigDecimal aDecimalONulo(Object valor) {
        if (valor == null) {
            return null;
        }
        try {
            return new BigDecimal(valor.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private LocalDate aFechaONula(Object valor) {
        if (valor == null) {
            return null;
        }
        String texto = valor.toString();
        if (!StringUtils.hasText(texto)) {
            return null;
        }
        try {
            return LocalDate.parse(texto);
        } catch (DateTimeParseException ex) {
            return null;
        }
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
