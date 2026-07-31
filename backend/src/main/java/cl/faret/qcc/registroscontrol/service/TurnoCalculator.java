package cl.faret.qcc.registroscontrol.service;

import java.time.LocalTime;

// Replica TurnoCalculadoSql de RegistrosControlRepository.cs: el propio comentario del Photino
// explica que la columna cruda `registros_control.turno` la elige libremente quien registra en la
// app movil y puede no coincidir con la hora real, por lo que ese modulo prefiere derivarlo de
// hora_registro. Decision de esta migracion: unificar ese criterio en las 4 pantallas (Dashboard,
// RegistrosControl, Laboratorio, MaquinasSeguimiento usaban criterios distintos en el original).
public final class TurnoCalculator {

    private static final LocalTime INICIO_TURNO_A = LocalTime.of(7, 0);
    private static final LocalTime FIN_TURNO_A = LocalTime.of(19, 0);

    private TurnoCalculator() {
    }

    public static String calcular(LocalTime horaRegistro) {
        if (horaRegistro == null) {
            return null;
        }
        boolean esTurnoA = !horaRegistro.isBefore(INICIO_TURNO_A) && horaRegistro.isBefore(FIN_TURNO_A);
        return esTurnoA ? "A" : "B";
    }
}
