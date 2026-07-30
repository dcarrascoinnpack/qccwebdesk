package cl.faret.qcc.usuarios.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import cl.faret.qcc.exception.ResourceNotFoundException;
import cl.faret.qcc.usuarios.dto.CrearUsuarioRequest;
import cl.faret.qcc.usuarios.dto.UsuarioResponse;
import cl.faret.qcc.usuarios.entity.Usuario;
import cl.faret.qcc.usuarios.exception.OperacionNoPermitidaException;
import cl.faret.qcc.usuarios.exception.UsuarioDuplicadoException;
import cl.faret.qcc.usuarios.repository.GestionUsuarioRepository;

@ExtendWith(MockitoExtension.class)
class UsuarioServiceTest {

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Mock
    private GestionUsuarioRepository usuarioRepository;

    private UsuarioService usuarioService;

    @BeforeEach
    void setUp() {
        usuarioService = new UsuarioService(usuarioRepository);
    }

    @AfterEach
    void limpiarContextoDeSeguridad() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listarDevuelveTodosLosUsuariosOrdenadosPorNombre() {
        when(usuarioRepository.findAllByOrderByNombreCompletoAsc())
                .thenReturn(List.of(usuarioConId(1L, "ana"), usuarioConId(2L, "jperez")));

        List<UsuarioResponse> resultado = usuarioService.listar();

        assertThat(resultado).hasSize(2);
        assertThat(resultado.get(0).getCodigoUsuario()).isEqualTo("ana");
    }

    @Test
    void crearFallaSiElCodigoDeUsuarioYaExiste() {
        when(usuarioRepository.existsByCodigoUsuario("jperez")).thenReturn(true);

        assertThatThrownBy(() -> usuarioService.crear(crearRequest("jperez")))
                .isInstanceOf(UsuarioDuplicadoException.class)
                .hasMessage("Ya existe un usuario con ese código");
    }

    @Test
    void crearGuardaElUsuarioConLaPasswordHasheada() {
        when(usuarioRepository.existsByCodigoUsuario("jperez")).thenReturn(false);
        when(usuarioRepository.save(any(Usuario.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        usuarioService.crear(crearRequest("jperez"));

        ArgumentCaptor<Usuario> captor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(captor.capture());

        Usuario guardado = captor.getValue();
        assertThat(guardado.getCodigoUsuario()).isEqualTo("jperez");
        assertThat(guardado.getRol()).isEqualTo("operador");
        assertThat(passwordEncoder.matches("claveCorrecta123", guardado.getPasswordHash())).isTrue();
    }

    @Test
    void eliminarFallaSiElUsuarioIntentaEliminarseASiMismo() {
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuario("jperez"))
                .thenReturn(Optional.of(usuarioConId(1L, "jperez")));

        assertThatThrownBy(() -> usuarioService.eliminar(1L))
                .isInstanceOf(OperacionNoPermitidaException.class)
                .hasMessage("No puedes eliminar tu propio usuario");

        verify(usuarioRepository, never()).findById(any());
        verify(usuarioRepository, never()).delete(any());
    }

    @Test
    void eliminarFallaSiElUsuarioNoExiste() {
        autenticarComo("admin");
        when(usuarioRepository.findByCodigoUsuario("admin"))
                .thenReturn(Optional.of(usuarioConId(99L, "admin")));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> usuarioService.eliminar(1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Usuario no encontrado");
    }

    @Test
    void eliminarBorraElUsuarioCuandoTodoEsValido() {
        autenticarComo("admin");
        Usuario objetivo = usuarioConId(1L, "jperez");
        when(usuarioRepository.findByCodigoUsuario("admin"))
                .thenReturn(Optional.of(usuarioConId(99L, "admin")));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(objetivo));

        usuarioService.eliminar(1L);

        verify(usuarioRepository, times(1)).delete(objetivo);
    }

    @Test
    void resetPasswordFallaSiElUsuarioIntentaResetearseASiMismo() {
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuario("jperez"))
                .thenReturn(Optional.of(usuarioConId(1L, "jperez")));

        assertThatThrownBy(() -> usuarioService.resetPassword(1L, "nuevaClave123"))
                .isInstanceOf(OperacionNoPermitidaException.class)
                .hasMessage("No puedes cambiar tu propia contraseña desde aquí");

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void resetPasswordActualizaElHashCuandoTodoEsValido() {
        autenticarComo("admin");
        Usuario objetivo = usuarioConId(1L, "jperez");
        when(usuarioRepository.findByCodigoUsuario("admin"))
                .thenReturn(Optional.of(usuarioConId(99L, "admin")));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(objetivo));
        when(usuarioRepository.save(any(Usuario.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        usuarioService.resetPassword(1L, "nuevaClave123");

        assertThat(passwordEncoder.matches("nuevaClave123", objetivo.getPasswordHash())).isTrue();
    }

    private void autenticarComo(String codigoUsuario) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(codigoUsuario, null, List.of()));
    }

    private Usuario usuarioConId(Long id, String codigoUsuario) {
        return new Usuario(id, codigoUsuario, "Nombre Completo", "hash", "operador", true, null);
    }

    private CrearUsuarioRequest crearRequest(String codigoUsuario) {
        CrearUsuarioRequest request = new CrearUsuarioRequest();
        request.setCodigoUsuario(codigoUsuario);
        request.setNombreCompleto("Juan Pérez");
        request.setPassword("claveCorrecta123");
        request.setRol("operador");
        request.setActivo(true);
        return request;
    }
}
