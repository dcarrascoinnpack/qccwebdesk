package cl.faret.qcc.registroscontrol.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cl.faret.qcc.registroscontrol.entity.RegistroAdjunto;

public interface RegistroAdjuntoRepository extends JpaRepository<RegistroAdjunto, Long> {

    // Ordenado por id ascendente para poder tomar el primero por registro (equivalente a
    // MIN(id) del Photino) sin una subconsulta correlacionada por fila.
    List<RegistroAdjunto> findByRegistroIdInOrderByIdAsc(List<Long> registroIds);
}
