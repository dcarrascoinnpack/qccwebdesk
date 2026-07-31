package cl.faret.qcc.noconformidades.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cl.faret.qcc.noconformidades.entity.NcSeguimiento;

public interface NcSeguimientoRepository extends JpaRepository<NcSeguimiento, Long> {

    List<NcSeguimiento> findByNoConformidadIdOrderByCreadoEnDescIdDesc(Long noConformidadId);
}
