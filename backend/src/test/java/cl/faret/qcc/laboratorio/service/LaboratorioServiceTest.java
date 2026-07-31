package cl.faret.qcc.laboratorio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import cl.faret.qcc.auth.repository.UsuarioRepository;
import cl.faret.qcc.laboratorio.dto.LaboratorioCatalogosResponse;
import cl.faret.qcc.laboratorio.dto.LaboratorioResumenResponse;
import cl.faret.qcc.laboratorio.entity.EnsayoLaboratorio;
import cl.faret.qcc.laboratorio.entity.Material;
import cl.faret.qcc.laboratorio.entity.RegistroEnsayo;
import cl.faret.qcc.laboratorio.repository.EnsayoLaboratorioRepository;
import cl.faret.qcc.laboratorio.repository.MaterialRepository;
import cl.faret.qcc.laboratorio.repository.RegistroEnsayoRepository;
import cl.faret.qcc.registroscontrol.entity.RegistroControl;
import cl.faret.qcc.registroscontrol.repository.ProcesoRepository;
import cl.faret.qcc.registroscontrol.repository.RegistroControlRepository;
import cl.faret.qcc.registroscontrol.service.RegistroControlEnriquecimientoService;

@ExtendWith(MockitoExtension.class)
class LaboratorioServiceTest {

    @Mock
    private RegistroEnsayoRepository registroEnsayoRepository;

    @Mock
    private EnsayoLaboratorioRepository ensayoLaboratorioRepository;

    @Mock
    private MaterialRepository materialRepository;

    @Mock
    private RegistroControlRepository registroControlRepository;

    @Mock
    private ProcesoRepository procesoRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private RegistroControlEnriquecimientoService enriquecimientoService;

    private LaboratorioService service;

    @BeforeEach
    void setUp() {
        service = new LaboratorioService(
                registroEnsayoRepository, ensayoLaboratorioRepository, materialRepository,
                registroControlRepository, procesoRepository, usuarioRepository, enriquecimientoService);
    }

    @Test
    void catalogosDelegaEnLosRepositoriosDeEnsayosYMaterialesActivos() {
        when(ensayoLaboratorioRepository.findByActivoTrueOrderByNombreAsc())
                .thenReturn(List.of(new EnsayoLaboratorio(1L, 1L, "Gramaje", "g/m2", true)));
        when(materialRepository.findByActivoTrueOrderByNombreAsc())
                .thenReturn(List.of(new Material(1L, "Carton", true)));

        LaboratorioCatalogosResponse catalogos = service.catalogos();

        assertThat(catalogos.getEnsayos()).hasSize(1);
        assertThat(catalogos.getEnsayos().get(0).getNombre()).isEqualTo("Gramaje");
        assertThat(catalogos.getMateriales()).hasSize(1);
    }

    @Test
    void resumenActivaElHistoricoSiLaVentanaPorDefectoNoTraeResultados() {
        RegistroControl registroControl = new RegistroControl(500L, 10L, 1L, 1L, null, "AREA1", "NP-1",
                null, null, "A", 1L, null, false, null, null, LocalDate.now().minusDays(60),
                LocalTime.of(9, 0), "PENDIENTE", null, null, false);

        // Llamadas a registroControlRepository.findAll: (1) ids "hoy" (2) ids ventana 30 dias (3) ids sin filtro de fecha
        when(registroControlRepository.findAll(any(Specification.class)))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of(registroControl));

        RegistroEnsayo ensayo = new RegistroEnsayo(1L, 500L, 20L, null, new BigDecimal("12.5"), null);

        // Llamadas a registroEnsayoRepository.findAll: (1) ventana 30 dias -> vacio (2) fallback historico -> con datos
        when(registroEnsayoRepository.findAll(any(Specification.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(ensayo));

        when(registroControlRepository.findAllById(anyList())).thenReturn(List.of(registroControl));
        when(usuarioRepository.findByIdIn(anyList())).thenReturn(List.of());
        when(procesoRepository.findAllById(anyList())).thenReturn(List.of());
        when(ensayoLaboratorioRepository.findAllById(anyList())).thenReturn(List.of());
        when(materialRepository.findAllById(anyList())).thenReturn(List.of());
        when(enriquecimientoService.primerAdjuntoPorRegistro(anyList())).thenReturn(Map.of());

        LaboratorioResumenResponse resumen = service.resumen(null, null, null, null, false);

        assertThat(resumen.isMostrandoHistorico()).isTrue();
        assertThat(resumen.getEnsayosPeriodo()).isEqualTo(1);
        assertThat(resumen.getFechaUltimoRegistro()).isEqualTo(registroControl.getFechaRegistro());
    }

    @Test
    void resumenNoActivaElHistoricoSiElUsuarioEspecificoFechasExplicitas() {
        when(registroControlRepository.findAll(any(Specification.class))).thenReturn(List.of());
        when(registroEnsayoRepository.findAll(any(Specification.class))).thenReturn(List.of());

        LaboratorioResumenResponse resumen =
                service.resumen(LocalDate.now().minusDays(5), LocalDate.now(), null, null, false);

        assertThat(resumen.isMostrandoHistorico()).isFalse();
        assertThat(resumen.getRegistros()).isEmpty();
    }
}
