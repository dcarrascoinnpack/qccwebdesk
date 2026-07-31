package cl.faret.qcc.noconformidades.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
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
import cl.faret.qcc.exception.ResourceNotFoundException;
import cl.faret.qcc.noconformidades.dto.ActualizarAccionRequest;
import cl.faret.qcc.noconformidades.dto.ActualizarGestionRequest;
import cl.faret.qcc.noconformidades.dto.CerrarNoConformidadRequest;
import cl.faret.qcc.noconformidades.dto.CrearAccionRequest;
import cl.faret.qcc.noconformidades.dto.CrearNoConformidadRequest;
import cl.faret.qcc.noconformidades.dto.CrearNoConformidadResponse;
import cl.faret.qcc.noconformidades.dto.CrearSeguimientoRequest;
import cl.faret.qcc.noconformidades.dto.GuardarAnalisisRequest;
import cl.faret.qcc.noconformidades.dto.NoConformidadIdResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadListResponse;
import cl.faret.qcc.noconformidades.dto.NoConformidadResumenResponse;
import cl.faret.qcc.noconformidades.entity.NcAccionCorrectiva;
import cl.faret.qcc.noconformidades.entity.NcAnalisis;
import cl.faret.qcc.noconformidades.entity.NcSeguimiento;
import cl.faret.qcc.noconformidades.entity.NoConformidad;
import cl.faret.qcc.noconformidades.exception.ValidacionNoConformidadException;
import cl.faret.qcc.noconformidades.repository.NcAccionCorrectivaRepository;
import cl.faret.qcc.noconformidades.repository.NcAnalisisRepository;
import cl.faret.qcc.noconformidades.repository.NcSeguimientoRepository;
import cl.faret.qcc.noconformidades.repository.NoConformidadRepository;

@ExtendWith(MockitoExtension.class)
class NoConformidadServiceTest {

    @Mock
    private NoConformidadRepository noConformidadRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private NcSeguimientoRepository ncSeguimientoRepository;

    @Mock
    private NcAnalisisRepository ncAnalisisRepository;

    @Mock
    private NcAccionCorrectivaRepository ncAccionCorrectivaRepository;

    private NoConformidadService service;

    @BeforeEach
    void setUp() {
        service = new NoConformidadService(noConformidadRepository, usuarioRepository, ncSeguimientoRepository,
                ncAnalisisRepository, ncAccionCorrectivaRepository);
    }

