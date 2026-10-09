package cl.faret.qccweb.bridge.handlers;

import cl.faret.qccweb.auth.SessionUser;
import cl.faret.qccweb.bridge.BridgeAction;
import cl.faret.qccweb.bridge.BridgeResult;
import cl.faret.qccweb.bridge.PermisosModulo;
import cl.faret.qccweb.upstream.FaretApiClient;
import cl.faret.qccweb.upstream.InnpackApiClient;
import cl.faret.qccweb.upstream.FaretRespuestas;
import cl.faret.qccweb.upstream.UriEscape;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lecturas de la pantalla de inicio FARET (Fase 6b) — SOLO LECTURA. Port de Photino 1.8.15 (9e1b556)
 * src/Backend/Modules/Faret/FaretHandler.cs (+ servicios FaretApi): cada acción llama a UNA de las tres APIs FARET.
 *
 * Contrato igual que Photino: las acciones `faret.*` llegan con el payload PLANO (filtros en la raíz, no en `data`); cada
 * filtro viaja solo si no está en blanco, en el orden fijo de la query de Photino y escapado como Uri.EscapeDataString;
 * la respuesta de QualityControlFaret.Api (ApiResponse {success,data,message}) se desenvuelve (TryUnwrapApiResponse).
 * Seguridad transparente de la web (mismo criterio que Formularios): lista cerrada de claves (el resto del payload se
 * ignora, nada del navegador se concatena a la URL salvo los valores validados); bool/objeto/array en un filtro →
 * "Parámetro de filtro inválido." (regla 2e); fechas AAAA-MM-DD; textos con trim, sin caracteres de control y ≤ 200;
 * page/pageSize enteros positivos (como Photino, un valor no entero o ≤ 0 simplemente no viaja) y pageSize acotado a 200,
 * el tope real de la API (ImportacionesService.GetPncListAsync).
 *
 * La sesión FARET es obligatoria (ActionPolicy por empresa): faret.data.list, faret.indicadoresCalidad.resumen y
 * faret.talleresExternos.resumen van a QualityControlFaret.Api con el Bearer del usuario de la sesión.
 *
 * 6b-3: faret.nc.list va a MejoraContinua (GET api/no-conformidades, respuesta cruda: el arreglo tal cual; errores por
 * mensaje/error/title/detail como ExtractMcErrorMessage) y faret.inspecciones.resumen / faret.maquinas.resumen a Calidad
 * (backend Node, respuesta {ok,data}). Photino NUNCA les manda token (no hace SetToken en esos clientes): el gateway
 * tampoco, aunque haya sesión.
 *
 * Fase E: faret.nc.adjuntos.abrir (MejoraContinua, sin Authorization) devuelve el adjunto validado igual que
 * noConformidades.adjuntos.abrir de INNPACK (misma API de adjuntos: PDF/JPEG/PNG, firma real, tamaño, nombre saneado).
 * Fase F: faret.usuarios.list y faret.usuarios.permisos.get (QualityControlFaret.Api con el Bearer del admin_ti de la sesión).
 *
 * Fase F1 (ESCRITURAS): faret.catalogos.{areas,operadores,maquinas}.crear y faret.pncCatalogos.{...}.crear (QualityControlFaret.Api
 * con el Bearer del usuario; el autor de un valor PNC es el `sub` del JWT que pone la API, nunca algo del navegador). Lista
 * cerrada de claves, `nombre` validado como el de los catálogos de INNPACK, respuesta reproyectada a los campos del DTO.
 *
 * 6b-4: faret.dashboard.resumen = FaretDashboardService.ObtenerResumenAsync: GET api/no-conformidades y, SECUENCIALMENTE,
 * GET api/no-conformidades/{id}/acciones por cada NC (omitiendo las que fallan); el cálculo vive en FaretDashboardResumen.
 */
public class FaretBridgeHandler {

    public static final String MENSAJE_FILTRO_INVALIDO = FormulariosBridgeHandler.MENSAJE_FILTRO_INVALIDO;
    public static final String MENSAJE_FILTRO_LARGO = FormulariosBridgeHandler.MENSAJE_FILTRO_LARGO;
    public static final String MENSAJE_PAGINACION = "Parámetro de paginación inválido.";
    public static final String MENSAJE_PRESENTA_DEFECTOS = "Filtro presentaDefectos inválido.";
    public static final String MENSAJE_MC_NO_CONFIGURADA = "API de Mejora Continua no configurada. Revise la configuración del servidor.";
    public static final String MENSAJE_CALIDAD_NO_CONFIGURADA = "API de Calidad no configurada. Revise la configuración del servidor.";
    public static final String MENSAJE_FALTA_ID_NC = "Falta el id de la no conformidad";
    public static final String MENSAJE_ID_REQUERIDO = "id es requerido";
    public static final String MENSAJE_DASHBOARD_LISTADO = "No se pudo obtener el listado de no conformidades";
    public static final String MENSAJE_DASHBOARD_INVALIDO = "Respuesta inválida de la API al listar no conformidades";
    public static final String MENSAJE_FALTA_ID_ADJUNTO = "Falta el id del adjunto";
    public static final String MENSAJE_FALTA_ID_USUARIO = "Falta el id del usuario";
    public static final int MAX_FILTRO = FormulariosBridgeHandler.MAX_FILTRO;
    /** Tope de pageSize de api/importaciones/pnc (ImportacionesService: Math.Min(pageSize, 200)). */
    public static final int MAX_PAGE_SIZE_PNC = 200;
    /** Tope de pageSize de api/talleres-externos (TalleresExternosService: Math.Min(pageSize, 500)). */
    public static final int MAX_PAGE_SIZE_TALLERES = 500;
    /** Tope de pageSize de calidad-faret(-pallet)/registros (controllers de Calidad: Math.min(200, ...)). */
    public static final int MAX_PAGE_SIZE_CALIDAD = 200;
    /** Tope de page: una página mayor no tiene sentido y evita desbordes del offset de la API. */
    public static final int MAX_PAGE = 1_000_000;

