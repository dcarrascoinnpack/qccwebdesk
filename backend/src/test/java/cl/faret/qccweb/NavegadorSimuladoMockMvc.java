package cl.faret.qccweb;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests (Fase 3u): simula lo que hace web-bridge.js en el navegador real — cada POST al bridge lleva `_modulo`, el
 * módulo de Photino abierto que envía esa acción. Solo lo agrega si el test NO lo puso (los tests de permisos lo
 * fijan a mano, incluido `"_modulo": null` para simular su ausencia). Cuerpos que no son un objeto JSON con
 * `action` string se dejan intactos (los tests de solicitudes inválidas siguen probando lo mismo).
 */
@Component
public class NavegadorSimuladoMockMvc implements MockMvcBuilderCustomizer {

    /** Prefijo de acción → módulo de Photino (data-module) que la envía en uso normal. */
    private static final Map<String, String> MODULO_POR_PREFIJO = Map.ofEntries(
            Map.entry("inicio", "inicio"),
            Map.entry("permisos", "inicio"),
            Map.entry("maquinasSeguimiento", "maquinas-seguimiento"),
            Map.entry("dashboard", "dashboard"),
            Map.entry("registrosProduccion", "registros-produccion"),
            Map.entry("registrosControl", "registros-control"),
            Map.entry("productoTerminado", "producto-terminado"),
            Map.entry("certificadosLiberacion", "certificados-liberacion"),
            Map.entry("formularios", "formularios"),
            Map.entry("controlDocumental", "control-documental"),
            Map.entry("noConformidades", "no-conformidades"),
            Map.entry("liberacionCalidad", "no-conformidades"),
            Map.entry("recepcion", "recepcion-calidad"),
            Map.entry("usuarios", "usuarios"),
            Map.entry("muestraLab", "muestra-laboratorio"),
            Map.entry("talleresExternos", "talleres-externos"),
            Map.entry("trazabilidad", "trazabilidad"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void customize(ConfigurableMockMvcBuilder<?> builder) {
        builder.defaultRequest(MockMvcRequestBuilders.post("/").with(agregarModulo()));
    }

    static RequestPostProcessor agregarModulo() {
        return request -> {
            if (!request.getRequestURI().startsWith("/api/v1/bridge")) {
                return request;
            }
            byte[] contenido = request.getContentAsByteArray();
            if (contenido == null || contenido.length == 0) {
                return request;
            }
            JsonNode json;
            try {
                json = MAPPER.readTree(new String(contenido, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                return request;
            }
            if (!(json instanceof ObjectNode payload) || payload.has("_modulo") || payload.get("action") == null
                    || !payload.get("action").isString()) {
                return request;
            }
            String accion = payload.get("action").asString();
            String prefijo = accion.contains(".") ? accion.substring(0, accion.indexOf('.')) : accion;
            String modulo = MODULO_POR_PREFIJO.get(prefijo);
            if (modulo != null) {
                payload.put("_modulo", modulo);
                request.setContent(MAPPER.writeValueAsBytes(payload));
            }
            return request;
        };
    }
}
