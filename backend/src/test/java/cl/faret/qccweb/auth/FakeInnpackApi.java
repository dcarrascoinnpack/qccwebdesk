package cl.faret.qccweb.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * API INNPACK simulada para tests (NUNCA se usan credenciales reales ni la API de producción).
 * Replica el comportamiento real de QualityControlInnpack.Api AuthController/AuthService:
 * 400 si faltan campos; 401 con "Usuario no existe" / "Usuario desactivado" / "Contraseña
 * incorrecta"; 200 con ApiResponse { success, message, data { token, userId, codigoUsuario,
 * nombreCompleto, rol } } y un JWT cuyo payload trae exp.
 */
public final class FakeInnpackApi implements AutoCloseable {

    public record Usuario(int id, String codigo, String password, String nombre, String rol, boolean activo) {}

    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Usuario> usuarios = new ConcurrentHashMap<>();
    private final AtomicInteger llamadas = new AtomicInteger();
    private volatile Supplier<Instant> expiracionToken;
    private volatile boolean caida;

    public FakeInnpackApi(Clock clock) {
        this.expiracionToken = () -> clock.instant().plusSeconds(8 * 3600);
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/api/auth/login", this::login);
        server.createContext("/api/auth/mis-permisos", this::misPermisos);
        server.createContext("/fps/liberaciones/inspectores", this::fpsInspectores);
        server.createContext("/fps/materiales-por-proceso", this::fpsMateriales);
        server.createContext("/sap/api/recepcion/bobinas", this::sapBobinas);
        server.createContext("/api/home/dashboard", this::dashboard);
        server.createContext("/api/maquinas-seguimiento/resumen", this::maquinasResumen);
        server.createContext("/api/dashboard/filtros", ex -> dashboardLectura(ex, true));
        server.createContext("/api/dashboard/resumen", ex -> dashboardLectura(ex, false));
        server.createContext("/api/dashboard", this::dashboardEscritura);
        server.createContext("/api/registros-produccion/filtros", ex -> registrosProduccionLectura(ex, true));
        server.createContext("/api/registros-produccion/resumen", ex -> registrosProduccionLectura(ex, false));
        server.createContext("/api/registros-produccion", this::registrosProduccionEscritura);
        server.createContext("/api/registros-control", this::registrosControl);
        server.createContext("/api/producto-terminado", this::productoTerminado);
        server.createContext("/api/certificados-liberacion", this::certificadosLiberacion);
        server.createContext("/api/control-documental", this::controlDocumental);
        server.createContext("/api/no-conformidades", this::noConformidades);
        server.createContext("/api/nc-catalogos", this::noConformidades);
        server.createContext("/api/recepcion-calidad", this::recepcionCalidad);
        server.createContext("/api/usuarios", this::usuarios);
        server.createContext("/api/muestra-laboratorio", this::muestraLaboratorio);
        server.createContext("/api/talleres-externos", this::talleresExternos);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
    }

    /** Modo de respuesta de GET api/home/dashboard. */
    public enum ModoDashboard { NORMAL, ERROR_NEGOCIO, ERROR_500 }

    private final java.util.Set<Integer> revocados = ConcurrentHashMap.newKeySet();
    private final java.util.List<String> authorizationRecibidos = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final AtomicInteger llamadasDashboard = new AtomicInteger();
    private volatile ModoDashboard modoDashboard = ModoDashboard.NORMAL;

    /** Simula que la API invalida los tokens de un usuario (→ 401 en sus próximas llamadas). */
    public void revocarTokens(int userId) {
        revocados.add(userId);
    }

    public void modoDashboard(ModoDashboard modo) {
        this.modoDashboard = modo;
    }

    public int llamadasDashboard() {
        return llamadasDashboard.get();
    }

    public java.util.List<String> authorizationRecibidos() {
        return java.util.List.copyOf(authorizationRecibidos);
    }

    public void reiniciarDashboard() {
        revocados.clear();
        authorizationRecibidos.clear();
        queriesMaquinas.clear();
        peticionesDashboard.clear();
        peticionesProduccion.clear();
        peticionesControl.clear();
        peticionesProductoTerminado.clear();
        peticionesCertificados.clear();
        peticionesControlDocumental.clear();
        peticionesNoConformidades.clear();
        seguimientosRecibidos.clear();
        accionesRecibidas.clear();
        analisisRecibidos.clear();
        analisisPorNc.clear();
        catalogosRecibidos.clear();
        ncCreadasRecibidas.clear();
        adjuntosRecibidos.clear();
        ncActualizadasRecibidas.clear();
        versionNc.clear();
        gestionesRecibidas.clear();
        cierresRecibidos.clear();
        ncCerradas.clear();
        accionesActualizadas.clear();
        estadoAccion.clear();
        versionAccion.clear();
        creadosPorCatalogo.clear();
        peticionesRecepcion.clear();
        muestreosRecibidos.clear();
        muestreadasPorLote.clear();
        estadoLote.clear();
        muestrasRecibidas.clear();
        muestraPorLote.clear();
        siguienteMuestra.set(700);
        demoraMuestraMs = 0;
        estadosRecibidos.clear();
        demoraEstadoMs = 0;
        planPorLote.clear();
        planesRecibidos.clear();
        planesInsertados.set(0);
        demoraPlanMs = 0;
        peticionesUsuarios.clear();
        peticionesLaboratorio.clear();
        peticionesTalleres.clear();
        modoUsuarios = "NORMAL";
        modoDashboard = ModoDashboard.NORMAL;
        permisosPorUsuario.clear();
        misPermisosCaidos.clear();
        misPermisosConsultados.clear();
        fpsConsultas.clear();
        modoFps = "NORMAL";
        sapConsultas.clear();
        modoSap = "NORMAL";
        lotesCreados.clear();
        siguienteLote.set(1000);
        ncPorLote.clear();
        ncRecepcionRecibidas.clear();
        siguienteNcRecepcion.set(900);
        demoraNcRecepcionMs = 0;
        estadoValidacionDashboard.clear();
        eliminadosDashboard.clear();
        estadoGlobalDashboard = null;
        estadoValidacionProduccion.clear();
        eliminadosProduccion.clear();
        estadoGlobalProduccion = null;
        estadoValidacionControl.clear();
        eliminadosControl.clear();
        trabajosCreados.clear();
        versionTrabajo.clear();
        trabajosEliminados.clear();
        siguienteTrabajo.set(20000);
        talleresInactivos.clear();
        procesosInactivos.clear();
        modoSincronizarFps = "NORMAL";
    }

    // ------------------------------------------------------------------ Fase 3y: apisapfaret GET api/recepcion/bobinas[/lotes]
    public static final String SAP_API_KEY = "clave-sap-de-prueba";
    private final java.util.List<String> sapConsultas = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile String modoSap = "NORMAL";

    /** "<ruta>?<query cruda>" recibidas por el apisapfaret simulado. */
    public java.util.List<String> sapConsultas() {
        return java.util.List.copyOf(sapConsultas);
    }

    /** NORMAL | ERROR_502 | JSON_INVALIDO | TIPOS_RAROS (textos numéricos y campos ausentes). */
    public void modoSap(String modo) {
        this.modoSap = modo;
    }

    /**
     * Como RecepcionController de apisapfaret: X-Api-Key (401), desde/hasta o itemCode/fecha obligatorios (400 {ok,error}),
     * empresa INNPACK|FARET (400), 502 si falla SAP. Datos con un proveedor con HTML y un número de bobina con comillas
     * (la vista de Photino los pinta con innerHTML / value="...").
     */
    private void sapBobinas(HttpExchange ex) throws IOException {
        if (!SAP_API_KEY.equals(ex.getRequestHeaders().getFirst("X-Api-Key"))) {
            responder(ex, 401, "{\"ok\":false,\"error\":\"API key inválida\"}");
            return;
        }
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        sapConsultas.add(path.substring(4) + "?" + query);
        Map<String, String> q = new java.util.HashMap<>();
        for (String par : query.split("&")) {
            int i = par.indexOf('=');
            if (i > 0) {
                q.putIfAbsent(par.substring(0, i), java.net.URLDecoder.decode(par.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        String empresa = q.getOrDefault("empresa", "INNPACK");
        if (!empresa.equals("INNPACK") && !empresa.equals("FARET")) {
            responder(ex, 400, "{\"ok\":false,\"error\":\"empresa debe ser INNPACK o FARET\"}");
            return;
        }
        if (modoSap.equals("ERROR_502")) {
            responder(ex, 502, "{\"ok\":false,\"error\":\"Service Layer: login failed for B1SESSION xyz\",\"sap\":null}");
            return;
        }
        if (modoSap.equals("JSON_INVALIDO")) {
            responder(ex, 200, "<html>proxy</html>");
            return;
        }
        if (path.endsWith("/lotes")) {
            if (q.getOrDefault("itemCode", "").isBlank() || q.getOrDefault("fecha", "").isBlank()) {
                responder(ex, 400, "{\"ok\":false,\"error\":\"itemCode y fecha son obligatorios\"}");
                return;
            }
            String item = mapper.writeValueAsString(q.get("itemCode"));
            responder(ex, 200, "{\"ok\":true,\"total\":2,\"data\":[{\"itemCode\":" + item + ",\"numeroBobina\":\"B-001\",\"absEntry\":11,"
                    + "\"fechaCreacion\":\"20260926\"},{\"itemCode\":" + item + ",\"numeroBobina\":\"B\\\"><img src=x>\",\"absEntry\":12,"
                    + "\"fechaCreacion\":\"20260926\",\"extra\":\"no-debe-llegar\"}]}");
            return;
        }
        if (q.getOrDefault("desde", "").isBlank() || q.getOrDefault("hasta", "").isBlank()) {
            responder(ex, 400, "{\"ok\":false,\"error\":\"desde y hasta son obligatorios (yyyyMMdd)\"}");
            return;
        }
        if (modoSap.equals("TIPOS_RAROS")) {
            responder(ex, 200, "{\"ok\":true,\"data\":[{\"docEntry\":\"77\",\"lineNum\":1.5,\"fechaRecepcion\":20260926,"
                    + "\"cantidadRecibida\":\"x\",\"anchoDeclarado\":\"1.25\",\"proveedor\":null}]}");
            return;
        }
        responder(ex, 200, "{\"ok\":true,\"total\":2,\"data\":[{\"docEntry\":501,\"lineNum\":0,\"fechaRecepcion\":\"20260926\","
                + "\"proveedor\":\"Papeles & Cía <b>SA</b>\",\"guia\":\"G-77\",\"itemCode\":\"1095SC21000090\",\"descripcion\":\"Kraft 125\","
                + "\"cantidadRecibida\":1250.5,\"anchoDeclarado\":1600,\"gramajeDeclarado\":125,\"empresaInterna\":\"no-debe-llegar\"},"
                + "{\"docEntry\":502,\"lineNum\":1,\"fechaRecepcion\":\"20260927\",\"proveedor\":\"Papeles Sur\",\"guia\":\"G-78\","
                + "\"itemCode\":\"ABC-1\",\"descripcion\":\"Test\",\"cantidadRecibida\":10,\"anchoDeclarado\":null,\"gramajeDeclarado\":null}]}");
    }

    // ------------------------------------------------------------------ Fase 3v: fps-api GET liberaciones/inspectores
    /** API key que espera el fps-api simulado (la real vive solo en el entorno del servidor). */
    public static final String FPS_API_KEY = "clave-fps-de-prueba";
    private final java.util.List<String> fpsConsultas = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile String modoFps = "NORMAL";

    /** Query cruda recibida en cada GET (para verificar tandas, escape y que solo viajan dígitos). */
    public java.util.List<String> fpsConsultas() {
        return java.util.List.copyOf(fpsConsultas);
    }

    /** NORMAL | ERROR_500 | JSON_INVALIDO | SIN_DATA | GRANDE (cuerpo > tope del gateway). */
    public void modoFps(String modo) {
        this.modoFps = modo;
    }

    /**
     * GET materiales-por-proceso?ids= (Fase 3w), como fps-api: x-api-key (401), ids con formato (400). 88001 → 2 insumos
     * (Id_Proceso numérico, uno con HTML y un campo extra); 88003 → sin insumos. Usa modoFps para fallos.
     */
    private void fpsMateriales(HttpExchange ex) throws IOException {
        if (!FPS_API_KEY.equals(ex.getRequestHeaders().getFirst("x-api-key"))) {
            responder(ex, 401, "{\"ok\":false,\"message\":\"No autorizado\"}");
            return;
        }
        String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        fpsConsultas.add(query);
        if (!query.matches("ids=[0-9]{1,18}")) {
            responder(ex, 400, "{\"ok\":false,\"message\":\"Uno o más ids de proceso tienen un formato no válido.\"}");
            return;
        }
        switch (modoFps) {
            case "ERROR_500" -> { responder(ex, 500, "{\"ok\":false,\"message\":\"SQL timeout\"}"); return; }
            case "JSON_INVALIDO" -> { responder(ex, 200, "<html>proxy</html>"); return; }
            case "OK_FALSE" -> { responder(ex, 200, "{\"ok\":false,\"message\":\"x\"}"); return; }
            default -> { }
        }
        String data = query.equals("ids=88001")
                ? "[{\"Id_Proceso\":88001,\"ItemCode\":\"INS-01\",\"ItemName\":\"Tinta <b>negra</b>\",\"Estatus\":\"L\"},"
                        + "{\"Id_Proceso\":88001,\"ItemCode\":\"INS-02\",\"ItemName\":null,\"Estatus\":\"L\",\"Extra\":\"no-debe-llegar\"}]"
                : "[]";
        responder(ex, 200, "{\"ok\":true,\"total\":0,\"data\":" + data + "}");
    }

    /**
     * Como produccion.routes.js de fps-api: x-api-key obligatoria (401), nps = dígitos separados por coma, máx. 300
     * (400). Filas: NP terminada en 0 → sin liberación; el resto 1 fila INNPACK SPA (Np numérico). Además, a propósito,
     * filas que el gateway debe descartar: otra empresa (FARET), NP no pedida y un campo extra.
     */
    private void fpsInspectores(HttpExchange ex) throws IOException {
        if (!FPS_API_KEY.equals(ex.getRequestHeaders().getFirst("x-api-key"))) {
            responder(ex, 401, "{\"ok\":false,\"message\":\"No autorizado\"}");
            return;
        }
        String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        fpsConsultas.add(query);
        String crudo = java.net.URLDecoder.decode(query.startsWith("nps=") ? query.substring(4) : "", StandardCharsets.UTF_8);
        java.util.List<String> nps = java.util.Arrays.stream(crudo.split(",")).map(String::trim).filter(x -> !x.isEmpty()).distinct().toList();
        if (nps.isEmpty() || nps.size() > 300 || nps.stream().anyMatch(x -> !x.matches("[0-9]+"))) {
            responder(ex, 400, "{\"ok\":false,\"message\":\"El parámetro \\\"nps\\\" tiene un formato no válido.\"}");
            return;
        }
        switch (modoFps) {
            case "ERROR_500" -> { responder(ex, 500, "{\"ok\":false,\"codigo\":\"FPS_NO_DISPONIBLE\"}"); return; }
            case "JSON_INVALIDO" -> { responder(ex, 200, "<html>proxy</html>"); return; }
            case "SIN_DATA" -> { responder(ex, 200, "{\"ok\":true,\"total\":0}"); return; }
            case "GRANDE" -> { responder(ex, 200, "{\"ok\":true,\"data\":[],\"x\":\"" + "A".repeat(3 * 1024 * 1024) + "\"}"); return; }
            default -> { }
        }
        StringBuilder data = new StringBuilder("[");
        for (String np : nps) {
            if (np.endsWith("0")) {
                continue;
            }
            data.append(data.length() > 1 ? "," : "").append("{\"Np\":").append(np).append(",\"Empresa\":\"INNPACK SPA\",\"CodigoArticulo\":\"C-")
                    .append(np).append("\",\"Inspector\":\"Inspector <b>").append(np).append("</b>\",\"UltimaLiberacion\":\"2026-09-2")
                    .append(np.charAt(np.length() - 1)).append("T00:00:00.000Z\",\"UltimoFolio\":").append(np).append("1,\"Liberaciones\":2,")
                    .append("\"Secreto\":\"no-debe-llegar\"}");
        }
        if (nps.contains("4001")) {
            data.append(",{\"Np\":4001,\"Empresa\":\"FARET SPA\",\"CodigoArticulo\":\"F-1\",\"Inspector\":\"Inspector Faret\",")
                    .append("\"UltimaLiberacion\":\"2026-09-01T00:00:00.000Z\",\"UltimoFolio\":1,\"Liberaciones\":1}")
                    .append(",{\"Np\":999999,\"Empresa\":\"INNPACK SPA\",\"CodigoArticulo\":\"X\",\"Inspector\":\"No pedida\",")
                    .append("\"UltimaLiberacion\":\"2026-09-01T00:00:00.000Z\",\"UltimoFolio\":1,\"Liberaciones\":1}");
        }
        responder(ex, 200, "{\"ok\":true,\"total\":0,\"data\":" + data.append("]") + "}");
    }

    // ------------------------------------------------------------------ Fase 3u: GET api/auth/mis-permisos
    private final Map<Integer, String> permisosPorUsuario = new ConcurrentHashMap<>();
    private final java.util.Set<Integer> misPermisosCaidos = ConcurrentHashMap.newKeySet();
    private final java.util.List<Integer> misPermisosConsultados = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Permisos personalizados de un usuario (arreglo JSON crudo de data, p. ej. [{"modulo":"dashboard","nivel":"VER"}]). */
    public void permisos(int userId, String dataJson) {
        permisosPorUsuario.put(userId, dataJson);
    }

    /** Simula que mis-permisos falla (500) para un usuario: el login debe anularse, como en Photino. */
    public void misPermisosCaido(int userId) {
        misPermisosCaidos.add(userId);
    }

    /** "sub" de los tokens con que se consultó mis-permisos (la API toma el id del token). */
    public java.util.List<Integer> misPermisosConsultados() {
        return java.util.List.copyOf(misPermisosConsultados);
    }

    /** Como AuthController.MisPermisos de la API: [Authorize], id del token, ApiResponse con data = arreglo. */
    private void misPermisos(HttpExchange ex) throws IOException {
        Integer sub = subDeBearer(ex.getRequestHeaders().getFirst("Authorization"));
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        misPermisosConsultados.add(sub);
        if (misPermisosCaidos.contains(sub)) {
            responder(ex, 500, "{\"type\":\"about:blank\",\"title\":\"Internal Server Error\",\"status\":500}");
            return;
        }
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + permisosPorUsuario.getOrDefault(sub, "[]")
                + ",\"errors\":null}");
    }

    /**
     * Valida el Bearer como la API real ([Authorize]): 401 sin cuerpo si falta, no lo emitió esta API
     * o fue revocado. En data devuelve el "sub" del token recibido para que los tests comprueben con
     * qué identidad llamó el gateway.
     */
    private void dashboard(HttpExchange ex) throws IOException {
        llamadasDashboard.incrementAndGet();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        switch (modoDashboard) {
            case ERROR_NEGOCIO -> responder(ex, 400, fallo("No se pudo calcular el dashboard"));
            case ERROR_500 -> responder(ex, 500, "{\"type\":\"about:blank\",\"title\":\"Internal Server Error\",\"status\":500,\"detail\":\"SqlException: timeout en calidad_db\"}");
            default -> responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"kpis\":{\"controlesHoy\":42,"
                    + "\"noConformesHoy\":3,\"mermaHoy\":12.5},\"alertas\":[],\"usuarioDelToken\":" + sub + "},\"errors\":null}");
        }
    }

    private final java.util.List<String> queriesMaquinas = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Queries recibidas en GET api/maquinas-seguimiento/resumen (para verificar el mapeo de parámetros). */
    public java.util.List<String> queriesMaquinas() {
        return java.util.List.copyOf(queriesMaquinas);
    }

    /** Respuesta con la forma real de MaquinasSeguimientoResumenDto (camelCase, ApiResponse). */
    public static String dataMaquinas(int sub) {
        return "{\"totalMaquinas\":2,\"maquinasConRegistros\":1,\"registrosMaquinaSeleccionada\":1,"
                + "\"rechazosMaquinaSeleccionada\":0,\"maquinas\":[{\"id\":5,\"nombre\":\"Corrugadora 1\",\"proceso\":\"Corrugado\"},"
                + "{\"id\":7,\"nombre\":\"Troqueladora 2\",\"proceso\":\"Troquelado\"}],\"registros\":[{\"id\":901,"
                + "\"fechaRegistro\":\"23-09-2026\",\"horaRegistro\":\"10:15\",\"usuario\":\"Operador Uno\",\"proceso\":\"Corrugado\","
                + "\"maquina\":\"Corrugadora 1\",\"formulario\":\"Control visual\",\"np\":\"3996\",\"producto\":\"Caja 40x30\","
                + "\"turno\":\"A\",\"estado\":\"Validado\",\"observacion\":\"ñandú — ok\"}],\"usuarioDelToken\":" + sub + "}";
    }

    private void maquinasResumen(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        queriesMaquinas.add(String.valueOf(ex.getRequestURI().getRawQuery()));
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Máquina no encontrada"));
            return;
        }
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + dataMaquinas(sub) + ",\"errors\":null}");
    }

