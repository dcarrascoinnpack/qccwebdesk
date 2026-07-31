package cl.faret.qcc.controldocumental.service;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
import cl.faret.qcc.controldocumental.dto.CrearDocumentoRequest;
import cl.faret.qcc.controldocumental.dto.CrearVersionRequest;
import cl.faret.qcc.controldocumental.dto.DocumentoIdResponse;
import cl.faret.qcc.controldocumental.dto.DocumentoListItemResponse;
import cl.faret.qcc.controldocumental.dto.DocumentoListResponse;
import cl.faret.qcc.controldocumental.dto.DocumentoResponse;
import cl.faret.qcc.controldocumental.dto.VersionResponse;
import cl.faret.qcc.controldocumental.entity.Documento;
import cl.faret.qcc.controldocumental.entity.DocumentoVersion;
import cl.faret.qcc.controldocumental.exception.ValidacionDocumentoException;
import cl.faret.qcc.controldocumental.repository.DocumentoRepository;
import cl.faret.qcc.controldocumental.repository.DocumentoVersionRepository;
import cl.faret.qcc.exception.ResourceNotFoundException;

@Service
public class DocumentoService {

    private static final String ESTADO_INICIAL = "VIGENTE";
    private static final String ALCANCE_INICIAL = "INNPACK";
    private static final List<String> ESTADOS_VALIDOS = List.of("VIGENTE", "EN_REVISION", "OBSOLETO");
    private static final List<String> ALCANCES_VALIDOS = List.of("INNPACK", "FARET", "AMBAS");

    // Replica CamposEditables de ControlDocumentalRepository.cs: columnas de la cabecera del
    // documento que noConformidades.update... (controlDocumental.update) puede tocar.
    private static final Set<String> CAMPOS_EDITABLES = Set.of(
            "codigoBase", "tipoDocumento", "area", "nombre", "alcanceEmpresa", "estado",
            "responsable", "ubicacion", "observaciones");

    // A diferencia del Photino (que puede intentar guardar NULL en estas columnas NOT NULL si el
    // cliente envia un string vacio, ver hallazgo A), aca se rechaza explicitamente con 400.
    private static final Set<String> CAMPOS_NO_NULOS =
            Set.of("codigoBase", "tipoDocumento", "nombre", "alcanceEmpresa", "estado");

    private final DocumentoRepository documentoRepository;
    private final DocumentoVersionRepository documentoVersionRepository;
    private final UsuarioRepository usuarioRepository;

    public DocumentoService(
            DocumentoRepository documentoRepository,
            DocumentoVersionRepository documentoVersionRepository,
            UsuarioRepository usuarioRepository) {
        this.documentoRepository = documentoRepository;
        this.documentoVersionRepository = documentoVersionRepository;
        this.usuarioRepository = usuarioRepository;
    }

    public DocumentoListResponse listar(
            String texto, String tipoDocumento, String area, String estado, String alcanceEmpresa,
            Integer page, Integer pageSize) {

        int paginaActual = page != null && page > 0 ? page : 1;
        int tamanoPagina = pageSize != null && pageSize > 0 ? pageSize : 50;

        Specification<Documento> filtro = construirFiltro(texto, tipoDocumento, area, estado, alcanceEmpresa);
        Sort orden = Sort.by(Sort.Order.asc("nombre"), Sort.Order.desc("id"));
        Pageable pageable = PageRequest.of(paginaActual - 1, tamanoPagina, orden);

        Page<Documento> resultado = documentoRepository.findAll(filtro, pageable);
        List<Documento> documentos = resultado.getContent();
        List<Long> ids = documentos.stream().map(Documento::getId).toList();

        // (a, b) -> a: si por una condicion de carrera hubiera mas de una version "vigente" para el
        // mismo documento (riesgo ya documentado del Photino, no corregido aca), se toma cualquiera
        // en vez de que el listado reviente.
        Map<Long, DocumentoVersion> vigentesPorDocumento = documentoVersionRepository
                .findByDocumentoIdInAndEsVersionVigenteTrue(ids).stream()
                .collect(Collectors.toMap(DocumentoVersion::getDocumentoId, v -> v, (a, b) -> a));

        List<DocumentoListItemResponse> items = documentos.stream()
                .map(d -> new DocumentoListItemResponse(d, vigentesPorDocumento.get(d.getId())))
                .toList();

        return new DocumentoListResponse(items, resultado.getTotalElements(), paginaActual, tamanoPagina);
    }

    public DocumentoResponse obtenerPorId(Long id) {
        Documento documento = documentoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Documento no encontrado"));

        List<VersionResponse> versiones = documentoVersionRepository
                .findByDocumentoIdOrderByFechaCreacionDescIdDesc(id).stream()
                .map(VersionResponse::new)
                .toList();

        return new DocumentoResponse(documento, versiones);
    }

    // Crea el documento y su version inicial en una unica transaccion (igual que Crear del
    // Photino). estado/alcanceEmpresa vacios toman el mismo default que la columna en la BD --
    // corrige el hallazgo A (el original podia intentar guardar NULL en una columna NOT NULL).
    @Transactional
    public DocumentoIdResponse crear(CrearDocumentoRequest request) {
        String creadoPor = resolverUsuarioActual();

        String estado = StringUtils.hasText(request.getEstado()) ? request.getEstado() : ESTADO_INICIAL;
        String alcanceEmpresa = StringUtils.hasText(request.getAlcanceEmpresa())
                ? request.getAlcanceEmpresa() : ALCANCE_INICIAL;

        Documento documento = new Documento(
                request.getCodigoBase(),
                request.getTipoDocumento(),
                request.getArea(),
                request.getNombre(),
                alcanceEmpresa,
                estado,
                request.getResponsable(),
                request.getUbicacion(),
                request.getObservaciones(),
                creadoPor);
        Documento guardado = documentoRepository.save(documento);

        LocalDate proximaRevision = request.getProximaRevision() != null
                ? request.getProximaRevision()
                : request.getFechaActualizacion().plusDays(365);

        DocumentoVersion version = new DocumentoVersion(
                guardado.getId(), request.getVersion(), request.getFechaActualizacion(), proximaRevision, creadoPor);
        documentoVersionRepository.save(version);

        return new DocumentoIdResponse(guardado.getId());
    }

