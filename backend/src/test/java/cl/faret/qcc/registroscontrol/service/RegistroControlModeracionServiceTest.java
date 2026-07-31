package cl.faret.qcc.registroscontrol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.exception.ResourceNotFoundException;
import cl.faret.qcc.registroscontrol.entity.RegistroControl;
import cl.faret.qcc.registroscontrol.repository.RegistroControlRepository;

@ExtendWith(MockitoExtension.class)
class RegistroControlModeracionServiceTest {

    @Mock
    private RegistroControlRepository registroControlRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    private RegistroControlModeracionService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new RegistroControlModeracionService(registroControlRepository, usuarioRepository);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validarRegistroLanzaResourceNotFoundSiNoExiste() {
        when(registroControlRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validarRegistro(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void validarRegistroFijaEstadoValidadoYResuelveUsuarioDesdeElJwt() {
        RegistroControl registro = registroBase(1L);
        when(registroControlRepository.findById(1L)).thenReturn(Optional.of(registro));
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());

        service.validarRegistro(1L);

        assertThat(registro.getEstadoValidacion()).isEqualTo("VALIDADO");
        assertThat(registro.getUsuarioValidacion()).isEqualTo("jperez");
        assertThat(registro.getFechaValidacion()).isNotNull();
    }

    @Test
    void rechazarRegistroFijaEstadoRechazado() {
        RegistroControl registro = registroBase(1L);
        when(registroControlRepository.findById(1L)).thenReturn(Optional.of(registro));
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());

        service.rechazarRegistro(1L);

        assertThat(registro.getEstadoValidacion()).isEqualTo("RECHAZADO");
    }

    @Test
    void eliminarRegistroMarcaEliminadoSinTocarElEstadoDeValidacion() {
        RegistroControl registro = registroBase(1L);
        when(registroControlRepository.findById(1L)).thenReturn(Optional.of(registro));

        service.eliminarRegistro(1L);

        assertThat(registro.isEliminado()).isTrue();
        assertThat(registro.getEstadoValidacion()).isEqualTo("PENDIENTE");
    }

    @Test
    void validarTodoSoloAplicaSobreLosRegistrosQueCumplenElFiltro() {
        RegistroControl uno = registroBase(1L);
        RegistroControl dos = registroBase(2L);
        when(registroControlRepository.findAll(any(Specification.class))).thenReturn(List.of(uno, dos));
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());

        int afectados = service.validarTodo(RegistroControlFiltros.construir(
                null, LocalDate.now(), LocalDate.now(), null, null, null, null, null));

        assertThat(afectados).isEqualTo(2);
        assertThat(uno.getEstadoValidacion()).isEqualTo("VALIDADO");
        assertThat(dos.getEstadoValidacion()).isEqualTo("VALIDADO");
    }

    @Test
    void rechazarTodoDevuelveCeroSiNingunRegistroCumpleElFiltro() {
        when(registroControlRepository.findAll(any(Specification.class))).thenReturn(List.of());
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());

        int afectados = service.rechazarTodo(RegistroControlFiltros.construir(
                null, LocalDate.now(), LocalDate.now(), null, null, null, null, null));

        assertThat(afectados).isZero();
    }

    @Test
    void validarRegistroConAreaRestringidaLanza404SiElRegistroPerteneceAOtraArea() {
        RegistroControl registro = registroBase(1L);
        when(registroControlRepository.findById(1L)).thenReturn(Optional.of(registro));

        assertThatThrownBy(() -> service.validarRegistro(1L, List.of("PRODUCCION")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void validarRegistroConAreaRestringidaFuncionaSiElRegistroPerteneceAEsaArea() {
        RegistroControl registro = registroBase(1L);
        when(registroControlRepository.findById(1L)).thenReturn(Optional.of(registro));
        autenticarComo("jperez");
        when(usuarioRepository.findByCodigoUsuarioAndActivoTrue("jperez")).thenReturn(Optional.empty());

        service.validarRegistro(1L, List.of("AREA1"));

        assertThat(registro.getEstadoValidacion()).isEqualTo("VALIDADO");
    }

    private void autenticarComo(String codigoUsuario) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(codigoUsuario, null, List.of()));
    }

    private RegistroControl registroBase(Long id) {
        return new RegistroControl(id, 10L, 1L, 1L, null, "AREA1", "NP-1", null, null, "A", 1L,
                null, false, null, null, LocalDate.now(), LocalTime.of(10, 0), "PENDIENTE", null,
                null, false);
    }
}
