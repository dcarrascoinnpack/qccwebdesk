package cl.faret.qccweb;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Gateway web de QCC: sirve el frontend compartido de Photino (src/UI/www) y, en fases
 * siguientes, adapta el contrato PhotinoBridge hacia las APIs existentes.
 *
 * Vive en un paquete separado de la app legacy (cl.faret.qcc) para que ninguno de los dos
 * escanee los componentes del otro. No usa base de datos: se excluyen explícitamente las
 * autoconfiguraciones de JDBC/JPA/Flyway que siguen en el classpath por el código legacy.
 * Usa su propio archivo de configuración (gateway.yaml), no application.yaml.
 */
@SpringBootApplication(
        excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.XADataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"
        })
@ConfigurationPropertiesScan
public class QccWebGatewayApplication {

    /** Nombre del archivo de configuración propio del gateway (gateway.yaml). */
    public static final String CONFIG_NAME = "gateway";

    public static void main(String[] args) {
        new SpringApplicationBuilder(QccWebGatewayApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME)
                .run(args);
    }
}
