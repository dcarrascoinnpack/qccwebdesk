package cl.faret.qcc.usuarios.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import cl.faret.qcc.exception.ResourceNotFoundException;
import cl.faret.qcc.usuarios.dto.CrearUsuarioRequest;
import cl.faret.qcc.usuarios.dto.UsuarioResponse;
import cl.faret.qcc.usuarios.entity.Usuario;
import cl.faret.qcc.usuarios.exception.OperacionNoPermitidaException;
import cl.faret.qcc.usuarios.exception.UsuarioDuplicadoException;
import cl.faret.qcc.usuarios.repository.GestionUsuarioRepository;

@Service
public class UsuarioService {

    private final GestionUsuarioRepository usuarioRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UsuarioService(GestionUsuarioRepository usuarioRepository) {
        this.usuarioRepository = usuarioRepository;
    }

    public List<UsuarioResponse> listar() {
        return usuarioRepository.findAllByOrderByNombreCompletoAsc().stream()
                .map(UsuarioResponse::from)
                .collect(Collectors.toList());
    }

    public UsuarioResponse crear(CrearUsuarioRequest request) {
        if (usuarioRepository.existsByCodigoUsuario(request.getCodigoUsuario())) {
            throw new UsuarioDuplicadoException("Ya existe un usuario con ese código");
        }

        Usuario usuario = new Usuario(
                request.getCodigoUsuario().trim(),
                request.getNombreCompleto().trim(),
                passwordEncoder.encode(request.getPassword()),
                request.getRol().trim(),
                request.getActivo());

        return UsuarioResponse.from(usuarioRepository.save(usuario));
    }

    // Replica el orden de validacion de UsuariosHandler.DeleteAsync: primero se
    // rechaza la auto-eliminacion, recien despues se valida que el usuario exista.
    public void eliminar(Long id) {
        if (esUsuarioActual(id)) {
            throw new OperacionNoPermitidaException("No puedes eliminar tu propio usuario");
        }

        Usuario usuario = obtenerPorId(id);
        usuarioRepository.delete(usuario);
    }

    public void resetPassword(Long id, String nuevaPassword) {
        if (esUsuarioActual(id)) {
            throw new OperacionNoPermitidaException(
                    "No puedes cambiar tu propia contraseña desde aquí");
        }

        Usuario usuario = obtenerPorId(id);
        usuario.setPasswordHash(passwordEncoder.encode(nuevaPassword));
        usuarioRepository.save(usuario);
    }

    private Usuario obtenerPorId(Long id) {
        return usuarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
    }

    private boolean esUsuarioActual(Long id) {
        String codigoUsuarioActual =
                SecurityContextHolder.getContext().getAuthentication().getName();

        return usuarioRepository.findByCodigoUsuario(codigoUsuarioActual)
                .map(Usuario::getId)
                .map(id::equals)
                .orElse(false);
    }
}
