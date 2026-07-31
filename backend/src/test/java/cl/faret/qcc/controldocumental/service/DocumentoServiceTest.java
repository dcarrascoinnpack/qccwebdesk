package cl.faret.qcc.controldocumental.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import cl.faret.qcc.auth.entity.Usuario;
import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.controldocumental.dto.CrearDocumentoRequest;
import cl.faret.qcc.controldocumental.dto.CrearVersionRequest;
import cl.faret.qcc.controldocumental.dto.DocumentoIdResponse;
import cl.faret.qcc.controldocumental.dto.DocumentoListResponse;
import cl.faret.qcc.controldocumental.entity.Documento;
import cl.faret.qcc.controldocumental.entity.DocumentoVersion;
import cl.faret.qcc.controldocumental.exception.ValidacionDocumentoException;
import cl.faret.qcc.controldocumental.repository.DocumentoRepository;
import cl.faret.qcc.controldocumental.repository.DocumentoVersionRepository;
import cl.faret.qcc.exception.ResourceNotFoundException;

@ExtendWith(MockitoExtension.class)
class DocumentoServiceTest {

    @Mock
    private DocumentoRepository documentoRepository;

    @Mock
    private DocumentoVersionRepository documentoVersionRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    private DocumentoService service;

    @BeforeEach
    void setUp() {
        service = new DocumentoService(documentoRepository, documentoVersionRepository, usuarioRepository);
    }

