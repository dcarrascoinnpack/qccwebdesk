package cl.faret.qcc.noconformidades.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cl.faret.qcc.noconformidades.entity.NcAccionCorrectiva;

public interface NcAccionCorrectivaRepository extends JpaRepository<NcAccionCorrectiva, Long> {

    List<NcAccionCorrectiva> findByNoConformidadIdOrderByIdDesc(Long noConformidadId);
}
