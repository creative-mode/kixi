package ao.creativemode.kixi.config;

import java.util.Objects;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Runs the JDBC-based Flyway lifecycle before the R2DBC application starts serving requests.
 *
 * Spring Data R2DBC does not provide a JDBC DataSource for Flyway, so the JDBC URL is
 * derived from the configured R2DBC URL unless an explicit Flyway URL is supplied.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.r2dbc", name = "url")
public class FlywayMigrationConfig {

    @Bean(initMethod = "migrate")
    Flyway flyway(Environment environment) {
        String r2dbcUrl = required(environment, "spring.r2dbc.url");
        String jdbcUrl = firstPresent(
                environment.getProperty("spring.flyway.url"),
                toJdbcUrl(r2dbcUrl)
        );
        String username = firstPresent(
                environment.getProperty("spring.flyway.user"),
                environment.getProperty("spring.r2dbc.username")
        );
        String password = firstPresent(
                environment.getProperty("spring.flyway.password"),
                environment.getProperty("spring.r2dbc.password")
        );

        return Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .load();
    }

    static String toJdbcUrl(String r2dbcUrl) {
        Objects.requireNonNull(r2dbcUrl, "r2dbcUrl must not be null");
        if (r2dbcUrl.startsWith("r2dbc:jdbc:")) {
            return r2dbcUrl.substring("r2dbc:".length());
        }
        if (r2dbcUrl.startsWith("r2dbc:")) {
            return "jdbc:" + r2dbcUrl.substring("r2dbc:".length());
        }
        return r2dbcUrl;
    }

    private static String required(Environment environment, String key) {
        return Objects.requireNonNull(environment.getProperty(key), key + " must be configured");
    }

    private static String firstPresent(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }
}
