package cl.faret.qcc.registroscontrol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cl.faret.qcc.registroscontrol.entity.ParametroControlVisual;
import cl.faret.qcc.registroscontrol.entity.RegistroAdjunto;
import cl.faret.qcc.registroscontrol.entity.RegistroFallaVisual;
import cl.faret.qcc.registroscontrol.repository.ParametroControlVisualRepository;
import cl.faret.qcc.registroscontrol.repository.RegistroAdjuntoRepository;
import cl.faret.qcc.registroscontrol.repository.RegistroFallaVisualRepository;

@ExtendWith(MockitoExtension.class)
class RegistroControlEnriquecimientoServiceTest {

    @Mock
    private RegistroAdjuntoRepository registroAdjuntoRepository;

    @Mock
    private RegistroFallaVisualRepository registroFallaVisualRepository;

    @Mock
    private ParametroControlVisualRepository parametroControlVisualRepository;

    private RegistroControlEnriquecimientoService service;

    @BeforeEach
    void setUp() {
        service = new RegistroControlEnriquecimientoService(
                registroAdjuntoRepository, registroFallaVisualRepository, parametroControlVisualRepository);
    }

    @Test
    void primerAdjuntoPorRegistroTomaElDeMenorIdEquivalenteAMinDeSql() {
        RegistroAdjunto primero = new RegistroAdjunto(1L, 100L, "ruta/primero.jpg");
        RegistroAdjunto segundo = new RegistroAdjunto(2L, 100L, "ruta/segundo.jpg");
        when(registroAdjuntoRepository.findByRegistroIdInOrderByIdAsc(List.of(100L)))
                .thenReturn(List.of(primero, segundo));

        var resultado = service.primerAdjuntoPorRegistro(List.of(100L));

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(100L).getRutaArchivo()).isEqualTo("ruta/primero.jpg");
    }

    @Test
    void primerAdjuntoPorRegistroDevuelveVacioSiNoHayIds() {
        assertThat(service.primerAdjuntoPorRegistro(List.of())).isEmpty();
    }

    @Test
    void tipoDefectoPorRegistroAgrupaLosNombresDeParametrosPorRegistro() {
        RegistroFallaVisual falla1 = new RegistroFallaVisual(1L, 100L, 10L);
        RegistroFallaVisual falla2 = new RegistroFallaVisual(2L, 100L, 11L);
        when(registroFallaVisualRepository.findByRegistroIdIn(List.of(100L)))
                .thenReturn(List.of(falla1, falla2));
        when(parametroControlVisualRepository.findAllById(List.of(10L, 11L)))
                .thenReturn(List.of(
                        new ParametroControlVisual(10L, 1L, "Rayado", "mayor", true),
                        new ParametroControlVisual(11L, 1L, "Manchado", "menor", true)));

        var resultado = service.tipoDefectoPorRegistro(List.of(100L));

        assertThat(resultado.get(100L)).containsExactlyInAnyOrder("Rayado", "Manchado");
    }
}
