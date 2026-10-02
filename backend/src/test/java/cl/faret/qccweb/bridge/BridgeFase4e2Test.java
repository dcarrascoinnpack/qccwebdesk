package cl.faret.qccweb.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.faret.qccweb.QccWebGatewayApplication;
import cl.faret.qccweb.auth.FakeInnpackApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
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
 * Fase 4e-2 — resto de las escrituras de Laboratorio: los otros 12 tipos de ensayo (humedad, gramaje, cobb, espesor,
 * rct, fct, ect, bctMedido, bctTeorico, viscosidad, solidos, lugol), `nc.crear` y `adjunto.subir`, igual que Photino
 * para el usuario. Con esto Laboratorio queda 16/25. analistaUsuarioId/analistaNombre (o usuarioNombre/subidoPor)
 * SIEMPRE de sesión, ni siquiera son claves aceptadas del payload (mismo patrón que 4e-1); los campos restringidos a
 * un {@code <select>} de Photino que la API solo valida como "no vacío" (metodoEquipo, modalidad, tipoMaterial,
 * tamanoProbeta, tipoMedicion, componente, cara de probeta, posición de bobina, resultado de lugol) el gateway los
 * restringe a las opciones exactas. Objetos anidados (probeta, caja BCT, determinación de sólidos) y arrays
 * (bobinas) viajan tal cual Photino los arma, con el mismo control de caracteres/HTML en sus campos de texto.
 */
