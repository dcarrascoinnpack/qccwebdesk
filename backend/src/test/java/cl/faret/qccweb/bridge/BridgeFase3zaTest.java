package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

/**
 * Photino 1.8.23 — Despachos Diarios (solo lectura, INNPACK): despachosDiarios.resumen (QualityControlInnpack.Api) y
 * despachosDiarios.sap.{resumen,detalle,categorias} (apisapfaret con la API key del servidor). Fechas validadas, rango
 * acotado a 399 días, empresa como filtro exacto, filtros de texto escapados, errores de SAP genéricos. APIs SIMULADAS.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms"
})
@AutoConfigureMockMvc
class BridgeFase3zaTest {

    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveDespachos#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(30, "consulta1", PASS, "Consulta Uno", "consulta", true));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.innpack-api-base-url", API::baseUrl);
        registry.add("qcc.web.sap.base-url", () -> API.baseUrl() + "/sap/");
        registry.add("qcc.web.sap.api-key", () -> FakeInnpackApi.SAP_API_KEY);
        registry.add("qcc.web.www-dir", WWW::toString);
        registry.add("qcc.web.manifest-file", () -> WWW.resolve("no-existe.json").toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reiniciar() {
        API.reiniciarDashboard();
    }

    @AfterAll
    static void cerrar() {
        API.close();
    }

    private static String accion(String accion, String data) {
        return "{\"action\":\"" + accion + "\",\"_modulo\":\"despachos-diarios\",\"data\":" + data + "}";
    }

    @Test
    void resumenReenviaSoloFechasValidasEscapadas() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(enviar(s, accion("despachosDiarios.resumen", "{\"fechaDesde\":\"2026-09-01\",\"fechaHasta\":\"2026-09-29\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(json.get("data")).isEqualTo(mapper.readTree("{\"filas\":[{\"destino\":\"Cliente A\",\"cantidad\":5}]}"));
        enviar(s, accion("despachosDiarios.resumen", "{\"fechaDesde\":\"2026-09-01\"}"));
        enviar(s, accion("despachosDiarios.resumen", "{}"));
        enviar(s, "{\"action\":\"despachosDiarios.resumen\",\"_modulo\":\"despachos-diarios\"}");
        assertThat(API.despachosConsultas()).containsExactly(
                "/api/despachos-diarios?fechaDesde=2026-09-01&fechaHasta=2026-09-29",
                "/api/despachos-diarios?fechaDesde=2026-09-01",
                "/api/despachos-diarios?",
                "/api/despachos-diarios?");
        for (String malo : new String[] {"\"2026-09-01&x=1\"", "\"20260901\"", "\"2026-02-30\"", "\"ayer\""}) {
            enviar(s, accion("despachosDiarios.resumen", "{\"fechaDesde\":" + malo + "}"))
                    .andExpect(jsonPath("$.error").value("Las fechas deben tener el formato yyyy-MM-dd"));
        }
        assertThat(API.despachosConsultas()).hasSize(4);
    }

    @Test
    void sapResumenAcotaYReproyectaLaRespuesta() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode json = json(enviar(s, accion("despachosDiarios.sap.resumen",
                "{\"fechaDesde\":\"2026-09-01\",\"fechaHasta\":\"2026-09-29\",\"empresa\":\"FARET\",\"cliente\":\"C1,C 2\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(API.sapConsultas()).containsExactly("/api/despachos/resumen?desde=20260901&hasta=20260929&empresa=FARET&cliente=C1%2CC%202");
        assertThat(json.get("data")).isEqualTo(mapper.readTree("{\"desde\":\"2026-09-01\",\"hasta\":\"2026-09-29\",\"acotado\":false,"
                + "\"empresas\":[{\"empresa\":\"INNPACK\",\"pallets\":3,\"cliente\":\"A & <b>B</b>\"}],\"errors\":[\"FARET: sin datos\"]}"));
    }

    @Test
    void sapRangoSinDesdeOMuyLargoSeAcotaA399Dias() throws Exception {
        MockHttpSession s = login("operador1");
        LocalDate esperado = LocalDate.of(2026, 9, 29).minusDays(399);
        String fechaEsperada = esperado.toString().replace("-", "");
        JsonNode sinDesde = json(enviar(s, accion("despachosDiarios.sap.resumen", "{\"fechaHasta\":\"2026-09-29\"}")).andReturn());
        assertThat(sinDesde.at("/data/acotado").asBoolean()).isTrue();
        assertThat(sinDesde.at("/data/desde").asString()).isEqualTo(esperado.toString());
        enviar(s, accion("despachosDiarios.sap.resumen", "{\"fechaDesde\":\"2020-01-01\",\"fechaHasta\":\"2026-09-29\"}"));
        // Justo 399 días no se acota; 400 sí.
        JsonNode justo = json(enviar(s, accion("despachosDiarios.sap.resumen",
                "{\"fechaDesde\":\"" + esperado + "\",\"fechaHasta\":\"2026-09-29\"}")).andReturn());
        assertThat(justo.at("/data/acotado").asBoolean()).isFalse();
        JsonNode pasado = json(enviar(s, accion("despachosDiarios.sap.resumen",
                "{\"fechaDesde\":\"" + esperado.minusDays(1) + "\",\"fechaHasta\":\"2026-09-29\"}")).andReturn());
        assertThat(pasado.at("/data/acotado").asBoolean()).isTrue();
        String consulta = "/api/despachos/resumen?desde=" + fechaEsperada + "&hasta=20260929";
        assertThat(API.sapConsultas()).containsExactly(consulta, consulta, consulta, consulta);
        // Sin hasta: hoy.
        JsonNode hoy = json(enviar(s, accion("despachosDiarios.sap.resumen", "{}")).andReturn());
        assertThat(hoy.at("/data/hasta").asString()).isIn(LocalDate.now().toString(), LocalDate.now(java.time.ZoneOffset.UTC).toString());
        enviar(s, accion("despachosDiarios.sap.resumen", "{\"fechaDesde\":\"2026-09-30\",\"fechaHasta\":\"2026-09-29\"}"))
                .andExpect(jsonPath("$.error").value("La fecha desde no puede ser posterior a la fecha hasta"));
    }

    @Test
    void detalleYCategoriasSoloMandanSuFiltro() throws Exception {
        MockHttpSession s = login("operador1");
        String data = "{\"fechaDesde\":\"2026-09-01\",\"fechaHasta\":\"2026-09-29\",\"categoria\":\"Estuches,Fajas\",\"seleccion\":\"Cajas&x=1\"}";
        enviar(s, accion("despachosDiarios.sap.detalle", data)).andExpect(status().isOk());
        enviar(s, accion("despachosDiarios.sap.categorias", data)).andExpect(status().isOk());
        enviar(s, accion("despachosDiarios.sap.resumen", data)).andExpect(status().isOk());
        assertThat(API.sapConsultas()).containsExactly(
                "/api/despachos/detalle?desde=20260901&hasta=20260929&categoria=Estuches%2CFajas",
                "/api/despachos/categorias?desde=20260901&hasta=20260929&seleccion=Cajas%26x%3D1",
                "/api/despachos/resumen?desde=20260901&hasta=20260929");
    }

    @Test
    void filtrosInvalidosSeRechazanAntesDeLlamarASap() throws Exception {
        MockHttpSession s = login("operador1");
        String f = "\"fechaDesde\":\"2026-09-01\",\"fechaHasta\":\"2026-09-29\"";
        enviar(s, accion("despachosDiarios.sap.resumen", "{" + f + ",\"empresa\":\"OTRA\"}"))
                .andExpect(jsonPath("$.error").value("La empresa debe ser INNPACK o FARET"));
        enviar(s, accion("despachosDiarios.sap.resumen", "{" + f + ",\"empresa\":\"INNPACK&x=1\"}"))
                .andExpect(jsonPath("$.error").value("La empresa debe ser INNPACK o FARET"));
        enviar(s, accion("despachosDiarios.sap.detalle", "{" + f + ",\"categoria\":\"" + "A".repeat(401) + "\"}"))
                .andExpect(jsonPath("$.error").value("La selección de categorías no es válida"));
        enviar(s, accion("despachosDiarios.sap.categorias", "{" + f + ",\"seleccion\":\"A\\nB\"}"))
                .andExpect(jsonPath("$.error").value("La selección de categorías no es válida"));
        enviar(s, accion("despachosDiarios.sap.resumen", "{" + f + ",\"cliente\":\"" + "A".repeat(1101) + "\"}"))
                .andExpect(jsonPath("$.error").value("La selección de clientes no es válida"));
        enviar(s, accion("despachosDiarios.sap.resumen", "{" + f + ",\"cliente\":\"A\\u0000\"}"))
                .andExpect(jsonPath("$.error").value("La selección de clientes no es válida"));
        assertThat(API.sapConsultas()).isEmpty();
    }

    @Test
    void erroresDeSapSoloMuestranLaValidacionDeLaApi() throws Exception {
        MockHttpSession s = login("operador1");
        String data = "{\"fechaDesde\":\"2026-09-01\",\"fechaHasta\":\"2026-09-29\"";
        enviar(s, accion("despachosDiarios.sap.resumen", data + ",\"cliente\":\"MAL\"}"))
                .andExpect(jsonPath("$.error").value("cliente invalido"));
        API.modoSap("ERROR_502");
        MvcResult r = enviar(s, accion("despachosDiarios.sap.resumen", data + "}")).andReturn();
        assertThat(json(r).get("error").asString()).isEqualTo("No se pudo consultar los despachos en SAP").doesNotContain("B1SESSION");
        API.modoSap("JSON_INVALIDO");
        enviar(s, accion("despachosDiarios.sap.resumen", data + "}"))
                .andExpect(jsonPath("$.error").value("Respuesta inválida de SAP (apisapfaret)"));
    }

    @Test
    void soloRolesDeInnpack() throws Exception {
        MockHttpSession consulta = login("consulta1");
        enviar(consulta, accion("despachosDiarios.resumen", "{}")).andExpect(status().isForbidden());
        enviar(consulta, accion("despachosDiarios.sap.categorias", "{}")).andExpect(status().isForbidden());
        assertThat(API.despachosConsultas()).isEmpty();
        assertThat(API.sapConsultas()).isEmpty();
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.38.0." + IP.getAndIncrement());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"CodigoUsuario\":\"" + usuario + "\",\"Password\":\"" + PASS + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private ResultActions enviar(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private JsonNode json(MvcResult res) throws Exception {
        return mapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-despachos");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
