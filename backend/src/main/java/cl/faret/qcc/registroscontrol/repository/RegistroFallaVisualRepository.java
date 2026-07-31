package cl.faret.qcc.registroscontrol.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cl.faret.qcc.registroscontrol.entity.RegistroFallaVisual;

public interface RegistroFallaVisualRepository extends JpaRepository<RegistroFallaVisual, Long> {

    List<RegistroFallaVisual> findByRegistroIdIn(List<Long> registroIds);
}