@SpringBootTest(properties = {
    "spring.config.name=" + QccWebGatewayApplication.CONFIG_NAME,
    "qcc.web.auth.login-min-duration=0ms",
    "qcc.web.bridge.escrituras-por-minuto=1000"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BridgeFase4e2Test {

    private static final FakeInnpackApi API = new FakeInnpackApi(Clock.systemUTC());
    private static final Path WWW = crearWww();
    private static final String PASS = "ClaveMuestraLab4e2#2026";
    private static final AtomicInteger IP = new AtomicInteger(1);

    static {
        API.agregar(new FakeInnpackApi.Usuario(10, "operador1", PASS, "Operador Uno", "operador", true));
        API.agregar(new FakeInnpackApi.Usuario(20, "admin1", PASS, "Admin Uno", "admin", true));
        API.agregar(new FakeInnpackApi.Usuario(30, "consulta1", PASS, "Consulta Uno", "consulta", true));
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("qcc.web.auth.innpack-api-base-url", API::baseUrl);
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

    // ------------------------------------------------------------------ lista blanca / autor de sesión (las 12 de ensayo)

    private static final List<String> ACCIONES_ENSAYO = List.of(
            "muestraLab.humedad.guardar", "muestraLab.gramaje.guardar", "muestraLab.cobb.guardar",
            "muestraLab.espesor.guardar", "muestraLab.rct.guardar", "muestraLab.fct.guardar", "muestraLab.ect.guardar",
            "muestraLab.bctMedido.guardar", "muestraLab.bctTeorico.guardar", "muestraLab.viscosidad.guardar",
            "muestraLab.solidos.guardar", "muestraLab.lugol.guardar");

    @Test
    void analistaManipuladoSeRechazaEnLos12Ensayos() throws Exception {
        MockHttpSession s = login("operador1");
        for (String accion : ACCIONES_ENSAYO) {
            ObjectNode cuerpo = cuerpoMinimo(accion);
            cuerpo.put("analistaUsuarioId", 999);
            enviar(s, acc(accion, cuerpo)).andExpect(jsonPath("$.error").value("Campo no permitido: analistaUsuarioId"));

            ObjectNode cuerpo2 = cuerpoMinimo(accion);
            cuerpo2.put("analistaNombre", "Hacker");
            enviar(s, acc(accion, cuerpo2)).andExpect(jsonPath("$.error").value("Campo no permitido: analistaNombre"));
        }
    }

    @Test
    void campoNoPermitidoEnLos12Ensayos() throws Exception {
        MockHttpSession s = login("operador1");
        for (String accion : ACCIONES_ENSAYO) {
            ObjectNode cuerpo = cuerpoMinimo(accion);
            cuerpo.put("idEnsayo", 1);
            enviar(s, acc(accion, cuerpo)).andExpect(jsonPath("$.error").value("Campo no permitido: idEnsayo"));
        }
    }

    @Test
    void autorSiempreDeSesionEnLos12Ensayos() throws Exception {
        MockHttpSession s = login("operador1");
        for (String accion : ACCIONES_ENSAYO) {
            JsonNode json = json(enviar(s, acc(accion, cuerpoMinimo(accion))).andExpect(status().isOk()).andReturn());
            assertThat(json.get("ok").asBoolean()).as(accion).isTrue();
        }
        List<JsonNode> recibidos = API.cuerposEnsayoLabRecibidos();
        assertThat(recibidos).hasSize(ACCIONES_ENSAYO.size());
        for (JsonNode r : recibidos) {
            assertThat(r.get("analistaUsuarioId").asInt()).isEqualTo(10);
            assertThat(r.get("analistaNombre").asString()).isEqualTo("Operador Uno");
        }
    }

    // ------------------------------------------------------------------ flat: ect / bctTeorico / viscosidad / solidos / lugol

    @Test
    void ectBctTeoricoYViscosidadCamposPlanos() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode ect = cuerpoBase().put("p1Force", 10).put("p2Force", 11).put("p3Force", 12).put("p4Force", 13).put("p5Force", 14);
        enviar(s, acc("muestraLab.ect.guardar", ect)).andExpect(jsonPath("$.ok").value(true));

        ObjectNode bctTeo = cuerpoBase().put("ectEnsayoId", 1).put("espesorEnsayoId", 2).put("largoMm", 100).put("anchoMm", 50);
        enviar(s, acc("muestraLab.bctTeorico.guardar", bctTeo)).andExpect(jsonPath("$.ok").value(true));

        ObjectNode visc = cuerpoBase().put("tipoAdhesivo", "PVA").put("temperatura", 25).put("equipo", "Brookfield")
                .put("husillo", "LV2").put("velocidadRpm", 60).put("resultadoCp", 500);
        enviar(s, acc("muestraLab.viscosidad.guardar", visc)).andExpect(jsonPath("$.ok").value(true));
        JsonNode recibido = API.cuerposEnsayoLabRecibidos().get(2);
        assertThat(recibido.get("tipoAdhesivo").asString()).isEqualTo("PVA");
    }

    @Test
    void solidosConDeterminacionesAnidadas() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode d1 = mapper.createObjectNode().put("m1", 1.1).put("m2", 1.2).put("m3", 1.3);
        ObjectNode cuerpo = cuerpoBase();
        cuerpo.set("d1", d1);
        enviar(s, acc("muestraLab.solidos.guardar", cuerpo)).andExpect(jsonPath("$.ok").value(true));
        JsonNode recibido = API.cuerposEnsayoLabRecibidos().get(0);
        assertThat(recibido.at("/d1/m1").asDouble()).isEqualTo(1.1);
        assertThat(recibido.get("d2").isNull()).isTrue();
    }

    @Test
    void lugolResultadoRestringidoYCumplimientoPorDefecto() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode sinResultado = cuerpoBase();
        enviar(s, acc("muestraLab.lugol.guardar", sinResultado))
                .andExpect(jsonPath("$.error").value("Falta el resultado (Positivo/Negativo/No concluyente)"));

        ObjectNode resultadoInvalido = cuerpoBase().put("resultado", "Inventado");
        enviar(s, acc("muestraLab.lugol.guardar", resultadoInvalido))
                .andExpect(jsonPath("$.error").value("Valor no permitido en resultado."));

        ObjectNode sinCumplimiento = cuerpoBase().put("resultado", "Positivo");
        enviar(s, acc("muestraLab.lugol.guardar", sinCumplimiento)).andExpect(jsonPath("$.ok").value(true));
        assertThat(ultimo(API.cuerposEnsayoLabRecibidos()).get("cumplimiento").asString()).isEqualTo("Sin especificacion");
    }

    // ------------------------------------------------------------------ probeta: cobb / rct / fct

    @Test
    void cobbConProbetasAnidadasYCaraRestringida() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode p1 = mapper.createObjectNode().put("bobina", "B-1").put("cara", "Externa")
                .put("pesoInicial", 1.1).put("pesoFinal", 1.5).put("tiempo", "120s");
        ObjectNode cuerpo = cuerpoBase();
        cuerpo.set("p1", p1);
        enviar(s, acc("muestraLab.cobb.guardar", cuerpo)).andExpect(jsonPath("$.ok").value(true));
        JsonNode recibido = API.cuerposEnsayoLabRecibidos().get(0);
        assertThat(recibido.at("/p1/cara").asString()).isEqualTo("Externa");
        assertThat(recibido.get("p2").isNull()).isTrue();

        ObjectNode caraInvalida = cuerpoBase();
        caraInvalida.set("p1", mapper.createObjectNode().put("cara", "Arriba"));
        enviar(s, acc("muestraLab.cobb.guardar", caraInvalida)).andExpect(jsonPath("$.error").value("Valor no permitido en cara."));
    }

    @Test
    void rctExigeComponenteYFctNoLoAcepta() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc("muestraLab.rct.guardar", cuerpoBase()))
                .andExpect(jsonPath("$.error").value("Debes indicar el componente (Liner/Onda) para RCT"));

        ObjectNode conComponente = cuerpoBase().put("componente", "Liner");
        enviar(s, acc("muestraLab.rct.guardar", conComponente)).andExpect(jsonPath("$.ok").value(true));
        assertThat(ultimo(API.cuerposEnsayoLabRecibidos()).get("componente").asString()).isEqualTo("Liner");

        ObjectNode componenteInvalido = cuerpoBase().put("componente", "Lateral");
        enviar(s, acc("muestraLab.rct.guardar", componenteInvalido)).andExpect(jsonPath("$.error").value("Valor no permitido en componente."));

        enviar(s, acc("muestraLab.fct.guardar", cuerpoBase())).andExpect(jsonPath("$.ok").value(true));
        enviar(s, acc("muestraLab.fct.guardar", cuerpoBase().put("componente", "Liner")))
                .andExpect(jsonPath("$.error").value("Campo no permitido: componente"));
    }

    // ------------------------------------------------------------------ bobinas: humedad / gramaje / espesor

    @Test
    void humedadExigeMetodoEquipoYValidaBobinas() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc("muestraLab.humedad.guardar", cuerpoBase()))
                .andExpect(jsonPath("$.error").value("Debes indicar el metodo de equipo (Higrometro/Termobalanza/Horno)"));

        ObjectNode metodoInvalido = cuerpoBase().put("metodoEquipo", "Microondas");
        enviar(s, acc("muestraLab.humedad.guardar", metodoInvalido)).andExpect(jsonPath("$.error").value("Valor no permitido en metodoEquipo."));

        ObjectNode conBobinaSinNumero = cuerpoBase().put("metodoEquipo", "Higrometro");
        conBobinaSinNumero.set("bobinas", mapper.createArrayNode().add(mapper.createObjectNode().put("lote", "L1")));
        enviar(s, acc("muestraLab.humedad.guardar", conBobinaSinNumero))
                .andExpect(jsonPath("$.error").value("Cada bobina del muestreo debe tener su número de bobina"));

        ObjectNode valido = cuerpoBase().put("metodoEquipo", "Higrometro").put("higrometroIzquierdo", 10);
        valido.set("bobinas", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("numeroBobina", "B-1").put("posicion", "Onda").put("valor1", 1.0)));
        enviar(s, acc("muestraLab.humedad.guardar", valido)).andExpect(jsonPath("$.ok").value(true));
        JsonNode recibido = API.cuerposEnsayoLabRecibidos().get(2);
        assertThat(recibido.at("/bobinas/0/numeroBobina").asString()).isEqualTo("B-1");
        assertThat(recibido.at("/bobinas/0/posicion").asString()).isEqualTo("Onda");

        ObjectNode posicionInvalida = cuerpoBase().put("metodoEquipo", "Higrometro");
        posicionInvalida.set("bobinas", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("numeroBobina", "B-1").put("posicion", "Fondo")));
        enviar(s, acc("muestraLab.humedad.guardar", posicionInvalida)).andExpect(jsonPath("$.error").value("Valor no permitido en posicion."));
    }

    @Test
    void gramajeYEspesorRestringenSusSelects() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc("muestraLab.gramaje.guardar", cuerpoBase()))
                .andExpect(jsonPath("$.error").value("Debes indicar la modalidad (ProbetaPeso/Directo)"));
        enviar(s, acc("muestraLab.gramaje.guardar", cuerpoBase().put("modalidad", "ProbetaPeso").put("tipoMaterial", "Inventado")))
                .andExpect(jsonPath("$.error").value("Valor no permitido en tipoMaterial."));
        enviar(s, acc("muestraLab.gramaje.guardar", cuerpoBase().put("modalidad", "ProbetaPeso").put("tamanoProbeta", "20x20")))
                .andExpect(jsonPath("$.error").value("Valor no permitido en tamanoProbeta."));
        enviar(s, acc("muestraLab.gramaje.guardar", cuerpoBase().put("modalidad", "Directo").put("tipoMaterial", "Papel")
                .put("tamanoProbeta", "10x10").put("muestra1", 5)))
                .andExpect(jsonPath("$.ok").value(true));

        enviar(s, acc("muestraLab.espesor.guardar", cuerpoBase()))
                .andExpect(jsonPath("$.error").value("Debes indicar el tipo de medición (Ubicacion/Muestra)"));
        enviar(s, acc("muestraLab.espesor.guardar", cuerpoBase().put("tipoMedicion", "Ubicacion")))
                .andExpect(jsonPath("$.ok").value(true));
    }

    // ------------------------------------------------------------------ bctMedido: cajas 1/2/3 y c2/c3 forzados a null

    @Test
    void bctMedidoCajasYMotivoMenosDeTres() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc("muestraLab.bctMedido.guardar", cuerpoBase().put("cajasEnsayadas", 5)))
                .andExpect(jsonPath("$.error").value("Cajas ensayadas debe ser 1, 2 o 3"));
        enviar(s, acc("muestraLab.bctMedido.guardar", cuerpoBase().put("cajasEnsayadas", 1)))
                .andExpect(jsonPath("$.error").value("Debes indicar el motivo por ensayar menos de 3 cajas"));

        ObjectNode unaCaja = cuerpoBase().put("cajasEnsayadas", 1).put("motivoMenos3", "Solo una muestra disponible");
        unaCaja.set("c1", mapper.createObjectNode().put("largo", 30).put("ancho", 20).put("alto", 20).put("tipoOnda", "C"));
        unaCaja.set("c2", mapper.createObjectNode().put("largo", 99));
        enviar(s, acc("muestraLab.bctMedido.guardar", unaCaja)).andExpect(jsonPath("$.ok").value(true));
        JsonNode recibido = ultimo(API.cuerposEnsayoLabRecibidos());
        assertThat(recibido.at("/c1/tipoOnda").asString()).isEqualTo("C");
        assertThat(recibido.get("c2").isNull()).as("c2 se fuerza a null con 1 caja aunque el payload mande datos").isTrue();
        assertThat(recibido.get("c3").isNull()).isTrue();

        ObjectNode tresCajas = cuerpoBase().put("cajasEnsayadas", 3);
        tresCajas.set("c1", mapper.createObjectNode());
        tresCajas.set("c2", mapper.createObjectNode());
        tresCajas.set("c3", mapper.createObjectNode().put("resultadoLbf", 42));
        enviar(s, acc("muestraLab.bctMedido.guardar", tresCajas)).andExpect(jsonPath("$.ok").value(true));
        assertThat(ultimo(API.cuerposEnsayoLabRecibidos()).at("/c3/resultadoLbf").asDouble()).isEqualTo(42.0);
    }

    // ------------------------------------------------------------------ patrón de reemplazo (común a los 13 ensayos)

    @Test
    void reemplazoSinMotivoYConOriginalInexistente() throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode original = json(enviar(s, acc("muestraLab.ect.guardar", cuerpoBase())).andExpect(status().isOk()).andReturn());
        int ensayoOriginalId = original.at("/data/ensayoId").asInt();

        ObjectNode sinMotivo = cuerpoBase().put("ensayoOriginalId", ensayoOriginalId);
        enviar(s, acc("muestraLab.ect.guardar", sinMotivo)).andExpect(jsonPath("$.error").value("Debes indicar el motivo de la corrección"));

        ObjectNode correccion = cuerpoBase().put("ensayoOriginalId", ensayoOriginalId).put("motivoReemplazo", "Error de digitación");
        enviar(s, acc("muestraLab.ect.guardar", correccion)).andExpect(jsonPath("$.ok").value(true));
    }

    // ------------------------------------------------------------------ nc.crear

    @Test
    void ncCrearReglasDeNegocioYUsuarioManipulado() throws Exception {
        MockHttpSession s = login("operador1");
        enviar(s, acc("muestraLab.nc.crear", mapper.createObjectNode().put("muestraId", 0)))
                .andExpect(jsonPath("$.error").value("Falta indicar la muestra"));
        enviar(s, acc("muestraLab.nc.crear", mapper.createObjectNode().put("muestraId", 404)))
                .andExpect(jsonPath("$.error").value("Muestra no encontrada"));
        enviar(s, acc("muestraLab.nc.crear", mapper.createObjectNode().put("muestraId", 501)))
                .andExpect(jsonPath("$.error").value("Solo se puede crear una No Conformidad cuando la muestra evaluó \"No cumple\""));

        API.fijarEvaluacionMuestraLab(501, "No cumple");
        JsonNode creada = json(enviar(s, acc("muestraLab.nc.crear", mapper.createObjectNode().put("muestraId", 501)))
                .andExpect(status().isOk()).andReturn());
        assertThat(creada.at("/data/ncId").asInt()).isGreaterThan(0);
        assertThat(ultimo(API.cuerposNcLabRecibidos()).get("usuarioNombre").asString()).isEqualTo("Operador Uno");

        enviar(s, acc("muestraLab.nc.crear", mapper.createObjectNode().put("muestraId", 501)))
                .andExpect(jsonPath("$.error").value("Esta muestra ya tiene una No Conformidad vinculada"));

        ObjectNode manipulado = mapper.createObjectNode().put("muestraId", 501).put("usuarioNombre", "Hacker");
        enviar(s, acc("muestraLab.nc.crear", manipulado)).andExpect(jsonPath("$.error").value("Campo no permitido: usuarioNombre"));
    }

    // ------------------------------------------------------------------ adjunto.subir

    @Test
    void adjuntoSubirValidacionesYSubidoPorDeSesion() throws Exception {
        MockHttpSession s = login("operador1");
        ObjectNode sinMuestra = mapper.createObjectNode().put("muestraId", 0).put("nombreArchivo", "foto.jpg")
                .put("contenidoBase64", Base64.getEncoder().encodeToString(jpegMinimo()));
        enviar(s, acc("muestraLab.adjunto.subir", sinMuestra)).andExpect(jsonPath("$.error").value("Falta indicar la muestra"));

        ObjectNode sinNombre = mapper.createObjectNode().put("muestraId", 501).put("nombreArchivo", "")
                .put("contenidoBase64", "x");
        enviar(s, acc("muestraLab.adjunto.subir", sinNombre)).andExpect(jsonPath("$.error").value("Falta el nombre del archivo"));

        ObjectNode extensionInvalida = mapper.createObjectNode().put("muestraId", 501).put("nombreArchivo", "virus.exe")
                .put("contenidoBase64", Base64.getEncoder().encodeToString(jpegMinimo()));
        enviar(s, acc("muestraLab.adjunto.subir", extensionInvalida))
                .andExpect(jsonPath("$.error").value("Tipo de archivo no permitido. Formatos válidos: .pdf, .doc, .docx, .jpg, .jpeg, .png, .webp"));

        ObjectNode firmaFalsa = mapper.createObjectNode().put("muestraId", 501).put("nombreArchivo", "foto.jpg")
                .put("contenidoBase64", Base64.getEncoder().encodeToString("no es un jpg".getBytes(StandardCharsets.UTF_8)));
        enviar(s, acc("muestraLab.adjunto.subir", firmaFalsa))
                .andExpect(jsonPath("$.error").value("El archivo no corresponde al tipo declarado por su extensión."));

        ObjectNode valido = mapper.createObjectNode().put("muestraId", 501).put("nombreArchivo", "foto.jpg")
                .put("contenidoBase64", Base64.getEncoder().encodeToString(jpegMinimo()));
        JsonNode json = json(enviar(s, acc("muestraLab.adjunto.subir", valido)).andExpect(status().isOk()).andReturn());
        assertThat(json.at("/data/adjuntoId").asInt()).isGreaterThan(0);
        assertThat(API.cuerposAdjuntoLabRecibidos().get(0).get("subidoPor").asString()).isEqualTo("Operador Uno");

        ObjectNode conSubidoPor = mapper.createObjectNode().put("muestraId", 501).put("subidoPor", "Hacker")
                .put("nombreArchivo", "foto.jpg").put("contenidoBase64", Base64.getEncoder().encodeToString(jpegMinimo()));
        enviar(s, acc("muestraLab.adjunto.subir", conSubidoPor)).andExpect(jsonPath("$.error").value("Campo no permitido: subidoPor"));
    }

    // ------------------------------------------------------------------ roles / auditoría

    @Test
    void rolConsultaDenegadoEnLasNuevasAcciones() throws Exception {
        MockHttpSession consulta = login("consulta1");
        for (String accion : List.of("muestraLab.ect.guardar", "muestraLab.nc.crear", "muestraLab.adjunto.subir")) {
            enviar(consulta, acc(accion, mapper.createObjectNode().put("muestraId", 501))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(BridgeController.MENSAJE_NO_DISPONIBLE));
        }
    }

    @Test
    void auditoriaConRecursoPorAccion(CapturedOutput salida) throws Exception {
        MockHttpSession s = login("operador1");
        JsonNode ensayo = json(enviar(s, acc("muestraLab.ect.guardar", cuerpoBase())).andExpect(status().isOk()).andReturn());
        int ensayoId = ensayo.at("/data/ensayoId").asInt();
        assertThat(salida.getAll()).containsPattern(
                "evento=ESCRITURA usuario=operador1 empresa=INNPACK accion=muestraLab\\.ect\\.guardar recurso=muestraLab:501:ect:"
                        + ensayoId + " resultado=OK ms=\\d+");
    }

    // ------------------------------------------------------------------ helpers

    private static JsonNode ultimo(List<JsonNode> lista) {
        return lista.get(lista.size() - 1);
    }

    private ObjectNode cuerpoBase() {
        return mapper.createObjectNode().put("muestraId", 501).put("metodo", "Metodo X").put("observacion", "ok");
    }

    /** Cuerpo mínimo que deja pasar la validación propia de cada acción (para los tests de lista blanca/autor). */
    private ObjectNode cuerpoMinimo(String accion) {
        ObjectNode cuerpo = cuerpoBase();
        return switch (accion) {
            case "muestraLab.humedad.guardar" -> cuerpo.put("metodoEquipo", "Higrometro");
            case "muestraLab.gramaje.guardar" -> cuerpo.put("modalidad", "ProbetaPeso");
            case "muestraLab.espesor.guardar" -> cuerpo.put("tipoMedicion", "Ubicacion");
            case "muestraLab.rct.guardar" -> cuerpo.put("componente", "Liner");
            case "muestraLab.bctMedido.guardar" -> cuerpo.put("cajasEnsayadas", 3);
            case "muestraLab.bctTeorico.guardar" -> cuerpo.put("ectEnsayoId", 1).put("espesorEnsayoId", 2);
            case "muestraLab.lugol.guardar" -> cuerpo.put("resultado", "Positivo");
            default -> cuerpo;
        };
    }

    private String acc(String accion, ObjectNode data) {
        ObjectNode raiz = mapper.createObjectNode();
        raiz.put("action", accion);
        raiz.set("data", data);
        return raiz.toString();
    }

    private ResultActions enviar(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/v1/bridge").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private MockHttpSession login(String usuario) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr("10.30.0." + IP.getAndIncrement());
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

    /** JPEG mínimo válido (firma FF D8 FF) para pasar la validación de firma real. */
    private static byte[] jpegMinimo() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 1, 2, 3, 4};
    }

    private static Path crearWww() {
        try {
            Path www = Files.createTempDirectory("qcc-web-fixture-4e2");
            Files.writeString(www.resolve("index.html"), "<html></html>");
            return www;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
