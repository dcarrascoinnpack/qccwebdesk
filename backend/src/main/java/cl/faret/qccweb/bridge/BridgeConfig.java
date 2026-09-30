package cl.faret.qccweb.bridge;

import cl.faret.qccweb.auth.AuthProperties;
import cl.faret.qccweb.bridge.handlers.CertificadosLiberacionBridgeHandler;
import cl.faret.qccweb.bridge.handlers.ControlDocumentalBridgeHandler;
import cl.faret.qccweb.bridge.handlers.DashboardBridgeHandler;
import cl.faret.qccweb.bridge.handlers.InicioBridgeHandler;
import cl.faret.qccweb.bridge.handlers.LiberacionCalidadBridgeHandler;
import cl.faret.qccweb.bridge.handlers.MaquinasSeguimientoBridgeHandler;
import cl.faret.qccweb.bridge.handlers.MuestraLaboratorioBridgeHandler;
import cl.faret.qccweb.bridge.handlers.NoConformidadesBridgeHandler;
import cl.faret.qccweb.bridge.handlers.ProductoTerminadoBridgeHandler;
import cl.faret.qccweb.bridge.handlers.RecepcionCalidadBridgeHandler;
import cl.faret.qccweb.bridge.handlers.RegistrosControlBridgeHandler;
import cl.faret.qccweb.bridge.handlers.RegistrosProduccionBridgeHandler;
import cl.faret.qccweb.bridge.handlers.TalleresExternosBridgeHandler;
import cl.faret.qccweb.bridge.handlers.UsuariosBridgeHandler;
import cl.faret.qccweb.upstream.FpsApiClient;
import cl.faret.qccweb.upstream.FpsProperties;
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
    /** Gestión de Usuarios: solo admin_ti desde Photino 1.8.14 (UsuariosHandler.IsAdmin y PermisosService). */
    static final Set<String> ROLES_ADMIN_TI_INNPACK = Set.of("admin_ti");
    /**
     * ESCRITURAS operativas aditivas (docs/matriz-escrituras-propuesta.md). Política técnica inicial:
     * estado PENDIENTE_VALIDACION_NEGOCIO hasta que el negocio confirme los permisos funcionales.
     */
    static final Set<String> ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO = Set.of("operador", "admin", "admin_ti");
    /** Catálogos de NC con `crear` habilitado en la web (por fase, cada uno auditado y validado). */
    static final List<String> CATALOGOS_CREAR_HABILITADOS = List.of(
            "clientes", "categoriasDefecto", "tiposFalla", "supervisores", "revisores", "areas", "familiasProducto",
            "impactos", "niveles");

    @Bean
    public InnpackApiClient innpackApiClient(AuthProperties properties) {
        return new InnpackApiClient(properties);
    }

    /** fps-api (Fase 3v): API key del servidor; sin base URL responde como Photino sin fps-api configurada. */
    @Bean
    public FpsApiClient fpsApiClient(FpsProperties properties) {
        return new FpsApiClient(properties);
    }

    @Bean
    public LiberacionCalidadBridgeHandler liberacionCalidadBridgeHandler(FpsApiClient fps, ObjectMapper mapper) {
        return new LiberacionCalidadBridgeHandler(fps, mapper);
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
    public MuestraLaboratorioBridgeHandler muestraLaboratorioBridgeHandler(InnpackApiClient api, FpsApiClient fps,
            ObjectMapper mapper) {
        return new MuestraLaboratorioBridgeHandler(api, fps, mapper);
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
            MuestraLaboratorioBridgeHandler laboratorio, TalleresExternosBridgeHandler talleres,
            LiberacionCalidadBridgeHandler liberacionCalidad) {
        // "empresa" en Producto Terminado es contexto de sesión (cada módulo Photino la manda
        // hardcodeada), nunca un filtro elegible: se pisa con la sesión antes de llegar al handler.
        Map<String, IdentityOverride.Fuente> empresaDeSesion = Map.of("empresa", IdentityOverride.Fuente.EMPRESA);
        List<ActionPolicy.Regla> reglas = new ArrayList<>(List.of(
                // Fase 1c — Inicio. Solo lectura; no reenvía nada del payload.
                new ActionPolicy.Regla(
                        "inicio.getDashboard", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), inicio::getDashboard),
                // Fase 3u — permisos.mios (Photino 1.8.14): niveles efectivos por módulo de la PROPIA sesión, para el
                // menú y el modo "Solo vista". Calculados en el gateway (rol + personalizados leídos al iniciar sesión).
                new ActionPolicy.Regla(
                        PermisosModulo.ACCION_PERMISOS_MIOS, Set.of("INNPACK"), ROLES_INNPACK, Map.of(),
                        (payload, usuario) -> BridgeResult.ok(PermisosModulo.nivelesEfectivos(usuario))),
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
                // Fase 3v — columna "Liberación Calidad" de PNC (Photino 1.8.14): GET fps-api liberaciones/inspectores con
                // la API key del servidor. Solo lectura; sin fps-api configurada responde como Photino (celda "No
                // disponible"). Photino no restringe por rol (cualquier sesión que abra No Conformidades).
                new ActionPolicy.Regla(
                        "liberacionCalidad.inspectores", Set.of("INNPACK"), ROLES_INNPACK, Map.of(),
                        liberacionCalidad::inspectores),
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
                // real. Escrituras crear/nc.crear/plan.generar y sap.* (otra API externa) fuera (deny-by-default).
                new ActionPolicy.Regla(
                        "recepcion.list", Set.of("INNPACK"), ROLES_INNPACK, empresaDeSesion, recepcionCalidad::list),
                new ActionPolicy.Regla(
                        "recepcion.detalle", Set.of("INNPACK"), ROLES_INNPACK, empresaDeSesion, recepcionCalidad::detalle),
                new ActionPolicy.Regla(
                        "recepcion.foto.abrir", Set.of("INNPACK"), ROLES_INNPACK, empresaDeSesion, recepcionCalidad::fotoAbrir),
                // Fase 3q — PRIMERA escritura de Recepción: guardar bobinas muestreadas. Roles = Photino (cualquier
                // usuario INNPACK); "usuario" ← sesión; lote confirmado con la empresa de sesión; detección de cambios
                // concurrentes en la selección; recurso "recepcion:<lote>:muestreadas:<n>".
                new ActionPolicy.Regla(
                        "recepcion.bobinas.muestrear", Set.of("INNPACK"), ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO,
                        Map.of("usuario", IdentityOverride.Fuente.NOMBRE_COMPLETO),
                        recepcionCalidad::bobinasMuestrear, RecepcionCalidadBridgeHandler::recursoMuestreo),
                // Fase 3s — crear muestra de Laboratorio desde el lote. Roles = Photino (cualquier usuario INNPACK);
                // empresa/usuarioId/usuarioNombre ← sesión (los arma el handler); lote de la empresa de sesión; creaciones
                // del mismo lote serializadas + detección de cambios; recurso "recepcion:<lote>:muestra:<id>".
                new ActionPolicy.Regla(
                        "recepcion.muestra.crear", Set.of("INNPACK"), ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO,
                        Map.of(), recepcionCalidad::muestraCrear, RecepcionCalidadBridgeHandler::recursoMuestra),
                // Fase 3t — actualizar el estado manual del lote. Roles = Photino (cualquier usuario INNPACK); lote de
                // la empresa de sesión (la API no filtra empresa ni eliminado — R5); estado restringido a los 3 valores
                // del <select> de Photino; detección de cambios concurrentes; recurso "recepcion:<lote>:estado:<valor>".
                new ActionPolicy.Regla(
                        "recepcion.estado.actualizar", Set.of("INNPACK"), ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO,
                        Map.of(), recepcionCalidad::estadoActualizar, RecepcionCalidadBridgeHandler::recursoEstado),
                // Fase 2k — Gestión de Usuarios, SOLO LECTURA. Fase 3u: solo admin_ti como Photino 1.8.14
                // (antes admin/admin_ti). Respuesta reproyectada a los 7 campos PascalCase de Photino.
                // Escrituras y matriz de permisos fuera (deny-by-default; se abordan aparte).
                new ActionPolicy.Regla(
                        "usuarios.list", Set.of("INNPACK"), ROLES_ADMIN_TI_INNPACK, Map.of(), usuarios::list),
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
                // Fase 3w — materiales FPS del detalle de una muestra (fps-api, API key del servidor). Solo lectura;
                // solo el proceso FPS de una muestra abierta en la sesión.
                new ActionPolicy.Regla(
                        "muestraLab.materialesFps", Set.of("INNPACK"), ROLES_INNPACK, Map.of(), laboratorio::materialesFps),
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
                        noConformidades::analisisGuardar, NoConformidadesBridgeHandler::recursoAnalisis),
                // Fase 3j — alta de NC ("Nueva NC"): identidad "creadoPor" = sesión; lista blanca de las claves de
                // Photino (sin empresa/ambito: la NC queda como en Photino); cabecera recalculada en el gateway;
                // largos del esquema; recurso "nc:<id creado>". Los adjuntos (adjuntos.subir) siguen denegados.
                new ActionPolicy.Regla(
                        "noConformidades.create", Set.of("INNPACK"),
                        ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO,
                        Map.of("creadoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO),
                        noConformidades::ncCrear, NoConformidadesBridgeHandler::recursoNcCreada),
                // Fase 3k — subida de adjuntos (alta de NC y modal de análisis): SOLO por /api/v1/bridge/archivo;
                // identidad "subidoPor" = sesión; firma real y tamaño decodificado; nombre saneado al subir;
                // recurso "nc:<id>:adjunto:<id>". Reemplazar el PDF lo resuelve la API (oculta el anterior).
                new ActionPolicy.Regla(
                        "noConformidades.adjuntos.subir", Set.of("INNPACK"),
                        ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO,
                        Map.of("subidoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO),
                        noConformidades::adjuntosSubir, NoConformidadesBridgeHandler::recursoAdjunto),
                // Fase 3l/3o — editar NC: mismo contrato que create; identidad "actualizadoPor" = sesión; exige haber
                // abierto la NC (huella de noConformidades.get) y rechaza si cambió (lost update) o no existe; una NC
                // cerrada se edita igual que en Photino; recurso "nc:<id>".
                new ActionPolicy.Regla(
                        "noConformidades.update", Set.of("INNPACK"),
                        ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO,
                        Map.of("actualizadoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO),
                        noConformidades::ncActualizar, (p, data) -> NoConformidadesBridgeHandler.recursoNc(p)),
                // Fase 3m/3o — modal "Gestionar": mismas reglas y roles que Photino (cualquier usuario INNPACK; la matriz de
                // negocio más restrictiva queda como propuesta para TODO el sistema); identidad de sesión y lost update.
                new ActionPolicy.Regla(
                        "noConformidades.gestion.actualizar", Set.of("INNPACK"), ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO,
                        Map.of("actualizadoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO),
                        noConformidades::gestionActualizar, NoConformidadesBridgeHandler::recursoGestion),
                new ActionPolicy.Regla(
                        "noConformidades.cerrar", Set.of("INNPACK"), ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO,
                        Map.of("cerradoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO),
                        noConformidades::cerrar, NoConformidadesBridgeHandler::recursoCierre),
                // Fase 3n — estado de una acción correctiva (modal de análisis): solo `estado` viene del navegador; el
                // resto son los valores ORIGINALES registrados al listar (sin doble escape); lost update; NC cerrada
                // permitida como en Photino (decisión 3n-b). Identidad "actualizadoPor" = sesión.
                new ActionPolicy.Regla(
                        "noConformidades.acciones.actualizar", Set.of("INNPACK"),
                        ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO,
                        Map.of("actualizadoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO),
                        noConformidades::accionesActualizar, NoConformidadesBridgeHandler::recursoAccionActualizada),
                // Fase 3r — Photino dd147ad (NC Internas y PNC): borrado lógico de NC y de adjuntos, con los roles de Photino
                // (cualquier usuario INNPACK); actualizadoPor ← sesión; NC releída antes de eliminar.
                new ActionPolicy.Regla(
                        "noConformidades.eliminar", Set.of("INNPACK"), ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO,
                        Map.of("actualizadoPor", IdentityOverride.Fuente.NOMBRE_COMPLETO),
                        noConformidades::eliminar, NoConformidadesBridgeHandler::recursoEliminada),
                new ActionPolicy.Regla(
                        "noConformidades.adjuntos.eliminar", Set.of("INNPACK"), ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO,
                        Map.of(), noConformidades::adjuntosEliminar, NoConformidadesBridgeHandler::recursoAdjuntoEliminado)));
        // Fase 3d (clientes), 3e (categoriasDefecto), 3f (tiposFalla, supervisores, revisores), 3g (areas), 3h
        // (familiasProducto, impactos; largo 50) y 3i (niveles; largo 20) — escrituras del combo de
        // catálogos (acción dinámica, mismo contrato): identidad "creadoPor" = sesión; `nombre` con el contrato real
        // de la API (trim/colapso, ≤ largo de la columna, sin HTML ni controles); duplicados resueltos por la API;
        // recurso "catalogo:<cat>:<id>".
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
        // Fase 3r — catálogos de NC Internas (Photino dd147ad): solo lectura (la pantalla no crea ni desactiva).
        for (String catalogo : NoConformidadesBridgeHandler.CATALOGOS_NCI) {
            reglas.add(new ActionPolicy.Regla(
                    "noConformidades.catalogos." + catalogo + ".list", Set.of("INNPACK"), ROLES_INNPACK, Map.of(),
                    noConformidades.catalogoList(catalogo)));
        }
        return new ActionPolicy(reglas);
    }
}
