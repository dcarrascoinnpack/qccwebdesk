package cl.faret.qcc.registroscontrol.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import cl.faret.qcc.registroscontrol.entity.EstadoCatalogo;

public interface EstadoCatalogoRepository extends JpaRepository<EstadoCatalogo, Long> {

    List<EstadoCatalogo> findByActivoTrueOrderByNombreAsc();

    // Usado por MaquinasSeguimiento para resolver el id del estado "Rechazado" una sola vez, en
    // vez de comparar LOWER(nombre) fila por fila como hacia el Photino.
    Optional<EstadoCatalogo> findByNombreIgnoreCase(String nombre);
}
