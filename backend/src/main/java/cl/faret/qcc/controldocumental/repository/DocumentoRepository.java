package cl.faret.qcc.controldocumental.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import cl.faret.qcc.controldocumental.entity.Documento;

public interface DocumentoRepository
        extends JpaRepository<Documento, Long>, JpaSpecificationExecutor<Documento> {
}
