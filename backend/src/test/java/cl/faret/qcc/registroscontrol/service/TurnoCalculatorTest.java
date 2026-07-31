package cl.faret.qcc.registroscontrol.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class TurnoCalculatorTest {

    @Test
    void devuelveTurnoAEntreLas7YLas19() {
        assertThat(TurnoCalculator.calcular(LocalTime.of(7, 0))).isEqualTo("A");
        assertThat(TurnoCalculator.calcular(LocalTime.of(12, 30))).isEqualTo("A");
        assertThat(TurnoCalculator.calcular(LocalTime.of(18, 59, 59))).isEqualTo("A");
    }

    @Test
    void devuelveTurnoBFueraDelRangoDiurno() {
        assertThat(TurnoCalculator.calcular(LocalTime.of(19, 0))).isEqualTo("B");
        assertThat(TurnoCalculator.calcular(LocalTime.of(23, 30))).isEqualTo("B");
        assertThat(TurnoCalculator.calcular(LocalTime.of(6, 59, 59))).isEqualTo("B");
    }

    @Test
    void devuelveNuloSiLaHoraEsNula() {
        assertThat(TurnoCalculator.calcular(null)).isNull();
    }
}
