package cl.faret.qcc.registroscontrol.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import cl.faret.qcc.registroscontrol.entity.RegistroControl;

public interface RegistroControlRepository
        extends JpaRepository<RegistroControl, Long>, JpaSpecificationExecutor<RegistroControl> {

    // Usados por MaquinasSeguimiento -- ese modulo, a diferencia de RegistrosControl/Dashboard, no
    // filtra por `eliminado` en ninguna de sus consultas (confirmado en la investigacion).
    long countByMaquinaId(Long maquinaId);

    long countByMaquinaIdAndEstadoId(Long maquinaId, Long estadoId);

    List<RegistroControl> findByMaquinaIdOrderByFechaRegistroDescIdDesc(Long maquinaId);

    @Query("SELECT COUNT(DISTINCT r.maquinaId) FROM RegistroControl r WHERE r.maquinaId IS NOT NULL")
    long countDistinctMaquinaId();
}