    /** Orden de la query de FaretHandler.BuildDataFiltros (page y pageSize se agregan al final, solo en list). */
    static final List<String> FILTROS_DATA = List.of("cliente", "tipoPnc", "nivel", "fechaDesde", "fechaHasta");
    /** FaretHandler.HandleIndicadoresCalidad: solo el período. */
    static final List<String> FILTROS_INDICADORES = List.of("fechaDesde", "fechaHasta");
    /** FaretHandler.BuildTalleresExternosFiltros. */
    static final List<String> FILTROS_TALLERES = List.of("nv", "producto", "cliente", "tallerExterno", "proceso", "responsable",
            "prioridad", "estado", "fechaAsignacionDesde", "fechaAsignacionHasta", "fechaCompromisoDesde", "fechaCompromisoHasta");
    /** FaretHandler.BuildInspeccionesFiltros (Calidad). */
    static final List<String> FILTROS_INSPECCIONES = List.of("fechaDesde", "fechaHasta", "areaControl", "operador", "maquina",
            "presentaDefectos", "nvFaret");
    /** FaretHandler.BuildInspeccionesPalletFiltros (Calidad). */
    static final List<String> FILTROS_PALLET = List.of("fechaDesde", "fechaHasta", "tipoMaterial", "numeroPallet", "maquina", "operador");
    /** Claves de fecha (AAAA-MM-DD) entre todos los filtros anteriores. */
    static final Set<String> FECHAS = Set.of("fechaDesde", "fechaHasta", "fechaAsignacionDesde", "fechaAsignacionHasta",
            "fechaCompromisoDesde", "fechaCompromisoHasta");
    /** Valores que entiende Calidad para presentaDefectos (el select de la vista manda "true"/"false"; vacío = todos). */
    static final Set<String> PRESENTA_DEFECTOS = Set.of("true", "false", "1", "0");

    private static final String RUTA_IMPORTACIONES = "/api/importaciones";
    private static final String RUTA_TALLERES = "/api/talleres-externos";
    private static final String RUTA_PNC = "/api/importaciones/pnc";
    private static final String RUTA_INDICADORES = "/api/importaciones/pnc/indicadores-calidad";
    private static final String RUTA_TALLERES_RESUMEN = "/api/talleres-externos/resumen";
    private static final String RUTA_NC = "/api/no-conformidades";
    private static final String RUTA_USUARIOS = "/api/usuarios";
    private static final String RUTA_CATALOGOS = "/api/catalogos";
    private static final String RUTA_PNC_CATALOGOS = "/api/pnc-catalogos";
    /** Claves de las escrituras de catálogo (+ "action"); cualquier otra se rechaza. Photino manda {action, nombre[, areaId]}. */
    private static final Set<String> CAMPOS_CATALOGO_NOMBRE = Set.of("action", "nombre");
    private static final Set<String> CAMPOS_CATALOGO_POR_AREA = Set.of("action", "nombre", "areaId");
    /**
     * Largo máximo de `nombre` de area/operador/maquina. La API (CatalogosController) no valida largo ni normaliza; Photino
     * tampoco. Sin DDL a la vista (cat_areas/cat_operadores/cat_maquinas), se acota a 100 unidades UTF-16, conservador.
     */
    static final int MAX_NOMBRE_CATALOGO_PLANTA = 100;
    /** Largo de la columna cat_areas.codigo (GenerarCodigoArea de Photino recorta a 20). */
    static final int MAX_CODIGO_AREA = 20;
    public static final String MENSAJE_FALTA_AREA = "Falta el área";
    public static final String MENSAJE_CODIGO_AREA_VACIO = "El nombre debe tener al menos una letra o número";
    /** Roles de QualityControlFaret.Api (UsuariosService.RolesValidos); el rol que manda el navegador solo etiqueta la matriz. */
    private static final Set<String> ROLES_FARET = Set.of("ADMIN_TI", "ADMIN", "CALIDAD", "INSPECTOR", "CONSULTA");
    private static final Pattern MARCADO_ATRIBUTO = Pattern.compile("[<>\"']");
    private static final String RUTA_INSPECCIONES_RESUMEN = "/calidad-faret/resumen";
    private static final String RUTA_INSPECCIONES_REGISTROS = "/calidad-faret/registros";
    private static final String RUTA_PALLET_REGISTROS = "/calidad-faret-pallet/registros";
    private static final String RUTA_MAQUINAS_RESUMEN = "/calidad-faret/maquinas/resumen";
    private static final Pattern FECHA_ISO = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");

