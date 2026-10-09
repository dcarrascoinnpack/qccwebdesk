package cl.faret.qccweb.bridge.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fase 6b-4 — cálculo del dashboard FARET: port de FaretDashboardService (Photino 1.8.15, 9e1b556) con "hoy" fijo.
 * Los números esperados están calculados a mano siguiendo el C# (ver comentarios).
 */
class FaretDashboardResumenTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 9);
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String NCS = "["
            + "{\"id\":1,\"codigo\":\"NC-1\",\"titulo\":\"Rayas\",\"severidad\":\"ALTA\",\"estado\":\"ABIERTA\",\"proceso\":\" Corte \",\"fechaCreacion\":\"2026-10-09T08:00:00\"},"
            + "{\"id\":2,\"codigo\":\"NC-2\",\"titulo\":\"Medida\",\"severidad\":\"alta\",\"estado\":\"cerrada\",\"proceso\":\"Corte\",\"fechaCreacion\":\"2026-10-01T10:00:00\"},"
            + "{\"id\":3,\"codigo\":null,\"titulo\":null,\"severidad\":\"MEDIA\",\"estado\":\"EN_PROCESO\",\"proceso\":null,\"fechaCreacion\":\"2026-09-20T09:00:00\"},"
            + "{\"id\":4,\"codigo\":\"NC-4\",\"titulo\":\"Color\",\"severidad\":\"ALTA\",\"estado\":\"ABIERTA\",\"proceso\":\"Pintura\",\"fechaCreacion\":\"2026-09-25T00:00:00\"},"
            + "{\"id\":5,\"codigo\":\"NC-5\",\"titulo\":\"Sin fecha\",\"severidad\":null,\"estado\":\"ABIERTA\",\"proceso\":\"  \",\"fechaCreacion\":null}"
            + "]";
    private static final String ACCIONES = "["
            + "{\"id\":1,\"noConformidadId\":1,\"estado\":\"PENDIENTE\",\"fechaLimite\":\"2026-10-05T00:00:00\",\"fechaCierre\":null},"
            + "{\"id\":2,\"noConformidadId\":1,\"estado\":\"COMPLETADA\",\"fechaLimite\":\"2026-10-05T00:00:00\",\"fechaCierre\":\"2026-10-04T15:00:00\"},"
            + "{\"id\":3,\"noConformidadId\":2,\"estado\":\"COMPLETADA\",\"fechaLimite\":\"2026-10-03T00:00:00\",\"fechaCierre\":\"2026-10-04T08:00:00\"},"
            + "{\"id\":4,\"noConformidadId\":4,\"estado\":\"EN_PROCESO\",\"fechaLimite\":\"2026-10-20T00:00:00\",\"fechaCierre\":null},"
            + "{\"id\":5,\"noConformidadId\":99,\"estado\":\"CANCELADA\",\"fechaLimite\":\"2026-09-01T00:00:00\",\"fechaCierre\":null},"
            + "{\"id\":6,\"noConformidadId\":3,\"estado\":\" completada \",\"fechaLimite\":null,\"fechaCierre\":null}"
            + "]";

    private ObjectNode dashboard() throws Exception {
        List<FaretDashboardResumen.Nc> ncs = FaretDashboardResumen.leerNcs(mapper.readTree(NCS), FaretDashboardResumen.ZONA);
        List<FaretDashboardResumen.Accion> acciones = FaretDashboardResumen.leerAcciones(mapper.readTree(ACCIONES), FaretDashboardResumen.ZONA);
        return FaretDashboardResumen.calcular(ncs, acciones, HOY, mapper);
    }

    @Test
    void kpisComoFaretDashboardService() throws Exception {
        JsonNode k = dashboard().get("kpis");
        assertThat(k.toString()).isEqualTo("{\"ncRegistradasHoy\":1,\"ncAbiertas\":4,\"accionesPendientes\":1,\"accionesVencidas\":1,"
                + "\"porcentajeAccionesCompletadas\":50,\"accionesCompletadasATiempo\":1,\"accionesCompletadasFueraDePlazo\":1,"
                + "\"porcentajeAccionesCompletadasATiempo\":50}");
    }

    @Test
    void seriesAgrupadasConSinDatoTrimOrdenEstableYEstadosFijos() throws Exception {
        ObjectNode d = dashboard();
        assertThat(d.get("ncPorProceso").toString()).isEqualTo(
                "[{\"categoria\":\"Corte\",\"total\":2},{\"categoria\":\"Sin dato\",\"total\":2},{\"categoria\":\"Pintura\",\"total\":1}]");
        // Agrupa por texto exacto (sensible a mayúsculas, como el GroupBy de LINQ): ALTA y alta son categorías distintas.
        assertThat(d.get("ncPorSeveridad").toString()).isEqualTo(
                "[{\"categoria\":\"ALTA\",\"total\":2},{\"categoria\":\"alta\",\"total\":1},{\"categoria\":\"MEDIA\",\"total\":1},{\"categoria\":\"Sin dato\",\"total\":1}]");
        assertThat(d.get("accionesPorProceso").toString()).isEqualTo(
                "[{\"categoria\":\"Corte\",\"total\":3},{\"categoria\":\"Sin dato\",\"total\":2},{\"categoria\":\"Pintura\",\"total\":1}]");
        assertThat(d.get("estadoAcciones").toString()).isEqualTo("[{\"categoria\":\"PENDIENTE\",\"total\":1},"
                + "{\"categoria\":\"EN_PROCESO\",\"total\":1},{\"categoria\":\"COMPLETADA\",\"total\":3},{\"categoria\":\"CANCELADA\",\"total\":1}]");
    }

    @Test
    void tendenciaDe30DiasTerminaHoyYCuentaPorFechaLocal() throws Exception {
        JsonNode t = dashboard().get("tendenciaNc30Dias");
        assertThat(t.size()).isEqualTo(30);
        assertThat(t.get(0).toString()).isEqualTo("{\"fecha\":\"10 sep.\",\"total\":0}");
        assertThat(t.get(29).toString()).isEqualTo("{\"fecha\":\"09 oct.\",\"total\":1}");
        int suma = 0;
        for (JsonNode dia : t) {
            suma += dia.get("total").asInt();
        }
        assertThat(suma).isEqualTo(4); // NC 1, 2, 3 y 4 (la 5 no tiene fecha)
        assertThat(t.get(20).toString()).isEqualTo("{\"fecha\":\"30 sep.\",\"total\":0}");
        assertThat(t.get(25).toString()).isEqualTo("{\"fecha\":\"05 oct.\",\"total\":0}");
        assertThat(t.get(28).toString()).isEqualTo("{\"fecha\":\"08 oct.\",\"total\":0}");
    }

    @Test
    void ultimasNcOrdenadasPorFechaDescendenteConNulosAlFinalYMaximoOcho() throws Exception {
        JsonNode u = dashboard().get("ultimasNc");
        assertThat(u.size()).isEqualTo(5);
        assertThat(u.get(0).toString()).isEqualTo("{\"id\":1,\"codigo\":\"NC-1\",\"titulo\":\"Rayas\",\"proceso\":\" Corte \",\"severidad\":\"ALTA\","
                + "\"estado\":\"ABIERTA\",\"fechaCreacion\":\"09-10-2026\"}");
        assertThat(u.get(1).get("id").asInt()).isEqualTo(2);
        assertThat(u.get(2).get("id").asInt()).isEqualTo(4);
        assertThat(u.get(3).toString()).isEqualTo("{\"id\":3,\"codigo\":\"\",\"titulo\":\"\",\"proceso\":\"\",\"severidad\":\"MEDIA\","
                + "\"estado\":\"EN_PROCESO\",\"fechaCreacion\":\"20-09-2026\"}");
        assertThat(u.get(4).get("id").asInt()).isEqualTo(5);
        assertThat(u.get(4).get("severidad").asString()).isEmpty();
        assertThat(u.get(4).get("fechaCreacion").asString()).isEmpty();

        StringBuilder muchas = new StringBuilder("[");
        for (int i = 1; i <= 12; i++) {
            muchas.append(i > 1 ? "," : "").append("{\"id\":").append(i).append(",\"fechaCreacion\":\"2026-10-").append(String.format("%02d", i)).append("T00:00:00\"}");
        }
        List<FaretDashboardResumen.Nc> ncs = FaretDashboardResumen.leerNcs(mapper.readTree(muchas + "]"), FaretDashboardResumen.ZONA);
        JsonNode ult = FaretDashboardResumen.calcular(ncs, List.of(), HOY, mapper).get("ultimasNc");
        assertThat(ult.size()).isEqualTo(8);
        assertThat(ult.get(0).get("id").asInt()).isEqualTo(12);
        assertThat(ult.get(7).get("id").asInt()).isEqualTo(5);
    }

    @Test
    void alertasVencidasCriticasAbiertasYProcesosSinCriticasHace7DiasOMas() throws Exception {
        JsonNode a = dashboard().get("alertas");
        assertThat(a.toString()).isEqualTo("[{\"tipo\":\"warning\",\"mensaje\":\"Hay 1 acción(es) correctiva(s) vencida(s)\"},"
                + "{\"tipo\":\"warning\",\"mensaje\":\"Existen 2 no conformidad(es) crítica(s) abierta(s)\"},"
                + "{\"tipo\":\"success\",\"mensaje\":\"Pintura sin nuevas no conformidades críticas registradas hace 14 días\"}]");
        // Corte tuvo una crítica hoy (0 días < 7): sin alerta de éxito. Sin datos: sin alertas ni contadores.
        JsonNode vacio = FaretDashboardResumen.calcular(List.of(), List.of(), HOY, mapper);
        assertThat(vacio.get("alertas").size()).isZero();
        assertThat(vacio.get("kpis").toString()).isEqualTo("{\"ncRegistradasHoy\":0,\"ncAbiertas\":0,\"accionesPendientes\":0,\"accionesVencidas\":0,"
                + "\"porcentajeAccionesCompletadas\":0,\"accionesCompletadasATiempo\":0,\"accionesCompletadasFueraDePlazo\":0,"
                + "\"porcentajeAccionesCompletadasATiempo\":0}");
        assertThat(vacio.get("estadoAcciones").size()).isEqualTo(4);
        assertThat(vacio.get("tendenciaNc30Dias").size()).isEqualTo(30);
        assertThat(vacio.get("ncPorProceso").size()).isZero();
    }

    @Test
    void soloLosTresPrimerosProcesosConCriticasGeneranAlertaDeExito() throws Exception {
        String ncs = "[{\"id\":1,\"severidad\":\"ALTA\",\"proceso\":\"A\",\"fechaCreacion\":\"2026-09-01T00:00:00\"},"
                + "{\"id\":2,\"severidad\":\"ALTA\",\"proceso\":\"B\",\"fechaCreacion\":\"2026-09-02T00:00:00\"},"
                + "{\"id\":3,\"severidad\":\"ALTA\",\"proceso\":\"C\",\"fechaCreacion\":\"2026-09-03T00:00:00\"},"
                + "{\"id\":4,\"severidad\":\"ALTA\",\"proceso\":\"D\",\"fechaCreacion\":\"2026-09-04T00:00:00\"},"
                + "{\"id\":5,\"severidad\":\"ALTA\",\"proceso\":\"E\",\"fechaCreacion\":null}]";
        JsonNode a = FaretDashboardResumen.calcular(FaretDashboardResumen.leerNcs(mapper.readTree(ncs), FaretDashboardResumen.ZONA), List.of(), HOY, mapper)
                .get("alertas");
        // 5 críticas abiertas + success de A, B, C (el cuarto grupo, D, queda fuera del Take(3)).
        assertThat(a.size()).isEqualTo(4);
        assertThat(a.get(1).get("mensaje").asString()).startsWith("A sin nuevas").endsWith("hace 38 días");
        assertThat(a.get(3).get("mensaje").asString()).startsWith("C sin nuevas");
    }

    @Test
    void porcentajeRedondeaComoDecimalDeNetBancario() {
        assertThat(FaretDashboardResumen.porcentaje(0, 0)).isZero();
        assertThat(FaretDashboardResumen.porcentaje(1, 8)).isEqualTo(12); // 12,5 -> 12 (ToEven)
        assertThat(FaretDashboardResumen.porcentaje(3, 8)).isEqualTo(38); // 37,5 -> 38
        assertThat(FaretDashboardResumen.porcentaje(1, 3)).isEqualTo(33);
        assertThat(FaretDashboardResumen.porcentaje(2, 3)).isEqualTo(67);
        assertThat(FaretDashboardResumen.porcentaje(5, 5)).isEqualTo(100);
        assertThat(FaretDashboardResumen.porcentaje(1, 200)).isEqualTo(0); // 0,5 -> 0 (ToEven)
        assertThat(FaretDashboardResumen.porcentaje(3, 200)).isEqualTo(2); // 1,5 -> 2
    }

    @Test
    void fechasConZYConDesfaseSeInterpretanComoDateTimeDeNet() throws Exception {
        String ncs = "[{\"id\":1,\"fechaCreacion\":\"2026-10-10T01:00:00Z\"},{\"id\":2,\"fechaCreacion\":\"2026-10-10T01:00:00+00:00\"},"
                + "{\"id\":3,\"fechaCreacion\":\"2026-10-09T23:30:00-03:00\"},{\"id\":4,\"fechaCreacion\":\"2026-10-09\"},"
                + "{\"id\":5,\"fechaCreacion\":\"2026-10-09T08:00:00.1234567\"}]";
        List<FaretDashboardResumen.Nc> l = FaretDashboardResumen.leerNcs(mapper.readTree(ncs), FaretDashboardResumen.ZONA);
        // Z: Kind=Utc, .Date no convierte; +00:00: se convierte a la hora local de Santiago (UTC-3 en octubre).
        assertThat(l.get(0).fechaCreacion()).isEqualTo(LocalDateTime.of(2026, 10, 10, 1, 0));
        assertThat(l.get(1).fechaCreacion()).isEqualTo(LocalDateTime.of(2026, 10, 9, 22, 0));
        assertThat(l.get(2).fechaCreacion()).isEqualTo(LocalDateTime.of(2026, 10, 9, 23, 30));
        assertThat(l.get(3).fechaCreacion()).isEqualTo(LocalDateTime.of(2026, 10, 9, 0, 0));
        assertThat(l.get(4).fechaCreacion().toLocalDate()).isEqualTo(HOY);
    }

    @Test
    void lecturaComoSystemTextJsonNombresSinMayusculasDefectosYFormasInvalidas() throws Exception {
        List<FaretDashboardResumen.Nc> l = FaretDashboardResumen.leerNcs(
                mapper.readTree("[{\"ID\":7,\"Codigo\":\"X\",\"FECHACREACION\":\"2026-10-09T00:00:00\"},{}]"), FaretDashboardResumen.ZONA);
        assertThat(l.get(0).id()).isEqualTo(7);
        assertThat(l.get(0).codigo()).isEqualTo("X");
        assertThat(l.get(0).fechaCreacion()).isNotNull();
        assertThat(l.get(1).id()).isZero();
        assertThat(FaretDashboardResumen.leerNcs(mapper.readTree("null"), FaretDashboardResumen.ZONA)).isEmpty();
        assertThat(FaretDashboardResumen.leerNcs(mapper.readTree("[]"), FaretDashboardResumen.ZONA)).isEmpty();
        for (String malo : List.of("{}", "\"x\"", "5", "[1]", "[null]", "[[]]", "[{\"id\":\"7\"}]", "[{\"id\":null}]", "[{\"id\":1.5}]",
                "[{\"id\":99999999999}]", "[{\"estado\":5}]", "[{\"proceso\":{}}]", "[{\"fechaCreacion\":\"ayer\"}]", "[{\"fechaCreacion\":20261009}]")) {
            assertThatThrownBy(() -> FaretDashboardResumen.leerNcs(mapper.readTree(malo), FaretDashboardResumen.ZONA))
                    .as(malo).isInstanceOf(FaretDashboardResumen.RespuestaInvalida.class);
        }
        assertThatThrownBy(() -> FaretDashboardResumen.leerAcciones(mapper.readTree("[{\"noConformidadId\":\"1\"}]"), FaretDashboardResumen.ZONA))
                .isInstanceOf(FaretDashboardResumen.RespuestaInvalida.class);
    }
}