    // Actualizacion parcial de la cabecera del documento (mismo enfoque ya usado en
    // NoConformidadService.actualizar): solo se tocan las claves presentes en el JSON recibido.
    // A diferencia del Photino (hallazgo B: update con id inexistente respondia exito falso), aca
    // se valida existencia -> 404. A diferencia del hallazgo A, un campo NOT NULL presente pero
    // vacio se rechaza con 400 en vez de intentar guardar NULL.
    @Transactional
    public DocumentoIdResponse actualizar(Long id, Map<String, Object> camposRecibidos) {
        Documento documento = documentoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Documento no encontrado"));

        Set<String> camposPresentes = new LinkedHashSet<>(camposRecibidos.keySet());
        camposPresentes.retainAll(CAMPOS_EDITABLES);
        if (camposPresentes.isEmpty()) {
            throw new ValidacionDocumentoException("No se recibió ningún campo para actualizar");
        }

        for (String campo : camposPresentes) {
            String valor = aTextoONulo(camposRecibidos.get(campo));

            if (valor == null && CAMPOS_NO_NULOS.contains(campo)) {
                throw new ValidacionDocumentoException("El campo '" + campo + "' no puede quedar vacío");
            }
            if ("estado".equals(campo) && valor != null && !ESTADOS_VALIDOS.contains(valor)) {
                throw new ValidacionDocumentoException(
                        "Estado inválido. Valores permitidos: " + String.join(", ", ESTADOS_VALIDOS));
            }
            if ("alcanceEmpresa".equals(campo) && valor != null && !ALCANCES_VALIDOS.contains(valor)) {
                throw new ValidacionDocumentoException(
                        "Alcance inválido. Valores permitidos: " + String.join(", ", ALCANCES_VALIDOS));
            }

            aplicarCampoDocumento(documento, campo, valor);
        }

        documento.setActualizadoPor(resolverUsuarioActual());
        documentoRepository.save(documento);
        return new DocumentoIdResponse(id);
    }

    // Replica CrearVersion: desmarca la version vigente anterior, inserta la nueva como vigente y
    // toca la auditoria del documento padre, todo en una transaccion. A diferencia del Photino
    // (hallazgo E: no validaba que el documento existiera antes de mutar), aca se valida -> 404.
    @Transactional
    public DocumentoIdResponse crearVersion(Long documentoId, CrearVersionRequest request) {
        Documento documento = documentoRepository.findById(documentoId)
                .orElseThrow(() -> new ResourceNotFoundException("Documento no encontrado"));

        documentoVersionRepository.desmarcarVigente(documentoId);

        LocalDate proximaRevision = request.getProximaRevision() != null
                ? request.getProximaRevision()
                : request.getFechaActualizacion().plusDays(365);

        String usuario = resolverUsuarioActual();
        DocumentoVersion version = new DocumentoVersion(
                documentoId, request.getVersion(), request.getFechaActualizacion(), proximaRevision, usuario);
        DocumentoVersion guardada = documentoVersionRepository.save(version);

        documento.setActualizadoPor(usuario);
        documentoRepository.save(documento);

        return new DocumentoIdResponse(guardada.getId());
    }

    private void aplicarCampoDocumento(Documento documento, String campo, String valor) {
        switch (campo) {
            case "codigoBase" -> documento.setCodigoBase(valor);
            case "tipoDocumento" -> documento.setTipoDocumento(valor);
            case "area" -> documento.setArea(valor);
            case "nombre" -> documento.setNombre(valor);
            case "alcanceEmpresa" -> documento.setAlcanceEmpresa(valor);
            case "estado" -> documento.setEstado(valor);
            case "responsable" -> documento.setResponsable(valor);
            case "ubicacion" -> documento.setUbicacion(valor);
            case "observaciones" -> documento.setObservaciones(valor);
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

    private String resolverUsuarioActual() {
        String codigoUsuario = SecurityContextHolder.getContext().getAuthentication().getName();
        return usuarioRepository.findByCodigoUsuarioAndActivoTrue(codigoUsuario)
                .map(usuario -> usuario.getNombreCompleto())
                .orElse(codigoUsuario);
    }

    private Specification<Documento> construirFiltro(
            String texto, String tipoDocumento, String area, String estado, String alcanceEmpresa) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new java.util.ArrayList<>();

            if (StringUtils.hasText(texto)) {
                Predicate porNombre = cb.like(root.get("nombre"), "%" + texto + "%");
                Predicate porCodigo = cb.like(root.get("codigoBase"), "%" + texto + "%");
                predicates.add(cb.or(porNombre, porCodigo));
            }
            if (StringUtils.hasText(tipoDocumento)) {
                predicates.add(cb.equal(root.get("tipoDocumento"), tipoDocumento));
            }
            if (StringUtils.hasText(area)) {
                predicates.add(cb.equal(root.get("area"), area));
            }
            if (StringUtils.hasText(estado)) {
                predicates.add(cb.equal(root.get("estado"), estado));
            }
            if (StringUtils.hasText(alcanceEmpresa)) {
                predicates.add(cb.equal(root.get("alcanceEmpresa"), alcanceEmpresa));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
