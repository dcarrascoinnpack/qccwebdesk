package cl.faret.qcc.registroscontrol.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cl.faret.qcc.registroscontrol.entity.Maquina;

public interface MaquinaRepository extends JpaRepository<Maquina, Long> {

    List<Maquina> findByActivoTrueOrderByNombreAsc();

    long countByActivoTrue();
}
