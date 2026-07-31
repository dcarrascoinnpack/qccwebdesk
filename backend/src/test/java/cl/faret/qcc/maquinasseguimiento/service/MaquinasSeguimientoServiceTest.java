package cl.faret.qcc.maquinasseguimiento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.maquinasseguimiento.dto.MaquinasSeguimientoResumenResponse;
import cl.faret.qcc.registroscontrol.entity.EstadoCatalogo;
import cl.faret.qcc.registroscontrol.entity.Maquina;
import cl.faret.qcc.registroscontrol.entity.RegistroControl;
import cl.faret.qcc.registroscontrol.repository.EstadoCatalogoRepository;
import cl.faret.qcc.registroscontrol.repository.FormularioControlRepository;
import cl.faret.qcc.registroscontrol.repository.MaquinaRepository;
import cl.faret.qcc.registroscontrol.repository.ProcesoRepository;
import cl.faret.qcc.registroscontrol.repository.RegistroControlRepository;

@ExtendWith(MockitoExtension.class)
class MaquinasSeguimientoServiceTest {

    @Mock
    private MaquinaRepository maquinaRepository;

    @Mock
    private ProcesoRepository procesoRepository;

    @Mock
    private RegistroControlRepository registroControlRepository;

    @Mock
    private EstadoCatalogoRepository estadoCatalogoRepository;

    @Mock
    private FormularioControlRepository formularioControlRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    private MaquinasSeguimientoService service;

    @BeforeEach
    void setUp() {
        service = new MaquinasSeguimientoService(
                maquinaRepository, procesoRepository, registroControlRepository, estadoCatalogoRepository,
                formularioControlRepository, usuarioRepository);
    }

    @Test
    void resumenSinMaquinaIdSoloDevuelveElCatalogo() {
        when(maquinaRepository.countByActivoTrue()).thenReturn(5L);
        when(registroControlRepository.countDistinctMaquinaId()).thenReturn(3L);
        when(maquinaRepository.findByActivoTrueOrderByNombreAsc())
                .thenReturn(List.of(new Maquina(1L, 1L, "Bobst 142-1", "MAQ-001", true)));
        when(procesoRepository.findAllById(anyList())).thenReturn(List.of());

        MaquinasSeguimientoResumenResponse resumen = service.resumen(null, false);

        assertThat(resumen.getTotalMaquinas()).isEqualTo(5);
        assertThat(resumen.getMaquinasConRegistros()).isEqualTo(3);
        assertThat(resumen.getMaquinas()).hasSize(1);
        assertThat(resumen.getRegistrosMaquinaSeleccionada()).isNull();
        assertThat(resumen.getRechazosMaquinaSeleccionada()).isNull();
        assertThat(resumen.getRegistros()).isEmpty();
    }

    @Test
    void resumenConMaquinaIdResuelveRechazosContraElIdDelEstadoRechazado() {
        when(maquinaRepository.countByActivoTrue()).thenReturn(1L);
        when(registroControlRepository.countDistinctMaquinaId()).thenReturn(1L);
        when(maquinaRepository.findByActivoTrueOrderByNombreAsc())
                .thenReturn(List.of(new Maquina(7L, 1L, "Bobst 142-1", "MAQ-001", true)));
        when(procesoRepository.findAllById(anyList())).thenReturn(List.of());
        when(registroControlRepository.countByMaquinaId(7L)).thenReturn(20L);
        when(estadoCatalogoRepository.findByNombreIgnoreCase("Rechazado"))
                .thenReturn(Optional.of(new EstadoCatalogo(3L, "Rechazado", true)));
        when(registroControlRepository.countByMaquinaIdAndEstadoId(7L, 3L)).thenReturn(4L);

        RegistroControl registro = new RegistroControl(1L, 10L, 1L, 7L, null, "AREA1", "NP-REAL",
                "COD-PRODUCTO", null, "A", 3L, null, false, null, null, LocalDate.now(),
                LocalTime.of(9, 0), "RECHAZADO", null, null, false);
        when(registroControlRepository.findByMaquinaIdOrderByFechaRegistroDescIdDesc(7L))
                .thenReturn(List.of(registro));
        when(usuarioRepository.findByIdIn(anyList())).thenReturn(List.of());
        when(estadoCatalogoRepository.findAllById(anyList())).thenReturn(List.of());
        when(formularioControlRepository.findAllById(anyList())).thenReturn(List.of());
        when(maquinaRepository.findAllById(anyList()))
                .thenReturn(List.of(new Maquina(7L, 1L, "Bobst 142-1", "MAQ-001", true)));

        MaquinasSeguimientoResumenResponse resumen = service.resumen(7L, false);

        assertThat(resumen.getRegistrosMaquinaSeleccionada()).isEqualTo(20L);
        assertThat(resumen.getRechazosMaquinaSeleccionada()).isEqualTo(4L);
        assertThat(resumen.getRegistros()).hasSize(1);
        // Usa la columna np real, no codigo_producto (bug del Photino ya corregido).
        assertThat(resumen.getRegistros().get(0).getNp()).isEqualTo("NP-REAL");
    }

    @Test
    void resumenDevuelveCeroRechazosSiNoExisteElEstadoRechazadoEnElCatalogo() {
        when(maquinaRepository.countByActivoTrue()).thenReturn(1L);
        when(registroControlRepository.countDistinctMaquinaId()).thenReturn(1L);
        when(maquinaRepository.findByActivoTrueOrderByNombreAsc()).thenReturn(List.of());
        when(registroControlRepository.countByMaquinaId(7L)).thenReturn(0L);
        when(estadoCatalogoRepository.findByNombreIgnoreCase("Rechazado")).thenReturn(Optional.empty());
        when(registroControlRepository.findByMaquinaIdOrderByFechaRegistroDescIdDesc(7L)).thenReturn(List.of());

        MaquinasSeguimientoResumenResponse resumen = service.resumen(7L, false);

        assertThat(resumen.getRechazosMaquinaSeleccionada()).isEqualTo(0L);
    }
}
