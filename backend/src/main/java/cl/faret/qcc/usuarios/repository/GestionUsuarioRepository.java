package cl.faret.qcc.usuarios.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import cl.faret.qcc.usuarios.entity.Usuario;

public interface GestionUsuarioRepository extends JpaRepository<Usuario, Long> {

    List<Usuario> findAllByOrderByNombreCompletoAsc();

    boolean existsByCodigoUsuario(String codigoUsuario);

    Optional<Usuario> findByCodigoUsuario(String codigoUsuario);
}
