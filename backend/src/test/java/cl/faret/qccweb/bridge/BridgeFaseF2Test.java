package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeFaretApi;
import cl.faret.qccweb.auth.FakeFaretApi.Escritura;
import cl.faret.qccweb.auth.FakeInnpackApi;
import cl.faret.qccweb.auth.SessionUser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fase F2 - ESCRITURAS de Talleres Externos FARET (Photino 1.8.15, 9e1b556, FaretHandler.cs): faret.talleresExternos.{create, update,
 * eliminar} y faret.talleresExternos.catalogos.{eliminarTaller, eliminarProceso} contra QualityControlFaret.Api con el Bearer del
 * usuario de la sesión. TODA la API es un fake en localhost (FakeFaretApi); nunca se escribe en una API real.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFaseF2Test {

    private static final FakeFaretApi QC = new FakeFaretApi(Clock.systemUTC());
    private static final FakeInnpackApi INNPACK = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveFaretF2#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String BASE = "/api/talleres-externos";
    private static final String CREAR = "faret.talleresExternos.create";
    private static final String ACTUALIZAR = "faret.talleresExternos.update";
    private static final String ELIMINAR = "faret.talleresExternos.eliminar";
    private static final String ELIMINAR_TALLER = "faret.talleresExternos.catalogos.eliminarTaller";
    private static final String ELIMINAR_PROCESO = "faret.talleresExternos.catalogos.eliminarProceso";
    private static final List<String> TRABAJO = List.of(CREAR, ACTUALIZAR, ELIMINAR);
    private static final List<String> CATALOGO = List.of(ELIMINAR_TALLER, ELIMINAR_PROCESO);

    static {
        QC.agregar(new FakeFaretApi.Usuario(10, "admin", "admin@faret.cl", PASS, "Admin Faret", List.of("ADMIN")));
        QC.agregar(new FakeFaretApi.Usuario(20, "ana", "ana@faret.cl", PASS, "Ana Calidad", List.of("CALIDAD")));
        QC.agregar(new FakeFaretApi.Usuario(30, "clara", null, PASS, "Clara Consulta", List.of("CONSULTA")));
        QC.agregar(new FakeFaretApi.Usuario(50, "tecnico", "tecnico@faret.cl", PASS, "Tecnico TI", List.of("ADMIN_TI")));
        QC.agregar(new FakeFaretApi.Usuario(60, "ines", "ines@faret.cl", PASS, "Ines Inspector", List.of("INSPECTOR")));
        INNPACK.agregar(new FakeInnpackApi.Usuario(41, "adminti1", PASS, "Admin TI Innpack", "admin_ti", true));
        INNPACK.agregar(new FakeInnpackApi.Usuario(40, "operador1", PASS, "Operador Uno", "operador", true));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.faret-api-base-url", QC::baseUrl);
        registry.add("qcc.web.auth.innpack-api-base-url", INNPACK::baseUrl);
        registry.add("qcc.web.www-dir", WWW::toString);
        registry.add("qcc.web.manifest-file", () -> WWW.resolve("no-existe.json").toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActionPolicy policy;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reiniciar() {
        QC.limpiarLecturas();
    }

    @AfterAll
    static void cerrar() {
        QC.close();
        INNPACK.close();
    }

    // ------------------------------------------------------------------------------- create

    @Test
    void crearEnviaElCuerpoExactoDePhotinoConElBearerDeLaSesion() throws Exception {
        MockHttpSession ana = login("ana");
        Map<String, Object> completo = new LinkedHashMap<>();
        completo.put("nv", "  NP-22421 ");
        completo.put("producto", "Caja 30x20");
        completo.put("codigoProducto", "C-1");
        completo.put("item", "10");
        completo.put("cliente", "ACME");
        completo.put("fechaAsignacion", "2026-10-01");
        completo.put("tallerExternoId", 3);
        completo.put("tallerExternoNombre", "Taller Sur");
        completo.put("procesoId", "4");
        completo.put("procesoNombre", "Pegado");
        completo.put("responsableInternoId", 5);
        completo.put("responsableInternoNombre", "Ana");
        completo.put("prioridad", "ALTA");
        completo.put("fechaCompromiso", "2026-10-20");
        completo.put("estado", "ASIGNADO");
        completo.put("cantidadARevisar", 1000.5);
        completo.put("cantidadRevisadaEntregada", "200");
        completo.put("cantidadFaltanteAjusteManual", true);
        completo.put("cantidadFaltanteManual", 10);
        completo.put("cantidadFaltanteJustificacion", "Merma\ncon salto");
        completo.put("precioCotizacion", "$100");
        completo.put("precioTaller", "$90");
        completo.put("observaciones", "Obs\tcon\r\nformato");
        JsonNode json = json(accion(ana, CREAR, completo).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        List<Escritura> e = QC.escrituras();
        assertThat(e).hasSize(1);
        assertThat(e.get(0).metodo()).isEqualTo("POST");
        assertThat(e.get(0).uri()).isEqualTo(BASE);
        assertThat(e.get(0).sub()).isEqualTo(20);
        assertThat(e.get(0).contentType()).startsWith("application/json");
        assertThat(e.get(0).cuerpo()).isEqualTo("{\"nv\":\"NP-22421\",\"producto\":\"Caja 30x20\",\"codigoProducto\":\"C-1\",\"item\":\"10\","
                + "\"cliente\":\"ACME\",\"fechaAsignacion\":\"2026-10-01\",\"tallerExternoId\":3,\"tallerExternoNombre\":\"Taller Sur\","
                + "\"procesoId\":4,\"procesoNombre\":\"Pegado\",\"responsableInternoId\":5,\"responsableInternoNombre\":\"Ana\","
                + "\"prioridad\":\"ALTA\",\"fechaCompromiso\":\"2026-10-20\",\"estado\":\"ASIGNADO\",\"cantidadARevisar\":1000.5,"
                + "\"cantidadRevisadaEntregada\":200,\"cantidadFaltanteAjusteManual\":true,\"cantidadFaltanteManual\":10,"
                + "\"cantidadFaltanteJustificacion\":\"Merma\\ncon salto\",\"precioCotizacion\":\"$100\",\"precioTaller\":\"$90\","
                + "\"observaciones\":\"Obs\\tcon\\r\\nformato\"}");
        // Respuesta reproyectada al TrabajoDto: el id y los campos conocidos; nada más de la API (p. ej. `activo` del eco) llega.
        assertThat(json.get("data").get("id").asInt()).isEqualTo(901);
        assertThat(json.get("data").get("nv").asString()).isEqualTo("NP-22421");
        assertThat(json.get("data").has("activo")).isFalse();
        assertThat(QC.lecturas()).isEmpty();
    }

    @Test
    void crearConElPayloadDelFormularioDePhotinoYDefaults() throws Exception {
        MockHttpSession ana = login("ana");
        // Exactamente lo que arma _guardar() de faret-talleres-externos.controller.js (fechas null, ajuste manual false, textos vacíos).
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("nv", "NV-1");
        ui.put("producto", "Bolsa");
        ui.put("codigoProducto", "");
        ui.put("item", "1");
        ui.put("cliente", "");
        ui.put("fechaAsignacion", null);
        ui.put("tallerExternoNombre", "");
        ui.put("procesoNombre", "");
        ui.put("responsableInternoNombre", "");
        ui.put("prioridad", "MEDIA");
        ui.put("fechaCompromiso", null);
        ui.put("estado", "PENDIENTE_ASIGNACION");
        ui.put("cantidadARevisar", 0);
        ui.put("cantidadRevisadaEntregada", 0);
        ui.put("cantidadFaltanteAjusteManual", false);
        ui.put("precioCotizacion", "");
        ui.put("precioTaller", "");
        ui.put("observaciones", "");
        accion(ana, CREAR, ui).andExpect(jsonPath("$.ok").value(true));
        assertThat(QC.escrituras().get(0).cuerpo()).isEqualTo("{\"nv\":\"NV-1\",\"producto\":\"Bolsa\",\"codigoProducto\":\"\",\"item\":\"1\","
                + "\"cliente\":\"\",\"fechaAsignacion\":null,\"tallerExternoId\":null,\"tallerExternoNombre\":\"\",\"procesoId\":null,"
                + "\"procesoNombre\":\"\",\"responsableInternoId\":null,\"responsableInternoNombre\":\"\",\"prioridad\":\"MEDIA\","
                + "\"fechaCompromiso\":null,\"estado\":\"PENDIENTE_ASIGNACION\",\"cantidadARevisar\":0,\"cantidadRevisadaEntregada\":0,"
                + "\"cantidadFaltanteAjusteManual\":false,\"cantidadFaltanteManual\":null,\"cantidadFaltanteJustificacion\":null,"
                + "\"precioCotizacion\":\"\",\"precioTaller\":\"\",\"observaciones\":\"\"}");
        // Solo lo obligatorio: defaults de Photino (MEDIA, PENDIENTE_ASIGNACION, cantidades 0); precios null (la API los conserva).
        accion(ana, CREAR, Map.of("nv", "N", "producto", "P", "item", "I", "prioridad", "", "estado", "")).andExpect(jsonPath("$.ok").value(true));
        JsonNode b = mapper.readTree(QC.escrituras().get(1).cuerpo());
        assertThat(b.get("prioridad").asString()).isEqualTo("MEDIA");
        assertThat(b.get("estado").asString()).isEqualTo("PENDIENTE_ASIGNACION");
        assertThat(b.get("cantidadARevisar").decimalValue().signum()).isZero();
        assertThat(b.get("precioCotizacion").isNull()).isTrue();
        assertThat(b.get("precioTaller").isNull()).isTrue();
        assertThat(b.has("version")).isFalse();
    }

    @Test
    void lasClavesFueraDeLaListaCerradaSeRechazanSinLlamarALaApi() throws Exception {
        MockHttpSession ana = login("ana");
        Map<String, Object> base = Map.of("nv", "N", "producto", "P", "item", "I");
        for (String extra : List.of("usuarioId", "creadoPor", "creado_por", "autor", "usuario", "empresa", "rol", "actualizadoPor", "eliminado",
                "anuladoPor", "fechaCreacion", "atrasado", "cantidadFaltante", "data", "filas", "motivo", "id", "version")) {
            Map<String, Object> m = new HashMap<>(base);
            m.put(extra, 1);
            accion(ana, CREAR, m).andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value("Campo no permitido: " + extra));
        }
        Map<String, Object> raro = new HashMap<>(base);
        raro.put("<script>alert(1)</script>", 1);
        accion(ana, CREAR, raro).andExpect(jsonPath("$.error").value("Campo no permitido: ?"));
        for (String extra : List.of("usuarioId", "creadoPor", "autor", "motivo")) {
            Map<String, Object> m = new HashMap<>(base);
            m.put("id", 7);
            m.put("version", 2);
            m.put(extra, 1);
            accion(ana, ACTUALIZAR, m).andExpect(jsonPath("$.error").value("Campo no permitido: " + extra));
        }
        accion(ana, ELIMINAR, Map.of("id", 7, "version", 2, "usuarioId", 1)).andExpect(jsonPath("$.error").value("Campo no permitido: usuarioId"));
        accion(ana, ELIMINAR, Map.of("id", 7, "version", 2, "nv", "x")).andExpect(jsonPath("$.error").value("Campo no permitido: nv"));
        accion(ana, ELIMINAR_TALLER, Map.of("id", 7, "nombre", "x")).andExpect(jsonPath("$.error").value("Campo no permitido: nombre"));
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void obligatoriosEnElOrdenDePhotinoYTiposEstrictos() throws Exception {
        MockHttpSession ana = login("ana");
        accion(ana, CREAR, Map.of()).andExpect(jsonPath("$.error").value("Falta NV"));
        accion(ana, CREAR, Map.of("nv", "   ", "producto", "", "item", "")).andExpect(jsonPath("$.error").value("Falta NV"));
        accion(ana, CREAR, Map.of("nv", "N")).andExpect(jsonPath("$.error").value("Falta Producto"));
        accion(ana, CREAR, Map.of("nv", "N", "producto", " ")).andExpect(jsonPath("$.error").value("Falta Producto"));
        accion(ana, CREAR, Map.of("nv", "N", "producto", "P")).andExpect(jsonPath("$.error").value("Falta Ítem"));
        for (Object malo : new Object[] {123, true, List.of("x"), Map.of("a", "b")}) {
            for (String campo : List.of("nv", "producto", "item", "codigoProducto", "cliente", "tallerExternoNombre", "procesoNombre",
                    "responsableInternoNombre", "precioCotizacion", "precioTaller", "observaciones", "cantidadFaltanteJustificacion",
                    "prioridad", "estado", "fechaAsignacion", "fechaCompromiso")) {
                Map<String, Object> m = new HashMap<>(Map.of("nv", "N", "producto", "P", "item", "I"));
                m.put(campo, malo);
                accion(ana, CREAR, m).andExpect(jsonPath("$.error").value("Parámetro inválido."));
            }
        }
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void textosSinControlesNiHtmlNiSustitutosYConLargoDeLaColumna() throws Exception {
        MockHttpSession ana = login("ana");
        Map<String, Integer> largos = new LinkedHashMap<>();
        largos.put("nv", 50);
        largos.put("producto", 255);
        largos.put("item", 20);
        largos.put("codigoProducto", 50);
        largos.put("cliente", 150);
        largos.put("tallerExternoNombre", 150);
        largos.put("procesoNombre", 150);
        largos.put("responsableInternoNombre", 150);
        largos.put("precioCotizacion", 200);
        largos.put("precioTaller", 200);
        largos.put("cantidadFaltanteJustificacion", 500);
        largos.put("observaciones", 2000);
        for (Map.Entry<String, Integer> l : largos.entrySet()) {
            String campo = l.getKey();
            accion(ana, CREAR, valido(campo, "x".repeat(l.getValue() + 1)))
                    .andExpect(jsonPath("$.error").value("El campo " + campo + " supera el máximo de " + l.getValue() + " caracteres."));
            accion(ana, CREAR, valido(campo, "😀".repeat(l.getValue() / 2) + "ab"))
                    .andExpect(jsonPath("$.error").value("El campo " + campo + " supera el máximo de " + l.getValue() + " caracteres."));
            for (String html : List.of("<b>x</b>", "A <img src=x>", "</td>", "<!--x-->", "<svg/onload=1>")) {
                accion(ana, CREAR, valido(campo, html)).andExpect(jsonPath("$.error")
                        .value("El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\")."));
            }
            for (String ctl : List.of("a\u0000b", "\u0007a", "a\u007F", "a\u001B[31m", "a\u0085b", "a b")) {
                accion(ana, CREAR, valido(campo, ctl)).andExpect(jsonPath("$.error").value("El texto contiene caracteres no permitidos."));
            }
        }
        // Una línea: el salto de línea solo se admite en justificación y observaciones.
        for (String campo : List.of("nv", "producto", "item", "cliente", "precioTaller")) {
            accion(ana, CREAR, valido(campo, "a\nb")).andExpect(jsonPath("$.error").value("El texto contiene caracteres no permitidos."));
        }
        for (String campo : List.of("cantidadFaltanteJustificacion", "observaciones")) {
            accion(ana, CREAR, valido(campo, "a\u0000b")).andExpect(jsonPath("$.error").value("El texto contiene caracteres no permitidos."));
        }
        mockMvc.perform(post("/api/v1/bridge").session(ana).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"" + CREAR + "\",\"_modulo\":\"faret-talleres-externos\",\"nv\":\"A \\ud800 B\",\"producto\":\"P\",\"item\":\"I\"}"))
                .andExpect(jsonPath("$.error").value("El texto contiene caracteres no permitidos."));
        assertThat(QC.escrituras()).isEmpty();
        // Justo el máximo pasa, y los signos "<" sueltos (p. ej. "a < b") no son marcado.
        for (Map.Entry<String, Integer> l : largos.entrySet()) {
            accion(ana, CREAR, valido(l.getKey(), "y".repeat(l.getValue()))).andExpect(jsonPath("$.ok").value(true));
        }
        accion(ana, CREAR, valido("observaciones", "a < b y 5 > 3")).andExpect(jsonPath("$.ok").value(true));
        assertThat(QC.escrituras()).hasSize(largos.size() + 1);
    }

    @Test
    void fechasPrioridadEstadoNumerosYBooleanosValidados() throws Exception {
        MockHttpSession ana = login("ana");
        for (String campo : List.of("fechaAsignacion", "fechaCompromiso")) {
            for (String mala : List.of("2026-13-01", "2026-02-30", "01-10-2026", "2026/10/01", "2026-10-1", "20261001", "ayer", "2026-10-01T00:00:00")) {
                accion(ana, CREAR, valido(campo, mala)).andExpect(jsonPath("$.error").value("La fecha no es válida (formato AAAA-MM-DD)."));
            }
            accion(ana, CREAR, valido(campo, "2028-02-29")).andExpect(jsonPath("$.ok").value(true));
            accion(ana, CREAR, valido(campo, "")).andExpect(jsonPath("$.ok").value(true));
        }
        for (String mala : List.of("media", "URGENTE", "BAJA ", "x")) {
            if (!mala.equals("BAJA ")) {
                accion(ana, CREAR, valido("prioridad", mala)).andExpect(jsonPath("$.error").value("Valor no permitido en prioridad."));
            }
        }
        for (String mala : List.of("entregado", "CERRADO", "x")) {
            accion(ana, CREAR, valido("estado", mala)).andExpect(jsonPath("$.error").value("Valor no permitido en estado."));
        }
        for (String ok : List.of("BAJA", "MEDIA", "ALTA")) {
            accion(ana, CREAR, valido("prioridad", ok)).andExpect(jsonPath("$.ok").value(true));
        }
        for (String ok : List.of("PENDIENTE_ASIGNACION", "ASIGNADO", "EN_PROCESO", "ENTREGADO", "ANULADO")) {
            accion(ana, CREAR, valido("estado", ok)).andExpect(jsonPath("$.ok").value(true));
        }
        int antes = QC.escrituras().size();
        for (String campo : List.of("cantidadARevisar", "cantidadRevisadaEntregada", "cantidadFaltanteManual")) {
            for (Object malo : new Object[] {-1, "-0.5", "abc", "1,5x", true, List.of(1), Map.of("a", 1), 1e17, "99999999999999999"}) {
                accion(ana, CREAR, valido(campo, malo)).andExpect(jsonPath("$.ok").value(false));
            }
            accion(ana, CREAR, valido(campo, "12.5")).andExpect(jsonPath("$.ok").value(true));
            accion(ana, CREAR, valido(campo, 7)).andExpect(jsonPath("$.ok").value(true));
        }
        for (Object malo : new Object[] {1, "si", List.of(true), Map.of("a", true)}) {
            accion(ana, CREAR, valido("cantidadFaltanteAjusteManual", malo)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        }
        for (Object ok : new Object[] {true, false, "true", "false"}) {
            accion(ana, CREAR, valido("cantidadFaltanteAjusteManual", ok)).andExpect(jsonPath("$.ok").value(true));
        }
        for (String campo : List.of("tallerExternoId", "procesoId", "responsableInternoId")) {
            for (Object malo : new Object[] {true, List.of(1), Map.of("a", 1), 1.5, "abc", 3_000_000_000L}) {
                accion(ana, CREAR, valido(campo, malo)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
            }
        }
        assertThat(QC.escrituras().size()).isGreaterThan(antes);
        // Ids <= 0 o vacíos viajan como null (Photino: id > 0 ? id : null).
        QC.limpiarLecturas();
        accion(ana, CREAR, valido("tallerExternoId", 0)).andExpect(jsonPath("$.ok").value(true));
        accion(ana, CREAR, valido("procesoId", -4)).andExpect(jsonPath("$.ok").value(true));
        accion(ana, CREAR, valido("responsableInternoId", "")).andExpect(jsonPath("$.ok").value(true));
        for (Escritura e : QC.escrituras()) {
            JsonNode b = mapper.readTree(e.cuerpo());
            assertThat(b.get("tallerExternoId").isNull() && b.get("procesoId").isNull() && b.get("responsableInternoId").isNull()).isTrue();
        }
    }

    // ------------------------------------------------------------------------------- update

    @Test
    void actualizarVaARutaConIdYVersionObligatoriaYConserva409Y404() throws Exception {
        MockHttpSession ana = login("ana");
        Map<String, Object> m = new LinkedHashMap<>(valido("observaciones", "cambio"));
        m.put("id", 7);
        m.put("version", 3);
        JsonNode json = json(accion(ana, ACTUALIZAR, m).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.get("data").get("id").asInt()).isEqualTo(901);
        Escritura e = QC.escrituras().get(0);
        assertThat(e.metodo()).isEqualTo("PUT");
        assertThat(e.uri()).isEqualTo(BASE + "/7");
        assertThat(e.sub()).isEqualTo(20);
        JsonNode cuerpo = mapper.readTree(e.cuerpo());
        assertThat(cuerpo.get("version").asInt()).isEqualTo(3);
        assertThat(cuerpo.has("id")).isFalse();
        assertThat(cuerpo.has("usuarioId")).isFalse();
        assertThat(e.cuerpo()).endsWith(",\"observaciones\":\"cambio\",\"version\":3}");
        // id y version: enteros positivos (el id va a la ruta, así que se valida antes). Orden de Photino: id, NV/Producto/Ítem, version.
        QC.limpiarLecturas();
        for (Object id : new Object[] {0, -1, null, "", "  "}) {
            Map<String, Object> x = new HashMap<>(valido("nv", "N"));
            x.put("id", id);
            x.put("version", 1);
            accion(ana, ACTUALIZAR, x).andExpect(jsonPath("$.error").value("Falta el id del trabajo"));
        }
        for (Object id : new Object[] {true, List.of(1), Map.of("a", 1), 1.5, "7/../9", "abc", 3_000_000_000L}) {
            Map<String, Object> x = new HashMap<>(valido("nv", "N"));
            x.put("id", id);
            x.put("version", 1);
            accion(ana, ACTUALIZAR, x).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        }
        Map<String, Object> sinNv = new HashMap<>(Map.of("id", 7, "version", 1, "producto", "P", "item", "I"));
        accion(ana, ACTUALIZAR, sinNv).andExpect(jsonPath("$.error").value("Falta NV"));
        for (Object version : new Object[] {0, -2, null, ""}) {
            Map<String, Object> x = new HashMap<>(valido("nv", "N"));
            x.put("id", 7);
            x.put("version", version);
            accion(ana, ACTUALIZAR, x).andExpect(jsonPath("$.error").value("Falta 'version' para actualizar (recarga el registro antes de guardar)."));
        }
        Map<String, Object> sinVersion = new HashMap<>(valido("nv", "N"));
        sinVersion.put("id", 7);
        accion(ana, ACTUALIZAR, sinVersion).andExpect(jsonPath("$.error").value("Falta 'version' para actualizar (recarga el registro antes de guardar)."));
        for (Object version : new Object[] {true, List.of(1), Map.of("a", 1), 1.5, "x"}) {
            Map<String, Object> x = new HashMap<>(valido("nv", "N"));
            x.put("id", 7);
            x.put("version", version);
            accion(ana, ACTUALIZAR, x).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        }
        assertThat(QC.escrituras()).isEmpty();
        // 409 (conflicto de versión) y 404: su mensaje llega tal cual, una sola llamada, sin reintento.
        Map<String, Object> ok = new LinkedHashMap<>(valido("nv", "N"));
        ok.put("id", 7);
        ok.put("version", 3);
        QC.respuestaEscritura("PUT " + BASE + "/7", 409, "{\"success\":false,\"message\":\"El registro fue modificado por otro usuario.\",\"data\":null}");
        accion(ana, ACTUALIZAR, ok).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("El registro fue modificado por otro usuario."));
        assertThat(QC.escrituras()).hasSize(1);
        QC.respuestaEscritura("PUT " + BASE + "/7", 404, "{\"success\":false,\"message\":\"No existe un trabajo con id 7.\",\"data\":null}");
        accion(ana, ACTUALIZAR, ok).andExpect(jsonPath("$.error").value("No existe un trabajo con id 7."));
        QC.respuestaEscritura("PUT " + BASE + "/7", 400, "{\"success\":false,\"message\":\"El ajuste manual de cantidad faltante requiere una justificación.\"}");
        accion(ana, ACTUALIZAR, ok).andExpect(jsonPath("$.error").value("El ajuste manual de cantidad faltante requiere una justificación."));
        QC.respuestaEscritura("PUT " + BASE + "/7", 409, null);
        accion(ana, ACTUALIZAR, ok).andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret"));
        QC.respuestaEscritura("PUT " + BASE + "/7", 500, "<html>boom stack trace</html>");
        MvcResult r = accion(ana, ACTUALIZAR, ok).andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret")).andReturn();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("boom").doesNotContain("stack");
        assertThat(QC.escrituras()).hasSize(5);
    }

    // ------------------------------------------------------------------------------- eliminar

    @Test
    void eliminarAnulaPorRutaConVersionYMotivoEscapado() throws Exception {
        MockHttpSession ana = login("ana");
        QC.respuestaEscritura("DELETE " + BASE + "/7", 200, "{\"success\":true,\"message\":\"Trabajo anulado.\",\"data\":null}");
        JsonNode json = json(accion(ana, ELIMINAR, Map.of("id", 7, "version", 4)).andExpect(status().isOk()).andReturn());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.get("data").isNull()).isTrue();
        accion(ana, ELIMINAR, Map.of("id", "7", "version", "4", "motivo", "  Duplicado & más?  ")).andExpect(jsonPath("$.ok").value(true));
        accion(ana, ELIMINAR, Map.of("id", 7, "version", 4, "motivo", "   ")).andExpect(jsonPath("$.ok").value(true));
        List<Escritura> e = QC.escrituras();
        assertThat(e).hasSize(3);
        assertThat(e.get(0).metodo()).isEqualTo("DELETE");
        assertThat(e.get(0).uri()).isEqualTo(BASE + "/7?version=4");
        assertThat(e.get(0).sub()).isEqualTo(20);
        assertThat(e.get(1).uri()).isEqualTo(BASE + "/7?version=4&motivo=Duplicado%20%26%20m%C3%A1s%3F");
        assertThat(e.get(2).uri()).isEqualTo(BASE + "/7?version=4");
    }

    @Test
    void eliminarValidaIdVersionYMotivoAntesDeLaRuta() throws Exception {
        MockHttpSession ana = login("ana");
        for (Object id : new Object[] {0, -3, null, ""}) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", id);
            m.put("version", 1);
            accion(ana, ELIMINAR, m).andExpect(jsonPath("$.error").value("Falta el id del trabajo"));
        }
        accion(ana, ELIMINAR, Map.of("version", 1)).andExpect(jsonPath("$.error").value("Falta el id del trabajo"));
        for (Object id : new Object[] {"7?version=1&x=", "../7", true, List.of(1), Map.of("a", 1), 1.5}) {
            accion(ana, ELIMINAR, Map.of("id", id, "version", 1)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        }
        for (Object version : new Object[] {0, -1, ""}) {
            accion(ana, ELIMINAR, Map.of("id", 7, "version", version)).andExpect(jsonPath("$.error")
                    .value("Falta 'version' para anular (recarga el registro antes de anular)."));
        }
        accion(ana, ELIMINAR, Map.of("id", 7)).andExpect(jsonPath("$.error").value("Falta 'version' para anular (recarga el registro antes de anular)."));
        for (Object version : new Object[] {"4&motivo=x", true, 2.5}) {
            accion(ana, ELIMINAR, Map.of("id", 7, "version", version)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        }
        for (Object motivo : new Object[] {5, true, List.of("x"), Map.of("a", "b")}) {
            accion(ana, ELIMINAR, Map.of("id", 7, "version", 1, "motivo", motivo)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        }
        accion(ana, ELIMINAR, Map.of("id", 7, "version", 1, "motivo", "<b>x</b>")).andExpect(jsonPath("$.error")
                .value("El texto no puede contener etiquetas HTML (por ejemplo \"<b>\" o \"<script>\")."));
        accion(ana, ELIMINAR, Map.of("id", 7, "version", 1, "motivo", "a\nb")).andExpect(jsonPath("$.error").value("El texto contiene caracteres no permitidos."));
        accion(ana, ELIMINAR, Map.of("id", 7, "version", 1, "motivo", "x".repeat(1001))).andExpect(jsonPath("$.error")
                .value("El campo motivo supera el máximo de 1000 caracteres."));
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void eliminar409Y404SePropaganConSuMensaje() throws Exception {
        MockHttpSession ana = login("ana");
        QC.respuestaEscritura("DELETE " + BASE + "/9", 409, "{\"success\":false,\"message\":\"El registro fue modificado por otro usuario.\",\"data\":null}");
        accion(ana, ELIMINAR, Map.of("id", 9, "version", 1)).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("El registro fue modificado por otro usuario."));
        QC.respuestaEscritura("DELETE " + BASE + "/9", 404, "{\"success\":false,\"message\":\"No existe un trabajo con id 9.\"}");
        accion(ana, ELIMINAR, Map.of("id", 9, "version", 1)).andExpect(jsonPath("$.error").value("No existe un trabajo con id 9."));
        assertThat(QC.escrituras()).hasSize(2);
    }

    // ------------------------------------------------------------------------------- catálogos

    @Test
    void eliminarTallerYProcesoVanASuRutaConElIdValidado() throws Exception {
        MockHttpSession ana = login("ana");
        QC.respuestaEscritura("DELETE " + BASE + "/catalogos/talleres/12", 200, "{\"success\":true,\"message\":\"Taller externo eliminado.\",\"data\":null}");
        QC.respuestaEscritura("DELETE " + BASE + "/catalogos/procesos/13", 200, "{\"success\":true,\"message\":\"Proceso externo eliminado.\",\"data\":null}");
        accion(ana, ELIMINAR_TALLER, Map.of("id", 12)).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data").doesNotExist());
        accion(ana, ELIMINAR_PROCESO, Map.of("id", "13")).andExpect(jsonPath("$.ok").value(true));
        assertThat(QC.escrituras()).hasSize(2);
        assertThat(QC.escrituras().get(0).metodo()).isEqualTo("DELETE");
        assertThat(QC.escrituras().get(0).uri()).isEqualTo(BASE + "/catalogos/talleres/12");
        assertThat(QC.escrituras().get(0).sub()).isEqualTo(20);
        assertThat(QC.escrituras().get(0).cuerpo()).isEmpty();
        assertThat(QC.escrituras().get(1).uri()).isEqualTo(BASE + "/catalogos/procesos/13");
        QC.limpiarLecturas();
        for (Object id : new Object[] {0, -1, null, ""}) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", id);
            accion(ana, ELIMINAR_TALLER, m).andExpect(jsonPath("$.error").value("Falta el id del taller"));
            accion(ana, ELIMINAR_PROCESO, m).andExpect(jsonPath("$.error").value("Falta el id del proceso"));
        }
        accion(ana, ELIMINAR_TALLER, Map.of()).andExpect(jsonPath("$.error").value("Falta el id del taller"));
        for (Object id : new Object[] {"12/../x", "1?a=b", true, List.of(1), Map.of("a", 1), 1.5, 3_000_000_000L}) {
            accion(ana, ELIMINAR_TALLER, Map.of("id", id)).andExpect(jsonPath("$.error").value("Parámetro inválido."));
        }
        assertThat(QC.escrituras()).isEmpty();
        QC.respuestaEscritura("DELETE " + BASE + "/catalogos/talleres/99", 404, "{\"success\":false,\"message\":\"No existe un taller externo activo con id 99.\"}");
        accion(ana, ELIMINAR_TALLER, Map.of("id", 99)).andExpect(jsonPath("$.error").value("No existe un taller externo activo con id 99."));
        QC.respuestaEscritura("DELETE " + BASE + "/catalogos/procesos/99", 403, "{\"success\":false,\"message\":\"Sin permiso\"}");
        accion(ana, ELIMINAR_PROCESO, Map.of("id", 99)).andExpect(jsonPath("$.error").value("Sin permiso"));
    }

    // ------------------------------------------------------------------------------- roles, empresa, módulo

    @Test
    void rolesEmpresaYAuditoriaDeLaPolitica() {
        for (String a : TRABAJO) {
            assertThat(roles(a)).as(a).containsExactly("admin", "admin_ti", "calidad", "consulta", "inspector");
            for (String rol : List.of("ADMIN", "ADMIN_TI", "CALIDAD", "CONSULTA", "INSPECTOR")) {
                assertThat(policy.evaluar(a, usuario("FARET", rol))).as(a + " " + rol).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            }
            assertThat(policy.evaluar(a, usuario("FARET", "operador"))).as(a).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
        }
        for (String a : CATALOGO) {
            assertThat(roles(a)).as(a).containsExactly("admin", "admin_ti", "calidad");
            for (String rol : List.of("ADMIN", "ADMIN_TI", "CALIDAD")) {
                assertThat(policy.evaluar(a, usuario("FARET", rol))).as(a + " " + rol).isInstanceOf(ActionPolicy.Decision.Permitida.class);
            }
            for (String rol : List.of("CONSULTA", "INSPECTOR", "operador")) {
                assertThat(policy.evaluar(a, usuario("FARET", rol))).as(a + " " + rol).isEqualTo(new ActionPolicy.Decision.Denegada("ROL_NO_PERMITIDO"));
            }
        }
        for (String a : concat(TRABAJO, CATALOGO)) {
            for (String rol : List.of("admin", "admin_ti", "operador")) {
                assertThat(policy.evaluar(a, usuario("INNPACK", rol))).as(a + " " + rol).isEqualTo(new ActionPolicy.Decision.Denegada("EMPRESA_NO_PERMITIDA"));
            }
            Map<String, Object> d = policy.describir().stream().filter(x -> x.get("accion").equals(a)).findFirst().orElseThrow();
            assertThat(d.get("escritura")).isEqualTo(true);
            assertThat(d.get("empresas")).isEqualTo(List.of("FARET"));
            // Sin identidad del navegador: el autor lo pone la API desde el sub del JWT.
            assertThat(d.get("identidad")).isEqualTo(Map.of());
            assertThat(PermisosModulo.esLectura(a)).as(a).isFalse();
        }
    }

    @Test
    void rolEmpresaYModuloCruzadosNoLlamanALaApi() throws Exception {
        MockHttpSession clara = login("clara");
        MockHttpSession ines = login("ines");
        MockHttpSession tecnico = login("tecnico");
        // CONSULTA e INSPECTOR crean/editan/anulan trabajos (EDITAR en talleres en Photino) pero no tocan el catálogo.
        accion(clara, CREAR, valido("nv", "N")).andExpect(jsonPath("$.ok").value(true));
        accion(ines, CREAR, valido("nv", "N")).andExpect(jsonPath("$.ok").value(true));
        accion(tecnico, CREAR, valido("nv", "N")).andExpect(jsonPath("$.ok").value(true));
        assertThat(QC.escrituras().stream().map(Escritura::sub)).containsExactly(30, 60, 50);
        QC.limpiarLecturas();
        for (String a : CATALOGO) {
            accion(clara, a, Map.of("id", 1)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
            accion(ines, a, Map.of("id", 1)).andExpect(status().isForbidden());
            accion(tecnico, a, Map.of("id", 1)).andExpect(jsonPath("$.ok").value(true));
        }
        assertThat(QC.escrituras()).hasSize(2);
        QC.limpiarLecturas();
        // Una sesión INNPACK (incluso admin_ti) nunca llega a las acciones FARET.
        MockHttpSession operadorInnpack = loginInnpack("operador1");
        MockHttpSession adminTiInnpack = loginInnpack("adminti1");
        for (String a : concat(TRABAJO, CATALOGO)) {
            for (MockHttpSession s : List.of(operadorInnpack, adminTiInnpack)) {
                accion(s, a, Map.of("id", 1, "version", 1, "nv", "N", "producto", "P", "item", "I")).andExpect(status().isForbidden());
            }
        }
        // Permisos por módulo de Photino: faret-talleres-externos con EDITAR; otro módulo o sin módulo se rechaza.
        MockHttpSession ana = login("ana");
        for (String modulo : List.of("faret-nc", "faret-data", "faret", "talleres-externos")) {
            mockMvc.perform(post("/api/v1/bridge").session(ana).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"" + CREAR + "\",\"_modulo\":\"" + modulo + "\",\"nv\":\"N\",\"producto\":\"P\",\"item\":\"I\"}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/v1/bridge").session(ana).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"" + ELIMINAR + "\",\"id\":1,\"version\":1}")).andExpect(status().isForbidden());
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void sinSesionOSinCsrfNoSeEscribe() throws Exception {
        String cuerpo = "{\"action\":\"" + CREAR + "\",\"_modulo\":\"faret-talleres-externos\",\"nv\":\"N\",\"producto\":\"P\",\"item\":\"I\"}";
        mockMvc.perform(post("/api/v1/bridge").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isUnauthorized());
        MockHttpSession ana = login("ana");
        for (String a : concat(TRABAJO, CATALOGO)) {
            mockMvc.perform(post("/api/v1/bridge").session(ana).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"" + a + "\",\"_modulo\":\"faret-talleres-externos\",\"id\":1,\"version\":1}")).andExpect(status().isForbidden());
        }
        assertThat(QC.escrituras()).isEmpty();
    }

    @Test
    void accionesNoHabilitadasSiguenDenegadas() throws Exception {
        MockHttpSession admin = login("admin");
        for (String a : List.of("faret.talleresExternos.catalogos.crearTaller", "faret.talleresExternos.catalogos.crearProceso",
                "faret.talleresExternos.lote", "faret.talleresExternos.get", "faret.talleresExternos.anular", "faret.talleresExternos.restaurar",
                "faret.talleresExternos.catalogos.editarTaller", "faret.talleresExternos.sincronizarFps")) {
            assertThat(policy.accionesRegistradas()).as(a).doesNotContain(a);
            accion(admin, a, Map.of("id", 1, "nombre", "X")).andExpect(status().isForbidden());
        }
        assertThat(QC.escrituras()).isEmpty();
    }

    // ------------------------------------------------------------------------------- sesiones

    @Test
    void unauthorizedDeLaApiInvalidaSoloEsaSesion() throws Exception {
        MockHttpSession a = login("ana");
        MockHttpSession b = login("admin");
        QC.rechazarLecturas(true);
        accion(a, CREAR, valido("nv", "N")).andExpect(status().isUnauthorized());
        assertThat(a.isInvalid()).isTrue();
        QC.rechazarLecturas(false);
        assertThat(b.isInvalid()).isFalse();
        accion(b, CREAR, valido("nv", "N")).andExpect(jsonPath("$.ok").value(true));
        QC.rechazarLecturas(true);
        MockHttpSession c = login("tecnico");
        accion(c, ELIMINAR, Map.of("id", 1, "version", 1)).andExpect(status().isUnauthorized());
        assertThat(c.isInvalid()).isTrue();
        assertThat(b.isInvalid()).isFalse();
        assertThat(QC.escrituras().get(0).sub()).isEqualTo(20);
        assertThat(QC.escrituras().get(1).sub()).isEqualTo(10);
    }

    @Test
    void dosSesionesCadaEscrituraConSuPropioToken() throws Exception {
        MockHttpSession ana = login("ana");
        MockHttpSession admin = login("admin");
        for (int i = 0; i < 6; i++) {
            accion(i % 2 == 0 ? ana : admin, CREAR, valido("nv", "NV-" + i)).andExpect(jsonPath("$.ok").value(true));
        }
        for (int i = 0; i < 6; i++) {
            assertThat(QC.escrituras().get(i).sub()).isEqualTo(i % 2 == 0 ? 20 : 10);
            assertThat(mapper.readTree(QC.escrituras().get(i).cuerpo()).get("nv").asString()).isEqualTo("NV-" + i);
        }
    }

    @Test
    void respuestaDeLaApiSeReproyectaYLosErroresInternosSonGenericos() throws Exception {
        MockHttpSession ana = login("ana");
        QC.respuestaEscritura("POST " + BASE, 201, "{\"success\":true,\"data\":{\"id\":55,\"nv\":\"N\",\"version\":1,\"hash\":\"SECRETO\","
                + "\"password\":\"x\",\"anidado\":{\"a\":1},\"lista\":[1],\"atrasado\":false,\"creadoPorNombre\":\"Ana\"}}");
        MvcResult r = accion(ana, CREAR, valido("nv", "N")).andExpect(jsonPath("$.ok").value(true)).andReturn();
        assertThat(json(r).get("data").toString()).isEqualTo("{\"id\":55,\"nv\":\"N\",\"version\":1,\"creadoPorNombre\":\"Ana\",\"atrasado\":false}");
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SECRETO").doesNotContain("password");
        QC.respuestaEscritura("POST " + BASE, 500, "<html>boom stack trace</html>");
        accion(ana, CREAR, valido("nv", "N")).andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret"));
        QC.respuestaEscritura("POST " + BASE, 201, "{\"algo\":1}");
        accion(ana, CREAR, valido("nv", "N")).andExpect(jsonPath("$.error").value("Error al comunicarse con la API Faret"));
        QC.respuestaEscritura("POST " + BASE, 400, "{\"success\":false,\"message\":\"NV es obligatorio.\"}");
        accion(ana, CREAR, valido("nv", "N")).andExpect(jsonPath("$.error").value("NV es obligatorio."));
        assertThat(QC.escrituras()).hasSize(4);
    }

    // ------------------------------------------------------------------------------- auditoría

    @Test
    void auditoriaConRecursoEIdSinContenido(CapturedOutput salida) throws Exception {
        MockHttpSession ana = login("ana");
        accion(ana, CREAR, valido("observaciones", "Dato 12.345.678-9 sensible")).andExpect(jsonPath("$.ok").value(true));
        Map<String, Object> up = new LinkedHashMap<>(valido("observaciones", "Otro secreto"));
        up.put("id", 7);
        up.put("version", 2);
        accion(ana, ACTUALIZAR, up).andExpect(jsonPath("$.ok").value(true));
        QC.respuestaEscritura("DELETE " + BASE + "/7", 200, "{\"success\":true,\"data\":null}");
        accion(ana, ELIMINAR, Map.of("id", 7, "version", 2, "motivo", "Motivo sensible")).andExpect(jsonPath("$.ok").value(true));
        QC.respuestaEscritura("DELETE " + BASE + "/catalogos/talleres/12", 200, "{\"success\":true,\"data\":null}");
        accion(ana, ELIMINAR_TALLER, Map.of("id", 12)).andExpect(jsonPath("$.ok").value(true));
        QC.respuestaEscritura("DELETE " + BASE + "/catalogos/procesos/13", 404, "{\"success\":false,\"message\":\"no\"}");
        accion(ana, ELIMINAR_PROCESO, Map.of("id", 13)).andExpect(jsonPath("$.ok").value(false));
        accion(ana, CREAR, valido("observaciones", "<b>x</b>")).andExpect(jsonPath("$.ok").value(false));
        String log = salida.getAll();
        assertThat(log).containsPattern("evento=ESCRITURA usuario=ana empresa=FARET accion=faret\\.talleresExternos\\.create "
                + "recurso=faret:talleres:901:crear resultado=OK ms=\\d+");
        assertThat(log).containsPattern("accion=faret\\.talleresExternos\\.update recurso=faret:talleres:7:actualizar resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.talleresExternos\\.eliminar recurso=faret:talleres:7:eliminar resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.talleresExternos\\.catalogos\\.eliminarTaller recurso=faret:talleres:catalogo:taller:12:eliminar resultado=OK");
        assertThat(log).containsPattern("accion=faret\\.talleresExternos\\.catalogos\\.eliminarProceso recurso=faret:talleres:catalogo:proceso:13:eliminar resultado=ERROR");
        assertThat(log).contains("recurso=faret:talleres:_:crear resultado=ERROR");
        assertThat(log).doesNotContain("12.345.678-9", "Otro secreto", "Motivo sensible", "<b>x</b>", FakeFaretApi.firmaDeToken(20), PASS);
    }

    // ------------------------------------------------------------------------------- helpers

    /** Payload mínimo válido (nv, producto, item) con `campo` = `valor` (pisa o agrega). */
    private static Map<String, Object> valido(String campo, Object valor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nv", "N");
        m.put("producto", "P");
        m.put("item", "I");
        m.put(campo, valor);
        return m;
    }

    private List<String> roles(String accion) {
        Object roles = policy.describir().stream().filter(x -> x.get("accion").equals(accion)).findFirst().orElseThrow().get("roles");
        return ((List<?>) roles).stream().map(Object::toString).sorted().toList();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> r = new java.util.ArrayList<>(a);
        r.addAll(b);
        return r;
    }

    private static SessionUser usuario(String empresa, String rol) {
        return new SessionUser(1, "u", "U", rol, empresa, "t", Instant.now(), Instant.now().plusSeconds(60));
    }

    private ResultActions accion(MockHttpSession sesion, String accion, Map<String, Object> campos) throws Exception {
        ObjectNode p = mapper.createObjectNode();
        p.put("action", accion);
        p.put("_modulo", "faret-talleres-externos");
        campos.forEach((k, v) -> p.set(k, mapper.valueToTree(v)));
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(p.toString()));
    }

    private MockHttpSession login(String identificador) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/faret/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.82.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identificador\":\"" + identificador + "\",\"password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private MockHttpSession loginInnpack(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.82.1." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private JsonNode json(MvcResult res) throws Exception {
        return mapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Path crearWww() {
        try {
            Path dir = Files.createTempDirectory("qcc-web-fixture-f2");
            Files.writeString(dir.resolve("index.html"), "<!doctype html><title>fixture</title>");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
