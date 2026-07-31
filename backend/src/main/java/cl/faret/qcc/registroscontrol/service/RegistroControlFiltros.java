package cl.faret.qcc.registroscontrol.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import cl.faret.qcc.registroscontrol.entity.RegistroControl;

// Specification compartida por RegistrosControl (listado) y Dashboard (ultimos registros +
// validarTodo/rechazarTodo, que deben aplicar exactamente los mismos filtros que el listado en
// pantalla -- decision de fidelidad ya acordada, a diferencia del Photino que no filtraba nada en
// esas dos operaciones). El filtro de turno no compara contra la columna cruda `turno`: traduce
// el turno pedido a un rango de `horaRegistro`, coherente con TurnoCalculator (turno siempre
// recalculado en vivo, no confiable desde la columna cruda segun el propio comentario del Photino).
public final class RegistroControlFiltros {

    private static final LocalTime INICIO_TURNO_A = LocalTime.of(7, 0);
    private static final LocalTime FIN_TURNO_A = LocalTime.of(19, 0);

    private RegistroControlFiltros() {
    }

    public static Specification<RegistroControl> construir(
            Long id, LocalDate fechaDesde, LocalDate fechaHasta, String np, String turno,
            Long estadoId, Long procesoId, Long usuarioId) {
        return construir(id, fechaDesde, fechaHasta, np, turno, estadoId, procesoId, usuarioId, null);
    }

    // Sobrecarga con filtro de `area`: la usan Dashboard (area IN ('CALIDAD','CALIDAD INNPACK')) y
    // RegistrosProduccion (area = 'PRODUCCION'), que en el Photino son el mismo reporte clonado
    // literalmente cambiando solo este filtro. RegistrosControl (listado plano) no filtra por area
    // en el original, por eso la sobrecarga de 8 argumentos (sin areas) se mantiene para ese caso.
    public static Specification<RegistroControl> construir(
            Long id, LocalDate fechaDesde, LocalDate fechaHasta, String np, String turno,
            Long estadoId, Long procesoId, Long usuarioId, List<String> areas) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isFalse(root.get("eliminado")));

            if (id != null) {
                predicates.add(cb.equal(root.get("id"), id));
            }
            if (fechaDesde != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("fechaRegistro"), fechaDesde));
            }
            if (fechaHasta != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("fechaRegistro"), fechaHasta));
            }
            if (StringUtils.hasText(np)) {
                predicates.add(cb.like(root.get("np"), "%" + np + "%"));
            }
            if (StringUtils.hasText(turno)) {
                predicates.add(construirPredicadoTurno(root, cb, turno));
            }
            if (estadoId != null) {
                predicates.add(cb.equal(root.get("estadoId"), estadoId));
            }
            if (procesoId != null) {
                predicates.add(cb.equal(root.get("procesoId"), procesoId));
            }
            if (usuarioId != null) {
                predicates.add(cb.equal(root.get("usuarioId"), usuarioId));
            }
            if (areas != null && !areas.isEmpty()) {
                predicates.add(root.get("area").in(areas));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static Predicate construirPredicadoTurno(Root<RegistroControl> root, CriteriaBuilder cb, String turno) {
        Predicate esTurnoA = cb.and(
                cb.greaterThanOrEqualTo(root.get("horaRegistro"), INICIO_TURNO_A),
                cb.lessThan(root.get("horaRegistro"), FIN_TURNO_A));
        return "A".equalsIgnoreCase(turno) ? esTurnoA : cb.not(esTurnoA);
    }
}
