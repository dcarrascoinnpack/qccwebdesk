package cl.faret.qccweb.bridge;

import cl.faret.qccweb.auth.AuthProperties;
import cl.faret.qccweb.bridge.handlers.DashboardBridgeHandler;
import cl.faret.qccweb.bridge.handlers.InicioBridgeHandler;
import cl.faret.qccweb.bridge.handlers.MaquinasSeguimientoBridgeHandler;
import cl.faret.qccweb.bridge.handlers.RegistrosProduccionBridgeHandler;
import cl.faret.qccweb.upstream.InnpackApiClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Registro ÚNICO de las acciones habilitadas en la web. Lo que no está aquí, se rechaza.
 *
 * Roles INNPACK: la API solo permite crear "admin" y "operador"; "admin_ti" existe en los chequeos
 * de Photino (UsuariosHandler.IsAdmin). Photino no restringe Inicio por rol.
 */
@Configuration
public class BridgeConfig {

    static final Set<String> ROLES_INNPACK = Set.of("admin", "admin_ti", "operador");

    @Bean
    public InnpackApiClient innpackApiClient(AuthProperties properties) {
        return new InnpackApiClient(properties);
    }

    @Bean
    public InicioBridgeHandler inicioBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        return new InicioBridgeHandler(api, mapper);
    }

    @Bean
    public MaquinasSeguimientoBridgeHandler maquinasSeguimientoBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        return new MaquinasSeguimientoBridgeHandler(api, mapper);
    }

    @Bean
    public DashboardBridgeHandler dashboardBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        return new DashboardBridgeHandler(api, mapper);
    }

    @Bean
    public RegistrosProduccionBridgeHandler registrosProduccionBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        return new RegistrosProduccionBridgeHandler(api, mapper);
    }

    @Bean
    public ActionPolicy actionPolicy(
            InicioBridgeHandler inicio, MaquinasSeguimientoBridgeHandler maquinas, DashboardBridgeHandler dashboard,
            RegistrosProduccionBridgeHandler registrosProduccion) {
        return new ActionPolicy(List.of(
                // Fase 1c — Inicio. Solo lectura; no reenvía nada del payload.
                new ActionPolicy.Regla(
                        "inicio.getDashboard", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), inicio::getDashboard),
                // Fase 2a — Máquinas y Procesos. Solo lectura; solo maquinaId/sinLimite (sin identidad).
                // Photino y la API no restringen por rol (cualquier usuario INNPACK autenticado).
                new ActionPolicy.Regla(
                        "maquinasSeguimiento.obtenerResumen", Set.of("INNPACK"), ROLES_INNPACK, Map.of(),
                        maquinas::obtenerResumen),
                // Fase 2c — Inspecciones Calidad, SOLO LECTURA (filtros + resumen). Las escrituras
                // dashboard.validarRegistro/rechazarRegistro/eliminarRegistro/validarTodo/rechazarTodo
                // quedan deliberadamente fuera (deny-by-default).
                new ActionPolicy.Regla(
                        "dashboard.obtenerFiltros", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), dashboard::obtenerFiltros),
                new ActionPolicy.Regla(
                        "dashboard.obtenerResumen", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), dashboard::obtenerResumen),
                // Fase 2d — Inspecciones Producción, SOLO LECTURA (filtros + resumen). Mismo criterio
                // que 2c: las 5 escrituras registrosProduccion.* quedan fuera (deny-by-default).
                new ActionPolicy.Regla(
                        "registrosProduccion.obtenerFiltros", Set.of("INNPACK"), ROLES_INNPACK, Map.of(),
                        registrosProduccion::obtenerFiltros),
                new ActionPolicy.Regla(
                        "registrosProduccion.obtenerResumen", Set.of("INNPACK"), ROLES_INNPACK, Map.of(),
                        registrosProduccion::obtenerResumen)));
    }
}
