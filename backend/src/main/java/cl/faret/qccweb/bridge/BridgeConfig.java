package cl.faret.qccweb.bridge;

import cl.faret.qccweb.auth.AuthProperties;
import cl.faret.qccweb.bridge.handlers.CertificadosLiberacionBridgeHandler;
import cl.faret.qccweb.bridge.handlers.ControlDocumentalBridgeHandler;
import cl.faret.qccweb.bridge.handlers.DashboardBridgeHandler;
import cl.faret.qccweb.bridge.handlers.InicioBridgeHandler;
import cl.faret.qccweb.bridge.handlers.MaquinasSeguimientoBridgeHandler;
import cl.faret.qccweb.bridge.handlers.MuestraLaboratorioBridgeHandler;
import cl.faret.qccweb.bridge.handlers.NoConformidadesBridgeHandler;
import cl.faret.qccweb.bridge.handlers.ProductoTerminadoBridgeHandler;
import cl.faret.qccweb.bridge.handlers.RecepcionCalidadBridgeHandler;
import cl.faret.qccweb.bridge.handlers.RegistrosControlBridgeHandler;
import cl.faret.qccweb.bridge.handlers.RegistrosProduccionBridgeHandler;
import cl.faret.qccweb.bridge.handlers.TalleresExternosBridgeHandler;
import cl.faret.qccweb.bridge.handlers.UsuariosBridgeHandler;
import cl.faret.qccweb.upstream.InnpackApiClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
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
    static final Set<String> ROLES_ADMIN_INNPACK = Set.of("admin", "admin_ti");
    /**
     * ESCRITURAS operativas aditivas (docs/matriz-escrituras-propuesta.md). Política técnica inicial:
     * estado PENDIENTE_VALIDACION_NEGOCIO hasta que el negocio confirme los permisos funcionales.
     */
    static final Set<String> ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO = Set.of("operador", "admin", "admin_ti");
    /** Catálogos de NC con `crear` habilitado en la web (por fase, cada uno auditado y validado). */
    static final List<String> CATALOGOS_CREAR_HABILITADOS = List.of(
            "clientes", "categoriasDefecto", "tiposFalla", "supervisores", "revisores", "areas");

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
    public RecepcionCalidadBridgeHandler recepcionCalidadBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        return new RecepcionCalidadBridgeHandler(api, mapper);
    }

    @Bean
    public UsuariosBridgeHandler usuariosBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        return new UsuariosBridgeHandler(api, mapper);
    }

    @Bean
    public MuestraLaboratorioBridgeHandler muestraLaboratorioBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        return new MuestraLaboratorioBridgeHandler(api, mapper);
    }

    @Bean
    public TalleresExternosBridgeHandler talleresExternosBridgeHandler(InnpackApiClient api, ObjectMapper mapper) {
        return new TalleresExternosBridgeHandler(api, mapper);
    }

    /** Límite de escrituras por usuario de sesión (lecturas sin límite propio). */
    @Bean
    public EscrituraRateLimiter escrituraRateLimiter(
            @Value("${qcc.web.bridge.escrituras-por-minuto:30}") int escriturasPorMinuto, Clock clock) {
        return new EscrituraRateLimiter(escriturasPorMinuto, Duration.ofMinutes(1), clock);
    }

    @Bean
    public ActionPolicy actionPolicy(
            InicioBridgeHandler inicio, MaquinasSeguimientoBridgeHandler maquinas, DashboardBridgeHandler dashboard,
            RegistrosProduccionBridgeHandler registrosProduccion, RegistrosControlBridgeHandler registrosControl,
            ProductoTerminadoBridgeHandler productoTerminado, CertificadosLiberacionBridgeHandler certificados,
            ControlDocumentalBridgeHandler controlDocumental, NoConformidadesBridgeHandler noConformidades,
            RecepcionCalidadBridgeHandler recepcionCalidad, UsuariosBridgeHandler usuarios,
            MuestraLaboratorioBridgeHandler laboratorio, TalleresExternosBridgeHandler talleres) {
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
                        noConformidades::adjuntosAbrir),
                // Fase 2j — Recepción Calidad, SOLO LECTURA (payload en "data"). "empresa" = sesión.
                // foto.abrir: lote confirmado con el detalle de la empresa de sesión y MIME por firma
                // real. Escrituras (crear/nc.crear/plan.generar/bobinas.muestrear/muestra.crear/
                // estado.actualizar) y sap.* (otra API externa) fuera (deny-by-default).
                new ActionPolicy.Regla(
                        "recepcion.list", Set.of("INNPACK"), ROLES_INNPACK, empresaDeSesion, recepcionCalidad::list),
                new ActionPolicy.Regla(
                        "recepcion.detalle", Set.of("INNPACK"), ROLES_INNPACK, empresaDeSesion, recepcionCalidad::detalle),
                new ActionPolicy.Regla(
                        "recepcion.foto.abrir", Set.of("INNPACK"), ROLES_INNPACK, empresaDeSesion, recepcionCalidad::fotoAbrir),
                // Fase 2k — Gestión de Usuarios, SOLO LECTURA y SOLO admin/admin_ti (UsuariosHandler.
                // IsAdmin + [Authorize(Roles)] de la API): primera regla con roles restringidos.
                // Respuesta reproyectada a los 7 campos PascalCase de Photino. create/delete/
                // resetPassword fuera (deny-by-default).
                new ActionPolicy.Regla(
                        "usuarios.list", Set.of("INNPACK"), ROLES_ADMIN_INNPACK, Map.of(), usuarios::list),
                // Fase 2l — Laboratorio - Muestras, SOLO LECTURA contra la API INNPACK (payload en
                // "data", sin empresa ni rol). adjunto.abrir: validado como Control Documental y
                // previsualizado/descargado en el navegador. Fuera: consultarNp/consultarRegistroProduccion/
                // materialesFps/resolverBobina (otras APIs externas) y las 25 escrituras.
                new ActionPolicy.Regla("muestraLab.list", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), laboratorio::list),
                new ActionPolicy.Regla("muestraLab.detalle", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), laboratorio::detalle),
                new ActionPolicy.Regla("muestraLab.catalogos", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), laboratorio::catalogos),
                new ActionPolicy.Regla("muestraLab.indicadores", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), laboratorio::indicadores),
                new ActionPolicy.Regla("muestraLab.metodo.list", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), laboratorio::metodoList),
                new ActionPolicy.Regla(
                        "muestraLab.especificacion.list", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), laboratorio::especificacionList),
                new ActionPolicy.Regla(
                        "muestraLab.bobinaHistorial", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), laboratorio::bobinaHistorial),
                new ActionPolicy.Regla(
                        "muestraLab.registroProduccion.list", Set.of("INNPACK"), ROLES_INNPACK, Map.of(),
                        laboratorio::registroProduccionList),
                new ActionPolicy.Regla(
                        "muestraLab.adjunto.abrir", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), laboratorio::adjuntoAbrir),
                // Fase 2m — Talleres Externos, SOLO LECTURA (payload en "data", sin empresa ni rol).
                // Escrituras (create/update/eliminar/catalogos.eliminar*/sincronizarFps) fuera.
                new ActionPolicy.Regla("talleresExternos.list", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), talleres::list),
                new ActionPolicy.Regla(
                        "talleresExternos.catalogos", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), talleres::catalogos),
                new ActionPolicy.Regla(
                        "talleresExternos.historialLiberaciones", Set.of("INNPACK"), ROLES_INNPACK, Map.of(),
                        talleres::historialLiberaciones),
                // Fase 3a — PRIMERA ESCRITURA (vertical slice, patrón oficial de escrituras):
                // - roles: ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO (matriz; falta validar negocio);
                // - identidad: "autor" = SIEMPRE el usuario de la sesión (el handler además arma el cuerpo
                //   solo con comentario + autor de sesión; nada más del navegador llega a la API);
                // - recurso auditado "nc:<id>" + límite de escrituras por usuario (BridgeController).
                new ActionPolicy.Regla(
                        "noConformidades.seguimiento.crear", Set.of("INNPACK"),
                        ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO,
                        Map.of("autor", IdentityOverride.Fuente.NOMBRE_COMPLETO),
                        noConformidades::seguimientoCrear, (p, data) -> NoConformidadesBridgeHandler.recursoNc(p)),
                // Fase 3b — SEGUNDA ESCRITURA, mismo patrón: identidad "creadoPor" = sesión; datos de negocio
                // (responsable, descripción, fecha, prioridad, análisis) validados con lista blanca y conservados.
                new ActionPolicy.Regla(
                        "noConformidades.acciones.crear", Set.of("INNPACK"),
                        ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO,
                        Map.of("creadoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO),
                        noConformidades::accionesCrear, NoConformidadesBridgeHandler::recursoAccion),
                // Fase 3c — TERCERA ESCRITURA (sobrescribe el análisis vigente, sin versión en la API): identidad
                // "usuario" = sesión; textos de causa raíz con lista blanca y límites del esquema; detección de
                // lost update por huella de lectura en la sesión; auditoría NUEVO/REEMPLAZO.
                new ActionPolicy.Regla(
                        "noConformidades.analisis.guardar", Set.of("INNPACK"),
                        ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO,
                        Map.of("usuario", IdentityOverride.Fuente.NOMBRE_COMPLETO),
                        noConformidades::analisisGuardar, NoConformidadesBridgeHandler::recursoAnalisis)));
        // Fase 3d (clientes), 3e (categoriasDefecto), 3f (tiposFalla, supervisores, revisores) y 3g (areas) —
        // escrituras del combo de catálogos (acción dinámica, mismo contrato): identidad "creadoPor" = sesión;
        // `nombre` con el contrato real de la API (trim/colapso, ≤ largo de la columna, sin HTML ni controles);
        // duplicados resueltos por la API; recurso "catalogo:<cat>:<id>".
        // SOLO los catálogos listados: crear en los demás, desactivar/editar/eliminar siguen denegados.
        for (String catalogo : CATALOGOS_CREAR_HABILITADOS) {
            reglas.add(new ActionPolicy.Regla(
                    "noConformidades.catalogos." + catalogo + ".crear", Set.of("INNPACK"),
                    ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO,
                    Map.of("creadoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO),
                    noConformidades.catalogoCrear(catalogo),
                    (p, data) -> NoConformidadesBridgeHandler.recursoCatalogo(catalogo, data)));
        }
        for (String catalogo : NoConformidadesBridgeHandler.CATALOGOS) {
            reglas.add(new ActionPolicy.Regla(
                    "noConformidades.catalogos." + catalogo + ".list", Set.of("INNPACK"), ROLES_INNPACK, Map.of(),
                    noConformidades.catalogoList(catalogo)));
        }
        return new ActionPolicy(reglas);
    }
}