    private final java.util.List<String> peticionesDashboard = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    /** Fase 4b: estado_validacion mutado por validar/rechazar (id → VALIDADO|RECHAZADO), eliminado lógico por id. */
    private final Map<Integer, String> estadoValidacionDashboard = new ConcurrentHashMap<>();
    private final java.util.Set<Integer> eliminadosDashboard = ConcurrentHashMap.newKeySet();
    /** Fase 4b': validarTodo/rechazarTodo de Dashboard NO tienen WHERE en la API real → afecta TODA fila, sin excepción. */
    private volatile String estadoGlobalDashboard;

    /** "filtros", "resumen?<query cruda>" o "validar:<id>"/"rechazar:<id>"/"eliminar:<id>"/"validarTodo"/"rechazarTodo" en api/dashboard/*. */
    public java.util.List<String> peticionesDashboard() {
        return java.util.List.copyOf(peticionesDashboard);
    }

    /** Fase 4b: estado_validacion vigente de un registro de Dashboard (lo que mutó validar/rechazar/validarTodo/rechazarTodo). */
    public String estadoValidacionDashboard(int id) {
        return estadoValidacionDashboard.containsKey(id) ? estadoValidacionDashboard.get(id) : estadoGlobalDashboard;
    }

    /** Fase 4b: simula que otra persona (Photino u otra sesión) ya validó/rechazó este registro. */
    public void estadoValidacionDashboardPorOtro(int id, String estado) {
        estadoValidacionDashboard.put(id, estado);
    }

    public static String dataDashboardFiltros() {
        return "{\"usuarios\":[{\"id\":10,\"nombre\":\"Operador Uno\"},{\"id\":20,\"nombre\":\"María José Peña\"}],"
                + "\"procesos\":[{\"id\":4,\"nombre\":\"Pegado\"},{\"id\":5,\"nombre\":\"Termoformado\"}]}";
    }

    /**
     * Forma real de DashboardResumenDto (camelCase). Con inspector=0 devuelve listas vacías y KPIs en
     * cero; con proceso=999 un dataset grande (400 registros, 40 inspectores).
     */
    public static String dataDashboardResumen(int sub, String query) {
        boolean vacio = query != null && query.contains("inspector=0");
        int registros = vacio ? 0 : (query != null && query.contains("proceso=999") ? 400 : 2);
        int inspectores = vacio ? 0 : (registros == 400 ? 40 : 2);
        StringBuilder desempeno = new StringBuilder();
        for (int i = 0; i < inspectores; i++) {
            desempeno.append(i > 0 ? "," : "").append("{\"inspector\":\"Inspector ").append(i)
                    .append("\",\"cumplimiento\":").append(90 + i % 10).append(",\"controlesProgramados\":50,\"controlesRealizados\":")
                    .append(45 + i % 5).append(",\"noConformidades\":").append(i % 3).append(",\"estado\":\"OK\"}");
        }
        StringBuilder ultimos = new StringBuilder();
        for (int i = 0; i < registros; i++) {
            ultimos.append(i > 0 ? "," : "").append("{\"id\":").append(500 + i)
                    .append(",\"fechaRegistro\":\"23-09-2026\",\"horaRegistro\":\"09:").append(String.format("%02d", i % 60))
                    .append("\",\"usuario\":\"María José Peña\",\"proceso\":\"Pegado\",\"maquina\":\"Pegadora 3\",\"formulario\":\"Control visual\","
                            + "\"np\":\"41").append(String.format("%02d", i % 100)).append("\",\"codigoProducto\":\"CP-").append(i)
                    .append("\",\"producto\":\"Estuche cartón ñandú «E2E»\",\"turno\":\"A\",\"estado\":\"Conforme\",\"observacion\":\"Sin novedad\","
                            + "\"tipoMerma\":\"Pegado\",\"cantidadMerma\":\"1.50\",\"tipoDefecto\":\"\",\"bobinaLote\":\"L-1\",\"bobinaCodigo\":\"B-1\","
                            + "\"bobinaDescripcion\":\"Bobina 120g\",\"bobinaObservacion\":\"\",\"estadoValidacion\":\"Pendiente\","
                            + "\"fechaValidacion\":\"\",\"usuarioValidacion\":\"\",\"imagenUrl\":\"\"}");
        }
        return "{\"controlesHoy\":" + (vacio ? 0 : 12) + ",\"controlesPeriodo\":" + registros + ",\"cumplimientoGeneral\":" + (vacio ? 0 : 93.5)
                + ",\"noConformidadesDetectadas\":" + (vacio ? 0 : 3) + ",\"mermaHoy\":" + (vacio ? 0 : 4.25) + ",\"registrosConObservacionHoy\":1,"
                + "\"cumplimientoPorInspector\":[" + (vacio ? "" : "{\"inspector\":\"María José Peña\",\"total\":10,\"porcentaje\":95.5}") + "],"
                + "\"noConformidadesPorInspector\":[" + (vacio ? "" : "{\"inspector\":\"Operador Uno\",\"total\":2,\"porcentaje\":20}") + "],"
                + "\"controlesPorProceso\":[" + (vacio ? "" : "{\"proceso\":\"Pegado\",\"inspector\":\"Operador Uno\",\"total\":7}") + "],"
                + "\"tendenciaCumplimiento\":[" + (vacio ? "" : "{\"fecha\":\"2026-09-22\",\"cumplimiento\":91.2},{\"fecha\":\"2026-09-23\",\"cumplimiento\":93.5}") + "],"
                + "\"desempenoIndividual\":[" + desempeno + "],\"ultimosRegistros\":[" + ultimos + "],\"usuarioDelToken\":" + sub + "}";
    }

