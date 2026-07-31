package cl.faret.qcc.dashboard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import cl.faret.qcc.dashboard.dto.DashboardFiltrosResponse;
import cl.faret.qcc.dashboard.dto.DashboardResumenResponse;
import cl.faret.qcc.dashboard.service.DashboardService;

@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @Mock
    private DashboardService dashboardService;

    @InjectMocks
    private DashboardController controller;

    @Test
    void resumenDevuelve200ConElResultadoDelServicio() {
        DashboardResumenResponse esperado = new DashboardResumenResponse(
                0, 0, 0, java.math.BigDecimal.ZERO, 0, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of());
        when(dashboardService.resumen(null, null, null, null, null)).thenReturn(esperado);

        ResponseEntity<DashboardResumenResponse> respuesta = controller.resumen(null, null, null, null, null);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void filtrosDevuelve200ConElResultadoDelServicio() {
        DashboardFiltrosResponse esperado = new DashboardFiltrosResponse(List.of(), List.of());
        when(dashboardService.filtros()).thenReturn(esperado);

        ResponseEntity<DashboardFiltrosResponse> respuesta = controller.filtros();

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void validarTodoDevuelve200ConLaCantidadAfectada() {
        when(dashboardService.validarTodo(null, null, null, null, null)).thenReturn(7);

        ResponseEntity<java.util.Map<String, Integer>> respuesta =
                controller.validarTodo(null, null, null, null, null);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).containsEntry("actualizados", 7);
    }

    @Test
    void rechazarTodoDevuelve200ConLaCantidadAfectada() {
        when(dashboardService.rechazarTodo(null, null, null, null, null)).thenReturn(0);

        ResponseEntity<java.util.Map<String, Integer>> respuesta =
                controller.rechazarTodo(null, null, null, null, null);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).containsEntry("actualizados", 0);
    }
}