    @AfterEach
    void limpiarContextoDeSeguridad() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listarUsaPaginaYTamanoPorDefectoCuandoNoSeEnvian() {
        Page<NoConformidad> paginaVacia = new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
        when(noConformidadRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(paginaVacia);

        NoConformidadListResponse respuesta =
                service.listar(null, null, null, null, null, null, null, null, null);

        assertThat(respuesta.getPage()).isEqualTo(1);
        assertThat(respuesta.getPageSize()).isEqualTo(50);
        assertThat(respuesta.getItems()).isEmpty();
    }

    @Test
    void listarIgnoraPageYPageSizeInvalidosYUsaDefaults() {
        Page<NoConformidad> paginaVacia = new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
        when(noConformidadRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(paginaVacia);

        NoConformidadListResponse respuesta =
                service.listar(null, null, null, null, null, null, null, 0, -5);

        assertThat(respuesta.getPage()).isEqualTo(1);
        assertThat(respuesta.getPageSize()).isEqualTo(50);
    }

    @Test
    void listarMapeaLosItemsDeLaPaginaDevueltaPorElRepositorio() {
        NoConformidad nc = ncConId(1L, "NC-2026-00001");
        Page<NoConformidad> pagina = new PageImpl<>(List.of(nc), PageRequest.of(0, 50), 1);
        when(noConformidadRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(pagina);

        NoConformidadListResponse respuesta =
                service.listar("acme", null, null, null, null, null, null, 1, 50);

        assertThat(respuesta.getTotal()).isEqualTo(1);
        assertThat(respuesta.getItems()).hasSize(1);
        assertThat(respuesta.getItems().get(0).getCodigo()).isEqualTo("NC-2026-00001");
    }

    @Test
    void resumenCalculaLosCuatroContadoresConLosFiltrosAplicados() {
        when(noConformidadRepository.count(any(Specification.class)))
                .thenReturn(10L, 6L, 4L, 2L);

        NoConformidadResumenResponse resumen =
                service.resumen(null, null, null, null, null, null, null);

        assertThat(resumen.getTotal()).isEqualTo(10);
        assertThat(resumen.getAbiertas()).isEqualTo(6);
        assertThat(resumen.getCerradas()).isEqualTo(4);
        assertThat(resumen.getCriticas()).isEqualTo(2);
    }

    @Test
    void filtrosOpcionesDelegaEnLosSieteMetodosDelRepositorio() {
        when(noConformidadRepository.findDistinctClientes()).thenReturn(List.of("Cliente A"));
        when(noConformidadRepository.findDistinctTiposPnc()).thenReturn(List.of("Cuarentena"));
        when(noConformidadRepository.findDistinctResponsables()).thenReturn(List.of("Juan Perez"));
        when(noConformidadRepository.findDistinctCategoriasDefecto()).thenReturn(List.of("Dimensional"));
        when(noConformidadRepository.findDistinctAreas()).thenReturn(List.of("Produccion"));
        when(noConformidadRepository.findDistinctSupervisores()).thenReturn(List.of("Ana Soto"));
        when(noConformidadRepository.findDistinctRevisadoPor()).thenReturn(List.of("Luis Diaz"));

        var opciones = service.filtrosOpciones();

        assertThat(opciones.getClientes()).containsExactly("Cliente A");
        assertThat(opciones.getTiposPnc()).containsExactly("Cuarentena");
        assertThat(opciones.getResponsables()).containsExactly("Juan Perez");
        assertThat(opciones.getCategoriasDefecto()).containsExactly("Dimensional");
        assertThat(opciones.getAreas()).containsExactly("Produccion");
        assertThat(opciones.getSupervisores()).containsExactly("Ana Soto");
        assertThat(opciones.getRevisadoPor()).containsExactly("Luis Diaz");
    }

    @Test
    void obtenerPorIdLanzaResourceNotFoundSiNoExiste() {
        when(noConformidadRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtenerPorId(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("No conformidad no encontrada");
    }

    @Test
    void obtenerPorIdDevuelveElMapeoCuandoExiste() {
        when(noConformidadRepository.findById(1L)).thenReturn(Optional.of(ncConId(1L, "NC-2026-00001")));

        var respuesta = service.obtenerPorId(1L);

        assertThat(respuesta.getCodigo()).isEqualTo("NC-2026-00001");
    }

    @Test
    void crearAsignaCodigoCorrelativoConElAnioActualYElIdEnCincoDigitos() {
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());
        stubSaveAsignandoId(7L);

        CrearNoConformidadResponse respuesta = service.crear(crearRequestValido());

        String anioActual = String.valueOf(Year.now().getValue());
        assertThat(respuesta.getCodigo()).isEqualTo("NC-" + anioActual + "-00007");
        assertThat(respuesta.getId()).isEqualTo(7L);
    }

    @Test
    void crearResuelveCreadoPorDesdeElNombreCompletoDelUsuarioAutenticado() {
        autenticarComo("jperez");
        Usuario usuarioActivo = new Usuario(1L, "jperez", "Juan Perez", "hash", "operador", true, null);
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez"))
                .thenReturn(Optional.of(usuarioActivo));
        stubSaveAsignandoId(1L);

        service.crear(crearRequestValido());

        ArgumentCaptor<NoConformidad> captor = ArgumentCaptor.forClass(NoConformidad.class);
        org.mockito.Mockito.verify(noConformidadRepository, org.mockito.Mockito.atLeastOnce())
                .save(captor.capture());
        assertThat(captor.getAllValues().get(0).getCreadoPor()).isEqualTo("Juan Perez");
    }

    @Test
    void crearUsaElCodigoDeUsuarioComoCreadoPorSiNoEncuentraUnUsuarioActivo() {
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());
        stubSaveAsignandoId(1L);

        service.crear(crearRequestValido());

        ArgumentCaptor<NoConformidad> captor = ArgumentCaptor.forClass(NoConformidad.class);
        org.mockito.Mockito.verify(noConformidadRepository, org.mockito.Mockito.atLeastOnce())
                .save(captor.capture());
        assertThat(captor.getAllValues().get(0).getCreadoPor()).isEqualTo("jperez");
    }

    @Test
    void crearCalculaElPorcentajeDeRecuperacionCuandoHayCantidadesValidas() {
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue(anyString())).thenReturn(Optional.empty());
        stubSaveAsignandoId(1L);

        CrearNoConformidadRequest request = crearRequestValido();
        request.setCantRechazada(new BigDecimal("200"));
        request.setCantRecuperada(new BigDecimal("150"));

        service.crear(request);

        ArgumentCaptor<NoConformidad> captor = ArgumentCaptor.forClass(NoConformidad.class);
        org.mockito.Mockito.verify(noConformidadRepository, org.mockito.Mockito.atLeastOnce())
                .save(captor.capture());
        assertThat(captor.getAllValues().get(0).getPctRecuperacion()).isEqualByComparingTo("75.00");
    }

    @Test
    void crearDejaElPorcentajeDeRecuperacionNuloSiCantRechazadaEsCeroOAusente() {
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue(anyString())).thenReturn(Optional.empty());
        stubSaveAsignandoId(1L);

        CrearNoConformidadRequest request = crearRequestValido();
        request.setCantRechazada(BigDecimal.ZERO);
        request.setCantRecuperada(new BigDecimal("10"));

        service.crear(request);

        ArgumentCaptor<NoConformidad> captor = ArgumentCaptor.forClass(NoConformidad.class);
        org.mockito.Mockito.verify(noConformidadRepository, org.mockito.Mockito.atLeastOnce())
                .save(captor.capture());
        assertThat(captor.getAllValues().get(0).getPctRecuperacion()).isNull();
    }

    @Test
    void actualizarLanzaResourceNotFoundSiNoExiste() {
        when(noConformidadRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizar(99L, Map.of("titulo", "Nuevo")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void actualizarLanzaValidacionSiNoSeEnviaNingunCampoEditable() {
        when(noConformidadRepository.findById(1L)).thenReturn(Optional.of(ncConId(1L, "NC-2026-00001")));

        assertThatThrownBy(() -> service.actualizar(1L, Map.of("campoQueNoExiste", "x")))
                .isInstanceOf(ValidacionNoConformidadException.class)
                .hasMessage("No se recibió ningún campo para actualizar");
    }

    @Test
    void actualizarSoloTocaLosCamposPresentesEnElPayload() {
        NoConformidad nc = ncConId(1L, "NC-2026-00001");
        when(noConformidadRepository.findById(1L)).thenReturn(Optional.of(nc));
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());

        service.actualizar(1L, Map.of("titulo", "Titulo nuevo"));

        assertThat(nc.getTitulo()).isEqualTo("Titulo nuevo");
        assertThat(nc.getDescripcion()).isEqualTo("Descripcion");
    }

    @Test
    void actualizarPoneEnNuloUnCampoTextoEnviadoVacio() {
        NoConformidad nc = ncConId(1L, "NC-2026-00001");
        when(noConformidadRepository.findById(1L)).thenReturn(Optional.of(nc));
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());

        Map<String, Object> campos = new java.util.HashMap<>();
        campos.put("norma", "   ");
        service.actualizar(1L, campos);

        assertThat(nc.getNorma()).isNull();
    }

    @Test
    void actualizarLanzaValidacionSiNivelEsInvalido() {
        when(noConformidadRepository.findById(1L)).thenReturn(Optional.of(ncConId(1L, "NC-2026-00001")));

        assertThatThrownBy(() -> service.actualizar(1L, Map.of("nivel", "Grave")))
                .isInstanceOf(ValidacionNoConformidadException.class);
    }

    @Test
    void actualizarRecalculaElPorcentajeDeRecuperacionConLosValoresResultantes() {
        NoConformidad nc = ncConId(1L, "NC-2026-00001");
        nc.setCantRechazada(new BigDecimal("5"));
        when(noConformidadRepository.findById(1L)).thenReturn(Optional.of(nc));
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());

        service.actualizar(1L, Map.of("cantRecuperada", new BigDecimal("2.5")));

        assertThat(nc.getPctRecuperacion()).isEqualByComparingTo("50.00");
        assertThat(nc.getActualizadoPor()).isEqualTo("jperez");
    }

    @Test
    void actualizarGestionLanzaResourceNotFoundSiNoExiste() {
        when(noConformidadRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizarGestion(99L, new ActualizarGestionRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void actualizarGestionLanzaValidacionSiEstadoGestionEsInvalido() {
        when(noConformidadRepository.findById(1L)).thenReturn(Optional.of(ncConId(1L, "NC-2026-00001")));
        ActualizarGestionRequest request = new ActualizarGestionRequest();
        request.setEstadoGestion("NO_EXISTE");

        assertThatThrownBy(() -> service.actualizarGestion(1L, request))
                .isInstanceOf(ValidacionNoConformidadException.class);
    }

    @Test
    void actualizarGestionSobreescribeLosTresCamposAunqueVenganNulos() {
        NoConformidad nc = ncConId(1L, "NC-2026-00001");
        nc.setResponsable("Responsable viejo");
        when(noConformidadRepository.findById(1L)).thenReturn(Optional.of(nc));
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());

        service.actualizarGestion(1L, new ActualizarGestionRequest());

        assertThat(nc.getResponsable()).isNull();
        assertThat(nc.getEstadoGestion()).isNull();
        assertThat(nc.getActualizadoPor()).isEqualTo("jperez");
    }

    @Test
    void cerrarLanzaResourceNotFoundSiNoExiste() {
        when(noConformidadRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cerrar(99L, new CerrarNoConformidadRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cerrarFijaEstadoGestionCerradaYResuelveCerradoPorDesdeElJwt() {
        NoConformidad nc = ncConId(1L, "NC-2026-00001");
        when(noConformidadRepository.findById(1L)).thenReturn(Optional.of(nc));
        autenticarComo("jperez");
        Usuario usuarioActivo = new Usuario(1L, "jperez", "Juan Perez", "hash", "operador", true, null);
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.of(usuarioActivo));
        CerrarNoConformidadRequest request = new CerrarNoConformidadRequest();
        request.setComentarioCierre("Cierre de prueba");

        service.cerrar(1L, request);

        assertThat(nc.getEstadoGestion()).isEqualTo("CERRADA");
        assertThat(nc.getCerradoPor()).isEqualTo("Juan Perez");
        assertThat(nc.getComentarioCierre()).isEqualTo("Cierre de prueba");
        assertThat(nc.getFechaCierre()).isNotNull();
    }

    @Test
    void cerrarNoValidaQueLaNcNoEsteYaCerrada() {
        NoConformidad nc = ncConId(1L, "NC-2026-00001");
        nc.setEstadoGestion("CERRADA");
        when(noConformidadRepository.findById(1L)).thenReturn(Optional.of(nc));
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());

        NoConformidadIdResponse respuesta = service.cerrar(1L, new CerrarNoConformidadRequest());

        assertThat(respuesta.getId()).isEqualTo(1L);
    }

    @Test
    void listarSeguimientoDelegaEnElRepositorioConElOrdenEsperado() {
        NcSeguimiento seguimiento = new NcSeguimiento(1L, "Comentario", "jperez");
        when(ncSeguimientoRepository.findByNoConformidadIdOrderByCreadoEnDescIdDesc(1L))
                .thenReturn(List.of(seguimiento));

        var items = service.listarSeguimiento(1L);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getComentario()).isEqualTo("Comentario");
    }

    @Test
    void crearSeguimientoLanzaResourceNotFoundSiLaNcNoExiste() {
        when(noConformidadRepository.existsById(99L)).thenReturn(false);
        CrearSeguimientoRequest request = new CrearSeguimientoRequest();
        request.setComentario("Comentario");

        assertThatThrownBy(() -> service.crearSeguimiento(99L, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void crearSeguimientoResuelveAutorDesdeElJwtYGuardaElComentario() {
        when(noConformidadRepository.existsById(1L)).thenReturn(true);
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());
        CrearSeguimientoRequest request = new CrearSeguimientoRequest();
        request.setComentario("Seguimiento de prueba");

        service.crearSeguimiento(1L, request);

        ArgumentCaptor<NcSeguimiento> captor = ArgumentCaptor.forClass(NcSeguimiento.class);
        org.mockito.Mockito.verify(ncSeguimientoRepository).save(captor.capture());
        assertThat(captor.getValue().getComentario()).isEqualTo("Seguimiento de prueba");
        assertThat(captor.getValue().getAutor()).isEqualTo("jperez");
        assertThat(captor.getValue().getNoConformidadId()).isEqualTo(1L);
    }

    @Test
    void obtenerAnalisisDevuelveNuloSiNoHayNingunoRegistrado() {
        when(ncAnalisisRepository.findTopByNoConformidadIdOrderByIdDesc(1L)).thenReturn(Optional.empty());

        assertThat(service.obtenerAnalisis(1L)).isNull();
    }

    @Test
    void obtenerAnalisisDevuelveElMasRecienteSiExiste() {
        NcAnalisis analisis = new NcAnalisis(1L, "ISHIKAWA", "Problema", null, null, null, null, null, null, null, "jperez");
        when(ncAnalisisRepository.findTopByNoConformidadIdOrderByIdDesc(1L)).thenReturn(Optional.of(analisis));

        assertThat(service.obtenerAnalisis(1L).getMetodologia()).isEqualTo("ISHIKAWA");
    }

    @Test
    void guardarAnalisisLanzaResourceNotFoundSiLaNcNoExiste() {
        when(noConformidadRepository.existsById(99L)).thenReturn(false);
        GuardarAnalisisRequest request = analisisRequestValido();

        assertThatThrownBy(() -> service.guardarAnalisis(99L, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void guardarAnalisisInsertaUnoNuevoSiNoExisteNingunoPrevio() {
        when(noConformidadRepository.existsById(1L)).thenReturn(true);
        when(ncAnalisisRepository.findTopByNoConformidadIdOrderByIdDesc(1L)).thenReturn(Optional.empty());
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());
        when(ncAnalisisRepository.save(any(NcAnalisis.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NoConformidadIdResponse respuesta = service.guardarAnalisis(1L, analisisRequestValido());

        ArgumentCaptor<NcAnalisis> captor = ArgumentCaptor.forClass(NcAnalisis.class);
        org.mockito.Mockito.verify(ncAnalisisRepository).save(captor.capture());
        assertThat(captor.getValue().getCreadoPor()).isEqualTo("jperez");
        assertThat(respuesta).isNotNull();
    }

    @Test
    void guardarAnalisisActualizaElExistenteEnVezDeCrearUnoNuevo() {
        NcAnalisis existente = new NcAnalisis(1L, "CINCO_PORQUES", "Problema viejo", null, null, null, null, null, null, null, "jperez");
        when(noConformidadRepository.existsById(1L)).thenReturn(true);
        when(ncAnalisisRepository.findTopByNoConformidadIdOrderByIdDesc(1L)).thenReturn(Optional.of(existente));
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());
        when(ncAnalisisRepository.save(any(NcAnalisis.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.guardarAnalisis(1L, analisisRequestValido());

        assertThat(existente.getMetodologia()).isEqualTo("ISHIKAWA");
        assertThat(existente.getActualizadoPor()).isEqualTo("jperez");
        org.mockito.Mockito.verify(ncAnalisisRepository, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.argThat(a -> a != existente));
    }

    @Test
    void listarAccionesDelegaEnElRepositorio() {
        NcAccionCorrectiva accion = new NcAccionCorrectiva(1L, null, "Descripcion", "Responsable",
                LocalDate.now(), "ALTA", "jperez");
        when(ncAccionCorrectivaRepository.findByNoConformidadIdOrderByIdDesc(1L)).thenReturn(List.of(accion));

        var items = service.listarAcciones(1L);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getDescripcion()).isEqualTo("Descripcion");
    }

    @Test
    void crearAccionLanzaResourceNotFoundSiLaNcNoExiste() {
        when(noConformidadRepository.existsById(99L)).thenReturn(false);
        CrearAccionRequest request = crearAccionRequestValido();

        assertThatThrownBy(() -> service.crearAccion(99L, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void crearAccionAsignaEstadoPendienteYResuelveCreadoPorDesdeElJwt() {
        when(noConformidadRepository.existsById(1L)).thenReturn(true);
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());
        when(ncAccionCorrectivaRepository.save(any(NcAccionCorrectiva.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.crearAccion(1L, crearAccionRequestValido());

        ArgumentCaptor<NcAccionCorrectiva> captor = ArgumentCaptor.forClass(NcAccionCorrectiva.class);
        org.mockito.Mockito.verify(ncAccionCorrectivaRepository).save(captor.capture());
        assertThat(captor.getValue().getEstado()).isEqualTo("PENDIENTE");
        assertThat(captor.getValue().getCreadoPor()).isEqualTo("jperez");
    }

    @Test
    void actualizarAccionLanzaResourceNotFoundSiNoExiste() {
        when(ncAccionCorrectivaRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizarAccion(99L, actualizarAccionRequestValido()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void actualizarAccionNoValidaTransicionesDeEstado() {
        NcAccionCorrectiva accion = new NcAccionCorrectiva(1L, null, "Descripcion", "Responsable",
                LocalDate.now(), "ALTA", "jperez");
        accion.setEstado("COMPLETADA");
        when(ncAccionCorrectivaRepository.findById(1L)).thenReturn(Optional.of(accion));
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());
        ActualizarAccionRequest request = actualizarAccionRequestValido();
        request.setEstado("PENDIENTE");

        service.actualizarAccion(1L, request);

        assertThat(accion.getEstado()).isEqualTo("PENDIENTE");
        assertThat(accion.getActualizadoPor()).isEqualTo("jperez");
    }

    private GuardarAnalisisRequest analisisRequestValido() {
        GuardarAnalisisRequest request = new GuardarAnalisisRequest();
        request.setMetodologia("ISHIKAWA");
        request.setProblemaDetectado("Problema de prueba");
        return request;
    }

    private CrearAccionRequest crearAccionRequestValido() {
        CrearAccionRequest request = new CrearAccionRequest();
        request.setDescripcion("Descripcion de prueba");
        request.setResponsable("Responsable de prueba");
        request.setFechaLimite(LocalDate.now().plusDays(7));
        return request;
    }

    private ActualizarAccionRequest actualizarAccionRequestValido() {
        ActualizarAccionRequest request = new ActualizarAccionRequest();
        request.setDescripcion("Descripcion actualizada");
        request.setResponsable("Responsable actualizado");
        request.setFechaLimite(LocalDate.now().plusDays(3));
        request.setEstado("EN_PROCESO");
        return request;
    }

    private void stubSaveAsignandoId(long id) {
        when(noConformidadRepository.save(any(NoConformidad.class))).thenAnswer(invocation -> {
            NoConformidad argumento = invocation.getArgument(0);
            if (argumento.getId() == null) {
                argumento.setId(id);
            }
            return argumento;
        });
    }

    private void autenticarComo(String codigoUsuario) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(codigoUsuario, null, List.of()));
    }

    private NoConformidad ncConId(Long id, String codigo) {
        NoConformidad nc = new NoConformidad(
                codigo,                 // codigo
                "INTERNA",              // tipo
                "AUDITORIA_INTERNA",    // origen
                "Titulo",               // titulo
                "Descripcion",          // descripcion
                "MEDIA",                // severidad
                "Proceso",              // proceso
                null,                   // norma
                null,                   // reportadoPor
                LocalDate.now(),        // fechaDeteccion
                "ABIERTA",              // estado
                "PENDIENTE",            // estadoGestion
                null,                   // tipoPnc
                null,                   // fechaIngreso
                null,                   // fechaSalida
                "NP-1",                 // npNv
                "acme",                 // cliente
                null,                   // codigoProducto
                "Producto",             // producto
                new BigDecimal("10"),   // cantRequerida
                new BigDecimal("5"),    // cantRechazada
                null,                   // cantRecuperada
                null,                   // pncReal
                null,                   // pctRecuperacion
                null,                   // fechaFabricacion
                "Defecto",              // descripcionDefecto
                "Categoria",            // categoriaDefecto
                "Mayor",                // nivel
                null,                   // tipoFalla
                null,                   // area
                null,                   // maquina
                null,                   // operador
                null,                   // supervisor
                null,                   // revisadoPor
                null,                   // impacto
                null,                   // observacion
                null,                   // causaRaiz
                null,                   // accionesCorrectivas
                null,                   // verificacionSeguimiento
                "jperez");              // creadoPor
        nc.setId(id);
        return nc;
    }

    private CrearNoConformidadRequest crearRequestValido() {
        CrearNoConformidadRequest request = new CrearNoConformidadRequest();
        request.setTipo("INTERNA");
        request.setOrigen("AUDITORIA_INTERNA");
        request.setTitulo("Titulo de prueba");
        request.setDescripcion("Descripcion de prueba");
        request.setSeveridad("MEDIA");
        request.setProceso("Proceso X");
        request.setFechaDeteccion(LocalDate.now());
        request.setNpNv("NP-1");
        request.setCliente("Cliente de prueba");
        request.setProducto("Producto de prueba");
        request.setCantRequerida(new BigDecimal("100"));
        request.setCantRechazada(new BigDecimal("10"));
        request.setCategoriaDefecto("Dimensional");
        request.setNivel("Mayor");
        request.setDescripcionDefecto("Descripcion del defecto");
        return request;
    }
}
