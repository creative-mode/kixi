package ao.creativemode.kixi.simulations.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * PostgreSQL-only proof that room closure and answer lifecycle writes serialize
 * in the room-then-simulation order. Run with KIXI_POSTGRES_TESTS=true.
 */
@EnabledIfEnvironmentVariable(named = "KIXI_POSTGRES_TESTS", matches = "true")
class SimulationAnswerPostgresConcurrencyTest {
    private static final String SCHEMA = "answer_lock_test";
    private ConnectionFactory connectionFactory;
    private Connection first;
    private Connection second;

    @BeforeEach
    void setUp() {
        connectionFactory = ConnectionFactories.get(ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, env("KIXI_POSTGRES_HOST", "localhost"))
                .option(ConnectionFactoryOptions.PORT, Integer.parseInt(env("KIXI_POSTGRES_PORT", "5434")))
                .option(ConnectionFactoryOptions.DATABASE, env("KIXI_POSTGRES_DATABASE", "prumo"))
                .option(ConnectionFactoryOptions.USER, env("KIXI_POSTGRES_USER", "prumo"))
                .option(ConnectionFactoryOptions.PASSWORD, env("KIXI_POSTGRES_PASSWORD", "change-me"))
                .build());
        first = Mono.from(connectionFactory.create()).block(Duration.ofSeconds(10));
        second = Mono.from(connectionFactory.create()).block(Duration.ofSeconds(10));
        execute(first, "CREATE SCHEMA " + SCHEMA);
        execute(first, "SET search_path TO " + SCHEMA);
        execute(second, "SET search_path TO " + SCHEMA);
        execute(first, "CREATE TABLE exam_rooms (id BIGINT PRIMARY KEY, status VARCHAR(20) NOT NULL)");
        execute(first, "CREATE TABLE simulations (id BIGINT PRIMARY KEY, exam_room_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL)");
        execute(first, "CREATE TABLE simulation_answers (id BIGINT PRIMARY KEY, simulation_id BIGINT NOT NULL, deleted_at TIMESTAMP NULL)");
        execute(first, "INSERT INTO exam_rooms VALUES (1, 'RUNNING')");
        execute(first, "INSERT INTO simulations VALUES (1, 1, 'IN_PROGRESS')");
        execute(first, "INSERT INTO simulation_answers VALUES (1, 1, NULL)");
    }

    @AfterEach
    void tearDown() {
        if (first != null) execute(first, "DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        close(first);
        close(second);
    }

    @Test
    void closeWaitsForAnAnswerWriteThatStartedFirst() {
        execute(first, "BEGIN");
        execute(first, "SELECT * FROM exam_rooms WHERE id = 1 FOR UPDATE");
        execute(first, "SELECT * FROM simulations WHERE id = 1 FOR UPDATE");

        execute(second, "BEGIN");
        Mono<Void> close = Mono.from(second.createStatement(
                "UPDATE exam_rooms SET status = 'CLOSED' WHERE id = 1 AND status = 'RUNNING'").execute())
                .then(Mono.from(second.createStatement("SELECT * FROM simulations WHERE exam_room_id = 1 FOR UPDATE").execute()))
                .then().timeout(Duration.ofSeconds(2)).subscribeOn(Schedulers.boundedElastic()).cache();

        AtomicBoolean closed = new AtomicBoolean();
        close.subscribe(ignored -> { }, error -> { }, () -> closed.set(true));
        try {
            Thread.sleep(200);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        assertThat(closed).isFalse();

        execute(first, "UPDATE simulation_answers SET deleted_at = CURRENT_TIMESTAMP WHERE id = 1");
        execute(first, "COMMIT");
        StepVerifier.create(close).verifyComplete();
        execute(second, "COMMIT");

        assertThat(query(first, "SELECT status FROM exam_rooms WHERE id = 1")).isEqualTo("CLOSED");
        assertThat(query(first, "SELECT deleted_at IS NOT NULL FROM simulation_answers WHERE id = 1"))
                .isEqualTo("true");
    }

    @Test
    void answerWaitsWhenCloseStartedFirstAndCannotWriteAfterClose() {
        execute(first, "BEGIN");
        execute(first, "UPDATE exam_rooms SET status = 'CLOSED' WHERE id = 1 AND status = 'RUNNING'");
        execute(first, "SELECT * FROM simulations WHERE id = 1 FOR UPDATE");

        execute(second, "BEGIN");
        Mono<Void> answer = Mono.from(second.createStatement(
                "SELECT * FROM exam_rooms WHERE id = 1 FOR UPDATE").execute())
                .then(Mono.from(second.createStatement("SELECT * FROM simulations WHERE id = 1 FOR UPDATE").execute()))
                .then(Mono.from(second.createStatement(
                        "UPDATE simulation_answers SET deleted_at = CURRENT_TIMESTAMP WHERE id = 1 "
                                + "AND EXISTS (SELECT 1 FROM simulations WHERE id = 1 AND status = 'IN_PROGRESS') "
                                + "AND EXISTS (SELECT 1 FROM exam_rooms WHERE id = 1 AND status = 'RUNNING')").execute()))
                .then().timeout(Duration.ofSeconds(2)).subscribeOn(Schedulers.boundedElastic()).cache();

        AtomicBoolean answered = new AtomicBoolean();
        answer.subscribe(ignored -> { }, error -> { }, () -> answered.set(true));
        try {
            Thread.sleep(200);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        assertThat(answered).isFalse();

        execute(first, "COMMIT");
        StepVerifier.create(answer).verifyComplete();
        execute(second, "COMMIT");

        assertThat(query(first, "SELECT status FROM exam_rooms WHERE id = 1")).isEqualTo("CLOSED");
        assertThat(query(first, "SELECT deleted_at IS NOT NULL FROM simulation_answers WHERE id = 1"))
                .isEqualTo("false");
    }

    private static void execute(Connection connection, String sql) {
        Mono.from(connection.createStatement(sql).execute()).block(Duration.ofSeconds(10));
    }

    private static String query(Connection connection, String sql) {
        return Mono.from(connection.createStatement(sql).execute())
                .flatMap(result -> Mono.from(result.map((row, metadata) -> String.valueOf(row.get(0)))))
                .block(Duration.ofSeconds(10));
    }

    private static void close(Connection connection) {
        if (connection != null) Mono.from(connection.close()).block(Duration.ofSeconds(10));
    }

    private static String env(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }
}
