package cl.faret.qcc.laboratorio.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cl.faret.qcc.laboratorio.entity.Material;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    List<Material> findByActivoTrueOrderByNombreAsc();
}