    private void dashboardLectura(HttpExchange ex, boolean filtros) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String query = ex.getRequestURI().getRawQuery();
        peticionesDashboard.add(filtros ? "filtros" + (query == null ? "" : "?" + query) : "resumen?" + query);
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Rango de fechas inválido"));
            return;
        }
        String data = filtros ? dataDashboardFiltros() : dataDashboardResumen(sub, query);
        if (!filtros) {
            data = aplicarEstados(data, "ultimosRegistros", estadoValidacionDashboard, eliminadosDashboard, estadoGlobalDashboard);
        }
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + data + ",\"errors\":null}");
    }

    /**
     * Fase 4b/4b': aplica sobre el JSON de una lista/resumen el estado_validacion mutado por validar/
     * rechazar (por id) o validarTodo/rechazarTodo (`estadoGlobal`, sin excepción, como el UPDATE sin
     * WHERE de la API real) y quita las filas marcadas como eliminadas. El mapa por id tiene prioridad
     * sobre `estadoGlobal` (una escritura individual posterior a un "todo" debe verse reflejada).
     */
    private String aplicarEstados(String dataJson, String campoFilas, Map<Integer, String> estados, java.util.Set<Integer> eliminados,
            String estadoGlobal) {
        try {
            JsonNode raiz = mapper.readTree(dataJson);
            if (!(raiz instanceof tools.jackson.databind.node.ObjectNode obj)) {
                return dataJson;
            }
            JsonNode filas = obj.get(campoFilas);
            if (filas != null && filas.isArray()) {
                tools.jackson.databind.node.ArrayNode nuevo = mapper.createArrayNode();
                for (JsonNode fila : filas) {
                    int id = fila.path("id").asInt();
                    if (eliminados.contains(id)) {
                        continue;
                    }
                    tools.jackson.databind.node.ObjectNode f = (tools.jackson.databind.node.ObjectNode) fila;
                    String estado = estados.containsKey(id) ? estados.get(id) : estadoGlobal;
                    if (estado != null) {
                        f.put("estadoValidacion", estado);
                    }
                    nuevo.add(f);
                }
                obj.set(campoFilas, nuevo);
            }
            return obj.toString();
        } catch (RuntimeException e) {
            return dataJson;
        }
    }

    /**
     * Fase 4b: PUT api/dashboard/{id}/validar|rechazar y DELETE api/dashboard/{id}, igual que
     * DashboardRepository.cs real (UPDATE/UPDATE lógico directo por id, sin autor ni validación de
     * existencia: responde success:true aunque el id no exista).
     */
    private void dashboardEscritura(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        java.util.regex.Matcher val = java.util.regex.Pattern.compile("^/api/dashboard/(\\d+)/validar$").matcher(path);
        if (ex.getRequestMethod().equals("PUT") && val.matches()) {
            int id = Integer.parseInt(val.group(1));
            peticionesDashboard.add("validar:" + id);
            estadoValidacionDashboard.put(id, "VALIDADO");
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"actualizado\":true},\"errors\":null}");
            return;
        }
        java.util.regex.Matcher rec = java.util.regex.Pattern.compile("^/api/dashboard/(\\d+)/rechazar$").matcher(path);
        if (ex.getRequestMethod().equals("PUT") && rec.matches()) {
            int id = Integer.parseInt(rec.group(1));
            peticionesDashboard.add("rechazar:" + id);
            estadoValidacionDashboard.put(id, "RECHAZADO");
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"actualizado\":true},\"errors\":null}");
            return;
        }
        java.util.regex.Matcher del = java.util.regex.Pattern.compile("^/api/dashboard/(\\d+)$").matcher(path);
        if (ex.getRequestMethod().equals("DELETE") && del.matches()) {
            int id = Integer.parseInt(del.group(1));
            peticionesDashboard.add("eliminar:" + id);
            eliminadosDashboard.add(id);
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"eliminado\":true},\"errors\":null}");
            return;
        }
        // Fase 4b': sin WHERE en la API real → afecta TODA la tabla registros_control, no solo lo filtrado en pantalla.
        if (ex.getRequestMethod().equals("PUT") && path.equals("/api/dashboard/validar-todo")) {
            peticionesDashboard.add("validarTodo");
            estadoGlobalDashboard = "VALIDADO";
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{},\"errors\":null}");
            return;
        }
        if (ex.getRequestMethod().equals("PUT") && path.equals("/api/dashboard/rechazar-todo")) {
            peticionesDashboard.add("rechazarTodo");
            estadoGlobalDashboard = "RECHAZADO";
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{},\"errors\":null}");
            return;
        }
        responder(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
    }

    private final java.util.List<String> peticionesProduccion = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    /** Fase 4b: mismo criterio que Dashboard, para Inspecciones Producción. */
    private final Map<Integer, String> estadoValidacionProduccion = new ConcurrentHashMap<>();
    private final java.util.Set<Integer> eliminadosProduccion = ConcurrentHashMap.newKeySet();
    /** Fase 4b': a diferencia de Dashboard, el UPDATE sí filtra area='PRODUCCION' en la API; el fake no simula otra
     * área en sus datos de prueba, así que el efecto observable es el mismo (afecta todo lo generado). */
    private volatile String estadoGlobalProduccion;

    /** "filtros", "resumen?<query cruda>" o "validar:<id>"/"rechazar:<id>"/"eliminar:<id>"/"validarTodo"/"rechazarTodo" en api/registros-produccion/*. */
    public java.util.List<String> peticionesProduccion() {
        return java.util.List.copyOf(peticionesProduccion);
    }

    /** Fase 4b: estado_validacion vigente de un registro de Producción. */
    public String estadoValidacionProduccion(int id) {
        return estadoValidacionProduccion.containsKey(id) ? estadoValidacionProduccion.get(id) : estadoGlobalProduccion;
    }

    /**
     * Misma forma que Dashboard (en la API real ambos módulos comparten ResumenOperacionalService,
     * solo cambia el área) con un marcador "area" para que los tests distingan la ruta atendida.
     */
    public static String dataProduccionResumen(int sub, String query) {
        String base = dataDashboardResumen(sub, query);
        return base.substring(0, base.length() - 1) + ",\"area\":\"PRODUCCION\"}";
    }

    private void registrosProduccionLectura(HttpExchange ex, boolean filtros) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String query = ex.getRequestURI().getRawQuery();
        peticionesProduccion.add(filtros ? "filtros" + (query == null ? "" : "?" + query) : "resumen?" + query);
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Rango de fechas inválido"));
            return;
        }
        String data = filtros ? dataDashboardFiltros() : dataProduccionResumen(sub, query);
        if (!filtros) {
            data = aplicarEstados(data, "ultimosRegistros", estadoValidacionProduccion, eliminadosProduccion, estadoGlobalProduccion);
        }
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + data + ",\"errors\":null}");
    }

    /**
     * Fase 4b: PUT api/registros-produccion/{id}/validar|rechazar y DELETE api/registros-produccion/{id},
     * igual que RegistrosProduccionRepository.cs real (mismas 3 sentencias que Dashboard, filtradas a
     * area='PRODUCCION' en la API; el fake no simula el filtro de área porque no hay otra área en los
     * datos de prueba).
     */
    private void registrosProduccionEscritura(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        java.util.regex.Matcher val = java.util.regex.Pattern.compile("^/api/registros-produccion/(\\d+)/validar$").matcher(path);
        if (ex.getRequestMethod().equals("PUT") && val.matches()) {
            int id = Integer.parseInt(val.group(1));
            peticionesProduccion.add("validar:" + id);
            estadoValidacionProduccion.put(id, "VALIDADO");
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"actualizado\":true},\"errors\":null}");
            return;
        }
        java.util.regex.Matcher rec = java.util.regex.Pattern.compile("^/api/registros-produccion/(\\d+)/rechazar$").matcher(path);
        if (ex.getRequestMethod().equals("PUT") && rec.matches()) {
            int id = Integer.parseInt(rec.group(1));
            peticionesProduccion.add("rechazar:" + id);
            estadoValidacionProduccion.put(id, "RECHAZADO");
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"actualizado\":true},\"errors\":null}");
            return;
        }
        java.util.regex.Matcher del = java.util.regex.Pattern.compile("^/api/registros-produccion/(\\d+)$").matcher(path);
        if (ex.getRequestMethod().equals("DELETE") && del.matches()) {
            int id = Integer.parseInt(del.group(1));
            peticionesProduccion.add("eliminar:" + id);
            eliminadosProduccion.add(id);
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"eliminado\":true},\"errors\":null}");
            return;
        }
        if (ex.getRequestMethod().equals("PUT") && path.equals("/api/registros-produccion/validar-todo")) {
            peticionesProduccion.add("validarTodo");
            estadoGlobalProduccion = "VALIDADO";
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{},\"errors\":null}");
            return;
        }
        if (ex.getRequestMethod().equals("PUT") && path.equals("/api/registros-produccion/rechazar-todo")) {
            peticionesProduccion.add("rechazarTodo");
            estadoGlobalProduccion = "RECHAZADO";
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{},\"errors\":null}");
            return;
        }
        responder(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
    }

    private final java.util.List<String> peticionesControl = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    /** Fase 4b: mismo criterio que Dashboard, para Registros de Control. */
    private final Map<Integer, String> estadoValidacionControl = new ConcurrentHashMap<>();
    private final java.util.Set<Integer> eliminadosControl = ConcurrentHashMap.newKeySet();

    /** "<método> <ruta>?<query cruda>" recibidos en api/registros-control* (para verificar el mapeo exacto). */
    public java.util.List<String> peticionesControl() {
        return java.util.List.copyOf(peticionesControl);
    }

    /** Fase 4b: estado_validacion vigente de un registro de Registros de Control. */
    public String estadoValidacionControl(int id) {
        return estadoValidacionControl.get(id);
    }

    /** Fase 4b: simula que otra persona (Photino u otra sesión) ya validó/rechazó este registro. */
    public void estadoValidacionControlPorOtro(int id, String estado) {
        estadoValidacionControl.put(id, estado);
    }

    /**
     * Forma real de la respuesta paginada de GET api/registros-control: { items, total, page, pages }.
     * Con np=VACIO devuelve 0 items; con limit=999999 devuelve todo (300 items en una página); con id=N
     * (Fase 4b: releer un registro antes de validar/rechazar) devuelve solo ESE item, como el filtro
     * "rc.id = @id" de la API real; si no, 2 items de un total de 45 y "page" = el recibido.
     */
    public static String dataRegistrosControl(int sub, String query) {
        if (query != null) {
            java.util.regex.Matcher mid = java.util.regex.Pattern.compile("(?:^|&)id=(\\d+)").matcher(query);
            if (mid.find()) {
                int id = Integer.parseInt(mid.group(1));
                String fila = filaRegistroControl(id, 0);
                return "{\"items\":[" + fila + "],\"total\":1,\"page\":1,\"pages\":1,\"usuarioDelToken\":" + sub + "}";
            }
        }
        boolean vacio = query != null && query.contains("np=VACIO");
        boolean todo = query != null && query.contains("limit=999999");
        int items = vacio ? 0 : (todo ? 300 : 2);
        int total = vacio ? 0 : (todo ? 300 : 45);
        int page = 1;
        if (query != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|&)page=(\\d+)").matcher(query);
            if (m.find()) {
                page = Integer.parseInt(m.group(1));
            }
        }
        int pages = vacio ? 1 : (todo ? 1 : 3);
        StringBuilder lista = new StringBuilder();
        for (int i = 0; i < items; i++) {
            lista.append(i > 0 ? "," : "").append(filaRegistroControl(7000 + i, i));
        }
        return "{\"items\":[" + lista + "],\"total\":" + total + ",\"page\":" + page + ",\"pages\":" + pages
                + ",\"usuarioDelToken\":" + sub + "}";
    }

    private static String filaRegistroControl(int id, int i) {
        return "{\"id\":" + id + ",\"fechaRegistro\":\"24-09-2026\",\"horaRegistro\":\"08:" + String.format("%02d", i % 60)
                + "\",\"usuario\":\"María José Peña\",\"proceso\":\"Pegado\",\"parametro\":\"Adhesivo\",\"maquina\":\"Pegadora 3\","
                + "\"np\":\"41" + String.format("%02d", i % 100) + "\",\"producto\":\"Estuche cartón ñandú «E2E»\","
                + "\"turno\":\"A\",\"valor\":\"12.5\",\"unidad\":\"g\",\"estado\":\"Conforme\",\"estadoValidacion\":\"Pendiente\","
                + "\"observacion\":\"=SUMA(1;2) sin fórmula\",\"imagenUrl\":\"\"}";
    }

    /**
     * GET api/registros-control (lista/filtra, incluido ?id=) + Fase 4b: PUT {id}/validar|rechazar y
     * DELETE {id}, igual que RegistrosControlRepository.cs real (mismas sentencias que Dashboard, sin
     * filtro de área).
     */
    private void registrosControl(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        java.util.regex.Matcher val = java.util.regex.Pattern.compile("^/api/registros-control/(\\d+)/validar$").matcher(path);
        if (ex.getRequestMethod().equals("PUT") && val.matches()) {
            int id = Integer.parseInt(val.group(1));
            peticionesControl.add("PUT " + path);
            estadoValidacionControl.put(id, "VALIDADO");
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"actualizado\":true},\"errors\":null}");
            return;
        }
        java.util.regex.Matcher rec = java.util.regex.Pattern.compile("^/api/registros-control/(\\d+)/rechazar$").matcher(path);
        if (ex.getRequestMethod().equals("PUT") && rec.matches()) {
            int id = Integer.parseInt(rec.group(1));
            peticionesControl.add("PUT " + path);
            estadoValidacionControl.put(id, "RECHAZADO");
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"actualizado\":true},\"errors\":null}");
            return;
        }
        java.util.regex.Matcher del = java.util.regex.Pattern.compile("^/api/registros-control/(\\d+)$").matcher(path);
        if (ex.getRequestMethod().equals("DELETE") && del.matches()) {
            int id = Integer.parseInt(del.group(1));
            peticionesControl.add("DELETE " + path);
            eliminadosControl.add(id);
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"eliminado\":true},\"errors\":null}");
            return;
        }
        peticionesControl.add(ex.getRequestMethod() + " " + path + "?" + query);
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Rango de fechas inválido"));
            return;
        }
        String data = aplicarEstados(dataRegistrosControl(sub, query), "items", estadoValidacionControl, eliminadosControl, null);
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + data + ",\"errors\":null}");
    }

    private final java.util.List<String> peticionesProductoTerminado = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** "GET <ruta>?<query cruda>" recibidos en api/producto-terminado* (para verificar el mapeo exacto). */
    public java.util.List<String> peticionesProductoTerminado() {
        return java.util.List.copyOf(peticionesProductoTerminado);
    }

    public static String dataProductoTerminadoFiltros(String empresa) {
        return "{\"maquinas\":[\"Termoformadora 1\",\"Pegadora 3\"],\"inspectores\":[{\"id\":10,\"nombre\":\"Operador Uno\"},"
                + "{\"id\":20,\"nombre\":\"María José Peña\"}],\"defectos\":[{\"id\":1,\"nombre\":\"Rebaba\"}],"
                + "\"origenes\":[{\"id\":3,\"nombre\":\"Máquina\"}],\"empresaConsultada\":\"" + empresa + "\"}";
    }

    public static String dataProductoTerminadoResumen(int sub, String empresa) {
        return "{\"totalInspecciones\":45,\"conformes\":40,\"noConformes\":5,\"porcentajeConformidad\":88.9,"
                + "\"pareto\":[{\"defecto\":\"Rebaba\",\"cantidad\":3}],\"origenes\":[{\"origen\":\"Máquina\",\"cantidad\":2}],"
                + "\"tendencia\":[{\"fecha\":\"2026-09-23\",\"inspecciones\":20,\"noConformes\":2}],"
                + "\"comparacion\":[{\"proceso\":\"Pegado\",\"inspecciones\":25,\"noConformes\":3}],"
                + "\"empresaConsultada\":\"" + empresa + "\",\"usuarioDelToken\":" + sub + "}";
    }

    /** Filas de la lista/exportación (22 campos, los que arma construirTablaExportTemp en Photino). */
    private static String filaProductoTerminado(int i) {
        return "{\"inspeccionId\":" + (9000 + i) + ",\"fecha\":\"24-09-2026\",\"inspector\":\"María José Peña\",\"np\":\"41" + String.format("%02d", i % 100)
                + "\",\"cliente\":\"Cliente Ñandú «E2E»\",\"codigoProducto\":\"CP-" + i + "\",\"descripcionProducto\":\"Estuche cartón =SUMA(1;2)\","
                + "\"proceso\":\"Pegado\",\"cantidadLote\":\"1.500\",\"maquina\":\"Pegadora 3\",\"nivelInspeccion\":\"II\",\"aql\":\"1,5\","
                + "\"letraCodigo\":\"K\",\"tamanoMuestra\":125,\"ac\":5,\"re\":6,\"unidadesNoConformes\":" + (i % 3) + ",\"defectosTotales\":" + (i % 4)
                + ",\"resultado\":\"" + (i % 3 == 0 ? "NO CONFORME" : "CONFORME") + "\",\"hallazgoCorrelativo\":" + (i % 2 == 0 ? "null" : "1")
                + ",\"defecto\":\"" + (i % 2 == 0 ? "" : "Rebaba") + "\",\"origen\":\"" + (i % 2 == 0 ? "" : "Máquina") + "\"}";
    }

    /** Lista paginada { items, total, page, limit }: np=VACIO → 0; np=GRANDE → 500 en una página; si no 2 de 45. */
    public static String dataProductoTerminadoList(int sub, String query) {
        boolean vacio = query.contains("np=VACIO");
        boolean grande = query.contains("np=GRANDE");
        int n = vacio ? 0 : (grande ? 500 : 2);
        int page = 1;
        int limit = 50;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|&)page=(\\d+)").matcher(query);
        if (m.find()) {
            page = Integer.parseInt(m.group(1));
        }
        m = java.util.regex.Pattern.compile("(?:^|&)limit=(\\d+)").matcher(query);
        if (m.find()) {
            limit = Integer.parseInt(m.group(1));
        }
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < n; i++) {
            items.append(i > 0 ? "," : "").append(filaProductoTerminado(i));
        }
        return "{\"items\":[" + items + "],\"total\":" + (vacio ? 0 : (grande ? 500 : 45)) + ",\"page\":" + page + ",\"limit\":" + limit
                + ",\"usuarioDelToken\":" + sub + "}";
    }

    /** exportar-detalle devuelve un array plano de filas (una por inspección/hallazgo). */
    public static String dataProductoTerminadoExport(String query) {
        int n = query.contains("np=VACIO") ? 0 : (query.contains("np=GRANDE") ? 500 : 3);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            sb.append(i > 0 ? "," : "").append(filaProductoTerminado(i));
        }
        return sb.append("]").toString();
    }

    public static String dataProductoTerminadoDetalle(int id, String empresa, int sub) {
        return "{\"id\":" + id + ",\"fechaRegistro\":\"24-09-2026\",\"horaRegistro\":\"10:15\",\"inspector\":\"María José Peña\","
                + "\"np\":\"4101\",\"resultado\":\"CONFORME\",\"hallazgos\":[{\"correlativo\":1,\"defecto\":\"Rebaba\",\"origen\":\"Máquina\",\"cantidad\":2}],"
                + "\"empresaConsultada\":\"" + empresa + "\",\"usuarioDelToken\":" + sub + "}";
    }

    private void productoTerminado(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        String query = String.valueOf(ex.getRequestURI().getRawQuery());
        peticionesProductoTerminado.add(ex.getRequestMethod() + " " + path + "?" + query);
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            responder(ex, 200, "{\"success\":true,\"message\":\"NO DEBERIA LLEGAR\",\"data\":null,\"errors\":null}");
            return;
        }
        // Misma validación que ProductoTerminadoController: empresa obligatoria INNPACK|FARET.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|&)empresa=([^&]*)").matcher(query);
        String empresa = m.find() ? m.group(1) : "";
        if (!empresa.equals("INNPACK") && !empresa.equals("FARET")) {
            responder(ex, 400, fallo("Falta indicar la empresa (INNPACK o FARET)"));
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Rango de fechas inválido"));
            return;
        }
        String data;
        if (path.equals("/api/producto-terminado/filtros")) {
            data = dataProductoTerminadoFiltros(empresa);
        } else if (path.equals("/api/producto-terminado/resumen")) {
            data = dataProductoTerminadoResumen(sub, empresa);
        } else if (path.equals("/api/producto-terminado/exportar-detalle")) {
            data = dataProductoTerminadoExport(query);
        } else if (path.equals("/api/producto-terminado")) {
            data = dataProductoTerminadoList(sub, query);
        } else {
            int id = Integer.parseInt(path.substring("/api/producto-terminado/".length()));
            if (id == 404) {
                responder(ex, 404, fallo("Inspección no encontrada"));
                return;
            }
            data = dataProductoTerminadoDetalle(id, empresa, sub);
        }
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + data + ",\"errors\":null}");
    }

    private final java.util.List<String> peticionesCertificados = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** "GET <ruta>[?<query cruda>]" recibidos en api/certificados-liberacion* (para verificar el mapeo). */
    public java.util.List<String> peticionesCertificados() {
        return java.util.List.copyOf(peticionesCertificados);
    }

    /** Búsqueda: lista de CertificadoLiberacionDto (np=VACIO → []). */
    public static String dataCertificados(String query) {
        if (query != null && query.contains("np=VACIO")) {
            return "[]";
        }
        return "[{\"folio\":123456,\"empresa\":\"INNPACK SPA\",\"np\":\"4101\",\"cliente\":\"Cliente Ñandú «E2E»\",\"item\":\"IT-1\","
                + "\"codigoArticulo\":\"CP-1\",\"descripcionArticulo\":\"Estuche cartón =SUMA(1;2)\",\"cantidadBase\":1500,\"cantidadLiberacion\":1480,"
                + "\"operador\":\"Operador Uno\",\"inspector\":\"María José Peña\",\"fechaLiberacion\":\"2026-09-24T10:15:00\",\"bodegaDestino\":\"PT-01\"},"
                + "{\"folio\":123457,\"empresa\":\"FARET SPA\",\"np\":\"4102\",\"cliente\":\"Otro Cliente\",\"item\":\"IT-2\",\"codigoArticulo\":\"CP-2\","
                + "\"descripcionArticulo\":\"Caja\",\"cantidadBase\":100,\"cantidadLiberacion\":100,\"operador\":\"Operador Dos\",\"inspector\":\"Inspector X\","
                + "\"fechaLiberacion\":\"2026-09-23T08:00:00\",\"bodegaDestino\":\"PT-02\"}]";
    }

    /** PDF mínimo válido (xref con offsets correctos) para que abra en cualquier visor. */
    public static byte[] pdfMinimo(long folio) {
        String header = "%PDF-1.4\n";
        String o1 = "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n";
        String o2 = "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n";
        String o3 = "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R>>endobj\n";
        String texto = "BT /F1 12 Tf 20 100 Td (Certificado " + folio + ") Tj ET";
        String o4 = "4 0 obj<</Length " + texto.length() + ">>stream\n" + texto + "\nendstream\nendobj\n";
        int off1 = header.length();
        int off2 = off1 + o1.length();
        int off3 = off2 + o2.length();
        int off4 = off3 + o3.length();
        int xref = off4 + o4.length();
        String tabla = "xref\n0 5\n0000000000 65535 f \n" + String.format("%010d 00000 n \n%010d 00000 n \n%010d 00000 n \n%010d 00000 n \n", off1, off2, off3, off4)
                + "trailer<</Size 5/Root 1 0 R>>\nstartxref\n" + xref + "\n%%EOF\n";
        return (header + o1 + o2 + o3 + o4 + tabla).getBytes(StandardCharsets.ISO_8859_1);
    }

    private void certificadosLiberacion(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        peticionesCertificados.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 502, fallo("No fue posible consultar los certificados de liberación"));
            return;
        }
        if (path.equals("/api/certificados-liberacion")) {
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + dataCertificados(query) + ",\"errors\":null}");
            return;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^/api/certificados-liberacion/(\\d+)/(calidad-pdf|pdf)$").matcher(path);
        if (!m.matches()) {
            responder(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
            return;
        }
        long folio = Long.parseLong(m.group(1));
        String base64;
        if (folio == 404) {
            responder(ex, 404, fallo("No se encontraron datos para el certificado N° 404"));
            return;
        } else if (folio == 500) {
            base64 = Base64.getEncoder().encodeToString("<html>no soy un pdf</html>".getBytes(StandardCharsets.UTF_8));
        } else if (folio == 600) {
            base64 = "";
        } else if (folio == 700) {
            // Excede el máximo del gateway (15 MB): solo el largo importa, no se decodifica.
            base64 = "A".repeat(((15 * 1024 * 1024 + 2) / 3) * 4 + 4);
        } else if (folio == 800) {
            base64 = "%%%no-base64%%%";
        } else {
            base64 = Base64.getEncoder().encodeToString(pdfMinimo(folio));
        }
        String fileName = folio == 900 ? "..\\..\\evil<>:\"|?*.exe" : "CertificadoCalidad_" + folio + ".pdf";
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"folio\":" + folio + ",\"fileName\":\"" + fileName.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"base64\":\"" + base64 + "\",\"usuarioDelToken\":" + sub + "},\"errors\":null}");
    }

    private final java.util.List<String> peticionesControlDocumental = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** "<método> <ruta>[?<query cruda>]" recibidos en api/control-documental* (para verificar el mapeo). */
    public java.util.List<String> peticionesControlDocumental() {
        return java.util.List.copyOf(peticionesControlDocumental);
    }

    private static String documento(int i) {
        return "{\"id\":" + (300 + i) + ",\"codigo\":\"PR-CAL-" + String.format("%03d", i) + "\",\"titulo\":\"Procedimiento ñandú «" + i + "»\","
                + "\"tipoDocumento\":\"Procedimiento\",\"area\":\"Calidad\",\"estado\":\"VIGENTE\",\"alcanceEmpresa\":\"" + (i % 3 == 0 ? "AMBAS" : (i % 3 == 1 ? "INNPACK" : "FARET"))
                + "\",\"versionVigente\":\"1." + (i % 5) + "\",\"versionVigenteId\":" + (1000 + i) + ",\"tieneAdjuntoVigente\":" + (i % 2 == 0)
                + ",\"fechaVigencia\":\"2026-09-24\",\"actualizadoPor\":\"María José Peña\"}";
    }

    /** Lista paginada { items, total, page, pageSize, pages }: texto=VACIO → 0; texto=GRANDE → 300; si no 3 de 42. */
    public static String dataControlDocumentalList(int sub, String query) {
        boolean vacio = query.contains("texto=VACIO");
        boolean grande = query.contains("texto=GRANDE");
        int n = vacio ? 0 : (grande ? 300 : 3);
        int page = 1;
        int pageSize = 50;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|&)page=(\\d+)").matcher(query);
        if (m.find()) {
            page = Integer.parseInt(m.group(1));
        }
        m = java.util.regex.Pattern.compile("(?:^|&)pageSize=(\\d+)").matcher(query);
        if (m.find()) {
            pageSize = Integer.parseInt(m.group(1));
        }
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < n; i++) {
            items.append(i > 0 ? "," : "").append(documento(i));
        }
        int total = vacio ? 0 : (grande ? 300 : 42);
        return "{\"items\":[" + items + "],\"total\":" + total + ",\"page\":" + page + ",\"pageSize\":" + pageSize
                + ",\"pages\":" + Math.max(1, (total + pageSize - 1) / pageSize) + ",\"usuarioDelToken\":" + sub + "}";
    }

    public static String dataControlDocumentalGet(int id, int sub) {
        return "{\"id\":" + id + ",\"codigo\":\"PR-CAL-001\",\"titulo\":\"Procedimiento ñandú «1»\",\"descripcion\":\"=SUMA(1;2) sin fórmula\","
                + "\"tipoDocumento\":\"Procedimiento\",\"area\":\"Calidad\",\"estado\":\"VIGENTE\",\"alcanceEmpresa\":\"AMBAS\",\"versiones\":[{\"id\":1001,"
                + "\"version\":\"1.1\",\"vigente\":true,\"tieneAdjunto\":true,\"nombreArchivo\":\"PR-CAL-001_v1.1.pdf\"}],\"usuarioDelToken\":" + sub + "}";
    }

    /** PNG de 1x1 válido (firma + IHDR). */
    public static byte[] pngMinimo() {
        return Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
    }

    /** Adjunto por versionId: 1 PDF, 2 PNG, 3 DOCX (zip), 4 HTML declarado como PDF, 5 texto plano; errores 404/600/700/800/900. */
    private void controlDocumental(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        peticionesControlDocumental.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            responder(ex, 200, "{\"success\":true,\"message\":\"NO DEBERIA LLEGAR\",\"data\":null,\"errors\":null}");
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Filtro de fecha inválido"));
            return;
        }
        if (path.equals("/api/control-documental")) {
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + dataControlDocumentalList(sub, String.valueOf(query)) + ",\"errors\":null}");
            return;
        }
        java.util.regex.Matcher adj = java.util.regex.Pattern.compile("^/api/control-documental/adjunto/(\\d+)$").matcher(path);
        if (adj.matches()) {
            int versionId = Integer.parseInt(adj.group(1));
            String nombre = "adjunto_" + versionId;
            String mime = "application/octet-stream";
            String base64 = "";
            switch (versionId) {
                case 404 -> { responder(ex, 404, fallo("Esta versión no tiene ningún archivo adjunto")); return; }
                case 1 -> { nombre = "PR-CAL-001_v1.1.pdf"; mime = "application/pdf"; base64 = Base64.getEncoder().encodeToString(pdfMinimo(1)); }
                case 2 -> { nombre = "diagrama.png"; mime = "image/png"; base64 = Base64.getEncoder().encodeToString(pngMinimo()); }
                case 3 -> { nombre = "instructivo ñ.docx"; mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    base64 = Base64.getEncoder().encodeToString(new byte[] {0x50, 0x4b, 0x03, 0x04, 0x14, 0, 0, 0, 1, 2, 3}); }
                case 4 -> { nombre = "falso.pdf"; mime = "application/pdf"; base64 = Base64.getEncoder().encodeToString("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)); }
                case 5 -> { nombre = "notas.txt"; mime = "text/plain"; base64 = Base64.getEncoder().encodeToString("hola ñandú".getBytes(StandardCharsets.UTF_8)); }
                case 600 -> { nombre = "vacio.pdf"; mime = "application/pdf"; }
                case 700 -> { nombre = "grande.pdf"; mime = "application/pdf"; base64 = "A".repeat(((25 * 1024 * 1024 + 2) / 3) * 4 + 4); }
                case 800 -> { nombre = "roto.pdf"; mime = "application/pdf"; base64 = "%%%no-base64%%%"; }
                case 900 -> { nombre = "..\\..\\evil<>:\"|?*.exe"; mime = "text/html"; base64 = Base64.getEncoder().encodeToString("<b>x</b>".getBytes(StandardCharsets.UTF_8)); }
                default -> { nombre = "PR-CAL-001_v1.0.pdf"; mime = "application/pdf"; base64 = Base64.getEncoder().encodeToString(pdfMinimo(versionId)); }
            }
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"nombreArchivo\":\"" + nombre.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\",\"tipoMime\":\"" + mime + "\",\"contenidoBase64\":\"" + base64 + "\",\"usuarioDelToken\":" + sub + "},\"errors\":null}");
            return;
        }
        java.util.regex.Matcher doc = java.util.regex.Pattern.compile("^/api/control-documental/(\\d+)$").matcher(path);
        if (doc.matches()) {
            int id = Integer.parseInt(doc.group(1));
            if (id == 404) {
                responder(ex, 404, fallo("Documento no encontrado"));
                return;
            }
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + dataControlDocumentalGet(id, sub) + ",\"errors\":null}");
            return;
        }
        responder(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
    }

    private final java.util.List<String> peticionesNoConformidades = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Cuerpo EXACTO recibido en POST api/no-conformidades/{id}/seguimiento y el "sub" del JWT usado. */
    public record SeguimientoRecibido(int ncId, int sub, String cuerpo, String contentType) {}

    private final java.util.List<SeguimientoRecibido> seguimientosRecibidos = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public java.util.List<SeguimientoRecibido> seguimientosRecibidos() {
        return java.util.List.copyOf(seguimientosRecibidos);
    }

    private final java.util.List<SeguimientoRecibido> accionesRecibidas = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.List<SeguimientoRecibido> analisisRecibidos = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    /** Análisis vigente por NC (estado de la API simulada). NC 503 empieza SIN análisis. */
    private final Map<Integer, JsonNode> analisisPorNc = new ConcurrentHashMap<>();

    /** Cuerpo EXACTO recibido en PUT api/no-conformidades/{id}/analisis. */
    public java.util.List<SeguimientoRecibido> analisisRecibidos() {
        return java.util.List.copyOf(analisisRecibidos);
    }

    /** Análisis vigente simulado (null = la NC no tiene análisis). */
    public JsonNode analisisDe(int nc) {
        if (nc == 503 && !analisisPorNc.containsKey(nc)) {
            return null;
        }
        return analisisPorNc.computeIfAbsent(nc, k -> mapper.readTree("{\"id\":7,\"metodologia\":\"CINCO_PORQUES\",\"problemaDetectado\":\"Registro corrido\","
                + "\"porque1\":\"Tinta\",\"porque2\":\"\",\"porque3\":null,\"porque4\":null,\"porque5\":null,\"causaRaiz\":\"Rodillo gastado\","
                + "\"conclusion\":null,\"creadoPor\":\"María\",\"creadoEn\":\"2026-09-20T10:00:00\",\"actualizadoPor\":null,\"actualizadoEn\":null}"));
    }

    /** Cuerpo EXACTO recibido en POST api/nc-catalogos/{catalogo}. */
    public record CatalogoRecibido(String catalogo, int sub, String cuerpo, String contentType) {}

    /** Cuerpos EXACTOS recibidos en POST api/no-conformidades (alta de NC). */
    private final java.util.List<SeguimientoRecibido> ncCreadasRecibidas = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public java.util.List<SeguimientoRecibido> ncCreadasRecibidas() {
        return java.util.List.copyOf(ncCreadasRecibidas);
    }

    /** Cuerpos EXACTOS recibidos en PUT api/no-conformidades/{id} (editar NC) y versión simulada por NC. */
    private final java.util.List<SeguimientoRecibido> ncActualizadasRecibidas = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final Map<Integer, Integer> versionNc = new ConcurrentHashMap<>();

    public java.util.List<SeguimientoRecibido> ncActualizadasRecibidas() {
        return java.util.List.copyOf(ncActualizadasRecibidas);
    }

    /** Cuerpos EXACTOS de PATCH api/no-conformidades/{id}/gestion y POST .../cerrar; NC cerradas por la simulación. */
    private final java.util.List<SeguimientoRecibido> gestionesRecibidas = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.List<SeguimientoRecibido> cierresRecibidos = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.Set<Integer> ncCerradas = ConcurrentHashMap.newKeySet();

    public java.util.List<SeguimientoRecibido> gestionesRecibidas() {
        return java.util.List.copyOf(gestionesRecibidas);
    }

    public java.util.List<SeguimientoRecibido> cierresRecibidos() {
        return java.util.List.copyOf(cierresRecibidos);
    }

    /** Cuerpos EXACTOS de PUT api/no-conformidades/acciones/{accionId}; estado y versión simulados por acción. */
    private final java.util.List<SeguimientoRecibido> accionesActualizadas = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final Map<Integer, String> estadoAccion = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> versionAccion = new ConcurrentHashMap<>();

    /** ncId de cada cuerpo = accionId de la URL. */
    public java.util.List<SeguimientoRecibido> accionesActualizadas() {
        return java.util.List.copyOf(accionesActualizadas);
    }

    /** Simula que otra persona modificó la acción: cambia lo que devuelve GET api/no-conformidades/{id}/acciones. */
    public void modificarAccionPorOtro(int accionId) {
        versionAccion.merge(accionId, 1, Integer::sum);
    }

    /** Simula que otra persona (Photino u otra sesión) modificó la NC: cambia lo que devuelve GET api/no-conformidades/{id}. */
    public void modificarNcPorOtro(int id) {
        versionNc.merge(id, 1, Integer::sum);
    }

    /** Cuerpos EXACTOS recibidos en POST api/no-conformidades/{id}/adjuntos (ncId = id de la URL). */
    private final java.util.List<SeguimientoRecibido> adjuntosRecibidos = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public java.util.List<SeguimientoRecibido> adjuntosRecibidos() {
        return java.util.List.copyOf(adjuntosRecibidos);
    }

    private final java.util.List<CatalogoRecibido> catalogosRecibidos = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    /** Valores creados por catálogo (estado simulado): {id, nombre, activo, creadoPor}. id 1 = semilla fija. */
    private final Map<String, java.util.List<tools.jackson.databind.node.ObjectNode>> creadosPorCatalogo = new ConcurrentHashMap<>();

    public java.util.List<CatalogoRecibido> catalogosRecibidos() {
        return java.util.List.copyOf(catalogosRecibidos);
    }

    /** Valores del catálogo simulado (incluye inactivos); "Cliente Inactivo" (id 2) arranca desactivado. */
    public synchronized java.util.List<tools.jackson.databind.node.ObjectNode> valoresCatalogo(String catalogo) {
        return creadosPorCatalogo.computeIfAbsent(catalogo, k -> {
            java.util.List<tools.jackson.databind.node.ObjectNode> l = new java.util.ArrayList<>();
            tools.jackson.databind.node.ObjectNode inactivo = mapper.createObjectNode();
            inactivo.put("id", 2).put("nombre", "Cliente Inactivo").put("activo", false).put("creadoPor", "Semilla");
            l.add(inactivo);
            return l;
        });
    }

    /** Largo máximo por catálogo del diccionario de NoConformidadesCatalogosService (API real, columna `nombre`). */
    private static final Map<String, Integer> LARGO_CATALOGO_API = Map.of(
            "clientes", 150, "categoriasDefecto", 150, "tiposFalla", 150, "supervisores", 150, "revisores", 150,
            "areas", 150, "familiasProducto", 50, "niveles", 20, "impactos", 50);

    /** Como NoConformidadesCatalogosService.CrearAsync + repositorio (UNIQUE(nombre) con collation CI). */
    private synchronized void crearCatalogo(HttpExchange ex, String catalogo, int sub) throws IOException {
        String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        catalogosRecibidos.add(new CatalogoRecibido(catalogo, sub, cuerpo, ex.getRequestHeaders().getFirst("Content-Type")));
        JsonNode b = mapper.readTree(cuerpo);
        String raw = b.path("nombre").isString() ? b.get("nombre").asString() : null;
        if (raw == null || raw.isBlank()) {
            responder(ex, 400, fallo("Falta el nombre"));
            return;
        }
        String nombre = raw.strip().replaceAll("\\s+", " ");
        int max = LARGO_CATALOGO_API.getOrDefault(catalogo, 150);
        if (nombre.length() > max) {
            responder(ex, 400, fallo("El valor no puede superar los " + max + " caracteres."));
            return;
        }
        if (nombre.equals("ERROR_API")) {
            responder(ex, 400, fallo("No se pudo crear el valor"));
            return;
        }
        if (nombre.equals("ERROR_500")) {
            responder(ex, 500, "{\"title\":\"error interno\"}");
            return;
        }
        java.util.List<tools.jackson.databind.node.ObjectNode> valores = valoresCatalogo(catalogo);
        tools.jackson.databind.node.ObjectNode item = null;
        for (tools.jackson.databind.node.ObjectNode v : valores) {
            if (v.get("nombre").asString().equalsIgnoreCase(nombre)) {
                item = v;
                v.put("activo", true);
            }
        }
        if (item == null && (catalogo + " ñ").equalsIgnoreCase(nombre)) {
            item = mapper.createObjectNode().put("id", 1).put("nombre", catalogo + " ñ").put("activo", true);
        }
        if (item == null) {
            item = mapper.createObjectNode();
            item.put("id", 900 + valores.size()).put("nombre", nombre).put("activo", true);
            item.set("creadoPor", b.get("creadoPor"));
            valores.add(item);
        }
        tools.jackson.databind.node.ObjectNode data = mapper.createObjectNode();
        data.put("id", item.get("id").asInt()).put("nombre", item.get("nombre").asString()).put("activo", true);
        responder(ex, 200, "{\"success\":true,\"message\":\"Creado correctamente\",\"data\":" + data + ",\"errors\":null}");
    }

    /** Cuerpo EXACTO recibido en POST api/no-conformidades/{id}/acciones. */
    public java.util.List<SeguimientoRecibido> accionesRecibidas() {
        return java.util.List.copyOf(accionesRecibidas);
    }

    /** "<método> <ruta>[?<query cruda>]" recibidos en api/no-conformidades* y api/nc-catalogos*. */
    public java.util.List<String> peticionesNoConformidades() {
        return java.util.List.copyOf(peticionesNoConformidades);
    }

    private static String noConformidad(int i) {
        return "{\"id\":" + (500 + i) + ",\"codigo\":\"NC-" + String.format("%04d", i) + "\",\"cliente\":\"Viña Ñandú «" + i + "»\","
                + "\"tipoPnc\":\"" + (i % 2 == 0 ? "Cuarentena" : "Reclamo") + "\",\"nivel\":\"" + (i % 3 == 0 ? "Crítico" : "Menor")
                + "\",\"estadoGestion\":\"" + (i % 2 == 0 ? "ASIGNADA" : "CERRADA") + "\",\"area\":\"Impresión\",\"fechaIngreso\":\"2026-09-2" + (i % 10)
                + "\",\"familiaProducto\":\"Cajas\",\"categoriaDefecto\":\"=SUMA(1;2)\",\"cantRechazada\":" + (10 * i) + ",\"maquina\":\"Bobst 1\"}";
    }

    /** Lista paginada { items, total, page, pageSize, pages }: cliente=VACIO → 0; cliente=GRANDE → 300; si no 3 de 42. */
    public static String dataNoConformidadesList(int sub, String query) {
        boolean vacio = query.contains("cliente=VACIO");
        boolean grande = query.contains("cliente=GRANDE");
        int n = vacio ? 0 : (grande ? 300 : 3);
        int page = 1;
        int pageSize = 50;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|&)page=(\\d+)").matcher(query);
        if (m.find()) {
            page = Integer.parseInt(m.group(1));
        }
        m = java.util.regex.Pattern.compile("(?:^|&)pageSize=(\\d+)").matcher(query);
        if (m.find()) {
            pageSize = Integer.parseInt(m.group(1));
        }
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < n; i++) {
            items.append(i > 0 ? "," : "").append(noConformidad(i));
        }
        int total = vacio ? 0 : (grande ? 300 : 42);
        return "{\"items\":[" + items + "],\"total\":" + total + ",\"page\":" + page + ",\"pageSize\":" + pageSize
                + ",\"pages\":" + Math.max(1, (total + pageSize - 1) / pageSize) + ",\"usuarioDelToken\":" + sub + "}";
    }

    /** JPEG mínimo (solo SOI + APP0; basta para la validación de firma). */
    public static byte[] jpegMinimo() {
        return new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0x10, 0x4a, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1};
    }

    /**
     * No Conformidades + catálogos. Adjunto por adjuntoId: 1 PDF, 2 PNG, 3 JPEG (MIME en mayúsculas),
     * 4 HTML declarado PDF, 5 GIF real (fuera de la lista), 6 text/html, 900 PNG con nombre malicioso;
     * errores 404/600/700/800. NC 404 → no encontrada; adjuntos.list de la NC 900 trae nombres maliciosos.
     */
    private void noConformidades(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        peticionesNoConformidades.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        java.util.regex.Matcher an = java.util.regex.Pattern.compile("^/api/no-conformidades/(\\d+)/analisis$").matcher(path);
        if (ex.getRequestMethod().equals("PUT") && an.matches()) {
            int nc = Integer.parseInt(an.group(1));
            String cuerpoAn = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            analisisRecibidos.add(new SeguimientoRecibido(nc, sub, cuerpoAn, ex.getRequestHeaders().getFirst("Content-Type")));
            JsonNode b = mapper.readTree(cuerpoAn);
            if ("ERROR_API".equals(b.path("problemaDetectado").asString(""))) {
                responder(ex, 400, fallo("No se pudo guardar el análisis"));
                return;
            }
            if (nc == 777) {
                responder(ex, 500, "{\"title\":\"error interno\"}");
                return;
            }
            // Upsert como la API real: UPDATE en sitio del último análisis (sin historial ni versión) o INSERT.
            tools.jackson.databind.node.ObjectNode actual = (tools.jackson.databind.node.ObjectNode) analisisDe(nc);
            tools.jackson.databind.node.ObjectNode nuevo = actual != null ? actual.deepCopy() : mapper.createObjectNode();
            for (String c : new String[] {"metodologia", "problemaDetectado", "porque1", "porque2", "porque3", "porque4", "porque5", "causaRaiz", "conclusion"}) {
                nuevo.set(c, b.get(c));
            }
            if (actual == null) {
                nuevo.put("id", 100 + nc);
                nuevo.set("creadoPor", b.get("usuario"));
            } else {
                nuevo.set("actualizadoPor", b.get("usuario"));
            }
            analisisPorNc.put(nc, nuevo);
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"id\":" + nuevo.get("id").asInt() + "},\"errors\":null}");
            return;
        }
        java.util.regex.Matcher acc = java.util.regex.Pattern.compile("^/api/no-conformidades/(\\d+)/acciones$").matcher(path);
        if (ex.getRequestMethod().equals("POST") && acc.matches()) {
            String cuerpoAcc = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            accionesRecibidas.add(new SeguimientoRecibido(Integer.parseInt(acc.group(1)), sub, cuerpoAcc,
                    ex.getRequestHeaders().getFirst("Content-Type")));
            String desc = mapper.readTree(cuerpoAcc).path("descripcion").asString("");
            if ("ERROR_API".equals(desc)) {
                responder(ex, 400, fallo("No se pudo registrar la acción"));
            } else if (acc.group(1).equals("777")) {
                responder(ex, 500, "{\"title\":\"error interno\"}");
            } else if ("CON_ID".equals(desc)) {
                responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"id\":77},\"errors\":null}");
            } else {
                // La API real responde Ok(new { }): no devuelve el id de la acción creada.
                responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{},\"errors\":null}");
            }
            return;
        }
        java.util.regex.Matcher ncPut = java.util.regex.Pattern.compile("^/api/no-conformidades/(\\d+)$").matcher(path);
        if (ex.getRequestMethod().equals("PUT") && ncPut.matches()) {
            // Como NoConformidadesController.Actualizar: {id} (la API real no verifica existencia ni cierre).
            String cuerpoPut = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int ncId = Integer.parseInt(ncPut.group(1));
            ncActualizadasRecibidas.add(new SeguimientoRecibido(ncId, sub, cuerpoPut, ex.getRequestHeaders().getFirst("Content-Type")));
            if ("ERROR_API".equals(mapper.readTree(cuerpoPut).path("cliente").asString(""))) {
                responder(ex, 400, fallo("No se recibió ningún campo para actualizar"));
                return;
            }
            versionNc.merge(ncId, 1, Integer::sum);
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"id\":" + ncId + "},\"errors\":null}");
            return;
        }
        java.util.regex.Matcher accPut = java.util.regex.Pattern.compile("^/api/no-conformidades/acciones/(\\d+)$").matcher(path);
        if (ex.getRequestMethod().equals("PUT") && accPut.matches()) {
            // Como NoConformidadesController.AccionesActualizar: sin verificar existencia; descripcion ERROR_API → 400.
            String cuerpoAcc = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int accionId = Integer.parseInt(accPut.group(1));
            accionesActualizadas.add(new SeguimientoRecibido(accionId, sub, cuerpoAcc, ex.getRequestHeaders().getFirst("Content-Type")));
            JsonNode b = mapper.readTree(cuerpoAcc);
            if ("ERROR_API".equals(b.path("estado").asString("")) || "ERROR_API".equals(b.path("descripcion").asString(""))) {
                responder(ex, 400, fallo("Error de negocio simulado"));
                return;
            }
            estadoAccion.put(accionId, b.path("estado").asString(""));
            versionAccion.merge(accionId, 1, Integer::sum);
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"id\":" + accionId + "},\"errors\":null}");
            return;
        }
        java.util.regex.Matcher gesCer = java.util.regex.Pattern.compile("^/api/no-conformidades/(\\d+)/(gestion|cerrar)$").matcher(path);
        if (gesCer.matches() && (ex.getRequestMethod().equals("PATCH") && gesCer.group(2).equals("gestion")
                || ex.getRequestMethod().equals("POST") && gesCer.group(2).equals("cerrar"))) {
            // Como NoConformidadesController.GestionActualizar / Cerrar: {id}; responsable ERROR_API → 400 de negocio.
            String cuerpoGc = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int ncId = Integer.parseInt(gesCer.group(1));
            boolean esCierre = gesCer.group(2).equals("cerrar");
            (esCierre ? cierresRecibidos : gestionesRecibidas).add(new SeguimientoRecibido(ncId, sub, cuerpoGc,
                    ex.getRequestHeaders().getFirst("Content-Type")));
            if ("ERROR_API".equals(mapper.readTree(cuerpoGc).path(esCierre ? "comentarioCierre" : "responsable").asString(""))) {
                responder(ex, 400, fallo("Error de negocio simulado"));
                return;
            }
            versionNc.merge(ncId, 1, Integer::sum);
            if (esCierre) {
                ncCerradas.add(ncId);
            }
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"id\":" + ncId + "},\"errors\":null}");
            return;
        }
        java.util.regex.Matcher adjPost = java.util.regex.Pattern.compile("^/api/no-conformidades/(\\d+)/adjuntos$").matcher(path);
        if (ex.getRequestMethod().equals("POST") && adjPost.matches()) {
            // Como NoConformidadesController.AdjuntosSubir: {id}; NC 404 no existe, 777 cerrada (400 de negocio).
            String cuerpoAdj = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int ncId = Integer.parseInt(adjPost.group(1));
            adjuntosRecibidos.add(new SeguimientoRecibido(ncId, sub, cuerpoAdj, ex.getRequestHeaders().getFirst("Content-Type")));
            if (ncId == 404) {
                responder(ex, 400, fallo("No conformidad no encontrada"));
            } else if (ncId == 777) {
                responder(ex, 400, fallo("La no conformidad está cerrada, no se pueden agregar adjuntos"));
            } else {
                responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"id\":" + (700 + adjuntosRecibidos.size())
                        + "},\"errors\":null}");
            }
            return;
        }
        if (ex.getRequestMethod().equals("POST") && path.equals("/api/no-conformidades")) {
            // Como NoConformidadesController.Crear: {id, codigo}; ERROR_API → 400 de negocio, ERROR_500 → 500.
            String cuerpoNc = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ncCreadasRecibidas.add(new SeguimientoRecibido(0, sub, cuerpoNc, ex.getRequestHeaders().getFirst("Content-Type")));
            String cliente = mapper.readTree(cuerpoNc).path("cliente").asString("");
            if ("ERROR_API".equals(cliente)) {
                responder(ex, 400, fallo("Falta el campo obligatorio: cliente"));
            } else if ("ERROR_500".equals(cliente)) {
                responder(ex, 500, "{\"title\":\"error interno\"}");
            } else {
                int id = 950 + ncCreadasRecibidas.size();
                responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"id\":" + id + ",\"codigo\":\"NC-2026-" + id
                        + "\"},\"errors\":null}");
            }
            return;
        }
        java.util.regex.Matcher catPost = java.util.regex.Pattern.compile("^/api/nc-catalogos/([A-Za-z]+)$").matcher(path);
        if (ex.getRequestMethod().equals("POST") && catPost.matches()) {
            crearCatalogo(ex, catPost.group(1), sub);
            return;
        }
        java.util.regex.Matcher seg = java.util.regex.Pattern.compile("^/api/no-conformidades/(\\d+)/seguimiento$").matcher(path);
        if (ex.getRequestMethod().equals("POST") && seg.matches()) {
            String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seguimientosRecibidos.add(new SeguimientoRecibido(Integer.parseInt(seg.group(1)), sub, cuerpo,
                    ex.getRequestHeaders().getFirst("Content-Type")));
            JsonNode b = mapper.readTree(cuerpo);
            if ("ERROR_API".equals(b.path("comentario").asString(""))) {
                responder(ex, 400, fallo("No se pudo registrar el seguimiento"));
            } else if (seg.group(1).equals("777")) {
                responder(ex, 500, "{\"title\":\"error interno\"}");
            } else {
                responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"id\":" + seg.group(1) + "},\"errors\":null}");
            }
            return;
        }
        java.util.regex.Matcher ncDel = java.util.regex.Pattern.compile("^/api/no-conformidades/(\\d+)$").matcher(path);
        if (ex.getRequestMethod().equals("DELETE") && ncDel.matches()) {
            // Como NoConformidadesController.Eliminar: borrado lógico, siempre OK (la API no verifica existencia).
            responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"id\":" + ncDel.group(1) + "},\"errors\":null}");
            return;
        }
        java.util.regex.Matcher adjDel = java.util.regex.Pattern.compile("^/api/no-conformidades/(\\d+)/adjuntos/(\\d+)$").matcher(path);
        if (ex.getRequestMethod().equals("DELETE") && adjDel.matches()) {
            // Como AdjuntosEliminar: NC cerrada (777) o adjunto inexistente (404) → 400 de negocio.
            if (adjDel.group(1).equals("777")) {
                responder(ex, 400, fallo("La no conformidad está cerrada, no se pueden eliminar adjuntos"));
            } else if (adjDel.group(2).equals("404")) {
                responder(ex, 400, fallo("Adjunto no encontrado"));
            } else {
                responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"mensaje\":\"Adjunto eliminado correctamente\"},\"errors\":null}");
            }
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            responder(ex, 200, "{\"success\":true,\"message\":\"NO DEBERIA LLEGAR\",\"data\":null,\"errors\":null}");
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Filtro de fecha inválido"));
            return;
        }
        String ok = "{\"success\":true,\"message\":null,\"data\":";
        String fin = ",\"errors\":null}";
        java.util.regex.Matcher cat = java.util.regex.Pattern.compile("^/api/nc-catalogos/([A-Za-z]+)$").matcher(path);
        if (cat.matches()) {
            if (!java.util.List.of(cl.faret.qccweb.bridge.handlers.NoConformidadesBridgeHandler.CATALOGOS).contains(cat.group(1))
                    && !java.util.List.of(cl.faret.qccweb.bridge.handlers.NoConformidadesBridgeHandler.CATALOGOS_NCI).contains(cat.group(1))) {
                responder(ex, 404, fallo("Catálogo no reconocido"));
                return;
            }
            StringBuilder extra = new StringBuilder();
            for (tools.jackson.databind.node.ObjectNode v : valoresCatalogo(cat.group(1))) {
                if (v.get("activo").asBoolean()) {
                    extra.append(",{\"id\":").append(v.get("id").asInt()).append(",\"nombre\":")
                            .append(mapper.writeValueAsString(v.get("nombre").asString())).append(",\"activo\":true}");
                }
            }
            responder(ex, 200, ok + "[{\"id\":1,\"nombre\":\"" + cat.group(1) + " ñ\",\"activo\":true,\"usuarioDelToken\":" + sub + "}"
                    + extra + "]" + fin);
            return;
        }
        if (path.equals("/api/no-conformidades")) {
            responder(ex, 200, ok + dataNoConformidadesList(sub, String.valueOf(query)) + fin);
            return;
        }
        if (path.equals("/api/no-conformidades/resumen")) {
            responder(ex, 200, ok + "{\"total\":42,\"abiertas\":30,\"cerradas\":12,\"criticas\":5,\"usuarioDelToken\":" + sub + "}" + fin);
            return;
        }
        if (path.equals("/api/no-conformidades/filtros-opciones")) {
            responder(ex, 200, ok + "{\"clientes\":[\"Viña Ñandú\"],\"tiposPnc\":[\"Reclamo\"],\"areas\":[\"Impresión\"],\"usuarioDelToken\":" + sub + "}" + fin);
            return;
        }
        java.util.regex.Matcher adj = java.util.regex.Pattern.compile("^/api/no-conformidades/(\\d+)/adjuntos/(\\d+)$").matcher(path);
        if (adj.matches()) {
            int adjuntoId = Integer.parseInt(adj.group(2));
            String nombre = "adjunto_" + adjuntoId;
            String mime = "application/pdf";
            String base64 = "";
            switch (adjuntoId) {
                case 404 -> { responder(ex, 404, fallo("Adjunto no encontrado")); return; }
                case 1 -> { nombre = "causa raíz ñ.pdf"; base64 = Base64.getEncoder().encodeToString(pdfMinimo(1)); }
                case 2 -> { nombre = "foto1.png"; mime = "image/png"; base64 = Base64.getEncoder().encodeToString(pngMinimo()); }
                case 3 -> { nombre = "foto2.jpg"; mime = "IMAGE/JPEG"; base64 = Base64.getEncoder().encodeToString(jpegMinimo()); }
                case 4 -> { nombre = "falso.pdf"; base64 = Base64.getEncoder().encodeToString("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)); }
                case 5 -> { nombre = "anim.gif"; mime = "image/gif"; base64 = Base64.getEncoder().encodeToString("GIF89a....".getBytes(StandardCharsets.UTF_8)); }
                case 6 -> { nombre = "x.html"; mime = "text/html"; base64 = Base64.getEncoder().encodeToString("<b>x</b>".getBytes(StandardCharsets.UTF_8)); }
                case 600 -> nombre = "vacio.pdf";
                case 700 -> { nombre = "grande.pdf"; base64 = "A".repeat(((10 * 1024 * 1024 + 2) / 3) * 4 + 4); }
                case 800 -> { nombre = "roto.pdf"; base64 = "%%%no-base64%%%"; }
                case 900 -> { nombre = "..\\<img src=x onerror=alert(1)>\".png"; mime = "image/png"; base64 = Base64.getEncoder().encodeToString(pngMinimo()); }
                default -> base64 = Base64.getEncoder().encodeToString(pdfMinimo(adjuntoId));
            }
            responder(ex, 200, ok + "{\"id\":" + adjuntoId + ",\"nombreArchivo\":\"" + nombre.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\",\"tipoMime\":\"" + mime + "\",\"contenidoBase64\":\"" + base64 + "\",\"usuarioDelToken\":" + sub + "}" + fin);
            return;
        }
        java.util.regex.Matcher nc = java.util.regex.Pattern.compile("^/api/no-conformidades/(\\d+)(/[a-z]+)?$").matcher(path);
        if (nc.matches()) {
            int id = Integer.parseInt(nc.group(1));
            String rama = nc.group(2) == null ? "" : nc.group(2);
            if (id == 404) {
                responder(ex, 404, fallo("No conformidad no encontrada"));
                return;
            }
            switch (rama) {
                case "" -> responder(ex, 200, ok + "{\"id\":" + id + ",\"codigo\":\"NC-0001\",\"descripcion\":\"=HYPERLINK(1)\","
                        + "\"estadoGestion\":\"" + (id == 503 || ncCerradas.contains(id) ? "CERRADA" : "ASIGNADA") + "\",\"version\":" + versionNc.getOrDefault(id, 0)
                        + ",\"usuarioDelToken\":" + sub + "}" + fin);
                case "/seguimiento" -> responder(ex, 200, ok + (id == 901
                        // Comentario malicioso guardado desde Photino (que no valida): la web debe mostrarlo sin ejecutarlo.
                        ? "[{\"id\":9,\"comentario\":\"<img src=x onerror=alert(1)> & 'x' \\\"y\\\" ñ\",\"autor\":\"<b>Mallory</b>\",\"creadoEn\":\"2026-09-20T10:00:00\"}]"
                        : id == 902
                        // Texto normal con & < comillas (sin marcado): la web NO lo escapa (Fase 3r; NC Internas escapa en la vista).
                        ? "[{\"id\":2,\"comentario\":\"R&D 5<6 'x' \\\"y\\\"\",\"autor\":\"Ana & Co\"}]"
                        : "[{\"id\":1,\"comentario\":\"Revisión ñ\",\"autor\":\"María\",\"usuarioDelToken\":" + sub + "}]") + fin);
                case "/analisis" -> {
                    JsonNode a = analisisDe(id);
                    responder(ex, 200, ok + (a == null ? "null" : a.toString()) + fin);
                }
                case "/acciones" -> responder(ex, 200, ok + (id == 901
                        ? "[{\"id\":4,\"descripcion\":\"<img src=x onerror=alert(1)> & ñ\",\"responsable\":\"<b>Mallory</b>\",\"prioridad\":\"ALTA\","
                                + "\"fechaLimite\":\"2026-10-15T00:00:00\",\"estado\":\"" + estadoAccion.getOrDefault(4, "PENDIENTE")
                                + "\",\"version\":" + versionAccion.getOrDefault(4, 0) + "}]"
                        : "[{\"id\":3,\"descripcion\":\"Cambiar rodillo\",\"responsable\":\"Juan Pérez\",\"fechaLimite\":\"2026-10-20\","
                                + "\"prioridad\":null,\"estado\":\"" + estadoAccion.getOrDefault(3, "PENDIENTE") + "\",\"version\":"
                                + versionAccion.getOrDefault(3, 0) + ",\"usuarioDelToken\":" + sub + "}]") + fin);
                case "/adjuntos" -> {
                    String n1 = id == 900 ? "..\\\\<img src=x onerror=alert(1)>.pdf" : "causa raíz ñ.pdf";
                    responder(ex, 200, ok + "[{\"id\":1,\"tipo\":\"CAUSA_RAIZ_PDF\",\"nombreArchivo\":\"" + n1 + "\",\"tipoMime\":\"application/pdf\"},"
                            + "{\"id\":2,\"tipo\":\"EVIDENCIA_FOTO\",\"nombreArchivo\":\"\",\"tipoMime\":\"image/png\"},"
                            + "{\"id\":3,\"tipo\":\"EVIDENCIA_FOTO\",\"tipoMime\":\"image/jpeg\"}]" + fin);
                }
                default -> responder(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
            }
            return;
        }
        responder(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
    }

    private final java.util.List<String> peticionesRecepcion = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** "<método> <ruta>[?<query cruda>]" recibidos en api/recepcion-calidad*. */
    public java.util.List<String> peticionesRecepcion() {
        return java.util.List.copyOf(peticionesRecepcion);
    }

    /** Tipo de materia prima de cada lote de prueba (el resto de ids → 404 en el detalle). */
    private static String tipoLote(int id) {
        return switch (id) {
            case 1, 3, 4, 5, 6, 7, 8, 9, 24 -> "PVA"; // 24: fallo parcial de recepcion.nc.crear (Fase 3x)
            case 2 -> "PliegoFaret";
            case 10, 20, 21, 22, 23 -> "Bobina";
            default -> null;
        };
    }

    /** Bobinas de los lotes Bobina con estado (20: 4 bobinas; 21: otro lote; 22: EnAnalisis). */
    private static java.util.List<String> bobinasLote(int id) {
        return switch (id) {
            case 20 -> java.util.List.of("B-001", "B-002", "B-003", "B-004");
            case 21 -> java.util.List.of("X-001");
            case 22 -> java.util.List.of("C-001", "C-002");
            case 23 -> java.util.List.of("D-001");
            default -> java.util.List.of();
        };
    }

    /** Cuerpos EXACTOS de POST api/recepcion-calidad/{id}/bobinas-muestreadas (ncId = loteId). */
    private final java.util.List<SeguimientoRecibido> muestreosRecibidos = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final Map<Integer, String> muestreadasPorLote = new ConcurrentHashMap<>();
    private final Map<Integer, String> estadoLote = new ConcurrentHashMap<>();

    public java.util.List<SeguimientoRecibido> muestreosRecibidos() {
        return java.util.List.copyOf(muestreosRecibidos);
    }

    public String estadoLote(int id) {
        return estadoLote.getOrDefault(id, id == 22 ? "EnAnalisis" : "PendienteMuestreo");
    }

    /** Cuerpos EXACTOS de POST api/recepcion-calidad/{id}/muestra-laboratorio (ncId = loteId). */
    private final java.util.List<SeguimientoRecibido> muestrasRecibidas = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    /** Muestra vinculada que muestra el detalle (TOP 1 = la primera creada del lote). */
    private final Map<Integer, Integer> muestraPorLote = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger siguienteMuestra = new java.util.concurrent.atomic.AtomicInteger(700);
    private volatile long demoraMuestraMs;

    public java.util.List<SeguimientoRecibido> muestrasRecibidas() {
        return java.util.List.copyOf(muestrasRecibidas);
    }

    /** Muestras de Laboratorio creadas en total (la API no evita duplicados: cada POST inserta una). */
    public int muestrasCreadas() {
        return siguienteMuestra.get() - 700;
    }

    /** Demora de la API al crear la muestra (para superponer doble clic / sesiones concurrentes). */
    public void demoraMuestra(long ms) {
        this.demoraMuestraMs = ms;
    }

    /** Simula que otra persona (Photino u otra sesión) creó una muestra de Laboratorio del lote. */
    public void crearMuestraPorOtro(int loteId) {
        int id = siguienteMuestra.getAndIncrement();
        muestraPorLote.putIfAbsent(loteId, id);
        estadoLote.put(loteId, "EnAnalisis");
    }

    /**
     * Como RecepcionCalidadRepository.CrearMuestraLaboratorio (sin transacción ni control de duplicados): lee el lote,
     * INSERTA la muestra y pone el lote EnAnalisis. Lote 23: fallo parcial (muestra insertada, UPDATE de estado falla → 500).
     */
    private void crearMuestra(HttpExchange ex, int loteId, int sub) throws IOException {
        String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        muestrasRecibidas.add(new SeguimientoRecibido(loteId, sub, cuerpo, ex.getRequestHeaders().getFirst("Content-Type")));
        if (demoraMuestraMs > 0) {
            try {
                Thread.sleep(demoraMuestraMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (tipoLote(loteId) == null) {
            responder(ex, 400, fallo("Lote no encontrado"));
            return;
        }
        int id = siguienteMuestra.getAndIncrement();
        muestraPorLote.putIfAbsent(loteId, id);
        if (loteId == 23) {
            responder(ex, 500, "{\"title\":\"error interno\"}");
            return;
        }
        estadoLote.put(loteId, "EnAnalisis");
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"muestraLaboratorioId\":" + id + "},\"errors\":null}");
    }

    /** Cuerpos EXACTOS de PATCH api/recepcion-calidad/{id}/estado (ncId = loteId). */
    private final java.util.List<SeguimientoRecibido> estadosRecibidos = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile long demoraEstadoMs;

    public java.util.List<SeguimientoRecibido> estadosRecibidos() {
        return java.util.List.copyOf(estadosRecibidos);
    }

    /** Demora de la API al actualizar el estado (para superponer doble clic / sesiones concurrentes). */
    public void demoraEstado(long ms) {
        this.demoraEstadoMs = ms;
    }

    // ------------------------------------------------------------------ Fase 3z: POST api/recepcion-calidad (alta de lote)
    private final java.util.List<SeguimientoRecibido> lotesCreados = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.concurrent.atomic.AtomicInteger siguienteLote = new java.util.concurrent.atomic.AtomicInteger(1000);

    /** Cuerpos EXACTOS de POST api/recepcion-calidad (ncId = id asignado). */
    public java.util.List<SeguimientoRecibido> lotesCreados() {
        return java.util.List.copyOf(lotesCreados);
    }

    /** Como RecepcionCalidadService.CrearLoteAsync: tipo obligatorio, Bobina con bobinas, suma de colores en Pliego. */
    private void crearLoteRecepcion(HttpExchange ex, int sub) throws IOException {
        String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode b = mapper.readTree(cuerpo);
        String tipo = b.path("tipoMateriaPrima").asString("");
        if (tipo.isBlank()) {
            responder(ex, 400, fallo("Falta el tipo de materia prima"));
            return;
        }
        if (tipo.equals("Bobina") && b.path("bobinas").isEmpty()) {
            responder(ex, 400, fallo("Debes seleccionar al menos una bobina desde SAP"));
            return;
        }
        if (tipo.equals("PliegoFaret") && b.path("pfCantidadTotal").isNumber()) {
            java.math.BigDecimal suma = b.path("pfCantidadVerde").decimalValue().add(b.path("pfCantidadAzul").decimalValue())
                    .add(b.path("pfCantidadRoja").decimalValue());
            if (suma.compareTo(b.path("pfCantidadTotal").decimalValue()) != 0) {
                responder(ex, 400, fallo("Cantidad verde + azul + roja debe ser igual a la cantidad total"));
                return;
            }
        }
        int id = siguienteLote.getAndIncrement();
        lotesCreados.add(new SeguimientoRecibido(id, sub, cuerpo, ex.getRequestHeaders().getFirst("Content-Type")));
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"id\":" + id + "},\"errors\":null}");
    }

    // ------------------------------------------------------------------ Fase 3x: POST api/recepcion-calidad/{id}/nc
    private final Map<Integer, Integer> ncPorLote = new ConcurrentHashMap<>();
    private final java.util.List<SeguimientoRecibido> ncRecepcionRecibidas = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.concurrent.atomic.AtomicInteger siguienteNcRecepcion = new java.util.concurrent.atomic.AtomicInteger(900);
    private volatile long demoraNcRecepcionMs;

    /** Cuerpos EXACTOS de POST api/recepcion-calidad/{id}/nc (ncId = loteId). */
    public java.util.List<SeguimientoRecibido> ncRecepcionRecibidas() {
        return java.util.List.copyOf(ncRecepcionRecibidas);
    }

    /** No Conformidades creadas en total (la API crea la NC antes de vincularla: un fallo parcial deja una suelta). */
    public int ncRecepcionCreadas() {
        return siguienteNcRecepcion.get() - 900;
    }

    public void demoraNcRecepcion(long ms) {
        this.demoraNcRecepcionMs = ms;
    }

    /** Simula que otra persona (Photino u otra sesión) creó la NC del lote. */
    public void crearNcRecepcionPorOtro(int loteId) {
        ncPorLote.put(loteId, siguienteNcRecepcion.getAndIncrement());
    }

    private String ncDetalle(int loteId) {
        Integer nc = ncPorLote.get(loteId);
        return ",\"ncId\":" + nc + ",\"ncCodigo\":" + (nc == null ? "null" : "\"NC-2026-" + nc + "\"");
    }

    /**
     * Como RecepcionCalidadRepository.CrearNoConformidad (sin transacción): lee el lote SIN filtro de empresa, exige estado
     * NoConforme y sin NC vinculada, crea la NC y DESPUÉS la vincula. Lote 24: la NC se crea pero la vinculación falla (500).
     */
    private void crearNcRecepcion(HttpExchange ex, int loteId, int sub) throws IOException {
        String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        ncRecepcionRecibidas.add(new SeguimientoRecibido(loteId, sub, cuerpo, ex.getRequestHeaders().getFirst("Content-Type")));
        if (demoraNcRecepcionMs > 0) {
            try {
                Thread.sleep(demoraNcRecepcionMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (tipoLote(loteId) == null) {
            responder(ex, 400, fallo("Lote no encontrado"));
            return;
        }
        if (!estadoLote(loteId).equals("NoConforme")) {
            responder(ex, 400, fallo("Solo se puede crear una No Conformidad cuando el lote quedó \\\"No conforme\\\""));
            return;
        }
        if (ncPorLote.containsKey(loteId)) {
            responder(ex, 400, fallo("Este lote ya tiene una No Conformidad vinculada"));
            return;
        }
        int nc = siguienteNcRecepcion.getAndIncrement();
        if (loteId == 24) {
            responder(ex, 500, "{\"title\":\"error interno\"}");
            return;
        }
        ncPorLote.put(loteId, nc);
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"ncId\":" + nc + ",\"codigo\":\"NC-2026-" + nc
                + "\"},\"errors\":null}");
    }

    // ------------------------------------------------------------------ Fase 4a: POST api/recepcion-calidad/{id}/plan
    private final Map<Integer, String> planPorLote = new ConcurrentHashMap<>();
    private final java.util.List<SeguimientoRecibido> planesRecibidos = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.concurrent.atomic.AtomicInteger planesInsertados = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long demoraPlanMs;

    /** Cuerpos EXACTOS de POST api/recepcion-calidad/{id}/plan (ncId = loteId). */
    public java.util.List<SeguimientoRecibido> planesRecibidos() {
        return java.util.List.copyOf(planesRecibidos);
    }

    /** Filas insertadas en recepcion_plan_muestreo (la PK lote_id rechaza un segundo INSERT simultáneo: R10). */
    public int planesInsertados() {
        return planesInsertados.get();
    }

    /** Demora de la API entre el UPDATE y el INSERT (para superponer doble clic / sesiones concurrentes). */
    public void demoraPlan(long ms) {
        this.demoraPlanMs = ms;
    }

    /** Simula que otra persona (Photino u otra sesión) generó el plan del lote. */
    public void generarPlanPorOtro(int loteId) {
        planPorLote.put(loteId, planNch44(bobinasLote(loteId).size(), new java.math.BigDecimal("2.5")));
    }

    /** Plan del lote tal como lo devuelve el detalle (null si no tiene). */
    public String planDelLote(int loteId) {
        return planPorLote.get(loteId);
    }

    /** NCh44 nivel II mínima de la simulación: 2-8 → A (n=2), 9-15 → B (n=3); AQL 2.5 → Ac 0 / Re 1; otro AQL → sin Ac/Re. */
    private static String planNch44(int tamanoLote, java.math.BigDecimal aql) {
        String letra = tamanoLote >= 2 && tamanoLote <= 8 ? "A" : "B";
        int muestra = letra.equals("A") ? 2 : 3;
        boolean conAcRe = aql.compareTo(new java.math.BigDecimal("2.5")) == 0;
        return "{\"norma\":\"NCh44\",\"tamanoLote\":" + tamanoLote + ",\"nivelInspeccion\":\"II\",\"aql\":" + aql.toPlainString()
                + ",\"letraCodigo\":\"" + letra + "\",\"tamanoMuestra\":" + muestra + ",\"numeroAceptacion\":" + (conAcRe ? "0" : "null")
                + ",\"numeroRechazo\":" + (conAcRe ? "1" : "null") + "}";
    }

    /**
     * Como RecepcionCalidadRepository.GenerarPlan (R10): lee el tamaño del lote SIN filtro de empresa, busca la letra NCh44
     * del nivel (sin letra → error de negocio: PVA/Pliego tienen tamaño 0), UPDATE del plan y, si no había, INSERT. Dos
     * INSERT simultáneos del mismo lote: la PK rechaza el segundo (500), como SQL Server.
     */
    private void generarPlan(HttpExchange ex, int loteId, int sub) throws IOException {
        String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        planesRecibidos.add(new SeguimientoRecibido(loteId, sub, cuerpo, ex.getRequestHeaders().getFirst("Content-Type")));
        if (tipoLote(loteId) == null) {
            responder(ex, 400, fallo("Lote no encontrado"));
            return;
        }
        JsonNode b = mapper.readTree(cuerpo);
        String nivel = b.path("nivelInspeccion").asString("");
        java.math.BigDecimal aql = b.path("aql").isNumber() ? b.path("aql").decimalValue() : new java.math.BigDecimal("2.5");
        int tamano = bobinasLote(loteId).size();
        if (!nivel.equals("II") || tamano < 2) {
            responder(ex, 400, fallo("No hay tabla de muestreo NCh44 cargada para nivel " + nivel + " y tamaño de lote " + tamano));
            return;
        }
        String plan = planNch44(tamano, aql);
        boolean existia = planPorLote.containsKey(loteId);
        if (demoraPlanMs > 0) {
            try {
                Thread.sleep(demoraPlanMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (existia) {
            planPorLote.put(loteId, plan);
        } else if (planPorLote.putIfAbsent(loteId, plan) != null) {
            responder(ex, 500, "{\"title\":\"Violation of PRIMARY KEY constraint\"}");
            return;
        } else {
            planesInsertados.incrementAndGet();
        }
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + plan + ",\"errors\":null}");
    }

    /** Simula que otra persona (Photino u otra sesión) actualizó el estado del lote. */
    public void cambiarEstadoPorOtro(int loteId, String estado) {
        estadoLote.put(loteId, estado);
    }

    /**
     * Como RecepcionCalidadRepository.ActualizarEstado: un UPDATE directo, sin verificar existencia del lote ni
     * validar el valor de estado (la API real acepta cualquier string).
     */
    private void actualizarEstado(HttpExchange ex, int loteId, int sub) throws IOException {
        String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        estadosRecibidos.add(new SeguimientoRecibido(loteId, sub, cuerpo, ex.getRequestHeaders().getFirst("Content-Type")));
        if (demoraEstadoMs > 0) {
            try {
                Thread.sleep(demoraEstadoMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String estado = mapper.readTree(cuerpo).path("estado").asString("");
        estadoLote.put(loteId, estado);
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"actualizado\":true},\"errors\":null}");
    }

    /** Simula que otra persona (Photino u otra sesión) guardó otra selección del lote. */
    public void muestrearPorOtro(int loteId, String numeroBobina) {
        muestreadasPorLote.put(loteId, "[{\"numeroBobina\":\"" + numeroBobina + "\",\"seleccionTipo\":\"Manual\",\"criterioManual\":null,"
                + "\"usuario\":\"Otra Persona\",\"fechaSeleccion\":\"2026-09-28 10:00\"}]");
    }

    /** Como RecepcionCalidadRepository.MuestrearBobinas: valida pertenencia, REEMPLAZA la selección y avanza el estado. */
    private void muestrear(HttpExchange ex, int loteId, int sub) throws IOException {
        String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        muestreosRecibidos.add(new SeguimientoRecibido(loteId, sub, cuerpo, ex.getRequestHeaders().getFirst("Content-Type")));
        JsonNode b = mapper.readTree(cuerpo);
        java.util.List<String> invalidas = new java.util.ArrayList<>();
        StringBuilder sel = new StringBuilder("[");
        for (JsonNode x : b.path("bobinas")) {
            String n = x.path("numeroBobina").asString("");
            if (n.equals("ERROR_500")) {
                responder(ex, 500, "{\"title\":\"error interno\"}");
                return;
            }
            if (!bobinasLote(loteId).contains(n)) {
                invalidas.add(n);
            }
            sel.append(sel.length() > 1 ? "," : "").append("{\"numeroBobina\":").append(mapper.writeValueAsString(n))
                    .append(",\"seleccionTipo\":").append(mapper.writeValueAsString(x.path("seleccionTipo").asString("")))
                    .append(",\"criterioManual\":").append(x.path("criterioManual").isNull() ? "null" : mapper.writeValueAsString(x.path("criterioManual").asString("")))
                    .append(",\"usuario\":").append(mapper.writeValueAsString(b.path("usuario").asString(""))).append(",\"fechaSeleccion\":\"2026-09-28 11:00\"}");
        }
        if (b.path("bobinas").isEmpty()) {
            responder(ex, 400, fallo("Falta el lote o la lista de bobinas muestreadas"));
            return;
        }
        if (!invalidas.isEmpty()) {
            responder(ex, 400, fallo("Las siguientes bobinas no pertenecen a este lote: " + String.join(", ", invalidas)));
            return;
        }
        muestreadasPorLote.put(loteId, sel.append("]").toString());
        if (estadoLote(loteId).equals("PendienteMuestreo")) {
            estadoLote.put(loteId, "PendienteLaboratorio");
        }
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"muestreadas\":" + b.path("bobinas").size() + "},\"errors\":null}");
    }

    public static String dataRecepcionList(int sub, String query) {
        return "[{\"id\":1,\"fechaCreacion\":\"2026-09-20 08:00\",\"tipoMateriaPrima\":\"PVA\",\"proveedor\":\"Adhesivos Ñuñoa «1»\",\"itemCode\":\"PVA-01\","
                + "\"descripcion\":\"=SUMA(1;2)\",\"cantidadTotalLote\":12.5,\"estado\":\"PendienteMuestreo\",\"totalBobinas\":0,\"totalMuestreadas\":0,"
                + "\"query\":\"" + query + "\",\"usuarioDelToken\":" + sub + "},"
                + "{\"id\":10,\"fechaCreacion\":\"2026-09-21 09:30\",\"tipoMateriaPrima\":\"Bobina\",\"proveedor\":\"Papelera\",\"itemCode\":\"B-99\","
                + "\"descripcion\":\"Bobina kraft\",\"cantidadTotalLote\":null,\"estado\":\"EnAnalisis\",\"totalBobinas\":8,\"totalMuestreadas\":2}]";
    }

    /**
     * Recepción Calidad. Detalle por id (empresa=INNPACK): 1,3-9 PVA, 2 PliegoFaret, 10 Bobina; 404 el
     * resto o empresa≠INNPACK. Foto por loteId: 1 JPEG, 2 PNG (guardada como image/jpeg), 3 GIF, 4 WEBP,
     * 5 HTML, 6 sin foto, 7 vacía, 8 excesiva, 9 base64 corrupto.
     */
    private void recepcionCalidad(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        peticionesRecepcion.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        java.util.regex.Matcher mue = java.util.regex.Pattern.compile("^/api/recepcion-calidad/(\\d+)/bobinas-muestreadas$").matcher(path);
        if (ex.getRequestMethod().equals("POST") && mue.matches()) {
            muestrear(ex, Integer.parseInt(mue.group(1)), sub);
            return;
        }
        java.util.regex.Matcher mlab = java.util.regex.Pattern.compile("^/api/recepcion-calidad/(\\d+)/muestra-laboratorio$").matcher(path);
        if (ex.getRequestMethod().equals("POST") && mlab.matches()) {
            crearMuestra(ex, Integer.parseInt(mlab.group(1)), sub);
            return;
        }
        java.util.regex.Matcher est = java.util.regex.Pattern.compile("^/api/recepcion-calidad/(\\d+)/estado$").matcher(path);
        if (ex.getRequestMethod().equals("POST") && path.equals("/api/recepcion-calidad")) {
            crearLoteRecepcion(ex, sub);
            return;
        }
        java.util.regex.Matcher ncr = java.util.regex.Pattern.compile("^/api/recepcion-calidad/(\\d+)/nc$").matcher(path);
        if (ex.getRequestMethod().equals("POST") && ncr.matches()) {
            crearNcRecepcion(ex, Integer.parseInt(ncr.group(1)), sub);
            return;
        }
        if (ex.getRequestMethod().equals("PATCH") && est.matches()) {
            actualizarEstado(ex, Integer.parseInt(est.group(1)), sub);
            return;
        }
        java.util.regex.Matcher pln = java.util.regex.Pattern.compile("^/api/recepcion-calidad/(\\d+)/plan$").matcher(path);
        if (ex.getRequestMethod().equals("POST") && pln.matches()) {
            generarPlan(ex, Integer.parseInt(pln.group(1)), sub);
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            responder(ex, 200, "{\"success\":true,\"message\":\"NO DEBERIA LLEGAR\",\"data\":null,\"errors\":null}");
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Filtro de fecha inválido"));
            return;
        }
        String ok = "{\"success\":true,\"message\":null,\"data\":";
        String fin = ",\"errors\":null}";
        String q = query == null ? "" : query;
        if (path.equals("/api/recepcion-calidad")) {
            responder(ex, 200, ok + dataRecepcionList(sub, q) + fin);
            return;
        }
        java.util.regex.Matcher foto = java.util.regex.Pattern.compile("^/api/recepcion-calidad/(\\d+)/foto$").matcher(path);
        if (foto.matches()) {
            int id = Integer.parseInt(foto.group(1));
            String base64;
            switch (id) {
                case 1 -> base64 = Base64.getEncoder().encodeToString(jpegMinimo());
                case 2 -> base64 = Base64.getEncoder().encodeToString(pngMinimo());
                case 3 -> base64 = Base64.getEncoder().encodeToString("GIF89a\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1));
                case 4 -> base64 = Base64.getEncoder().encodeToString("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1));
                case 5 -> base64 = Base64.getEncoder().encodeToString("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
                case 6 -> { responder(ex, 400, fallo("Este lote no tiene fotografía cargada")); return; }
                case 7 -> base64 = "";
                case 8 -> base64 = "/9j/" + "A".repeat(((10 * 1024 * 1024 + 2) / 3) * 4);
                case 9 -> base64 = "%%%no-base64%%%";
                default -> base64 = Base64.getEncoder().encodeToString(jpegMinimo());
            }
            responder(ex, 200, ok + "{\"base64\":\"" + base64 + "\",\"mime\":\"image/jpeg\",\"usuarioDelToken\":" + sub + "}" + fin);
            return;
        }
        java.util.regex.Matcher det = java.util.regex.Pattern.compile("^/api/recepcion-calidad/(-?\\d+)$").matcher(path);
        if (det.matches()) {
            int id = Integer.parseInt(det.group(1));
            String tipo = tipoLote(id);
            if (tipo == null || !q.equals("empresa=INNPACK")) {
                responder(ex, 404, fallo("Lote no encontrado"));
                return;
            }
            if (!bobinasLote(id).isEmpty()) {
                responder(ex, 200, ok + "{\"id\":" + id + ",\"tipoMateriaPrima\":\"Bobina\",\"proveedor\":\"Papeles Ñuble\",\"estado\":\"" + estadoLote(id)
                        + "\",\"totalBobinas\":" + bobinasLote(id).size() + ",\"bobinas\":" + mapper.writeValueAsString(bobinasLote(id))
                        + ",\"plan\":" + planPorLote.getOrDefault(id, "null") + ",\"muestreadas\":" + muestreadasPorLote.getOrDefault(id, "[]")
                        + ",\"muestraLaboratorioId\":" + muestraPorLote.get(id) + ncDetalle(id) + ",\"usuarioDelToken\":" + sub + "}" + fin);
                return;
            }
            responder(ex, 200, ok + "{\"id\":" + id + ",\"tipoMateriaPrima\":\"" + tipo + "\",\"proveedor\":\"Adhesivos Ñuñoa\",\"estado\":\"" + estadoLote(id)
                    + "\",\"totalBobinas\":0,\"bobinas\":[],\"pva\":{\"tieneFoto\":true}" + ncDetalle(id) + ",\"usuarioDelToken\":" + sub + "}" + fin);
            return;
        }
        responder(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
    }

    private final java.util.List<String> peticionesUsuarios = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile String modoUsuarios = "NORMAL";

    /** "<método> <ruta>" recibidos en api/usuarios*. */
    public java.util.List<String> peticionesUsuarios() {
        return java.util.List.copyOf(peticionesUsuarios);
    }

    /** NORMAL | VACIO (data []) | NULO (data null) | OBJETO (data {}) | ITEM_INVALIDO ([1]). */
    public void modoUsuarios(String modo) {
        this.modoUsuarios = modo;
    }

    /**
     * GET api/usuarios con [Authorize(Roles = "admin,admin_ti")] (403 sin cuerpo para otro rol del
     * JWT). Fila 1 trae campos EXTRA (passwordHash, token) que el gateway debe descartar; fila 2 viene
     * incompleta y con nombres en PascalCase/mayúsculas mezcladas.
     */
    private void usuarios(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        peticionesUsuarios.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath());
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        Usuario actor = usuarios.values().stream().filter(u -> u.id() == sub).findFirst().orElse(null);
        if (actor == null || !(actor.rol().equals("admin") || actor.rol().equals("admin_ti"))) {
            ex.sendResponseHeaders(403, -1);
            ex.close();
            return;
        }
        if (!ex.getRequestMethod().equals("GET") || !ex.getRequestURI().getRawPath().equals("/api/usuarios")) {
            responder(ex, 200, "{\"success\":true,\"message\":\"NO DEBERIA LLEGAR\",\"data\":null,\"errors\":null}");
            return;
        }
        String data = switch (modoUsuarios) {
            case "VACIO" -> "[]";
            case "NULO" -> "null";
            case "OBJETO" -> "{\"id\":1}";
            case "ITEM_INVALIDO" -> "[1]";
            case "ACTIVO_TEXTO" -> "[{\"id\":1,\"activo\":\"true\"}]";
            case "FECHA_NUMERO" -> "[{\"id\":1,\"creadoEn\":123}]";
            case "ID_TEXTO" -> "[{\"id\":\"1\"}]";
            default -> "[{\"id\":10,\"codigoUsuario\":\"operador1\",\"nombreCompleto\":\"María José Peña «ñ»\",\"rol\":\"operador\",\"activo\":true,"
                    + "\"creadoEn\":\"2026-07-29T10:15:00\",\"actualizadoEn\":null,\"passwordHash\":\"$2a$11$SECRETO\",\"token\":\"eyJSECRETO\"},"
                    + "{\"Id\":20,\"CODIGOUSUARIO\":\"admin1\",\"NombreCompleto\":\"<b>Admin</b>\",\"rol\":null}]";
        };
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":" + data + ",\"errors\":null}");
    }

    private final java.util.List<String> peticionesLaboratorio = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** "<método> <ruta>[?<query cruda>]" recibidos en api/muestra-laboratorio*. */
    public java.util.List<String> peticionesLaboratorio() {
        return java.util.List.copyOf(peticionesLaboratorio);
    }

    /**
     * Laboratorio - Muestras (lecturas). Detalle 404 → "Muestra no encontrada"; registro-produccion con
     * np=ERROR → 400. Adjunto por id: 1 PDF, 2 PNG, 3 DOCX, 4 HTML declarado PDF, 404, 600 vacío,
     * 700 excesivo (> 10 MB), 800 corrupto, 900 nombre malicioso.
     */
    private void muestraLaboratorio(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        peticionesLaboratorio.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            responder(ex, 200, "{\"success\":true,\"message\":\"NO DEBERIA LLEGAR\",\"data\":null,\"errors\":null}");
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Filtro de fecha inválido"));
            return;
        }
        String ok = "{\"success\":true,\"message\":null,\"data\":";
        String fin = ",\"errors\":null}";
        String q = query == null ? "" : query;
        String base = "/api/muestra-laboratorio";
        switch (path) {
            case "/api/muestra-laboratorio" -> {
                responder(ex, 200, ok + "[{\"id\":501,\"np\":\"NP-100\",\"cliente\":\"Viña Ñandú «1»\",\"estado\":\"EnAnalisis\",\"q\":\"" + q
                        + "\",\"usuarioDelToken\":" + sub + "}]" + fin);
                return;
            }
            case "/api/muestra-laboratorio/catalogos" -> {
                responder(ex, 200, ok + "{\"maquinas\":[{\"id\":3,\"nombre\":\"Corrugadora ñ\"}],\"usuarioDelToken\":" + sub + "}" + fin);
                return;
            }
            case "/api/muestra-laboratorio/indicadores" -> {
                responder(ex, 200, ok + "{\"total\":12,\"enAnalisis\":4,\"usuarioDelToken\":" + sub + "}" + fin);
                return;
            }
            case "/api/muestra-laboratorio/metodos" -> {
                responder(ex, 200, ok + "[{\"id\":1,\"tipoEnsayo\":\"HUMEDAD\",\"nombre\":\"Estufa\",\"activo\":true}]" + fin);
                return;
            }
            case "/api/muestra-laboratorio/especificaciones" -> {
                responder(ex, 200, ok + "[{\"id\":2,\"tipoEnsayo\":\"ECT\",\"minimo\":5.5}]" + fin);
                return;
            }
            case "/api/muestra-laboratorio/bobina-historial" -> {
                responder(ex, 200, ok + "[{\"muestraId\":7,\"q\":\"" + q + "\"}]" + fin);
                return;
            }
            case "/api/muestra-laboratorio/registro-produccion" -> {
                if (q.contains("np=ERROR")) {
                    responder(ex, 400, fallo("No hay controles para esa NP"));
                } else {
                    responder(ex, 200, ok + "[{\"control\":\"Humedad\",\"q\":\"" + q + "\"}]" + fin);
                }
                return;
            }
            default -> { }
        }
        java.util.regex.Matcher adj = java.util.regex.Pattern.compile("^/api/muestra-laboratorio/adjunto/(\\d+)$").matcher(path);
        if (adj.matches()) {
            int id = Integer.parseInt(adj.group(1));
            String nombre = "adjunto_" + id;
            String mime = "application/pdf";
            String base64 = "";
            switch (id) {
                case 404 -> { responder(ex, 404, fallo("Adjunto no encontrado")); return; }
                case 1 -> { nombre = "informe ñ.pdf"; base64 = Base64.getEncoder().encodeToString(pdfMinimo(1)); }
                case 2 -> { nombre = "foto.png"; mime = "image/png"; base64 = Base64.getEncoder().encodeToString(pngMinimo()); }
                case 3 -> { nombre = "certificado.docx"; mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    base64 = Base64.getEncoder().encodeToString(new byte[] {0x50, 0x4b, 0x03, 0x04, 0x14, 0, 0, 0, 1, 2, 3}); }
                case 4 -> { nombre = "falso.pdf"; base64 = Base64.getEncoder().encodeToString("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)); }
                case 600 -> nombre = "vacio.pdf";
                case 700 -> base64 = "A".repeat(((10 * 1024 * 1024 + 2) / 3) * 4 + 4);
                case 800 -> base64 = "%%%no-base64%%%";
                case 900 -> { nombre = "..\\..\\evil<>.docx"; mime = "application/msword"; base64 = Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8)); }
                default -> base64 = Base64.getEncoder().encodeToString(pdfMinimo(id));
            }
            responder(ex, 200, ok + "{\"nombreArchivo\":\"" + nombre.replace("\\", "\\\\") + "\",\"tipoMime\":\"" + mime
                    + "\",\"contenidoBase64\":\"" + base64 + "\"}" + fin);
            return;
        }
        java.util.regex.Matcher det = java.util.regex.Pattern.compile("^" + base + "/(-?\\d+)$").matcher(path);
        if (det.matches()) {
            int id = Integer.parseInt(det.group(1));
            if (id == 404 || id <= 0) {
                responder(ex, 404, fallo("Muestra no encontrada"));
                return;
            }
            // Fase 3w: 501 → proceso FPS 88001 (número), 503 → "88003" (texto), el resto sin proceso FPS.
            String procesoFps = id == 501 ? "88001" : id == 503 ? "\"88003\"" : "null";
            responder(ex, 200, ok + "{\"id\":" + id + ",\"np\":\"NP-100\",\"ensayos\":[],\"adjuntos\":[{\"id\":1,\"nombreArchivo\":\"informe ñ.pdf\"}],"
                    + "\"idProcesoFps\":" + procesoFps + ",\"usuarioDelToken\":" + sub + "}" + fin);
            return;
        }
        responder(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
    }

    private final java.util.List<String> peticionesTalleres = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    /** Fase 4c: estado de los trabajos (sintéticos 9000-9449 de dataTalleresList + los creados vía POST). */
    private final java.util.Set<Long> trabajosCreados = ConcurrentHashMap.newKeySet();
    private final Map<Long, Integer> versionTrabajo = new ConcurrentHashMap<>();
    private final java.util.Set<Long> trabajosEliminados = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicLong siguienteTrabajo = new java.util.concurrent.atomic.AtomicLong(20000);
    private final java.util.Set<Integer> talleresInactivos = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Integer> procesosInactivos = ConcurrentHashMap.newKeySet();
    private volatile String modoSincronizarFps = "NORMAL";

    /** "<método> <ruta>[?<query cruda>]" recibidos en api/talleres-externos*. */
    public java.util.List<String> peticionesTalleres() {
        return java.util.List.copyOf(peticionesTalleres);
    }

    /** Fase 4c: estado_validacion... versión vigente de un trabajo (sintético o creado), o null si no existe/fue eliminado. */
    public Integer versionTrabajo(long id) {
        if (trabajosEliminados.contains(id) || (id < 9000 || id >= 9450) && !trabajosCreados.contains(id)) {
            return null;
        }
        return versionTrabajo.getOrDefault(id, 1);
    }

    /** Fase 4c: simula que otra persona (Photino u otra sesión) ya actualizó este trabajo (sube su versión). */
    public void avanzarVersionTrabajoPorOtro(long id) {
        versionTrabajo.put(id, versionTrabajo.getOrDefault(id, 1) + 1);
    }

    /** NORMAL | CON_ERRORES | NO_CONFIGURADO. */
    public void modoSincronizarFps(String modo) {
        this.modoSincronizarFps = modo;
    }

    /** Página de trabajos: 450 en total; items de la página pedida (máx. 200 por página, como la API ≤ 500). */
    public static String dataTalleresList(int sub, int page, int pageSize) {
        int total = 450;
        int tam = pageSize < 1 ? 50 : Math.min(pageSize, 500);
        int desde = Math.max(0, (page - 1) * tam);
        int hasta = Math.min(total, desde + tam);
        StringBuilder items = new StringBuilder();
        for (int i = desde; i < hasta; i++) {
            items.append(i > desde ? "," : "").append("{\"id\":").append(9000 + i).append(",\"nv\":\"NV-").append(i)
                    .append("\",\"producto\":\"Caja ñ «").append(i).append("»\",\"tallerExternoNombre\":\"Taller Uno\",\"version\":1}");
        }
        return "{\"items\":[" + items + "],\"totalCount\":" + total + ",\"page\":" + page + ",\"pageSize\":" + tam
                + ",\"usuarioDelToken\":" + sub + "}";
    }

    /** Talleres Externos (lecturas). historial de id 404 → []; id 500 → 500 de la API. */
    private void talleresExternos(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizationRecibidos.add(auth == null ? "" : auth);
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        peticionesTalleres.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
        Integer sub = subDeBearer(auth);
        if (sub == null || revocados.contains(sub)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        String ok = "{\"success\":true,\"message\":null,\"data\":";
        String fin = ",\"errors\":null}";
        if (ex.getRequestMethod().equals("POST") && path.equals("/api/talleres-externos")) {
            crearTrabajo(ex, sub);
            return;
        }
        java.util.regex.Matcher upd = java.util.regex.Pattern.compile("^/api/talleres-externos/(\\d+)$").matcher(path);
        if (ex.getRequestMethod().equals("PUT") && upd.matches()) {
            actualizarTrabajo(ex, Long.parseLong(upd.group(1)), sub);
            return;
        }
        if (ex.getRequestMethod().equals("DELETE") && upd.matches()) {
            eliminarTrabajo(ex, Long.parseLong(upd.group(1)), query);
            return;
        }
        java.util.regex.Matcher catT = java.util.regex.Pattern.compile("^/api/talleres-externos/catalogos/talleres/(\\d+)$").matcher(path);
        if (ex.getRequestMethod().equals("DELETE") && catT.matches()) {
            int id = Integer.parseInt(catT.group(1));
            if (!talleresInactivos.add(id)) {
                responder(ex, 404, fallo("No existe un taller externo activo con id " + id + "."));
            } else {
                responder(ex, 200, ok + "{}" + fin);
            }
            return;
        }
        java.util.regex.Matcher catP = java.util.regex.Pattern.compile("^/api/talleres-externos/catalogos/procesos/(\\d+)$").matcher(path);
        if (ex.getRequestMethod().equals("DELETE") && catP.matches()) {
            int id = Integer.parseInt(catP.group(1));
            if (!procesosInactivos.add(id)) {
                responder(ex, 404, fallo("No existe un proceso externo activo con id " + id + "."));
            } else {
                responder(ex, 200, ok + "{}" + fin);
            }
            return;
        }
        if (ex.getRequestMethod().equals("POST") && path.equals("/api/talleres-externos/sincronizar-fps")) {
            ex.getRequestBody().readAllBytes();
            switch (modoSincronizarFps) {
                case "NO_CONFIGURADO" -> responder(ex, 400,
                        fallo("La integración con FPS no está configurada (revisa la sección \\\"FpsApi\\\" en appsettings)."));
                case "CON_ERRORES" -> responder(ex, 200, ok + "{\"trabajosRevisados\":3,\"trabajosActualizados\":1,"
                        + "\"liberacionesNuevas\":2,\"errores\":[\"NV NV-1 ítem X: fps-api no respondió\"]}" + fin);
                default -> responder(ex, 200,
                        ok + "{\"trabajosRevisados\":3,\"trabajosActualizados\":2,\"liberacionesNuevas\":5,\"errores\":[]}" + fin);
            }
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            responder(ex, 200, "{\"success\":true,\"message\":\"NO DEBERIA LLEGAR\",\"data\":null,\"errors\":null}");
            return;
        }
        if (modoDashboard == ModoDashboard.ERROR_NEGOCIO) {
            responder(ex, 400, fallo("Filtro de fecha inválido"));
            return;
        }
        if (path.equals("/api/talleres-externos")) {
            java.util.regex.Matcher p = java.util.regex.Pattern.compile("page=(-?\\d+)&pageSize=(-?\\d+)").matcher(String.valueOf(query));
            int page = p.find() ? Integer.parseInt(p.group(1)) : 1;
            int pageSize = p.find(0) ? Integer.parseInt(p.group(2)) : 50;
            responder(ex, 200, ok + dataTalleresList(sub, page, pageSize) + fin);
            return;
        }
        if (path.equals("/api/talleres-externos/catalogos")) {
            responder(ex, 200, ok + "{\"talleres\":[{\"id\":1,\"nombre\":\"Taller Uno\"}],\"procesos\":[{\"id\":2,\"nombre\":\"Troquelado ñ\"}],"
                    + "\"usuarioDelToken\":" + sub + "}" + fin);
            return;
        }
        java.util.regex.Matcher h = java.util.regex.Pattern.compile("^/api/talleres-externos/(\\d+)/historial-liberaciones$").matcher(path);
        if (h.matches()) {
            long id = Long.parseLong(h.group(1));
            if (id == 500) {
                responder(ex, 500, "{\"title\":\"error interno\"}");
            } else if (id == 404) {
                responder(ex, 200, ok + "[]" + fin);
            } else {
                responder(ex, 200, ok + "[{\"folioFps\":\"F-1\",\"fechaLiberacion\":\"2026-09-20T10:00:00\",\"cantidad\":1500.5}]" + fin);
            }
            return;
        }
        responder(ex, 404, "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
    }

    private boolean existeTrabajo(long id) {
        boolean sintetico = id >= 9000 && id < 9450;
        return (sintetico || trabajosCreados.contains(id)) && !trabajosEliminados.contains(id);
    }

    /** Como TalleresExternosService.ValidarCampos: NV/Ítem/Producto obligatorios, prioridad/estado de los selects. */
    private static String validarCamposTrabajo(JsonNode b) {
        java.util.List<String> errores = new java.util.ArrayList<>();
        if (b.path("nv").asString("").isBlank()) {
            errores.add("NV es obligatorio.");
        }
        if (b.path("item").asString("").isBlank()) {
            errores.add("Ítem es obligatorio.");
        }
        if (b.path("producto").asString("").isBlank()) {
            errores.add("Producto no puede estar vacío.");
        }
        String prioridad = b.path("prioridad").asString("MEDIA");
        if (!java.util.Set.of("BAJA", "MEDIA", "ALTA").contains(prioridad)) {
            errores.add("Prioridad inválida: '" + prioridad + "'. Valores permitidos: BAJA, MEDIA, ALTA.");
        }
        String estado = b.path("estado").asString("PENDIENTE_ASIGNACION");
        if (!java.util.Set.of("PENDIENTE_ASIGNACION", "ASIGNADO", "EN_PROCESO", "ENTREGADO", "ANULADO").contains(estado)) {
            errores.add("Estado inválido: '" + estado + "'. Valores permitidos: PENDIENTE_ASIGNACION, ASIGNADO, EN_PROCESO, "
                    + "ENTREGADO, ANULADO.");
        }
        return errores.isEmpty() ? null : String.join(" ", errores);
    }

    private void crearTrabajo(HttpExchange ex, int sub) throws IOException {
        JsonNode b = mapper.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String error = validarCamposTrabajo(b);
        if (error != null) {
            responder(ex, 400, fallo(error));
            return;
        }
        long id = siguienteTrabajo.getAndIncrement();
        trabajosCreados.add(id);
        versionTrabajo.put(id, 1);
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"id\":" + id + ",\"nv\":"
                + mapper.writeValueAsString(b.path("nv").asString("")) + ",\"version\":1,\"usuarioDelToken\":" + sub + "},\"errors\":null}");
    }

    private void actualizarTrabajo(HttpExchange ex, long id, int sub) throws IOException {
        JsonNode b = mapper.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (!existeTrabajo(id)) {
            responder(ex, 404, fallo("No existe un trabajo con id " + id + "."));
            return;
        }
        String error = validarCamposTrabajo(b);
        if (error != null) {
            responder(ex, 400, fallo(error));
            return;
        }
        int versionActual = versionTrabajo.getOrDefault(id, 1);
        int versionPedida = b.path("version").asInt(0);
        if (versionPedida != versionActual) {
            responder(ex, 409, fallo("El registro fue modificado por otro usuario. Vuelve a cargarlo antes de guardar."));
            return;
        }
        versionTrabajo.put(id, versionActual + 1);
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{\"id\":" + id + ",\"version\":" + (versionActual + 1)
                + ",\"usuarioDelToken\":" + sub + "},\"errors\":null}");
    }

    private void eliminarTrabajo(HttpExchange ex, long id, String query) throws IOException {
        if (!existeTrabajo(id)) {
            responder(ex, 404, fallo("No existe un trabajo con id " + id + "."));
            return;
        }
        int versionActual = versionTrabajo.getOrDefault(id, 1);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|&)version=(-?\\d+)").matcher(query == null ? "" : query);
        int versionPedida = m.find() ? Integer.parseInt(m.group(1)) : 0;
        if (versionPedida != versionActual) {
            responder(ex, 409, fallo("El registro fue modificado por otro usuario. Vuelve a cargarlo antes de guardar."));
            return;
        }
        trabajosEliminados.add(id);
        versionTrabajo.put(id, versionActual + 1);
        responder(ex, 200, "{\"success\":true,\"message\":null,\"data\":{},\"errors\":null}");
    }

    private Integer subDeBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String[] partes = authorization.substring(7).split("\\.");
        if (partes.length != 3) {
            return null;
        }
        try {
            JsonNode payload = mapper.readTree(new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8));
            int sub = Integer.parseInt(payload.path("sub").asString(""));
            return partes[2].equals(firmaDeToken(sub)) ? sub : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void agregar(Usuario u) {
        usuarios.put(u.codigo().toLowerCase(), u);
    }

    public int llamadas() {
        return llamadas.get();
    }

    public void expiracionToken(Supplier<Instant> exp) {
        this.expiracionToken = exp;
    }

    public void caida(boolean valor) {
        this.caida = valor;
    }

    /** Token que emitiría la API para un usuario (para comprobar que nunca llega al navegador/logs). */
    public static String firmaDeToken(int userId) {
        return "firma-secreta-del-token-" + userId;
    }

    /** Cabeceras de encuadre del último login recibido: "Content-Length|Transfer-Encoding". */
    public String ultimoEncuadre() {
        return ultimoEncuadre;
    }

    private volatile String ultimoEncuadre;

    private void login(HttpExchange ex) throws IOException {
        llamadas.incrementAndGet();
        ultimoEncuadre = ex.getRequestHeaders().getFirst("Content-Length") + "|"
                + ex.getRequestHeaders().getFirst("Transfer-Encoding");
        if (caida) {
            responder(ex, 500, "{\"title\":\"error interno\"}");
            return;
        }
        JsonNode body = mapper.readTree(ex.getRequestBody().readAllBytes());
        String codigo = body.path("codigoUsuario").asString("");
        String password = body.path("password").asString("");
        if (codigo.isBlank() || password.isBlank()) {
            responder(ex, 400, fallo("Código de usuario y contraseña son requeridos."));
            return;
        }
        Usuario u = usuarios.get(codigo.toLowerCase());
        if (u == null) {
            responder(ex, 401, fallo("Usuario no existe"));
            return;
        }
        if (!u.activo()) {
            responder(ex, 401, fallo("Usuario desactivado"));
            return;
        }
        if (!u.password().equals(password)) {
            responder(ex, 401, fallo("Contraseña incorrecta"));
            return;
        }
        String token = jwt(u);
        responder(ex, 200, "{\"success\":true,\"message\":\"Login correcto\",\"data\":{\"token\":\"" + token
                + "\",\"userId\":" + u.id() + ",\"codigoUsuario\":\"" + u.codigo() + "\",\"nombreCompleto\":\""
                + u.nombre() + "\",\"rol\":\"" + u.rol() + "\"},\"errors\":null}");
    }

    private String jwt(Usuario u) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(("{\"sub\":\"" + u.id() + "\",\"role\":\"" + u.rol() + "\",\"exp\":"
                + expiracionToken.get().getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + firmaDeToken(u.id());
    }

    private static String fallo(String mensaje) {
        return "{\"success\":false,\"message\":\"" + mensaje + "\",\"data\":null,\"errors\":null}";
    }

    private static void responder(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
