package cl.faret.qcc.registroscontrol.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.exception.ResourceNotFoundException;
import cl.faret.qcc.registroscontrol.entity.RegistroControl;
import cl.faret.qcc.registroscontrol.repository.RegistroControlRepository;

// Unifica la logica de moderacion que en el Photino estaba duplicada palabra por palabra en
// DashboardRepository y RegistrosControlRepository (ValidarRegistro/RechazarRegistro/
// EliminarRegistro). La usan tanto el controller de Dashboard como el de RegistrosControl.
//
// Diferencias de fidelidad respecto al original (decisiones ya acordadas):
// - Se valida que el registro exista antes de mutar -> 404 (el Photino permitia id=0/inexistente
//   con un UPDATE de 0 filas que igual respondia "exito").
// - usuarioValidacion se resuelve desde el JWT (el Photino lo hardcodeaba al literal 'SUPERVISOR'
//   porque estos handlers no reciben sesion) -- mismo criterio ya usado en creadoPor/actualizadoPor
//   de los demas modulos.
// - validarTodo/rechazarTodo reciben los mismos filtros que el listado en pantalla, en vez de
//   actualizar toda la tabla sin condicion (bug real del Photino: sin WHERE alguno).
@Service
public class RegistroControlModeracionService {

    private static final String ESTADO_VALIDACION_VALIDADO = "VALIDADO";
    private static final String ESTADO_VALIDACION_RECHAZADO = "RECHAZADO";

    private final RegistroControlRepository registroControlRepository;
    private final UsuarioRepository usuarioRepository;

    public RegistroControlModeracionService(
            RegistroControlRepository registroControlRepository, UsuarioRepository usuarioRepository) {
        this.registroControlRepository = registroControlRepository;
        this.usuarioRepository = usuarioRepository;
    }

    @Transactional
    public void validarRegistro(Long id) {
        validarRegistro(id, null);
    }

    // RegistrosProduccion, a diferencia de Dashboard/RegistrosControl, SI restringe estas 3
    // acciones a `area='PRODUCCION'` en el Photino (WHERE id=@id AND area='PRODUCCION'): si el id
    // pertenece a otra area, para ese modulo "no existe". Se replica pasando la lista de areas
    // permitidas; null (los llamadores existentes) preserva el comportamiento sin restriccion.
    @Transactional
    public void validarRegistro(Long id, List<String> areasPermitidas) {
        RegistroControl registro = obtenerExistente(id, areasPermitidas);
        marcarValidado(registro, resolverUsuarioActual());
        registroControlRepository.save(registro);
    }

    @Transactional
    public void rechazarRegistro(Long id) {
        rechazarRegistro(id, null);
    }

    @Transactional
    public void rechazarRegistro(Long id, List<String> areasPermitidas) {
        RegistroControl registro = obtenerExistente(id, areasPermitidas);
        marcarRechazado(registro, resolverUsuarioActual());
        registroControlRepository.save(registro);
    }

    @Transactional
    public void eliminarRegistro(Long id) {
        eliminarRegistro(id, null);
    }

    @Transactional
    public void eliminarRegistro(Long id, List<String> areasPermitidas) {
        RegistroControl registro = obtenerExistente(id, areasPermitidas);
        registro.setEliminado(true);
        registroControlRepository.save(registro);
    }

    @Transactional
    public int validarTodo(Specification<RegistroControl> filtro) {
        String usuario = resolverUsuarioActual();
        return aplicarATodosLosFiltrados(filtro, registro -> marcarValidado(registro, usuario));
    }

    @Transactional
    public int rechazarTodo(Specification<RegistroControl> filtro) {
        String usuario = resolverUsuarioActual();
        return aplicarATodosLosFiltrados(filtro, registro -> marcarRechazado(registro, usuario));
    }

    private int aplicarATodosLosFiltrados(Specification<RegistroControl> filtro, Consumer<RegistroControl> aplicar) {
        List<RegistroControl> registros = registroControlRepository.findAll(filtro);
        registros.forEach(aplicar);
        registroControlRepository.saveAll(registros);
        return registros.size();
    }

    private void marcarValidado(RegistroControl registro, String usuario) {
        registro.setEstadoValidacion(ESTADO_VALIDACION_VALIDADO);
        registro.setFechaValidacion(LocalDateTime.now());
        registro.setUsuarioValidacion(usuario);
    }

    private void marcarRechazado(RegistroControl registro, String usuario) {
        registro.setEstadoValidacion(ESTADO_VALIDACION_RECHAZADO);
        registro.setFechaValidacion(LocalDateTime.now());
        registro.setUsuarioValidacion(usuario);
    }

    private RegistroControl obtenerExistente(Long id, List<String> areasPermitidas) {
        RegistroControl registro = registroControlRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Registro de control no encontrado"));
        if (areasPermitidas != null && !areasPermitidas.contains(registro.getArea())) {
            throw new ResourceNotFoundException("Registro de control no encontrado");
        }
        return registro;
    }

    private String resolverUsuarioActual() {
        String codigoUsuario = SecurityContextHolder.getContext().getAuthentication().getName();
        return usuarioRepository.findByCodigoUsuarioAndActivoTrue(codigoUsuario)
                .map(usuario -> usuario.getNombreCompleto())
                .orElse(codigoUsuario);
    }
}
