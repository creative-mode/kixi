package ao.creativemode.kixi.shared.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class R2dbcTransactionConfigTest {

    private final R2dbcTransactionConfig config = new R2dbcTransactionConfig();

    @Test
    void enablesTransactionManagement() {
        assertNotNull(
                R2dbcTransactionConfig.class.getAnnotation(EnableTransactionManagement.class),
                "sem @EnableTransactionManagement todo @Transactional reativo volta a ser no-op"
        );
    }

    @Test
    void exposesTransactionManagerBackedByTheConnectionFactory() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);

        var transactionManager = config.r2dbcTransactionManager(connectionFactory);

        assertInstanceOf(R2dbcTransactionManager.class, transactionManager);
        assertSame(connectionFactory, ((R2dbcTransactionManager) transactionManager).getConnectionFactory());
    }

    @Test
    void runsTheOperatorThroughTheGivenManager() {
        var transaction = mock(ReactiveTransaction.class);
        when(transaction.isRollbackOnly()).thenReturn(false);

        var transactionManager = mock(ReactiveTransactionManager.class);
        when(transactionManager.getReactiveTransaction(any(TransactionDefinition.class))).thenReturn(Mono.just(transaction));
        when(transactionManager.commit(any())).thenReturn(Mono.empty());
        when(transactionManager.rollback(any())).thenReturn(Mono.empty());

        var operator = config.transactionalOperator(transactionManager);

        StepVerifier.create(operator.execute(status -> Mono.just("ok")))
                .expectNext("ok")
                .verifyComplete();

        verify(transactionManager).getReactiveTransaction(any(TransactionDefinition.class));
        verify(transactionManager).commit(any());
        verify(transactionManager, never()).rollback(any());
    }
}