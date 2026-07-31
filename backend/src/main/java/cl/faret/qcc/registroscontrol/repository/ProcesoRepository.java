package cl.faret.qcc.registroscontrol.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cl.faret.qcc.registroscontrol.entity.Proceso;

public interface ProcesoRepository extends JpaRepository<Proceso, Long> {

    List<Proceso> findByActivoTrueOrderByNombreAsc();
}
