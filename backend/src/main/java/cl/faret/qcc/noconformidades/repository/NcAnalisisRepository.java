package cl.faret.qcc.noconformidades.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import cl.faret.qcc.noconformidades.entity.NcAnalisis;

public interface NcAnalisisRepository extends JpaRepository<NcAnalisis, Long> {

    Optional<NcAnalisis> findTopByNoConformidadIdOrderByIdDesc(Long noConformidadId);
}
