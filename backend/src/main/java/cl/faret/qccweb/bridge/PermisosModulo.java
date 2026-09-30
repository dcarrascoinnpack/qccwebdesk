package cl.faret.qccweb.bridge;

import cl.faret.qccweb.auth.SessionUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Permisos por módulo (Fase 3u) — port de PermisosService de Photino 1.8.14 (fd7f076), la misma regla:
 * <ol>
 * <li>admin_ti → EDITAR en todo, siempre.</li>
 * <li>Gestión de Usuarios → solo admin_ti (no se personaliza).</li>
 * <li>Permiso personalizado del usuario (API: usuario_modulo_permiso, leído al iniciar sesión) → manda ese.</li>
 * <li>Sin personalizar → la regla por rol (INNPACK: EDITAR en todo).</li>
 * </ol>
 * Inicio nunca baja de VER. Cada acción viaja con el módulo abierto (`_modulo`, lo agrega web-bridge.js como el
 * PhotinoBridge de Photino): SIN_ACCESO rechaza todo, VER rechaza escrituras, y una escritura debe pertenecer al
 * módulo desde el que se envía.
 *
 * Seguridad adicional de la web (transparente en el uso normal): en la web el navegador puede declarar cualquier
 * `_modulo`, así que las LECTURAS también deben pertenecer al módulo declarado (PREFIJOS_LECTURA_WEB, tomado de lo
 * que envía cada pantalla de Photino). Sin esto, un usuario con SIN_ACCESO en un módulo podría leer sus datos
 * declarando otro módulo al que sí tiene acceso.
 */
public final class PermisosModulo {

    public static final String SIN_ACCESO = "SIN_ACCESO";
    public static final String VER = "VER";
    public static final String EDITAR = "EDITAR";

    static final String MENSAJE_SIN_SESION = "No hay una sesión activa. Vuelve a iniciar sesión.";
    static final String MENSAJE_USUARIOS = "Acceso no autorizado: solo ADMIN_TI puede administrar usuarios.";
    static final String MENSAJE_MODULO_DESCONOCIDO = "Acción rechazada: módulo de origen no reconocido.";
    static final String MENSAJE_SIN_ACCESO = "No tienes acceso a este módulo.";
    static final String MENSAJE_SOLO_VISTA = "Solo vista: no tienes permiso para modificar datos en este módulo.";
    static final String MENSAJE_NO_PERMITIDA = "Acción no permitida desde este módulo.";

    /** Acción propia de la sesión (menú y modo "Solo vista" del frontend). */
    public static final String ACCION_PERMISOS_MIOS = "permisos.mios";

    private record ModuloDef(String empresa, List<String> prefijosEscritura) {}

    /** Módulo del frontend (data-module) → empresa + prefijos de acción que puede escribir. Igual que Photino. */
    private static final Map<String, ModuloDef> MODULOS = modulos();

