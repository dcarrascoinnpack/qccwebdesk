package cl.faret.qccweb.bridge.handlers;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cálculo del dashboard FARET: port FIEL de FaretDashboardService (Photino 1.8.15, 9e1b556) a partir de las no conformidades
 * y las acciones que entrega MejoraContinua. Mismos KPI y series; "hoy" es la fecha local en la zona indicada (Photino usa
 * DateTime.Today del equipo; aquí America/Santiago) y se inyecta para poder probarlo con un reloj fijo. Sin acceso a red:
 * recibe los datos ya leídos.
 *
 * Lectura de las filas como System.Text.Json con PropertyNameCaseInsensitive: nombres sin distinguir mayúsculas, ausente →
 * valor por defecto, tipo equivocado → respuesta inválida ({@link RespuestaInvalida}).
 */
final class FaretDashboardResumen {

    /** Zona de "hoy" (la de la planta; Photino toma DateTime.Today del equipo local). */
    static final ZoneId ZONA = ZoneId.of("America/Santiago");

    /** La respuesta de la API no tiene la forma esperada (Photino: JsonException al deserializar). */
    static final class RespuestaInvalida extends RuntimeException {
        RespuestaInvalida() {
            super(null, null, false, false);
        }
    }

    record Nc(int id, String codigo, String titulo, String severidad, String estado, String proceso, LocalDateTime fechaCreacion) {}

    record Accion(int id, int noConformidadId, String estado, LocalDateTime fechaLimite, LocalDateTime fechaCierre) {}

    private static final String[] ESTADOS_ACCION_ORDEN = {"PENDIENTE", "EN_PROCESO", "COMPLETADA", "CANCELADA"};
    /** Abreviaturas de mes de la cultura es-CL de Photino ("dd MMM"); etiqueta de la serie de 30 días. */
    private static final String[] MESES = {"ene.", "feb.", "mar.", "abr.", "may.", "jun.", "jul.", "ago.", "sep.", "oct.", "nov.", "dic."};

    private FaretDashboardResumen() {}

    // ------------------------------------------------------------------------------ lectura

    /** JsonSerializer.Deserialize&lt;List&lt;NcRaw&gt;&gt;: `null` → lista vacía; algo que no es arreglo de objetos → inválida. */
    static List<Nc> leerNcs(JsonNode raiz, ZoneId zona) {
        List<Nc> ncs = new ArrayList<>();
        for (JsonNode fila : filas(raiz)) {
            ncs.add(new Nc(entero(campo(fila, "id")), texto(campo(fila, "codigo")), texto(campo(fila, "titulo")),
                    texto(campo(fila, "severidad")), texto(campo(fila, "estado")), texto(campo(fila, "proceso")),
                    fecha(campo(fila, "fechaCreacion"), zona)));
        }
        return ncs;
    }

    /** JsonSerializer.Deserialize&lt;List&lt;AccionRaw&gt;&gt; (id, noConformidadId, estado, fechaLimite, fechaCierre). */
    static List<Accion> leerAcciones(JsonNode raiz, ZoneId zona) {
        List<Accion> acciones = new ArrayList<>();
        for (JsonNode fila : filas(raiz)) {
            acciones.add(new Accion(entero(campo(fila, "id")), entero(campo(fila, "noConformidadId")), texto(campo(fila, "estado")),
                    fecha(campo(fila, "fechaLimite"), zona), fecha(campo(fila, "fechaCierre"), zona)));
        }
        return acciones;
    }

    private static List<JsonNode> filas(JsonNode raiz) {
        List<JsonNode> filas = new ArrayList<>();
        if (raiz == null || raiz.isNull()) {
            return filas;
        }
        if (!raiz.isArray()) {
            throw new RespuestaInvalida();
        }
        for (JsonNode fila : raiz) {
            if (!fila.isObject()) {
                throw new RespuestaInvalida();
            }
            filas.add(fila);
        }
        return filas;
    }

