package cl.faret.qccweb.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sirve el frontend compartido de Photino desde el snapshot generado (mismo origen que la API,
 * sin CORS). Cache-Control: no-cache → el navegador siempre revalida (304 si no cambió), así un
 * deploy nuevo nunca deja JS/CSS antiguos en uso.
 */
@Configuration
public class StaticFrontendConfig implements WebMvcConfigurer {

    private final Path wwwDir;

    public StaticFrontendConfig(GatewayWebProperties properties) {
        if (properties.wwwDir() == null || properties.wwwDir().isBlank()) {
            throw new IllegalStateException("Falta configurar qcc.web.www-dir (QCC_WEB_WWW_DIR).");
        }
        this.wwwDir = Path.of(properties.wwwDir()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(wwwDir.resolve("index.html"))) {
            throw new IllegalStateException(
                    "No existe el frontend Photino en " + wwwDir
                            + ". Ejecuta tools/sync-photino-www.ps1 antes de iniciar el gateway.");
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(wwwDir.toUri().toString())
                .setCacheControl(CacheControl.noCache());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
