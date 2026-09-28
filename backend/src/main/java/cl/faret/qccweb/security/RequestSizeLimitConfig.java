package cl.faret.qccweb.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registra RequestSizeLimitFilter antes que cualquier otro filtro (incluido Spring Security). */
@Configuration
public class RequestSizeLimitConfig {

    @Bean
    public FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(
            @Value("${qcc.web.api.max-body-bytes:262144}") long maxBytes,
            @Value("${qcc.web.api.max-body-bytes-archivo:14680064}") long maxBytesArchivo) {
        FilterRegistrationBean<RequestSizeLimitFilter> registro = new FilterRegistrationBean<>(
                new RequestSizeLimitFilter(maxBytes, maxBytesArchivo));
        registro.addUrlPatterns("/api/*");
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registro;
    }
}
