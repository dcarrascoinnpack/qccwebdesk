package cl.faret.qcc.registroscontrol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import cl.faret.qcc.auth.entity.Usuario;
import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.registroscontrol.dto.RegistroControlListResponse;
import cl.faret.qcc.registroscontrol.entity.RegistroControl;
import cl.faret.qcc.registroscontrol.repository.EstadoCatalogoRepository;
import cl.faret.qcc.registroscontrol.repository.FormularioControlRepository;
import cl.faret.qcc.registroscontrol.repository.MaquinaRepository;
import cl.faret.qcc.registroscontrol.repository.ProcesoRepository;
import cl.faret.qcc.registroscontrol.repository.RegistroControlRepository;

@ExtendWith(MockitoExtension.class)
class RegistrosControlServiceTest {

    @Mock
    private RegistroControlRepository registroControlRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private ProcesoRepository procesoRepository;

    @Mock
    private MaquinaRepository maquinaRepository;

    @Mock
    private FormularioControlRepository formularioControlRepository;

    @Mock
    private EstadoCatalogoRepository estadoCatalogoRepository;

    @Mock
    private RegistroControlEnriquecimientoService enriquecimientoService;

    private RegistrosControlService service;

    @BeforeEach
    void setUp() {
        service = new RegistrosControlService(
                registroControlRepository, usuarioRepository, procesoRepository, maquinaRepository,
                formularioControlRepository, estadoCatalogoRepository, enriquecimientoService);
    }

    @Test
    void listarUsaPaginaYLimitePorDefectoCuandoNoHayFiltroDeNp() {
        Page<RegistroControl> paginaVacia = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(registroControlRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(paginaVacia);

        RegistroControlListResponse respuesta = service.listar(null, null, null, null, null, null, null, null);

        assertThat(respuesta.getPage()).isEqualTo(1);
        assertThat(respuesta.getPageSize()).isEqualTo(20);
        assertThat(respuesta.getItems()).isEmpty();
    }

    @Test
    void listarConFiltroDeNpIgnoraLaPaginacionYTraeTodo() {
        RegistroControl uno = registro(1L);
        RegistroControl dos = registro(2L);
        when(registroControlRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(uno, dos));
        sinEnriquecimiento();

        RegistroControlListResponse respuesta =
                service.listar(null, null, null, "NP-1", null, null, 1, 20);

        assertThat(respuesta.getPage()).isEqualTo(1);
        assertThat(respuesta.getPageSize()).isEqualTo(2);
        assertThat(respuesta.getTotal()).isEqualTo(2);
        assertThat(respuesta.getItems()).hasSize(2);
    }

    @Test
    void listarRecalculaElTurnoEnVivoDesdeLaHoraDeRegistro() {
        RegistroControl registro = new RegistroControl(1L, 10L, 1L, 1L, null, "AREA1", "NP-1", null, null,
                "B", 1L, null, false, null, null, LocalDate.now(), LocalTime.of(8, 0), "PENDIENTE", null, null, false);
        Page<RegistroControl> pagina = new PageImpl<>(List.of(registro), PageRequest.of(0, 20), 1);
        when(registroControlRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(pagina);
        sinEnriquecimiento();

        RegistroControlListResponse respuesta =
                service.listar(null, null, null, null, null, null, 1, 20);

        // La columna cruda dice turno "B" pero la hora (08:00) cae en el rango de turno "A":
        // se espera que prevalezca el recalculo en vivo.
        assertThat(respuesta.getItems().get(0).getTurno()).isEqualTo("A");
    }

    private void sinEnriquecimiento() {
        when(usuarioRepository.findByIdIn(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of());
        when(procesoRepository.findAllById(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of());
        when(maquinaRepository.findAllById(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of());
        when(formularioControlRepository.findAllById(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of());
        when(estadoCatalogoRepository.findAllById(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of());
        when(enriquecimientoService.primerAdjuntoPorRegistro(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Map.of());
        when(enriquecimientoService.tipoDefectoPorRegistro(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Map.of());
    }

    private RegistroControl registro(Long id) {
        return new RegistroControl(id, 10L, 1L, 1L, null, "AREA1", "NP-1", null, null, "A", 1L,
                null, false, null, null, LocalDate.now(), LocalTime.of(10, 0), "PENDIENTE", null, null, false);
    }
}
