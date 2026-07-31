package cl.faret.qcc.controldocumental.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cl.faret.qcc.controldocumental.entity.DocumentoVersion;

public interface DocumentoVersionRepository extends JpaRepository<DocumentoVersion, Long> {

    List<DocumentoVersion> findByDocumentoIdOrderByFechaCreacionDescIdDesc(Long documentoId);

    List<DocumentoVersion> findByDocumentoIdInAndEsVersionVigenteTrue(List<Long> documentoIds);

    @Modifying
    @Query("UPDATE DocumentoVersion v SET v.esVersionVigente = false "
            + "WHERE v.documentoId = :documentoId AND v.esVersionVigente = true")
    int desmarcarVigente(@Param("documentoId") Long documentoId);
}
