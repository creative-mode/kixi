package ao.creativemode.kixi.simulations.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import ao.creativemode.kixi.examrooms.service.ExamRoomService;
import ao.creativemode.kixi.simulations.dto.simulation.SimulationRequest;
import ao.creativemode.kixi.simulations.dto.simulationanswer.SimulationAnswerRequest;
import ao.creativemode.kixi.simulations.dto.simulation.SimulationResponse;
import ao.creativemode.kixi.simulations.service.SimulationAnswerService;
import ao.creativemode.kixi.simulations.service.SimulationService;
import ao.creativemode.kixi.simulations.service.SimulationSubmissionService;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.Signal;

/** Runs the public services against a database migrated by Flyway V1 through V37. */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
@EnabledIfEnvironmentVariable(named = "KIXI_POSTGRES_TESTS", matches = "true")
class SimulationAnswerPostgresConcurrencyTest {
    private static final long ACCOUNT = 910001L;
    private static final long STATEMENT = 910001L;
    private static final long QUESTION = 910001L;
    private static final long ROOM = 910001L;
    private static final long SIMULATION = 910001L;
    private static final long ANSWER = 910001L;
    private static final LocalDateTime START = LocalDateTime.now().minusMinutes(5);
    private static final LocalDateTime END = LocalDateTime.now().plusHours(1);