    private final FaretApiClient qualityControl;
    private final FaretApiClient mejoraContinua;
    private final FaretApiClient calidad;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * @param qualityControl QualityControlFaret.Api (con el Bearer del usuario)
     * @param mejoraContinua MejoraContinua (sin Authorization)
     * @param calidad        Calidad, backend Node (sin Authorization)
     * @param clock          reloj para "hoy" del dashboard (se interpreta en America/Santiago)
     */
    public FaretBridgeHandler(FaretApiClient qualityControl, FaretApiClient mejoraContinua, FaretApiClient calidad,
            ObjectMapper mapper, Clock clock) {
        this.qualityControl = qualityControl;
        this.mejoraContinua = mejoraContinua;
        this.calidad = calidad;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** faret.data.list → GET api/importaciones/pnc?cliente&tipoPnc&nivel&fechaDesde&fechaHasta&page&pageSize */
    public BridgeResult dataList(ObjectNode payload, SessionUser usuario) {
        Query filtros = construirQuery(payload, FILTROS_DATA);
        if (filtros.error() != null) {
            return BridgeResult.error(filtros.error());
        }
        StringBuilder query = new StringBuilder(filtros.texto());
        String errorPaginacion = agregarPaginacion(payload, query, MAX_PAGE_SIZE_PNC);
        if (errorPaginacion != null) {
            return BridgeResult.error(errorPaginacion);
        }
        return qc(usuario, RUTA_PNC, query);
    }

    /** faret.indicadoresCalidad.resumen → GET api/importaciones/pnc/indicadores-calidad?fechaDesde&fechaHasta */
    public BridgeResult indicadoresCalidad(ObjectNode payload, SessionUser usuario) {
        Query filtros = construirQuery(payload, FILTROS_INDICADORES);
        return filtros.error() != null ? BridgeResult.error(filtros.error()) : qc(usuario, RUTA_INDICADORES, new StringBuilder(filtros.texto()));
    }

    /** faret.talleresExternos.resumen → GET api/talleres-externos/resumen con los 12 filtros. */
    public BridgeResult talleresExternosResumen(ObjectNode payload, SessionUser usuario) {
        Query filtros = construirQuery(payload, FILTROS_TALLERES);
        return filtros.error() != null ? BridgeResult.error(filtros.error()) : qc(usuario, RUTA_TALLERES_RESUMEN, new StringBuilder(filtros.texto()));
    }

    /**
     * Fase A — faret.catalogos.areas → GET api/catalogos/areas y
     * faret.pncCatalogos.*.list → GET api/pnc-catalogos/*: sin payload (ApiResponse desenvuelta, como HandleCatalogo y
     * HandlePncCatalogoList). La ruta la fija la regla del bridge, jamás el navegador.
     */
    public BridgeResult catalogo(String ruta, SessionUser usuario) {
        return qc(usuario, ruta, new StringBuilder());
    }

    /** faret.catalogos.operadores / maquinas → GET api/catalogos/{operadores|maquinas}[?areaId=n] (areaId > 0; si no, no viaja). */
    public BridgeResult catalogoPorArea(String ruta, ObjectNode payload, SessionUser usuario) {
        JsonNode nodo = payload.get("areaId");
        if (tipoInvalido(nodo)) {
            return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
        }
        Integer areaId = enteroPositivo(nodo);
        return qc(usuario, ruta, new StringBuilder(areaId == null ? "" : "areaId=" + areaId));
    }

    // --------------------------------------------------------------------------------------- F1: escrituras de catálogos

    /**
     * Fase F1 - faret.pncCatalogos.{catalogo}.crear -> POST api/pnc-catalogos/{ruta} con {nombre} (QualityControlFaret.Api, Bearer del
     * usuario). Photino (HandlePncCatalogoCrear) manda {nombre}; el autor (`creado_por`) lo pone la API desde el `sub` del JWT.
     * `nombre`: lista cerrada {action, nombre}, string, una línea, trim + colapso de espacios (como la API: Normalizar), ≤ max
     * (150; familias 50, niveles 30, impactos 100 = la columna real), sin HTML. Duplicado: la API devuelve el valor existente
     * (y lo reactiva si estaba inactivo) con 201; el gateway no inventa otra política. Salida: {id, nombre, activo}.
     */
    public BridgeAction pncCatalogoCrear(String ruta, int max) {
        return (payload, usuario) -> {
            String campo = NoConformidadesBridgeHandler.campoNoPermitido(payload, CAMPOS_CATALOGO_NOMBRE);
            if (campo != null) {
                return BridgeResult.error(campo);
            }
            NoConformidadesBridgeHandler.NombreValidado nombre = NoConformidadesBridgeHandler.nombreCatalogo(payload.get("nombre"), max);
            if (nombre.error() != null) {
                return BridgeResult.error(nombre.error());
            }
            ObjectNode cuerpo = mapper.createObjectNode();
            cuerpo.put("nombre", nombre.nombre());
            return itemCreado(FaretRespuestas.desenvolver(qualityControl.postJson(usuario, RUTA_PNC_CATALOGOS + "/" + ruta, cuerpo), mapper),
                    List.of());
        };
    }

    /**
     * Fase F1 - faret.catalogos.areas.crear -> POST api/catalogos/areas con {codigo, nombre}. Como Photino (HandleCatalogoAreaCrear):
     * el formulario solo pide el nombre y el código se genera con {@link #generarCodigoArea}; si no queda ningún carácter A-Z/0-9,
     * "El nombre debe tener al menos una letra o número". Duplicado de código: la API devuelve el área existente (UNIQUE(codigo)).
     */
    public BridgeResult areaCrear(ObjectNode payload, SessionUser usuario) {
        String campo = NoConformidadesBridgeHandler.campoNoPermitido(payload, CAMPOS_CATALOGO_NOMBRE);
        if (campo != null) {
            return BridgeResult.error(campo);
        }
        NoConformidadesBridgeHandler.NombreValidado nombre =
                NoConformidadesBridgeHandler.nombreCatalogo(payload.get("nombre"), MAX_NOMBRE_CATALOGO_PLANTA);
        if (nombre.error() != null) {
            return BridgeResult.error(nombre.error());
        }
        String codigo = generarCodigoArea(nombre.nombre());
        if (codigo.isEmpty()) {
            return BridgeResult.error(MENSAJE_CODIGO_AREA_VACIO);
        }
        ObjectNode cuerpo = mapper.createObjectNode();
        cuerpo.put("codigo", codigo);
        cuerpo.put("nombre", nombre.nombre());
        return itemCreado(FaretRespuestas.desenvolver(qualityControl.postJson(usuario, RUTA_CATALOGOS + "/areas", cuerpo), mapper),
                List.of("codigo", "createdAt"));
    }

    /**
     * Fase F1 - faret.catalogos.{operadores|maquinas}.crear -> POST api/catalogos/{ruta} con {areaId, nombre}. Como Photino: areaId
     * entero int32 > 0 (si no, "Falta el área"; bool/objeto/array se rechazan) y luego el nombre. El área debe existir y estar
     * activa: lo resuelve la API (404 con su mensaje).
     */
    public BridgeAction catalogoPorAreaCrear(String ruta) {
        return (payload, usuario) -> {
            String campo = NoConformidadesBridgeHandler.campoNoPermitido(payload, CAMPOS_CATALOGO_POR_AREA);
            if (campo != null) {
                return BridgeResult.error(campo);
            }
            JsonNode nodoArea = payload.get("areaId");
            if (tipoInvalido(nodoArea)) {
                return BridgeResult.error(NoConformidadesBridgeHandler.MENSAJE_PARAMETRO_INVALIDO);
            }
            Integer areaId = enteroPositivo(nodoArea);
            if (areaId == null) {
                return BridgeResult.error(MENSAJE_FALTA_AREA);
            }
            NoConformidadesBridgeHandler.NombreValidado nombre =
                    NoConformidadesBridgeHandler.nombreCatalogo(payload.get("nombre"), MAX_NOMBRE_CATALOGO_PLANTA);
            if (nombre.error() != null) {
                return BridgeResult.error(nombre.error());
            }
            ObjectNode cuerpo = mapper.createObjectNode();
            cuerpo.put("areaId", areaId);
            cuerpo.put("nombre", nombre.nombre());
            return itemCreado(FaretRespuestas.desenvolver(qualityControl.postJson(usuario, RUTA_CATALOGOS + "/" + ruta, cuerpo), mapper),
                    List.of("areaId", "areaNombre", "createdAt"));
        };
    }

    /**
     * FaretHandler.GenerarCodigoArea de Photino: mayúsculas invariantes, cualquier corrida de caracteres fuera de A-Z/0-9 se
     * colapsa a "_", se recortan "_" en los extremos y el resultado se corta a 20 caracteres (y se recorta "_" otra vez).
     * La mayúscula es la de .NET ToUpperInvariant (carácter a carácter, sin expansiones como ß → SS de String.toUpperCase).
     */
    static String generarCodigoArea(String nombre) {
        StringBuilder mayus = new StringBuilder();
        String t = nombre.strip();
        for (int i = 0; i < t.length(); i++) {
            mayus.append(Character.toUpperCase(t.charAt(i)));
        }
        String codigo = recortarGuionBajo(mayus.toString().replaceAll("[^A-Z0-9]+", "_"));
        return codigo.length() > MAX_CODIGO_AREA ? recortarGuionBajo(codigo.substring(0, MAX_CODIGO_AREA)) : codigo;
    }

    private static String recortarGuionBajo(String s) {
        int ini = 0;
        int fin = s.length();
        while (ini < fin && s.charAt(ini) == '_') {
            ini++;
        }
        while (fin > ini && s.charAt(fin - 1) == '_') {
            fin--;
        }
        return s.substring(ini, fin);
    }

    /**
     * Reproyecta el valor creado (ApiResponse ya desenvuelta) a {id, nombre, activo} + los extras del DTO de cada catálogo: nada más
     * de la API llega al navegador. La creación ya ocurrió en la API: si `data` no es un objeto con id entero, error genérico.
     */
    private BridgeResult itemCreado(BridgeResult upstream, List<String> extras) {
        if (!upstream.ok()) {
            return upstream;
        }
        if (!(upstream.data() instanceof ObjectNode item) || item.get("id") == null || !item.get("id").isIntegralNumber()
                || !item.get("id").canConvertToInt()) {
            return BridgeResult.error(FaretRespuestas.ERROR_FARET);
        }
        ObjectNode salida = mapper.createObjectNode();
        salida.put("id", item.get("id").asInt());
        for (String campo : List.of("nombre", "codigo", "areaNombre", "createdAt")) {
            if (campo.equals("nombre") || extras.contains(campo)) {
                JsonNode v = item.get(campo);
                if (v != null && v.isString()) {
                    salida.put(campo, v.asString());
                }
            }
        }
        JsonNode area = item.get("areaId");
        if (extras.contains("areaId") && area != null && area.isIntegralNumber() && area.canConvertToInt()) {
            salida.put("areaId", area.asInt());
        }
        JsonNode activo = item.get("activo");
        if (activo != null && activo.isBoolean()) {
            salida.put("activo", activo.asBoolean());
        }
        return BridgeResult.ok(salida);
    }

    /** Recurso auditado de las escrituras de catálogo FARET: "faret:catalogo:<clave>:<id>" (nunca el nombre). */
    public static String recursoCatalogo(String clave, Object dataRespuesta) {
        String recurso = "faret:catalogo:" + clave;
        if (dataRespuesta instanceof JsonNode d && d.isObject() && d.get("id") != null && d.get("id").canConvertToInt()) {
            recurso += ":" + d.get("id").asInt();
        }
        return recurso;
    }

    /**
     * Fase C — faret.talleresExternos.list → GET api/talleres-externos?{12 filtros}&page&pageSize (mismos filtros y orden que
     * resumen; pageSize acotado a 500, el tope real de TalleresExternosService: misma página efectiva).
     */
    public BridgeResult talleresExternosList(ObjectNode payload, SessionUser usuario) {
        Query filtros = construirQuery(payload, FILTROS_TALLERES);
        if (filtros.error() != null) {
            return BridgeResult.error(filtros.error());
        }
        StringBuilder query = new StringBuilder(filtros.texto());
        String errorPaginacion = agregarPaginacion(payload, query, MAX_PAGE_SIZE_TALLERES);
        return errorPaginacion != null ? BridgeResult.error(errorPaginacion) : qc(usuario, RUTA_TALLERES, query);
    }

    /** faret.talleresExternos.catalogos → GET api/talleres-externos/catalogos (sin payload). */
    public BridgeResult talleresExternosCatalogos(ObjectNode payload, SessionUser usuario) {
        return qc(usuario, RUTA_TALLERES + "/catalogos", new StringBuilder());
    }

    /** faret.importacion.list → GET api/importaciones (historial de cargas; sin payload). */
    public BridgeResult importacionList(ObjectNode payload, SessionUser usuario) {
        return qc(usuario, RUTA_IMPORTACIONES, new StringBuilder());
    }

    /** faret.data.resumen → GET api/importaciones/pnc/resumen?cliente&tipoPnc&nivel&fechaDesde&fechaHasta (BuildDataFiltros, sin page). */
    public BridgeResult dataResumen(ObjectNode payload, SessionUser usuario) {
        Query filtros = construirQuery(payload, FILTROS_DATA);
        return filtros.error() != null ? BridgeResult.error(filtros.error()) : qc(usuario, RUTA_PNC + "/resumen", new StringBuilder(filtros.texto()));
    }

    /** faret.nc.list → GET api/no-conformidades (MejoraContinua, respuesta cruda: arreglo de NC). */
    public BridgeResult ncList(ObjectNode payload, SessionUser usuario) {
        if (!mejoraContinua.configurada()) {
            return BridgeResult.error(MENSAJE_MC_NO_CONFIGURADA);
        }
        return FaretRespuestas.crudoMc(mejoraContinua.get(usuario, RUTA_NC), mapper);
    }

    /**
     * Fase B — lecturas de una NC en MejoraContinua (sin Authorization, respuesta cruda): faret.nc.get →
     * GET api/no-conformidades/{id}, y {id}/seguimiento, {id}/acciones, {id}/adjuntos. El id (payload.id, entero positivo) se valida
     * antes de ir a la ruta; "Falta el id de la no conformidad" como Photino cuando no lo es.
     */
    public BridgeResult ncDetalle(String sufijo, ObjectNode payload, SessionUser usuario) {
        BridgeResult upstream = ncPorId(sufijo, payload, usuario, false);
        if ("/adjuntos".equals(sufijo) && upstream.ok() && upstream.data() instanceof ArrayNode items) {
            // Fase F3: la vista inserta nombreArchivo en innerHTML y en title="..."; se sanea igual que noConformidades.adjuntos.list.
            for (JsonNode item : items) {
                if (item instanceof ObjectNode adjunto && adjunto.has("nombreArchivo")) {
                    JsonNode id = adjunto.get("id");
                    adjunto.put("nombreArchivo", ControlDocumentalBridgeHandler.nombreArchivoSeguro(
                            adjunto.get("nombreArchivo").isString() ? adjunto.get("nombreArchivo").asString() : "",
                            "adjunto_" + (id != null && id.isIntegralNumber() ? id.asLong() : "")));
                }
            }
        }
        return upstream;
    }

    /**
     * faret.nc.analisis.get → GET api/no-conformidades/{id}/analisis; "aún no tiene un análisis" → Ok(null). Fase F3: además
     * registra en la sesión la huella del análisis que el usuario queda viendo (lost update en faret.nc.analisis.guardar).
     */
    public BridgeResult ncAnalisisGet(ObjectNode payload, SessionUser usuario) {
        BridgeResult upstream = ncPorId("/analisis", payload, usuario, true);
        Integer id = enteroPositivo(payload.get("id"));
        if (upstream.ok() && id != null) {
            FaretNoConformidadesBridgeHandler.registrarLectura(id, upstream.data());
        }
        return upstream;
    }

    private BridgeResult ncPorId(String sufijo, ObjectNode payload, SessionUser usuario, boolean analisis) {
        if (!mejoraContinua.configurada()) {
            return BridgeResult.error(MENSAJE_MC_NO_CONFIGURADA);
        }
        JsonNode nodo = payload.get("id");
        if (tipoInvalido(nodo)) {
            return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
        }
        Integer id = enteroPositivo(nodo);
        if (id == null) {
            return BridgeResult.error(MENSAJE_FALTA_ID_NC);
        }
        InnpackApiClient.Respuesta r = mejoraContinua.get(usuario, RUTA_NC + "/" + id + sufijo);
        return analisis ? FaretRespuestas.crudoMcAnalisis(r, mapper) : FaretRespuestas.crudoMc(r, mapper);
    }

    /**
     * Fase E - faret.nc.adjuntos.abrir -> GET api/no-conformidades/{id}/adjuntos/{adjuntoId} (MejoraContinua, sin Authorization).
     * Los ids (enteros int32 positivos, payload.id y payload.adjuntoId) se validan antes de ir a la ruta: "Falta el id de la no
     * conformidad" / "Falta el id del adjunto" como Photino. La respuesta cruda se valida como noConformidades.adjuntos.abrir
     * (contenido base64 acotado, tipo REAL por firma PDF/PNG/JPEG, nombre saneado): del upstream solo salen id, nombreArchivo,
     * tipoMime y contenidoBase64 (la vista los inserta en un data: URI / innerHTML).
     */
    public BridgeResult ncAdjuntosAbrir(ObjectNode payload, SessionUser usuario) {
        if (!mejoraContinua.configurada()) {
            return BridgeResult.error(MENSAJE_MC_NO_CONFIGURADA);
        }
        JsonNode nodoId = payload.get("id");
        if (tipoInvalido(nodoId)) {
            return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
        }
        Integer id = enteroPositivo(nodoId);
        if (id == null) {
            return BridgeResult.error(MENSAJE_FALTA_ID_NC);
        }
        JsonNode nodoAdjunto = payload.get("adjuntoId");
        if (tipoInvalido(nodoAdjunto)) {
            return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
        }
        Integer adjuntoId = enteroPositivo(nodoAdjunto);
        if (adjuntoId == null) {
            return BridgeResult.error(MENSAJE_FALTA_ID_ADJUNTO);
        }
        BridgeResult crudo = FaretRespuestas.crudoMc(mejoraContinua.get(usuario, RUTA_NC + "/" + id + "/adjuntos/" + adjuntoId), mapper);
        return crudo.ok() ? NoConformidadesBridgeHandler.adjuntoValidado(crudo.data(), adjuntoId, mapper) : crudo;
    }

    /**
     * Fase F - faret.usuarios.list -> GET api/usuarios[?soloActivos=true|false] (solo admin_ti de la sesion FARET; ActionPolicy).
     * soloActivos como Photino: bool o texto true/false; cualquier otro valor no viaja (objeto/array se rechazan). La respuesta se
     * reproyecta a los campos de UsuarioDetalleDto que la vista de Photino lee (id, nombre, correo, username, rol, activo, roles,
     * createdAt): jamas pasa un campo extra de la API (p. ej. un hash). Textos con marcado de atributo/etiqueta se escapan.
     */
    public BridgeResult usuariosList(ObjectNode payload, SessionUser usuario) {
        JsonNode nodo = payload.get("soloActivos");
        if (nodo != null && (nodo.isObject() || nodo.isArray())) {
            return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
        }
        String soloActivos = null;
        if (nodo != null && nodo.isBoolean()) {
            soloActivos = String.valueOf(nodo.asBoolean());
        } else if (nodo != null && nodo.isString()) {
            String t = nodo.asString().trim().toLowerCase(Locale.ROOT);
            soloActivos = t.equals("true") || t.equals("false") ? t : null;
        }
        BridgeResult upstream = qc(usuario, RUTA_USUARIOS, new StringBuilder(soloActivos == null ? "" : "soloActivos=" + soloActivos));
        if (!upstream.ok()) {
            return upstream;
        }
        if (!(upstream.data() instanceof ArrayNode items)) {
            return BridgeResult.error(FaretRespuestas.ERROR_FARET);
        }
        ArrayNode salida = mapper.createArrayNode();
        for (JsonNode item : items) {
            if (!item.isObject()) {
                return BridgeResult.error(FaretRespuestas.ERROR_FARET);
            }
            ObjectNode u = salida.addObject();
            JsonNode id = item.get("id");
            if (id != null && id.isIntegralNumber() && id.canConvertToInt()) {
                u.put("id", id.asInt());
            }
            for (String campo : List.of("nombre", "correo", "username", "rol")) {
                JsonNode v = item.get(campo);
                if (v != null && v.isString()) {
                    u.put(campo, escapar(v.asString()));
                } else if (v != null && v.isNull()) {
                    u.putNull(campo);
                }
            }
            JsonNode activo = item.get("activo");
            if (activo != null && activo.isBoolean()) {
                u.put("activo", activo.asBoolean());
            }
            JsonNode roles = item.get("roles");
            if (roles != null && roles.isArray()) {
                ArrayNode r = u.putArray("roles");
                roles.forEach(x -> {
                    if (x.isString()) {
                        r.add(escapar(x.asString()));
                    }
                });
            }
            JsonNode creado = item.get("createdAt");
            if (creado != null && creado.isString()) {
                u.put("createdAt", creado.asString());
            }
        }
        return BridgeResult.ok(salida);
    }

    /**
     * Fase F - faret.usuarios.permisos.get -> GET api/usuarios/{id}/permisos y matriz de PermisosService.ArmarMatriz("FARET", rol,
     * data): nivel por rol + permiso personalizado de cada modulo FARET. id = entero positivo (si no, "Falta el id del usuario");
     * `rol` solo etiqueta la columna "por rol" (Photino lo toma del navegador): debe ser uno de los 5 roles FARET o ir vacio.
     */
    public BridgeResult usuariosPermisosGet(ObjectNode payload, SessionUser usuario) {
        JsonNode nodoId = payload.get("id");
        if (tipoInvalido(nodoId)) {
            return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
        }
        Integer id = enteroPositivo(nodoId);
        if (id == null) {
            return BridgeResult.error(MENSAJE_FALTA_ID_USUARIO);
        }
        JsonNode nodoRol = payload.get("rol");
        String rol = "";
        if (nodoRol != null && !nodoRol.isNull()) {
            if (!nodoRol.isString()) {
                return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
            }
            rol = nodoRol.asString().trim().toUpperCase(Locale.ROOT);
            if (!rol.isEmpty() && !ROLES_FARET.contains(rol)) {
                return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
            }
        }
        BridgeResult upstream = qc(usuario, RUTA_USUARIOS + "/" + id + "/permisos", new StringBuilder());
        if (!upstream.ok()) {
            return upstream;
        }
        return BridgeResult.ok(PermisosModulo.armarMatriz("FARET", rol, upstream.data() instanceof JsonNode n ? n : null, mapper));
    }

    private static String escapar(String texto) {
        return MARCADO_ATRIBUTO.matcher(texto).find() ? HtmlUtils.htmlEscape(texto, "UTF-8") : texto;
    }

    /**
     * Fase D — Calidad (backend Node, sin Authorization, respuesta {ok,data} desenvuelta): faret.inspecciones.list →
     * GET calidad-faret/registros?{BuildInspeccionesFiltros}&page&pageSize (pageSize acotado a 200, el tope real del controller de Calidad).
     */
    public BridgeResult inspeccionesList(ObjectNode payload, SessionUser usuario) {
        return calidadLista(RUTA_INSPECCIONES_REGISTROS, FILTROS_INSPECCIONES, payload, usuario);
    }

    /** faret.inspeccionesPallet.list → GET calidad-faret-pallet/registros?{BuildInspeccionesPalletFiltros}&page&pageSize (tope 200). */
    public BridgeResult inspeccionesPalletList(ObjectNode payload, SessionUser usuario) {
        return calidadLista(RUTA_PALLET_REGISTROS, FILTROS_PALLET, payload, usuario);
    }

    /** faret.inspecciones.adjuntos → GET calidad-faret/registros/{id}/adjuntos (id entero positivo; si no, "id es requerido"). */
    public BridgeResult inspeccionesAdjuntos(ObjectNode payload, SessionUser usuario) {
        return calidadPorId(RUTA_INSPECCIONES_REGISTROS, "/adjuntos", payload, usuario);
    }

    /** faret.inspeccionesPallet.get → GET calidad-faret-pallet/registros/{id} (id entero positivo; si no, "id es requerido"). */
    public BridgeResult inspeccionesPalletGet(ObjectNode payload, SessionUser usuario) {
        return calidadPorId(RUTA_PALLET_REGISTROS, "", payload, usuario);
    }

    private BridgeResult calidadLista(String ruta, List<String> orden, ObjectNode payload, SessionUser usuario) {
        if (!calidad.configurada()) {
            return BridgeResult.error(MENSAJE_CALIDAD_NO_CONFIGURADA);
        }
        Query filtros = construirQuery(payload, orden);
        if (filtros.error() != null) {
            return BridgeResult.error(filtros.error());
        }
        StringBuilder query = new StringBuilder(filtros.texto());
        String errorPaginacion = agregarPaginacion(payload, query, MAX_PAGE_SIZE_CALIDAD);
        if (errorPaginacion != null) {
            return BridgeResult.error(errorPaginacion);
        }
        return FaretRespuestas.desenvolver(calidad.get(usuario, ruta(ruta, query.toString())), mapper);
    }

    private BridgeResult calidadPorId(String ruta, String sufijo, ObjectNode payload, SessionUser usuario) {
        if (!calidad.configurada()) {
            return BridgeResult.error(MENSAJE_CALIDAD_NO_CONFIGURADA);
        }
        JsonNode nodo = payload.get("id");
        if (tipoInvalido(nodo)) {
            return BridgeResult.error(MENSAJE_FILTRO_INVALIDO);
        }
        Integer id = enteroPositivo(nodo);
        if (id == null) {
            return BridgeResult.error(MENSAJE_ID_REQUERIDO);
        }
        return FaretRespuestas.desenvolver(calidad.get(usuario, ruta + "/" + id + sufijo), mapper);
    }

    /** faret.inspecciones.resumen → GET calidad-faret/resumen?fechaDesde&fechaHasta&areaControl&operador&maquina&presentaDefectos&nvFaret */
    public BridgeResult inspeccionesResumen(ObjectNode payload, SessionUser usuario) {
        if (!calidad.configurada()) {
            return BridgeResult.error(MENSAJE_CALIDAD_NO_CONFIGURADA);
        }
        Query filtros = construirQuery(payload, FILTROS_INSPECCIONES);
        return filtros.error() != null ? BridgeResult.error(filtros.error())
                : FaretRespuestas.desenvolver(calidad.get(usuario, ruta(RUTA_INSPECCIONES_RESUMEN, filtros.texto())), mapper);
    }

    /** faret.maquinas.resumen → GET calidad-faret/maquinas/resumen[?maquina=..] */
    public BridgeResult maquinasResumen(ObjectNode payload, SessionUser usuario) {
        if (!calidad.configurada()) {
            return BridgeResult.error(MENSAJE_CALIDAD_NO_CONFIGURADA);
        }
        Query filtros = construirQuery(payload, List.of("maquina"));
        return filtros.error() != null ? BridgeResult.error(filtros.error())
                : FaretRespuestas.desenvolver(calidad.get(usuario, ruta(RUTA_MAQUINAS_RESUMEN, filtros.texto())), mapper);
    }

    /**
     * faret.dashboard.resumen: listado de NC y luego las acciones de cada NC, una a una (igual que Photino; las NC cuyas
     * acciones fallan o vienen mal formadas se omiten sin romper el dashboard). El id va a la ruta solo si es un entero
     * positivo ya leído como int de la propia respuesta de la API.
     */
    public BridgeResult dashboardResumen(ObjectNode payload, SessionUser usuario) {
        if (!mejoraContinua.configurada()) {
            return BridgeResult.error(MENSAJE_MC_NO_CONFIGURADA);
        }
        InnpackApiClient.Respuesta listado = mejoraContinua.get(usuario, RUTA_NC);
        if (!FaretRespuestas.httpOk(listado)) {
            return BridgeResult.error(MENSAJE_DASHBOARD_LISTADO);
        }
        List<FaretDashboardResumen.Nc> ncs;
        try {
            ncs = FaretDashboardResumen.leerNcs(mapper.readTree(listado.body()), FaretDashboardResumen.ZONA);
        } catch (RuntimeException e) {
            return BridgeResult.error(MENSAJE_DASHBOARD_INVALIDO);
        }
        List<FaretDashboardResumen.Accion> acciones = new ArrayList<>();
        for (FaretDashboardResumen.Nc nc : ncs) {
            if (nc.id() <= 0) {
                continue;
            }
            InnpackApiClient.Respuesta r = mejoraContinua.get(usuario, RUTA_NC + "/" + nc.id() + "/acciones");
            if (!FaretRespuestas.httpOk(r)) {
                continue;
            }
            try {
                acciones.addAll(FaretDashboardResumen.leerAcciones(mapper.readTree(r.body()), FaretDashboardResumen.ZONA));
            } catch (RuntimeException e) {
                // se omite esa NC puntual; el resto del dashboard sigue funcionando
            }
        }
        LocalDate hoy = LocalDate.now(clock.withZone(FaretDashboardResumen.ZONA));
        return BridgeResult.ok(FaretDashboardResumen.calcular(ncs, acciones, hoy, mapper));
    }

    /**
     * page / pageSize de Photino (TryGetInt + {@code > 0}, al final de la query): un valor no entero o ≤ 0 no viaja;
     * bool/objeto/array se rechazan; page > MAX_PAGE se rechaza; pageSize se acota al tope real de la API (misma página efectiva).
     */
    private static String agregarPaginacion(ObjectNode payload, StringBuilder query, int topePageSize) {
        for (String clave : List.of("page", "pageSize")) {
            JsonNode nodo = payload.get(clave);
            if (tipoInvalido(nodo)) {
                return MENSAJE_FILTRO_INVALIDO;
            }
            Integer valor = enteroPositivo(nodo);
            if (valor == null) {
                continue;
            }
            if (clave.equals("page") && valor > MAX_PAGE) {
                return MENSAJE_PAGINACION;
            }
            if (clave.equals("pageSize")) {
                valor = Math.min(valor, topePageSize);
            }
            query.append(query.isEmpty() ? "" : "&").append(clave).append('=').append(valor);
        }
        return null;
    }

    /** Un valor que no sea null, texto ni número (bool, objeto, array) nunca es un filtro ni un id válido (regla 2e). */
    private static boolean tipoInvalido(JsonNode nodo) {
        return nodo != null && !nodo.isNull() && !nodo.isString() && !nodo.isNumber();
    }

    private static String ruta(String ruta, String query) {
        return query.isEmpty() ? ruta : ruta + "?" + query;
    }

    private BridgeResult qc(SessionUser usuario, String ruta, StringBuilder query) {
        String path = query.isEmpty() ? ruta : ruta + "?" + query;
        return FaretRespuestas.desenvolver(qualityControl.get(usuario, path), mapper);
    }

    // ----------------------------------------------------------------------------- filtros

    private record Query(String texto, String error) {}

    /** Arma "k=v&k=v" con los filtros no vacíos, en el orden dado; error = primer filtro inválido. */
    private Query construirQuery(ObjectNode payload, List<String> orden) {
        StringBuilder query = new StringBuilder();
        for (String clave : orden) {
            JsonNode nodo = payload.get(clave);
            if (nodo != null && !nodo.isNull() && !nodo.isString() && !nodo.isNumber()) {
                return new Query(null, MENSAJE_FILTRO_INVALIDO);
            }
            String valor = texto(nodo).trim();
            if (valor.isEmpty()) {
                continue;
            }
            String error = validar(clave, valor);
            if (error != null) {
                return new Query(null, error);
            }
            query.append(query.isEmpty() ? "" : "&").append(clave).append('=').append(UriEscape.dataString(valor));
        }
        return new Query(query.toString(), null);
    }

    private static String validar(String clave, String valor) {
        if (FECHAS.contains(clave)) {
            if (!FECHA_ISO.matcher(valor).matches()) {
                return "La fecha " + clave + " no es válida (formato AAAA-MM-DD).";
            }
            try {
                LocalDate.parse(valor);
            } catch (DateTimeParseException e) {
                return "La fecha " + clave + " no es válida (formato AAAA-MM-DD).";
            }
            return null;
        }
        if (clave.equals("presentaDefectos")) {
            return PRESENTA_DEFECTOS.contains(valor) ? null : MENSAJE_PRESENTA_DEFECTOS;
        }
        if (valor.length() > MAX_FILTRO) {
            return MENSAJE_FILTRO_LARGO;
        }
        return CONTROL.matcher(valor).find() ? MENSAJE_FILTRO_INVALIDO : null;
    }

    /** GetString de Photino: string o número → texto; cualquier otro tipo (o ausente) → "". */
    private static String texto(JsonNode nodo) {
        return nodo != null && (nodo.isString() || nodo.isNumber()) ? nodo.asString() : "";
    }

    /** TryGetInt + {@code > 0} de Photino: entero int32 (número entero o string) positivo; si no, null (no viaja). */
    private static Integer enteroPositivo(JsonNode nodo) {
        if (nodo == null || nodo.isNull()) {
            return null;
        }
        Integer valor = null;
        if (nodo.isIntegralNumber() && nodo.canConvertToInt()) {
            valor = nodo.asInt();
        } else if (nodo.isString()) {
            try {
                valor = Integer.valueOf(nodo.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return valor != null && valor > 0 ? valor : null;
    }
}
