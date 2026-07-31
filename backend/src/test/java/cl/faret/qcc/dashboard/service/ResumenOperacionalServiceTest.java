package cl.faret.qcc.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import cl.faret.qcc.auth.entity.Usuario;
import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.dashboard.dto.DashboardResumenResponse;
import cl.faret.qcc.registroscontrol.dto.RegistroControlListResponse;
import cl.faret.qcc.registroscontrol.entity.RegistroControl;
import cl.faret.qcc.registroscontrol.repository.ProcesoRepository;
import cl.faret.qcc.registroscontrol.repository.RegistroControlRepository;
import cl.faret.qcc.registroscontrol.service.RegistroControlEnriquecimientoService;
import cl.faret.qcc.registroscontrol.service.RegistroControlModeracionService;
import cl.faret.qcc.registroscontrol.service.RegistrosControlService;

@ExtendWith(MockitoExtension.class)
class ResumenOperacionalServiceTest {

    private static final List<String> AREAS_PRODUCCION = List.of("PRODUCCION");

    @Mock
    private RegistroControlRepository registroControlRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private ProcesoRepository procesoRepository;

    @Mock
    private RegistroControlEnriquecimientoService enriquecimientoService;

    @Mock
    private RegistrosControlService registrosControlService;

    @Mock
    private RegistroControlModeracionService moderacionService;

    private ResumenOperacionalService service;

    @BeforeEach
    void setUp() {
        service = new ResumenOperacionalService(
                registroControlRepository, usuarioRepository, procesoRepository, enriquecimientoService,
                registrosControlService, moderacionService);
    }

    @Test
    void resumenSumaSoloLaMermaDeRegistrosQueRequierenMerma() {
        RegistroControl conMerma = registro(1L, true, new BigDecimal("5.5"), null);
        RegistroControl sinMerma = registro(2L, false, new BigDecimal("100"), null);
        when(registroControlRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(conMerma, sinMerma));
        sinDependenciasExternas();

        DashboardResumenResponse resumen = service.resumen(AREAS_PRODUCCION, null, null, null, null, null);

        assertThat(resumen.getMermaHoy()).isEqualByComparingTo("5.5");
    }

    @Test
    void resumenCuentaRegistrosConObservacionNoVacia() {
        RegistroControl conObservacion = registro(1L, false, null, "Observacion real");
        RegistroControl sinObservacion = registro(2L, false, null, "   ");
        when(registroControlRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(conObservacion, sinObservacion));
        sinDependenciasExternas();

        DashboardResumenResponse resumen = service.resumen(AREAS_PRODUCCION, null, null, null, null, null);

        assertThat(resumen.getRegistrosConObservacionHoy()).isEqualTo(1);
    }

    @Test
    void resumenCalculaElPorcentajeDeCumplimientoPorInspector() {
        RegistroControl sinFalla1 = registro(1L, false, null, null);
        RegistroControl sinFalla2 = registro(2L, false, null, null);
        when(registroControlRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(sinFalla1, sinFalla2));
        when(usuarioRepository.findByIdIn(anyList()))
                .thenReturn(List.of(new Usuario(10L, "jperez", "Juan Perez", null, "operador", true, null)));
        when(procesoRepository.findAllById(anyList())).thenReturn(List.of());
        when(enriquecimientoService.tipoDefectoPorRegistro(anyList())).thenReturn(Map.of());
        when(registrosControlService.listar(any(), any(), any(), any(), any(), any(), any(), any(), anyList()))
                .thenReturn(new RegistroControlListResponse(List.of(), 0, 1, 20));

        DashboardResumenResponse resumen = service.resumen(AREAS_PRODUCCION, null, null, null, null, null);

        assertThat(resumen.getCumplimientoPorInspector()).hasSize(1);
        assertThat(resumen.getCumplimientoPorInspector().get(0).getPorcentajeCumplimiento()).isEqualTo(100.0);
        assertThat(resumen.getDesempenoIndividual()).isEqualTo(resumen.getCumplimientoPorInspector());
    }

    @Test
    void validarTodoDelegaEnElServicioDeModeracionYDevuelveLaCantidadAfectada() {
        when(moderacionService.validarTodo(any(Specification.class))).thenReturn(3);

        int afectados = service.validarTodo(AREAS_PRODUCCION, null, null, null, null, null);

        assertThat(afectados).isEqualTo(3);
    }

    @Test
    void rechazarTodoDelegaEnElServicioDeModeracion() {
        when(moderacionService.rechazarTodo(any(Specification.class))).thenReturn(0);

        int afectados = service.rechazarTodo(AREAS_PRODUCCION, LocalDate.now(), LocalDate.now(), null, null, null);

        assertThat(afectados).isZero();
    }

    private void sinDependenciasExternas() {
        when(usuarioRepository.findByIdIn(anyList())).thenReturn(List.of());
        when(procesoRepository.findAllById(anyList())).thenReturn(List.of());
        when(enriquecimientoService.tipoDefectoPorRegistro(anyList())).thenReturn(Map.of());
        when(registrosControlService.listar(any(), any(), any(), any(), any(), any(), any(), any(), anyList()))
                .thenReturn(new RegistroControlListResponse(List.of(), 0, 1, 20));
    }

    private RegistroControl registro(Long id, boolean requiereMerma, BigDecimal cantidadMerma, String observacion) {
        return new RegistroControl(id, 10L, 1L, 1L, null, "PRODUCCION", "NP-1", null, null, "A", 1L,
                observacion, requiereMerma, null, cantidadMerma, LocalDate.now(), LocalTime.of(10, 0),
                "PENDIENTE", null, null, false);
    }
}