    private static JsonNode campo(JsonNode objeto, String nombre) {
        for (Map.Entry<String, JsonNode> e : objeto.properties()) {
            if (e.getKey().equalsIgnoreCase(nombre)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** int (no nullable): ausente → 0; número entero int32; null, texto u otro tipo → inválida. */
    private static int entero(JsonNode nodo) {
        if (nodo == null) {
            return 0;
        }
        if (nodo.isIntegralNumber() && nodo.canConvertToInt()) {
            return nodo.asInt();
        }
        throw new RespuestaInvalida();
    }

    /** string?: ausente o null → null; string → él; otro tipo → inválida. */
    private static String texto(JsonNode nodo) {
        if (nodo == null || nodo.isNull()) {
            return null;
        }
        if (nodo.isString()) {
            return nodo.asString();
        }
        throw new RespuestaInvalida();
    }

    /**
     * DateTime?: ausente o null → null; texto ISO 8601 → fecha-hora local. Con "Z" se conserva la hora tal cual (DateTime
     * Kind=Utc: .Date no convierte); con otro desfase se pasa a la zona local, como DateTime.Kind=Local.
     */
    private static LocalDateTime fecha(JsonNode nodo, ZoneId zona) {
        if (nodo == null || nodo.isNull()) {
            return null;
        }
        if (!nodo.isString()) {
            throw new RespuestaInvalida();
        }
        String s = nodo.asString().trim();
        try {
            if (s.endsWith("Z") || s.endsWith("z")) {
                return OffsetDateTime.parse(s.substring(0, s.length() - 1) + "Z").toLocalDateTime();
            }
            if (s.length() > 10 && (s.lastIndexOf('+') > 10 || s.lastIndexOf('-') > 10)) {
                return OffsetDateTime.parse(s).atZoneSameInstant(zona).toLocalDateTime();
            }
            return s.length() == 10 ? LocalDate.parse(s).atStartOfDay() : LocalDateTime.parse(s);
        } catch (DateTimeParseException e) {
            throw new RespuestaInvalida();
        }
    }

    // ------------------------------------------------------------------------------ cálculo

    /** FaretDashboardService.ObtenerResumenAsync (la parte de cálculo) → FaretDashboardDto en camelCase. */
    static ObjectNode calcular(List<Nc> ncs, List<Accion> acciones, LocalDate hoy, ObjectMapper mapper) {
        ObjectNode dto = mapper.createObjectNode();
        dto.set("kpis", kpis(ncs, acciones, hoy, mapper));
        dto.set("ncPorProceso", categorias(agrupar(ncs.stream().map(Nc::proceso).toList()), mapper));
        dto.set("ncPorSeveridad", categorias(agrupar(ncs.stream().map(Nc::severidad).toList()), mapper));
        dto.set("tendenciaNc30Dias", tendencia30Dias(ncs, hoy, mapper));
        dto.set("accionesPorProceso", categorias(agrupar(procesoDeCadaAccion(ncs, acciones)), mapper));
        dto.set("estadoAcciones", estadoAcciones(acciones, mapper));
        dto.set("ultimasNc", ultimasNc(ncs, mapper));
        dto.set("alertas", alertas(ncs, acciones, hoy, mapper));
        return dto;
    }

    private static ObjectNode kpis(List<Nc> ncs, List<Accion> acciones, LocalDate hoy, ObjectMapper mapper) {
        List<Accion> completadas = acciones.stream().filter(a -> esEstado(a.estado(), "COMPLETADA")).toList();
        // Solo cuentan las completadas que tienen fecha de cierre real Y fecha límite.
        List<Accion> evaluables = completadas.stream().filter(a -> a.fechaCierre() != null && a.fechaLimite() != null).toList();
        int aTiempo = (int) evaluables.stream().filter(a -> !a.fechaCierre().toLocalDate().isAfter(a.fechaLimite().toLocalDate())).count();

        ObjectNode k = mapper.createObjectNode();
        k.put("ncRegistradasHoy", (int) ncs.stream().filter(n -> n.fechaCreacion() != null && n.fechaCreacion().toLocalDate().equals(hoy)).count());
        k.put("ncAbiertas", (int) ncs.stream().filter(n -> !esEstado(n.estado(), "CERRADA")).count());
        k.put("accionesPendientes", (int) acciones.stream().filter(a -> esEstado(a.estado(), "PENDIENTE")).count());
        k.put("accionesVencidas", vencidas(acciones, hoy));
        k.put("porcentajeAccionesCompletadas", porcentaje(completadas.size(), acciones.size()));
        k.put("accionesCompletadasATiempo", aTiempo);
        k.put("accionesCompletadasFueraDePlazo", evaluables.size() - aTiempo);
        k.put("porcentajeAccionesCompletadasATiempo", porcentaje(aTiempo, evaluables.size()));
        return k;
    }

    private static int vencidas(List<Accion> acciones, LocalDate hoy) {
        return (int) acciones.stream()
                .filter(a -> a.fechaLimite() != null && a.fechaLimite().toLocalDate().isBefore(hoy) && !esEstadoTerminal(a.estado()))
                .count();
    }

    /** {@code total == 0 ? 0 : Math.Round(100m * parte / total, 0)}: redondeo bancario (ToEven) como el decimal de .NET. */
    static int porcentaje(int parte, int total) {
        if (total == 0) {
            return 0;
        }
        return new BigDecimal(100).multiply(new BigDecimal(parte)).divide(new BigDecimal(total), MathContext.DECIMAL128)
                .setScale(0, RoundingMode.HALF_EVEN).intValueExact();
    }

    /** AgruparPorCategoria: vacío/null → "Sin dato", trim; GroupBy (orden de primera aparición) + OrderByDescending estable. */
    private static Map<String, Integer> agrupar(List<String> valores) {
        Map<String, Integer> conteo = new LinkedHashMap<>();
        for (String v : valores) {
            String clave = v == null || v.isBlank() ? "Sin dato" : v.trim();
            conteo.merge(clave, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> orden = new ArrayList<>(conteo.entrySet());
        orden.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        Map<String, Integer> resultado = new LinkedHashMap<>();
        orden.forEach(e -> resultado.put(e.getKey(), e.getValue()));
        return resultado;
    }

    private static List<String> procesoDeCadaAccion(List<Nc> ncs, List<Accion> acciones) {
        Map<Integer, String> procesoPorNcId = new LinkedHashMap<>();
        for (Nc n : ncs) {
            procesoPorNcId.putIfAbsent(n.id(), n.proceso());
        }
        List<String> procesos = new ArrayList<>();
        for (Accion a : acciones) {
            procesos.add(procesoPorNcId.get(a.noConformidadId()));
        }
        return procesos;
    }

    private static ArrayNode categorias(Map<String, Integer> conteo, ObjectMapper mapper) {
        ArrayNode arreglo = mapper.createArrayNode();
        conteo.forEach((categoria, total) -> arreglo.add(mapper.createObjectNode().put("categoria", categoria).put("total", total)));
        return arreglo;
    }

    private static ArrayNode estadoAcciones(List<Accion> acciones, ObjectMapper mapper) {
        ArrayNode arreglo = mapper.createArrayNode();
        for (String estado : ESTADOS_ACCION_ORDEN) {
            arreglo.add(mapper.createObjectNode().put("categoria", estado)
                    .put("total", (int) acciones.stream().filter(a -> esEstado(a.estado(), estado)).count()));
        }
        return arreglo;
    }

    private static ArrayNode tendencia30Dias(List<Nc> ncs, LocalDate hoy, ObjectMapper mapper) {
        ArrayNode arreglo = mapper.createArrayNode();
        for (int i = 29; i >= 0; i--) {
            LocalDate dia = hoy.minusDays(i);
            arreglo.add(mapper.createObjectNode()
                    .put("fecha", String.format(Locale.ROOT, "%02d %s", dia.getDayOfMonth(), MESES[dia.getMonthValue() - 1]))
                    .put("total", (int) ncs.stream().filter(n -> n.fechaCreacion() != null && n.fechaCreacion().toLocalDate().equals(dia)).count()));
        }
        return arreglo;
    }

    private static ArrayNode ultimasNc(List<Nc> ncs, ObjectMapper mapper) {
        List<Nc> orden = new ArrayList<>(ncs);
        // OrderByDescending sobre DateTime?: los null quedan al final; estable.
        orden.sort(Comparator.comparing(Nc::fechaCreacion, Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder())).reversed());
        ArrayNode arreglo = mapper.createArrayNode();
        for (Nc n : orden.stream().limit(8).toList()) {
            ObjectNode o = mapper.createObjectNode();
            o.put("id", n.id());
            o.put("codigo", n.codigo() == null ? "" : n.codigo());
            o.put("titulo", n.titulo() == null ? "" : n.titulo());
            o.put("proceso", n.proceso() == null ? "" : n.proceso());
            o.put("severidad", n.severidad() == null ? "" : n.severidad());
            o.put("estado", n.estado() == null ? "" : n.estado());
            o.put("fechaCreacion", n.fechaCreacion() == null ? "" : String.format(Locale.ROOT, "%02d-%02d-%04d",
                    n.fechaCreacion().getDayOfMonth(), n.fechaCreacion().getMonthValue(), n.fechaCreacion().getYear()));
            arreglo.add(o);
        }
        return arreglo;
    }

    private static ArrayNode alertas(List<Nc> ncs, List<Accion> acciones, LocalDate hoy, ObjectMapper mapper) {
        ArrayNode arreglo = mapper.createArrayNode();
        int vencidas = vencidas(acciones, hoy);
        if (vencidas > 0) {
            arreglo.add(alerta("warning", "Hay " + vencidas + " acción(es) correctiva(s) vencida(s)", mapper));
        }
        int criticasAbiertas = (int) ncs.stream().filter(n -> esEstado(n.severidad(), "ALTA") && !esEstado(n.estado(), "CERRADA")).count();
        if (criticasAbiertas > 0) {
            arreglo.add(alerta("warning", "Existen " + criticasAbiertas + " no conformidad(es) crítica(s) abierta(s)", mapper));
        }
        // "Proceso sin NC críticas hace N días": solo para procesos con al menos 1 NC crítica histórica y mínimo 7 días.
        Map<String, LocalDateTime> ultimaPorProceso = new LinkedHashMap<>();
        for (Nc n : ncs) {
            if (esEstado(n.severidad(), "ALTA") && n.proceso() != null && !n.proceso().isBlank()) {
                ultimaPorProceso.merge(n.proceso().trim(), n.fechaCreacion() == null ? LocalDateTime.MIN : n.fechaCreacion(),
                        (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        ultimaPorProceso.entrySet().stream().limit(3).forEach(e -> {
            if (e.getValue().equals(LocalDateTime.MIN)) {
                return; // todas las NC críticas del proceso sin fecha: Max devuelve null y se omite
            }
            long dias = ChronoUnit.DAYS.between(e.getValue().toLocalDate(), hoy);
            if (dias >= 7) {
                arreglo.add(alerta("success", e.getKey() + " sin nuevas no conformidades críticas registradas hace " + dias + " días", mapper));
            }
        });
        return arreglo;
    }

    private static ObjectNode alerta(String tipo, String mensaje, ObjectMapper mapper) {
        return mapper.createObjectNode().put("tipo", tipo).put("mensaje", mensaje);
    }

    private static boolean esEstado(String valor, String esperado) {
        return valor != null && valor.trim().equalsIgnoreCase(esperado);
    }

    private static boolean esEstadoTerminal(String estado) {
        return esEstado(estado, "COMPLETADA") || esEstado(estado, "CANCELADA");
    }
}
