package cl.faret.qcc.registrosproduccion.service;

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

import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.dashboard.dto.DashboardResumenResponse;
import cl.faret.qcc.dashboard.service.ResumenOperacionalService;
import cl.faret.qcc.registroscontrol.repository.ProcesoRepository;
import cl.faret.qcc.registroscontrol.service.RegistroControlModeracionService;

@ExtendWith(MockitoExtension.class)
class RegistrosProduccionServiceTest {

    private static final List<String> AREAS_PRODUCCION = List.of("PRODUCCION");

    @Mock
    private ResumenOperacionalService resumenOperacionalService;

    @Mock
    private RegistroControlModeracionService moderacionService;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private ProcesoRepository procesoRepository;

    private RegistrosProduccionService service;

    @BeforeEach
    void setUp() {
        service = new RegistrosProduccionService(
                resumenOperacionalService, moderacionService, usuarioRepository, procesoRepository);
    }

    @Test
    void resumenDelegaEnElServicioCompartidoFijandoElAreaProduccion() {
        DashboardResumenResponse esperado = new DashboardResumenResponse(
                0, 0, 0, BigDecimal.ZERO, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(resumenOperacionalService.resumen(eq(AREAS_PRODUCCION), any(), any(), any(), any(), any()))
                .thenReturn(esperado);

        DashboardResumenResponse resultado = service.resumen(null, null, null, null, null);

        assertThat(resultado).isSameAs(esperado);
    }

    @Test
    void validarRegistroRestringeLaModeracionAlAreaProduccion() {
        service.validarRegistro(1L);

        verify(moderacionService).validarRegistro(1L, AREAS_PRODUCCION);
    }

    @Test
    void rechazarRegistroRestringeLaModeracionAlAreaProduccion() {
        service.rechazarRegistro(1L);

        verify(moderacionService).rechazarRegistro(1L, AREAS_PRODUCCION);
    }

    @Test
    void eliminarRegistroRestringeLaModeracionAlAreaProduccion() {
        service.eliminarRegistro(1L);

        verify(moderacionService).eliminarRegistro(1L, AREAS_PRODUCCION);
    }

    @Test
    void validarTodoDelegaEnElServicioCompartidoFijandoElAreaProduccion() {
        when(resumenOperacionalService.validarTodo(eq(AREAS_PRODUCCION), any(), any(), any(), any(), any()))
                .thenReturn(5);

        int afectados = service.validarTodo(null, null, null, null, null);

        assertThat(afectados).isEqualTo(5);
    }
}
