package cl.faret.qcc.laboratorio.controller;

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

import cl.faret.qcc.laboratorio.dto.LaboratorioCatalogosResponse;
import cl.faret.qcc.laboratorio.dto.LaboratorioResumenResponse;
import cl.faret.qcc.laboratorio.service.LaboratorioService;

@ExtendWith(MockitoExtension.class)
class LaboratorioControllerTest {

    @Mock
    private LaboratorioService laboratorioService;

    @InjectMocks
    private LaboratorioController controller;

    @Test
    void resumenDevuelve200ConElResultadoDelServicio() {
        LaboratorioResumenResponse esperado =
                new LaboratorioResumenResponse(0, 0, 0, 0, false, null, List.of());
        when(laboratorioService.resumen(null, null, null, null, false)).thenReturn(esperado);

        ResponseEntity<LaboratorioResumenResponse> respuesta =
                controller.resumen(null, null, null, null, false);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }

    @Test
    void catalogosDevuelve200ConElResultadoDelServicio() {
        LaboratorioCatalogosResponse esperado = new LaboratorioCatalogosResponse(List.of(), List.of());
        when(laboratorioService.catalogos()).thenReturn(esperado);

        ResponseEntity<LaboratorioCatalogosResponse> respuesta = controller.catalogos();

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).isSameAs(esperado);
    }
}
