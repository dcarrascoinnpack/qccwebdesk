package cl.faret.qcc.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cl.faret.qcc.auth.entity.Usuario;
import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.dashboard.dto.DashboardFiltrosResponse;
import cl.faret.qcc.dashboard.dto.DashboardResumenResponse;
import cl.faret.qcc.registroscontrol.entity.Proceso;
import cl.faret.qcc.registroscontrol.repository.ProcesoRepository;

// DashboardService es un envoltorio delgado sobre ResumenOperacionalService (compartido con
// RegistrosProduccion), fijando siempre el area a Calidad -- ver ResumenOperacionalServiceTest
// para las pruebas del calculo real (cumplimiento, tendencia, merma, etc).
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final List<String> AREAS_CALIDAD = List.of("CALIDAD", "CALIDAD INNPACK");

    @Mock
    private ResumenOperacionalService resumenOperacionalService;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private ProcesoRepository procesoRepository;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(resumenOperacionalService, usuarioRepository, procesoRepository);
    }

    @Test
    void resumenDelegaEnElServicioCompartidoFijandoLasAreasDeCalidad() {
        DashboardResumenResponse esperado = new DashboardResumenResponse(
                0, 0, 0, BigDecimal.ZERO, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(resumenOperacionalService.resumen(eq(AREAS_CALIDAD), any(), any(), any(), any(), any()))
                .thenReturn(esperado);

        DashboardResumenResponse resultado = service.resumen(null, null, null, null, null);

        assertThat(resultado).isSameAs(esperado);
    }

    @Test
    void filtrosDelegaEnLosRepositoriosDeUsuariosYProcesosActivos() {
        when(usuarioRepository.findByActivoTrueOrderByNombreCompletoAsc())
                .thenReturn(List.of(new Usuario(1L, "jperez", "Juan Perez", null, "operador", true, null)));
        when(procesoRepository.findByActivoTrueOrderByNombreAsc())
                .thenReturn(List.of(new Proceso(1L, "Corrugado", true)));

        DashboardFiltrosResponse filtros = service.filtros();

        assertThat(filtros.getUsuarios()).hasSize(1);
        assertThat(filtros.getProcesos()).hasSize(1);
    }

    @Test
    void validarTodoDelegaEnElServicioCompartidoFijandoLasAreasDeCalidad() {
        when(resumenOperacionalService.validarTodo(eq(AREAS_CALIDAD), any(), any(), any(), any(), any()))
                .thenReturn(3);

        int afectados = service.validarTodo(null, null, null, null, null);

        assertThat(afectados).isEqualTo(3);
        verify(resumenOperacionalService).validarTodo(eq(AREAS_CALIDAD), any(), any(), any(), any(), any());
    }

    @Test
    void rechazarTodoDelegaEnElServicioCompartidoFijandoLasAreasDeCalidad() {
        when(resumenOperacionalService.rechazarTodo(eq(AREAS_CALIDAD), any(), any(), any(), any(), any()))
                .thenReturn(0);

        int afectados = service.rechazarTodo(null, null, null, null, null);

        assertThat(afectados).isZero();
    }
}
