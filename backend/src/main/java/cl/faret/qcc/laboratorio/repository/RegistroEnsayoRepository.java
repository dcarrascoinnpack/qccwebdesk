package cl.faret.qcc.laboratorio.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import cl.faret.qcc.laboratorio.entity.RegistroEnsayo;

public interface RegistroEnsayoRepository
        extends JpaRepository<RegistroEnsayo, Long>, JpaSpecificationExecutor<RegistroEnsayo> {
}
