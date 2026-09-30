package ao.creativemode.kixi.shared.config;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * Makes {@code @Transactional} actually effective on the reactive services.
 *
 * <p>Every service in this application returns a {@code Mono} or {@code Flux},
 * so the annotation has to be driven by a {@link ReactiveTransactionManager}.
 * Spring Boot auto-configures the {@code R2dbcTransactionManager} bean, but its
 * own {@code TransactionAutoConfiguration} only turns transaction management on
 * when a JDBC {@code TransactionManager} is present. This project has no
 * {@code DataSource}, so that condition never matched and every
 * {@code @Transactional} in the codebase was silently a no-op: the multi-step
 * writes below were never rolled back as a unit.
 *
 * <p>Declaring the manager here as well keeps the behaviour explicit and makes
 * the setup independent of the auto-configuration ordering, and
 * {@link TransactionalOperator} is exposed for the places that compose several
 * reactive steps without wanting the annotation on a public entry point.
 */
@Configuration
@EnableTransactionManagement
public class R2dbcTransactionConfig {

    @Bean
    public ReactiveTransactionManager r2dbcTransactionManager(ConnectionFactory connectionFactory) {
        return new R2dbcTransactionManager(connectionFactory);
    }

    @Bean
    public TransactionalOperator transactionalOperator(ReactiveTransactionManager transactionManager) {
        return TransactionalOperator.create(transactionManager);
    }
}