    @AfterEach
    void limpiarContextoDeSeguridad() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listarUsaPaginaYTamanoPorDefectoCuandoNoSeEnvian() {
        Page<Documento> paginaVacia = new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
        when(documentoRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(paginaVacia);

        DocumentoListResponse respuesta = service.listar(null, null, null, null, null, null, null);

        assertThat(respuesta.getPage()).isEqualTo(1);
        assertThat(respuesta.getPageSize()).isEqualTo(50);
        assertThat(respuesta.getItems()).isEmpty();
    }

    @Test
    void listarCombinaCadaDocumentoConSuVersionVigente() {
        Documento documento = documentoConId(1L, "PRO-001");
        Page<Documento> pagina = new PageImpl<>(List.of(documento), PageRequest.of(0, 50), 1);
        when(documentoRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(pagina);
        DocumentoVersion vigente = new DocumentoVersion(1L, "V02", LocalDate.now(), null, "jperez");
        when(documentoVersionRepository.findByDocumentoIdInAndEsVersionVigenteTrue(List.of(1L)))
                .thenReturn(List.of(vigente));

        DocumentoListResponse respuesta = service.listar(null, null, null, null, null, null, null);

        assertThat(respuesta.getItems()).hasSize(1);
        assertThat(respuesta.getItems().get(0).getVersionVigente()).isEqualTo("V02");
    }

    @Test
    void obtenerPorIdLanzaResourceNotFoundSiNoExiste() {
        when(documentoRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtenerPorId(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void obtenerPorIdDevuelveElDocumentoConSuHistorialDeVersiones() {
        Documento documento = documentoConId(1L, "PRO-001");
        when(documentoRepository.findById(1L)).thenReturn(Optional.of(documento));
        DocumentoVersion version = new DocumentoVersion(1L, "V01", LocalDate.now(), null, "jperez");
        when(documentoVersionRepository.findByDocumentoIdOrderByFechaCreacionDescIdDesc(1L))
                .thenReturn(List.of(version));

        var respuesta = service.obtenerPorId(1L);

        assertThat(respuesta.getCodigoBase()).isEqualTo("PRO-001");
        assertThat(respuesta.getVersiones()).hasSize(1);
    }

    @Test
    void crearAplicaLosDefaultsDeEstadoYAlcanceCuandoVienenVacios() {
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());
        stubSaveDocumentoAsignandoId(1L);

        CrearDocumentoRequest request = crearRequestValido();

        service.crear(request);

        ArgumentCaptor<Documento> captor = ArgumentCaptor.forClass(Documento.class);
        org.mockito.Mockito.verify(documentoRepository).save(captor.capture());
        assertThat(captor.getValue().getEstado()).isEqualTo("VIGENTE");
        assertThat(captor.getValue().getAlcanceEmpresa()).isEqualTo("INNPACK");
    }

    @Test
    void crearAutocalculaProximaRevisionA365DiasSiNoSeEnvia() {
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());
        stubSaveDocumentoAsignandoId(1L);

        CrearDocumentoRequest request = crearRequestValido();
        LocalDate fecha = LocalDate.of(2026, 1, 1);
        request.setFechaActualizacion(fecha);

        service.crear(request);

        ArgumentCaptor<DocumentoVersion> captor = ArgumentCaptor.forClass(DocumentoVersion.class);
        org.mockito.Mockito.verify(documentoVersionRepository).save(captor.capture());
        assertThat(captor.getValue().getProximaRevision()).isEqualTo(fecha.plusDays(365));
    }

    @Test
    void actualizarLanzaResourceNotFoundSiNoExiste() {
        when(documentoRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizar(99L, Map.of("nombre", "Nuevo")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void actualizarLanzaValidacionSiNoSeEnviaNingunCampoEditable() {
        when(documentoRepository.findById(1L)).thenReturn(Optional.of(documentoConId(1L, "PRO-001")));

        assertThatThrownBy(() -> service.actualizar(1L, Map.of("campoQueNoExiste", "x")))
                .isInstanceOf(ValidacionDocumentoException.class);
    }

    @Test
    void actualizarRechazaEstadoVacioEnUnCampoNotNull() {
        when(documentoRepository.findById(1L)).thenReturn(Optional.of(documentoConId(1L, "PRO-001")));

        assertThatThrownBy(() -> service.actualizar(1L, Map.of("estado", "   ")))
                .isInstanceOf(ValidacionDocumentoException.class);
    }

    @Test
    void actualizarPermiteVaciarUnCampoNullable() {
        Documento documento = documentoConId(1L, "PRO-001");
        when(documentoRepository.findById(1L)).thenReturn(Optional.of(documento));
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());

        service.actualizar(1L, Map.of("area", "   "));

        assertThat(documento.getArea()).isNull();
    }

    @Test
    void actualizarLanzaValidacionSiEstadoEsInvalido() {
        when(documentoRepository.findById(1L)).thenReturn(Optional.of(documentoConId(1L, "PRO-001")));

        assertThatThrownBy(() -> service.actualizar(1L, Map.of("estado", "NO_EXISTE")))
                .isInstanceOf(ValidacionDocumentoException.class);
    }

    @Test
    void actualizarLanzaValidacionSiAlcanceEsInvalido() {
        when(documentoRepository.findById(1L)).thenReturn(Optional.of(documentoConId(1L, "PRO-001")));

        assertThatThrownBy(() -> service.actualizar(1L, Map.of("alcanceEmpresa", "NO_EXISTE")))
                .isInstanceOf(ValidacionDocumentoException.class);
    }

    @Test
    void actualizarSoloTocaLosCamposPresentesYResuelveActualizadoPorDesdeElJwt() {
        Documento documento = documentoConId(1L, "PRO-001");
        when(documentoRepository.findById(1L)).thenReturn(Optional.of(documento));
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());

        DocumentoIdResponse respuesta = service.actualizar(1L, Map.of("nombre", "Nombre nuevo"));

        assertThat(documento.getNombre()).isEqualTo("Nombre nuevo");
        assertThat(documento.getActualizadoPor()).isEqualTo("jperez");
        assertThat(respuesta.getId()).isEqualTo(1L);
    }

    @Test
    void crearVersionLanzaResourceNotFoundSiElDocumentoNoExiste() {
        when(documentoRepository.findById(99L)).thenReturn(Optional.empty());
        CrearVersionRequest request = crearVersionRequestValido();

        assertThatThrownBy(() -> service.crearVersion(99L, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void crearVersionDesmarcaLaVigenteAnteriorYActualizaElDocumentoPadre() {
        Documento documento = documentoConId(1L, "PRO-001");
        when(documentoRepository.findById(1L)).thenReturn(Optional.of(documento));
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());
        when(documentoVersionRepository.save(any(DocumentoVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.crearVersion(1L, crearVersionRequestValido());

        org.mockito.Mockito.verify(documentoVersionRepository).desmarcarVigente(1L);
        assertThat(documento.getActualizadoPor()).isEqualTo("jperez");
    }

    private void stubSaveDocumentoAsignandoId(long id) {
        when(documentoRepository.save(any(Documento.class))).thenAnswer(invocation -> {
            Documento documento = invocation.getArgument(0);
            if (documento.getId() == null) {
                documento.setId(id);
            }
            return documento;
        });
    }

    private void autenticarComo(String codigoUsuario) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(codigoUsuario, null, List.of()));
    }

    private Documento documentoConId(Long id, String codigoBase) {
        Documento documento = new Documento(
                codigoBase, "Procedimiento", "Calidad", "Nombre del documento",
                "INNPACK", "VIGENTE", "Responsable", "ruta/doc.pdf", null, "jperez");
        documento.setId(id);
        return documento;
    }

    private CrearDocumentoRequest crearRequestValido() {
        CrearDocumentoRequest request = new CrearDocumentoRequest();
        request.setCodigoBase("PRO-001");
        request.setTipoDocumento("Procedimiento");
        request.setNombre("Nombre del documento");
        request.setVersion("V01");
        request.setFechaActualizacion(LocalDate.now());
        return request;
    }

    private CrearVersionRequest crearVersionRequestValido() {
        CrearVersionRequest request = new CrearVersionRequest();
        request.setVersion("V02");
        request.setFechaActualizacion(LocalDate.now());
        return request;
    }
}
