package cl.faret.qcc.noconformidades.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import cl.faret.qcc.noconformidades.entity.NoConformidad;

public interface NoConformidadRepository
        extends JpaRepository<NoConformidad, Long>, JpaSpecificationExecutor<NoConformidad> {

    @Query("SELECT DISTINCT n.cliente FROM NoConformidadInnpack n "
            + "WHERE n.cliente IS NOT NULL AND n.cliente <> '' ORDER BY n.cliente")
    List<String> findDistinctClientes();

    @Query("SELECT DISTINCT n.tipoPnc FROM NoConformidadInnpack n "
            + "WHERE n.tipoPnc IS NOT NULL AND n.tipoPnc <> '' ORDER BY n.tipoPnc")
    List<String> findDistinctTiposPnc();

    @Query("SELECT DISTINCT n.responsable FROM NoConformidadInnpack n "
            + "WHERE n.responsable IS NOT NULL AND n.responsable <> '' ORDER BY n.responsable")
    List<String> findDistinctResponsables();

    @Query("SELECT DISTINCT n.categoriaDefecto FROM NoConformidadInnpack n "
            + "WHERE n.categoriaDefecto IS NOT NULL AND n.categoriaDefecto <> '' ORDER BY n.categoriaDefecto")
    List<String> findDistinctCategoriasDefecto();

    @Query("SELECT DISTINCT n.area FROM NoConformidadInnpack n "
            + "WHERE n.area IS NOT NULL AND n.area <> '' ORDER BY n.area")
    List<String> findDistinctAreas();

    @Query("SELECT DISTINCT n.supervisor FROM NoConformidadInnpack n "
            + "WHERE n.supervisor IS NOT NULL AND n.supervisor <> '' ORDER BY n.supervisor")
    List<String> findDistinctSupervisores();

    @Query("SELECT DISTINCT n.revisadoPor FROM NoConformidadInnpack n "
            + "WHERE n.revisadoPor IS NOT NULL AND n.revisadoPor <> '' ORDER BY n.revisadoPor")
    List<String> findDistinctRevisadoPor();
}
