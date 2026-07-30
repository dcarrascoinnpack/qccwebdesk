package cl.faret.qcc.auth.service;

import java.util.Optional;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import cl.faret.qcc.auth.dto.LoginRequest;
import cl.faret.qcc.auth.dto.LoginResponse;
import cl.faret.qcc.auth.entity.Usuario;
import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.auth.security.JwtService;

@Service
public class AuthService {

    private static final String MENSAJE_USUARIO_NO_EXISTE = "Usuario no existe";
    private static final String MENSAJE_CONTRASENA_INCORRECTA = "Contraseña incorrecta";

    private final UsuarioRepository usuarioRepository;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UsuarioRepository usuarioRepository, JwtService jwtService) {
        this.usuarioRepository = usuarioRepository;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {
        Optional<Usuario> usuario =
                usuarioRepository.findByCodigoUsuarioAndActivoTrue(request.getCodigoUsuario());

        if (usuario.isEmpty()) {
            return LoginResponse.fallo(MENSAJE_USUARIO_NO_EXISTE);
        }

        String passwordHash = usuario.get().getPasswordHash();

        // Hay usuarios reales en produccion con password_hash NULL (columna nullable);
        // BCryptPasswordEncoder.matches lanza excepcion con hash null, asi que se corta antes.
        if (passwordHash == null || !passwordEncoder.matches(request.getPassword(), passwordHash)) {
            return LoginResponse.fallo(MENSAJE_CONTRASENA_INCORRECTA);
        }

        String token = jwtService.generarToken(usuario.get());
        return LoginResponse.exito(usuario.get(), token);
    }
}
