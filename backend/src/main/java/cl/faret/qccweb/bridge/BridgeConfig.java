package cl.faret.qccweb.bridge;

import cl.faret.qccweb.auth.AuthProperties;
import cl.faret.qccweb.bridge.handlers.CertificadosLiberacionBridgeHandler;
import cl.faret.qccweb.bridge.handlers.ControlDocumentalBridgeHandler;
import cl.faret.qccweb.bridge.handlers.DashboardBridgeHandler;
import cl.faret.qccweb.bridge.handlers.InicioBridgeHandler;
import cl.faret.qccweb.bridge.handlers.MaquinasSeguimientoBridgeHandler;
import cl.faret.qccweb.bridge.handlers.NoConformidadesBridgeHandler;
import cl.faret.qccweb.bridge.handlers.ProductoTerminadoBridgeHandler;
import cl.faret.qccweb.bridge.handlers.RegistrosControlBridgeHandler;
import cl.faret.qccweb.bridge.handlers.RegistrosProduccionBridgeHandler;
import cl.faret.qccweb.upstream.InnpackApiClient;
import java.util.ArrayList;
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
    public RegistrosControlBridgeHandler registrosControlBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        return new RegistrosControlBridgeHandler(api, mapper);
    }

    @Bean
    public ProductoTerminadoBridgeHandler productoTerminadoBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        return new ProductoTerminadoBridgeHandler(api, mapper);
    }

    @Bean
    public CertificadosLiberacionBridgeHandler certificadosLiberacionBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        return new CertificadosLiberacionBridgeHandler(api, mapper);
    }

    @Bean
    public ControlDocumentalBridgeHandler controlDocumentalBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        return new ControlDocumentalBridgeHandler(api, mapper);
    }

    @Bean
    public NoConformidadesBridgeHandler noConformidadesBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        return new NoConformidadesBridgeHandler(api, mapper);
    }

    @Bean
    public ActionPolicy actionPolicy(
            InicioBridgeHandler inicio, MaquinasSeguimientoBridgeHandler maquinas, DashboardBridgeHandler dashboard,
            RegistrosProduccionBridgeHandler registrosProduccion, RegistrosControlBridgeHandler registrosControl,
            ProductoTerminadoBridgeHandler productoTerminado, CertificadosLiberacionBridgeHandler certificados,
            ControlDocumentalBridgeHandler controlDocumental, NoConformidadesBridgeHandler noConformidades) {
        // "empresa" en Producto Terminado es contexto de sesión (cada módulo Photino la manda
        // hardcodeada), nunca un filtro elegible: se pisa con la sesión antes de llegar al handler.
        Map<String, IdentityOverride.Fuente> empresaDeSesion = Map.of("empresa", IdentityOverride.Fuente.EMPRESA);
        List<ActionPolicy.Regla> reglas = new ArrayList<>(List.of(
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
                        registrosProduccion::obtenerResumen),
                // Fase 2e — Registros de Control, SOLO LECTURA (grilla paginada + "traer todo" de
                // Exportar/Imprimir). Las escrituras registrosControl.validarRegistro/rechazarRegistro/
                // eliminarRegistro quedan fuera (deny-by-default).
                new ActionPolicy.Regla(
                        "registrosControl.obtenerRegistros", Set.of("INNPACK"), ROLES_INNPACK, Map.of(),
                        registrosControl::obtenerRegistros),
                // Fase 2f — Producto Terminado, SOLO LECTURA (5 acciones). Escrituras
                // productoTerminado.eliminar / actualizarFecha fuera (deny-by-default). Solo INNPACK:
                // no existe login FARET en la web todavía.
                new ActionPolicy.Regla(
                        "productoTerminado.filtros", Set.of("INNPACK"), ROLES_INNPACK, empresaDeSesion, productoTerminado::filtros),
                new ActionPolicy.Regla(
                        "productoTerminado.resumen", Set.of("INNPACK"), ROLES_INNPACK, empresaDeSesion, productoTerminado::resumen),
                new ActionPolicy.Regla(
                        "productoTerminado.list", Set.of("INNPACK"), ROLES_INNPACK, empresaDeSesion, productoTerminado::list),
                new ActionPolicy.Regla(
                        "productoTerminado.detalle", Set.of("INNPACK"), ROLES_INNPACK, empresaDeSesion, productoTerminado::detalle),
                new ActionPolicy.Regla(
                        "productoTerminado.exportarDetalle", Set.of("INNPACK"), ROLES_INNPACK, empresaDeSesion,
                        productoTerminado::exportarDetalle),
                // Fase 2g — Certificados de Liberación, SOLO LECTURA. "empresa" es un filtro de negocio
                // (select de la vista, valores del sistema legado), validado en el handler, no identidad.
                // El PDF lo valida el gateway y lo descarga el navegador (web-bridge.js), sin archivos
                // temporales. pdf.descargar (terminaciones) no lo usa ningún controller: fuera.
                new ActionPolicy.Regla(
                        "certificadosLiberacion.buscar", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), certificados::buscar),
                new ActionPolicy.Regla(
                        "certificadosLiberacion.calidadPdf.descargar", Set.of("INNPACK"), ROLES_INNPACK, Map.of(),
                        certificados::calidadPdfDescargar),
                // Fase 2h — Control Documental, SOLO LECTURA (payload plano). "alcanceEmpresa" es filtro
                // de negocio validado en el handler. adjunto.abrir: previsualiza o descarga en el
                // navegador, sin archivos temporales. Escrituras (create/update/version.crear/eliminar/
                // adjunto.subir) fuera (deny-by-default).
                new ActionPolicy.Regla(
                        "controlDocumental.list", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), controlDocumental::list),
                new ActionPolicy.Regla(
                        "controlDocumental.get", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), controlDocumental::get),
                new ActionPolicy.Regla(
                        "controlDocumental.adjunto.abrir", Set.of("INNPACK"), ROLES_INNPACK, Map.of(),
                        controlDocumental::adjuntoAbrir),
                // Fase 2i — No Conformidades (solo INNPACK), SOLO LECTURA (payload plano, sin "empresa").
                // adjuntos.abrir: solo PDF/PNG/JPEG con firma real, la vista lo muestra en la página.
                // Las 29 escrituras (create/update/eliminar/gestion/cerrar/seguimiento.crear/
                // analisis.guardar/acciones.*/adjuntos.subir|eliminar/catalogos.*.crear|desactivar)
                // quedan fuera (deny-by-default).
                new ActionPolicy.Regla(
                        "noConformidades.list", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), noConformidades::list),
                new ActionPolicy.Regla(
                        "noConformidades.resumen", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), noConformidades::resumen),
                new ActionPolicy.Regla(
                        "noConformidades.filtrosOpciones", Set.of("INNPACK"), ROLES_INNPACK, Map.of(),
                        noConformidades::filtrosOpciones),
                new ActionPolicy.Regla(
                        "noConformidades.get", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), noConformidades::get),
                new ActionPolicy.Regla(
                        "noConformidades.seguimiento.list", Set.of("INNPACK"), ROLES_INNPACK, Map.of(),
                        noConformidades::seguimientoList),
                new ActionPolicy.Regla(
                        "noConformidades.analisis.get", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), noConformidades::analisisGet),
                new ActionPolicy.Regla(
                        "noConformidades.acciones.list", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), noConformidades::accionesList),
                new ActionPolicy.Regla(
                        "noConformidades.adjuntos.list", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), noConformidades::adjuntosList),
                new ActionPolicy.Regla(
                        "noConformidades.adjuntos.abrir", Set.of("INNPACK"), ROLES_INNPACK, Map.of(),
                        noConformidades::adjuntosAbrir)));
        for (String catalogo : NoConformidadesBridgeHandler.CATALOGOS) {
            reglas.add(new ActionPolicy.Regla(
                    "noConformidades.catalogos." + catalogo + ".list", Set.of("INNPACK"), ROLES_INNPACK, Map.of(),
                    noConformidades.catalogoList(catalogo)));
        }
        return new ActionPolicy(reglas);
    }
}
