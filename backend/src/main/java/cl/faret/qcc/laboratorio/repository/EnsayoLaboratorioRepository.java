package cl.faret.qcc.laboratorio.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cl.faret.qcc.laboratorio.entity.EnsayoLaboratorio;

public interface EnsayoLaboratorioRepository extends JpaRepository<EnsayoLaboratorio, Long> {

    List<EnsayoLaboratorio> findByActivoTrueOrderByNombreAsc();
}
