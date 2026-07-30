package cl.faret.qcc.auth.repository;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import cl.faret.qcc.auth.entity.Usuario;

public interface UsuarioRepository extends Repository<Usuario, Long> {

    // Replica el filtro de AuthRepository.cs (WHERE codigo_usuario = ? AND activo = 1):
    // un usuario desactivado se ve como inexistente, igual que en Photino.
    Optional<Usuario> findByCodigoUsuarioAndActivoTrue(String codigoUsuario);
}