    @Autowired DatabaseClient database;
    @Autowired ExamRoomService rooms;
    @Autowired SimulationAnswerService answerService;
    @Autowired SimulationSubmissionService submissionService;
    @Autowired SimulationService simulationService;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String host = env("KIXI_POSTGRES_HOST", "localhost");
        String port = env("KIXI_POSTGRES_PORT", "5433");
        String database = env("KIXI_POSTGRES_DATABASE", "kixi_exam_room_it");
        String user = env("KIXI_POSTGRES_USER", "kixi");
        String password = env("KIXI_POSTGRES_PASSWORD", "kixi_secret");
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + host + ":" + port + "/" + database);
        registry.add("spring.r2dbc.username", () -> user);
        registry.add("spring.r2dbc.password", () -> password);
        registry.add("spring.flyway.url", () -> "jdbc:postgresql://" + host + ":" + port + "/" + database);
        registry.add("spring.flyway.user", () -> user);
        registry.add("spring.flyway.password", () -> password);
    }

    @BeforeEach
    void data() {
        sql("DELETE FROM exam_room_participants WHERE exam_room_id = " + ROOM).block(Duration.ofSeconds(10));
        sql("DELETE FROM simulation_answers WHERE simulation_id IN (SELECT id FROM simulations WHERE statement_id = "
                + STATEMENT + ")").block(Duration.ofSeconds(10));
        sql("DELETE FROM simulations WHERE statement_id = " + STATEMENT).block(Duration.ofSeconds(10));
        sql("DELETE FROM exam_rooms WHERE id = " + ROOM).block(Duration.ofSeconds(10));
        sql("DELETE FROM questions WHERE id = " + QUESTION).block(Duration.ofSeconds(10));
        sql("DELETE FROM statements WHERE id = " + STATEMENT).block(Duration.ofSeconds(10));
        sql("DELETE FROM accounts WHERE id = " + ACCOUNT).block(Duration.ofSeconds(10));
        sql("INSERT INTO accounts(id, username, email, password_hash) VALUES (" + ACCOUNT + ", 'it-room', 'it-room@example.com', 'x')").block(Duration.ofSeconds(10));
        sql("INSERT INTO statements(id, title, source, created_by) VALUES (" + STATEMENT + ", 'integration', 'manual', " + ACCOUNT + ")").block(Duration.ofSeconds(10));
        sql("INSERT INTO questions(id, statement_id, number, text, question_type, order_index) VALUES (" + QUESTION + ", " + STATEMENT + ", 1, '1 + 1', 'open', 1)").block(Duration.ofSeconds(10));
        sql("INSERT INTO exam_rooms(id, statement_id, teacher_account_id, starts_at, ends_at, duration_minutes, status) VALUES (" + ROOM + ", " + STATEMENT + ", " + ACCOUNT + ", '" + START + "', '" + END + "', 60, 'RUNNING')").block(Duration.ofSeconds(10));
        sql("INSERT INTO simulations(id, account_id, statement_id, exam_room_id, exam_room_duration_minutes, started_at, status) VALUES (" + SIMULATION + ", " + ACCOUNT + ", " + STATEMENT + ", " + ROOM + ", 60, CURRENT_TIMESTAMP, 'IN_PROGRESS')").block(Duration.ofSeconds(10));
        sql("INSERT INTO exam_room_participants(exam_room_id, account_id, simulation_id, joined_at) VALUES (" + ROOM + ", " + ACCOUNT + ", " + SIMULATION + ", CURRENT_TIMESTAMP)").block(Duration.ofSeconds(10));
        sql("INSERT INTO simulation_answers(id, simulation_id, question_id, answer_text) VALUES (" + ANSWER + ", " + SIMULATION + ", " + QUESTION + ", 'old')").block(Duration.ofSeconds(10));
    }

    @Test
    void closeAndAnswerMutationSerializeThroughThePublicServices() {
        Pair<Outcome<Object>, Outcome<Object>> closeFirst = race(
                close(), answer());
        assertCloseRace(closeFirst, true);

        data();
        Pair<Outcome<Object>, Outcome<Object>> answerFirst = race(
                answer(), close());
        assertCloseRace(answerFirst, false);
    }

    @Test
    void submitAndAnswerWriteSerializeThroughThePublicServices() {
        Pair<Outcome<Object>, Outcome<Object>> submitFirst = race(
                submit().cast(Object.class), answer());
        assertSubmitRace(submitFirst, true);

        data();
        Pair<Outcome<Object>, Outcome<Object>> answerFirst = race(
                answer(), submit().cast(Object.class));
        assertSubmitRace(answerFirst, false);
    }

    @Test
    void normalSimulationCreationAndOpeningRaceThroughRealServicesAndLocks() {
        sql("DELETE FROM exam_room_participants WHERE exam_room_id = " + ROOM).block(Duration.ofSeconds(10));
        sql("DELETE FROM simulation_answers WHERE simulation_id = " + SIMULATION).block(Duration.ofSeconds(10));
        sql("DELETE FROM simulations WHERE id = " + SIMULATION).block(Duration.ofSeconds(10));
        sql("UPDATE exam_rooms SET status = 'DRAFT' WHERE id = " + ROOM).block(Duration.ofSeconds(10));

        SimulationRequest request = new SimulationRequest(ACCOUNT, STATEMENT, null, null, null, null, null, null);
        Pair<Outcome<SimulationResponse>, Outcome<Object>> createFirst = race(
                simulationService.createForAccount(request, ACCOUNT),
                open());

        assertOpenRace(createFirst, false);

        sql("DELETE FROM simulations WHERE statement_id = " + STATEMENT + " AND exam_room_id IS NULL")
                .block(Duration.ofSeconds(10));
        sql("UPDATE exam_rooms SET status = 'DRAFT' WHERE id = " + ROOM).block(Duration.ofSeconds(10));

        Pair<Outcome<Object>, Outcome<SimulationResponse>> openFirst = race(
                open(),
                simulationService.createForAccount(request, ACCOUNT));

        assertOpenRace(openFirst, true);
    }

    private Mono<Object> answer() {
        return answerService.updateForAccount(ANSWER,
                new SimulationAnswerRequest(SIMULATION, QUESTION, null, "new", null), ACCOUNT)
                .cast(Object.class);
    }

    private Mono<Object> close() {
        return rooms.transition(ROOM, ACCOUNT, true, ExamRoomStatus.CLOSED).cast(Object.class);
    }

    private Mono<Object> open() {
        return rooms.transition(ROOM, ACCOUNT, true, ExamRoomStatus.OPEN).cast(Object.class);
    }

    private Mono<Object> submit() {
        return submissionService.submit(SIMULATION, ACCOUNT, false).cast(Object.class);
    }

    private <F, S> Pair<Outcome<F>, Outcome<S>> race(Mono<F> first, Mono<S> second) {
        CountDownLatch firstSubscribed = new CountDownLatch(1);
        Mono<Outcome<F>> firstOutcome = first
                .doOnSubscribe(ignored -> firstSubscribed.countDown())
                .materialize().map(Outcome::new).subscribeOn(Schedulers.boundedElastic());
        Mono<Outcome<S>> secondOutcome = Mono.defer(() -> {
            try {
                assertThat(firstSubscribed.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Mono.error(interrupted);
            }
            return second;
        }).materialize().map(Outcome::new).subscribeOn(Schedulers.boundedElastic());
        reactor.util.function.Tuple2<Outcome<F>, Outcome<S>> result = Mono.zip(firstOutcome, secondOutcome)
                .block(Duration.ofSeconds(20));
        return new Pair<>(result.getT1(), result.getT2());
    }

    private void assertCloseRace(Pair<?, ?> result, boolean closeIsFirst) {
        Outcome<?> first = (Outcome<?>) result.first();
        Outcome<?> second = (Outcome<?>) result.second();
        Outcome<?> close = closeIsFirst ? first : second;
        Outcome<?> answer = closeIsFirst ? second : first;
        assertThat(close.error()).isNull();
        assertOptionalAnswerConflict(answer);
        assertThat(value("SELECT status FROM exam_rooms WHERE id = " + ROOM)).isEqualTo("CLOSED");
        assertAnswerResult(answer);
    }

    private void assertSubmitRace(Pair<?, ?> result, boolean submitIsFirst) {
        Outcome<?> first = (Outcome<?>) result.first();
        Outcome<?> second = (Outcome<?>) result.second();
        Outcome<?> submit = submitIsFirst ? first : second;
        Outcome<?> answer = submitIsFirst ? second : first;
        assertThat(submit.error()).isNull();
        assertOptionalAnswerConflict(answer);
        assertThat(value("SELECT status FROM simulations WHERE id = " + SIMULATION)).isEqualTo("FINISHED");
        assertAnswerResult(answer);
    }

    private void assertOpenRace(Pair<?, ?> result, boolean openIsFirst) {
        Outcome<?> first = (Outcome<?>) result.first();
        Outcome<?> second = (Outcome<?>) result.second();
        Outcome<?> open = openIsFirst ? first : second;
        Outcome<?> create = openIsFirst ? second : first;
        assertThat(open.error()).isNull();
        assertThat(value("SELECT status FROM exam_rooms WHERE id = " + ROOM)).isEqualTo("OPEN");
        long simulations = count("SELECT COUNT(*) FROM simulations WHERE statement_id = " + STATEMENT
                + " AND exam_room_id IS NULL AND account_id = " + ACCOUNT + " AND status = 'IN_PROGRESS'");
        assertThat(simulations).isIn(0L, 1L);
        if (create.error() != null) {
            assertConflict(create);
            assertThat(simulations).isZero();
        } else {
            assertThat(simulations).isEqualTo(1L);
        }
    }

    private void assertOptionalAnswerConflict(Outcome<?> answer) {
        if (answer.error() != null) assertConflict(answer);
    }

    private void assertAnswerResult(Outcome<?> answerOutcome) {
        String answerText = value("SELECT answer_text FROM simulation_answers WHERE id = " + ANSWER);
        if (answerOutcome.error() != null) {
            assertThat(answerText).isEqualTo("old");
        } else {
            assertThat(answerText).isEqualTo("new");
        }
    }

    private void assertConflict(Outcome<?> outcome) {
        if (outcome.error() == null) fail("Expected a conflict, but the operation succeeded");
        assertThat(outcome.error()).isInstanceOf(ao.creativemode.kixi.shared.exception.ApiException.class);
        assertThat(((ao.creativemode.kixi.shared.exception.ApiException) outcome.error()).getStatus().value())
                .isEqualTo(409);
    }

    private Mono<Void> sql(String statement) {
        return database.sql(statement).then();
    }

    private String value(String statement) {
        return database.sql(statement).map((row, metadata) -> row.get(0, String.class))
                .one().block(Duration.ofSeconds(10));
    }

    private long count(String statement) {
        return database.sql(statement).map((row, metadata) -> row.get(0, Long.class))
                .one().block(Duration.ofSeconds(10));
    }

    private record Pair<F, S>(F first, S second) {}

    private record Outcome<T>(Signal<T> signal) {
        Throwable error() {
            return signal.isOnError() ? signal.getThrowable() : null;
        }
    }

    private static String env(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }
}