    private static Map<String, ModuloDef> modulos() {
        Map<String, ModuloDef> m = new LinkedHashMap<>();
        m.put("inicio", new ModuloDef("INNPACK", List.of("inicio")));
        m.put("dashboard", new ModuloDef("INNPACK", List.of("dashboard")));
        m.put("registros-produccion", new ModuloDef("INNPACK", List.of("registrosProduccion")));
        m.put("no-conformidades", new ModuloDef("INNPACK", List.of("noConformidades")));
        m.put("nc-internas", new ModuloDef("INNPACK", List.of("noConformidades")));
        m.put("control-documental", new ModuloDef("INNPACK", List.of("controlDocumental")));
        m.put("maquinas-seguimiento", new ModuloDef("INNPACK", List.of()));
        m.put("registros-control", new ModuloDef("INNPACK", List.of("registrosControl")));
        m.put("talleres-externos", new ModuloDef("INNPACK", List.of("talleresExternos")));
        m.put("producto-terminado", new ModuloDef("INNPACK", List.of("productoTerminado")));
        m.put("certificados-liberacion", new ModuloDef("INNPACK", List.of()));
        m.put("recepcion-calidad", new ModuloDef("INNPACK", List.of("recepcion")));
        m.put("muestra-laboratorio", new ModuloDef("INNPACK", List.of("muestraLab")));
        m.put("trazabilidad", new ModuloDef("INNPACK", List.of()));
        m.put("usuarios", new ModuloDef("INNPACK", List.of("usuarios")));
        m.put("faret", new ModuloDef("FARET", List.of()));
        m.put("faret-inspecciones", new ModuloDef("FARET", List.of("faret.inspecciones")));
        m.put("faret-inspecciones-pallet", new ModuloDef("FARET", List.of("faret.inspeccionesPallet")));
        m.put("faret-producto-terminado", new ModuloDef("FARET", List.of("productoTerminado")));
        m.put("faret-certificados-liberacion", new ModuloDef("FARET", List.of()));
        m.put("faret-nc", new ModuloDef("FARET", List.of("faret.nc", "faret.pncCatalogos", "faret.catalogos")));
        m.put("faret-nc-internas", new ModuloDef("FARET", List.of("noConformidades")));
        m.put("faret-control-documental", new ModuloDef("FARET", List.of("controlDocumental")));
        m.put("faret-talleres-externos", new ModuloDef("FARET", List.of("faret.talleresExternos")));
        m.put("faret-importacion", new ModuloDef("FARET", List.of("faret.importacion")));
        m.put("faret-maquinas", new ModuloDef("FARET", List.of()));
        m.put("faret-data", new ModuloDef("FARET", List.of()));
        m.put("faret-trazabilidad", new ModuloDef("FARET", List.of()));
        m.put("faret-laboratorio", new ModuloDef("FARET", List.of("faretLab")));
        m.put("faret-recepcion-calidad", new ModuloDef("FARET", List.of("recepcion")));
        m.put("faret-usuarios", new ModuloDef("FARET", List.of("faret.usuarios")));
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Web: prefijos de acción que cada pantalla INNPACK envía (lecturas y escrituras), según el frontend de Photino
     * fd7f076. No Conformidades además lee filtros de dashboard, el resumen de máquinas y la columna "Liberación
     * Calidad" (shared/utils.js). Módulo sin entrada (Faret: sin sesión web aún) → solo la regla de Photino.
     */
    private static final Map<String, Set<String>> PREFIJOS_LECTURA_WEB = Map.ofEntries(
            Map.entry("inicio", Set.of("inicio")),
            Map.entry("dashboard", Set.of("dashboard")),
            Map.entry("registros-produccion", Set.of("registrosProduccion")),
            Map.entry("no-conformidades", Set.of("noConformidades", "dashboard", "maquinasSeguimiento", "liberacionCalidad")),
            Map.entry("nc-internas", Set.of("noConformidades", "liberacionCalidad")),
            Map.entry("control-documental", Set.of("controlDocumental")),
            Map.entry("maquinas-seguimiento", Set.of("maquinasSeguimiento")),
            Map.entry("registros-control", Set.of("registrosControl")),
            Map.entry("talleres-externos", Set.of("talleresExternos")),
            Map.entry("producto-terminado", Set.of("productoTerminado")),
            Map.entry("certificados-liberacion", Set.of("certificadosLiberacion")),
            Map.entry("recepcion-calidad", Set.of("recepcion")),
            Map.entry("muestra-laboratorio", Set.of("muestraLab")),
            Map.entry("trazabilidad", Set.of("trazabilidad")),
            Map.entry("usuarios", Set.of("usuarios")));

    /** Módulos que nunca bajan de VER. */
    private static final Set<String> MODULOS_INICIO = Set.of("inicio", "faret");

    /** Acciones permitidas sin sesión ni módulo de origen (en la web, auth.* y excel.guardar no llegan al bridge). */
    private static final Set<String> ACCIONES_PUBLICAS = Set.of(
            "auth.login", "auth.logout", "auth.me", "faret.login", "faret.logout", "faret.health", "excel.guardar");

    /** Lecturas que no terminan en un sufijo genérico. Todo lo demás es escritura (ante la duda, se bloquea con VER). */
    private static final Set<String> ACCIONES_LECTURA = Set.of(
            "certificadosLiberacion.buscar",
            "certificadosLiberacion.pdf.descargar",
            "certificadosLiberacion.calidadPdf.descargar",
            "controlDocumental.adjunto.abrir",
            "dashboard.obtenerFiltros",
            "dashboard.obtenerResumen",
            "faret.catalogos.areas",
            "faret.catalogos.defectos",
            "faret.catalogos.inspectores",
            "faret.catalogos.maquinas",
            "faret.catalogos.operadores",
            "faret.inspecciones.adjuntos",
            "faret.nc.adjuntos.abrir",
            "faret.talleresExternos.catalogos",
            "inicio.getDashboard",
            "liberacionCalidad.inspectores",
            "maquinasSeguimiento.obtenerResumen",
            "muestraLab.adjunto.abrir",
            "muestraLab.bobinaHistorial",
            "muestraLab.catalogos",
            "muestraLab.consultarNp",
            "muestraLab.consultarRegistroProduccion",
            "muestraLab.indicadores",
            "muestraLab.materialesFps",
            "muestraLab.resolverBobina",
            "noConformidades.adjuntos.abrir",
            "noConformidades.filtrosOpciones",
            "productoTerminado.exportarDetalle",
            "productoTerminado.filtros",
            "recepcion.foto.abrir",
            "recepcion.sap.consultar",
            "recepcion.sap.lotes",
            "registrosControl.obtenerRegistros",
            "registrosProduccion.obtenerFiltros",
            "registrosProduccion.obtenerResumen",
            "talleresExternos.catalogos",
            "talleresExternos.historialLiberaciones",
            "trazabilidad.consultarNp");

    private static final Set<String> SUFIJOS_LECTURA = Set.of("list", "get", "resumen", "detalle");

    private PermisosModulo() {}

    /** Módulos conocidos (el gateway rechaza cualquier otro `_modulo` sin mirarlo). */
    public static boolean moduloConocido(String modulo) {
        return modulo != null && MODULOS.containsKey(modulo);
    }

    /** null = permitido; texto = motivo del rechazo (el mismo que muestra Photino). */
    public static String validar(String accion, String modulo, SessionUser usuario) {
        if (ACCIONES_PUBLICAS.contains(accion)) {
            return null;
        }
        if (usuario == null || usuario.empresa() == null) {
            return MENSAJE_SIN_SESION;
        }
        if (ACCION_PERMISOS_MIOS.equals(accion)) {
            return null;
        }
        String empresa = usuario.empresa().toUpperCase(Locale.ROOT);
        if (accion.startsWith("usuarios.") || accion.startsWith("faret.usuarios.")) {
            String empresaAccion = accion.startsWith("faret.") ? "FARET" : "INNPACK";
            if (!esAdminTi(usuario) || !empresaAccion.equals(empresa)) {
                return MENSAJE_USUARIOS;
            }
        }
        ModuloDef def = modulo == null ? null : MODULOS.get(modulo);
        if (def == null) {
            return MENSAJE_MODULO_DESCONOCIDO;
        }
        String nivel = nivelEfectivo(modulo, usuario);
        if (SIN_ACCESO.equals(nivel)) {
            return MENSAJE_SIN_ACCESO;
        }
        if (esLectura(accion)) {
            Set<String> prefijos = PREFIJOS_LECTURA_WEB.get(modulo);
            return prefijos == null || prefijos.stream().anyMatch(p -> accion.startsWith(p + ".")) ? null : MENSAJE_NO_PERMITIDA;
        }
        if (!EDITAR.equals(nivel)) {
            return MENSAJE_SOLO_VISTA;
        }
        return def.prefijosEscritura().stream().anyMatch(p -> accion.startsWith(p + ".")) ? null : MENSAJE_NO_PERMITIDA;
    }

    public static boolean esLectura(String accion) {
        if (ACCIONES_PUBLICAS.contains(accion) || ACCIONES_LECTURA.contains(accion)) {
            return true;
        }
        return SUFIJOS_LECTURA.contains(accion.substring(accion.lastIndexOf('.') + 1));
    }

    /** Nivel efectivo de un módulo (SIN_ACCESO si no hay sesión, si es de la otra empresa o si no está registrado). */
    public static String nivelEfectivo(String modulo, SessionUser usuario) {
        ModuloDef def = modulo == null ? null : MODULOS.get(modulo);
        if (usuario == null || usuario.empresa() == null || def == null
                || !def.empresa().equals(usuario.empresa().toUpperCase(Locale.ROOT))) {
            return SIN_ACCESO;
        }
        if (esAdminTi(usuario)) {
            return EDITAR;
        }
        if (modulo.equals("usuarios") || modulo.equals("faret-usuarios")) {
            return SIN_ACCESO;
        }
        String personalizado = usuario.permisosModulo().get(modulo);
        String nivel = personalizado != null ? personalizado : nivelPorRol(def.empresa(), usuario.rol(), modulo);
        return SIN_ACCESO.equals(nivel) && MODULOS_INICIO.contains(modulo) ? VER : nivel;
    }

    /** permisos.mios: todos los módulos de la empresa de la sesión con su nivel efectivo (para el frontend). */
    public static Map<String, String> nivelesEfectivos(SessionUser usuario) {
        Map<String, String> niveles = new LinkedHashMap<>();
        String empresa = usuario == null || usuario.empresa() == null ? "" : usuario.empresa().toUpperCase(Locale.ROOT);
        MODULOS.forEach((modulo, def) -> {
            if (def.empresa().equals(empresa)) {
                niveles.put(modulo, nivelEfectivo(modulo, usuario));
            }
        });
        return niveles;
    }

    /** Regla por rol previa a los permisos personalizados (la de refreshSidebarState en core/app.js). */
    private static String nivelPorRol(String empresa, String rolUsuario, String modulo) {
        if (!"FARET".equals(empresa)) {
            return EDITAR;
        }
        String rol = rolUsuario == null ? "" : rolUsuario.toUpperCase(Locale.ROOT);
        if (modulo.equals("faret-data") || modulo.equals("faret-importacion")) {
            return rol.equals("ADMIN") ? EDITAR : SIN_ACCESO;
        }
        if (rol.equals("CONSULTA")) {
            return Set.of("faret", "faret-talleres-externos", "faret-nc", "faret-nc-internas").contains(modulo) ? EDITAR : SIN_ACCESO;
        }
        return EDITAR;
    }

    private static boolean esAdminTi(SessionUser usuario) {
        return usuario.rol() != null && usuario.rol().equalsIgnoreCase("ADMIN_TI");
    }
}
