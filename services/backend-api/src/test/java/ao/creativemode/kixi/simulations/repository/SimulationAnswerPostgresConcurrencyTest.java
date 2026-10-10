package ao.creativemode.kixi.simulations.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;

import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import ao.creativemode.kixi.examrooms.service.ExamRoomService;
import ao.creativemode.kixi.simulations.dto.simulationanswer.SimulationAnswerRequest;
import ao.creativemode.kixi.simulations.service.SimulationAnswerService;
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
        sql("DELETE FROM simulation_answers WHERE id = " + ANSWER).block(Duration.ofSeconds(10));
        sql("DELETE FROM simulations WHERE id = " + SIMULATION).block(Duration.ofSeconds(10));
        sql("DELETE FROM exam_rooms WHERE id = " + ROOM).block(Duration.ofSeconds(10));
        sql("DELETE FROM questions WHERE id = " + QUESTION).block(Duration.ofSeconds(10));
        sql("DELETE FROM statements WHERE id = " + STATEMENT).block(Duration.ofSeconds(10));
        sql("DELETE FROM accounts WHERE id = " + ACCOUNT).block(Duration.ofSeconds(10));
        sql("INSERT INTO accounts(id, username, email, password_hash) VALUES (" + ACCOUNT + ", 'it-room', 'it-room@example.com', 'x')").block(Duration.ofSeconds(10));
        sql("INSERT INTO statements(id, title, source, created_by) VALUES (" + STATEMENT + ", 'integration', 'manual', " + ACCOUNT + ")").block(Duration.ofSeconds(10));
        sql("INSERT INTO questions(id, statement_id, number, text, question_type, order_index) VALUES (" + QUESTION + ", " + STATEMENT + ", 1, '1 + 1', 'open', 1)").block(Duration.ofSeconds(10));
        sql("INSERT INTO exam_rooms(id, statement_id, teacher_account_id, starts_at, ends_at, duration_minutes, status) VALUES (" + ROOM + ", " + STATEMENT + ", " + ACCOUNT + ", '" + START + "', '" + END + "', 60, 'RUNNING')").block(Duration.ofSeconds(10));
        sql("INSERT INTO simulations(id, account_id, statement_id, exam_room_id, exam_room_duration_minutes, status) VALUES (" + SIMULATION + ", " + ACCOUNT + ", " + STATEMENT + ", " + ROOM + ", 60, 'IN_PROGRESS')").block(Duration.ofSeconds(10));
        sql("INSERT INTO exam_room_participants(exam_room_id, account_id, simulation_id, joined_at) VALUES (" + ROOM + ", " + ACCOUNT + ", " + SIMULATION + ", CURRENT_TIMESTAMP)").block(Duration.ofSeconds(10));
        sql("INSERT INTO simulation_answers(id, simulation_id, question_id, answer_text) VALUES (" + ANSWER + ", " + SIMULATION + ", " + QUESTION + ", 'old')").block(Duration.ofSeconds(10));
    }

    @Test
    void closeAndAnswerMutationSerializeThroughThePublicServices() {
        Mono<?> close = rooms.transition(ROOM, ACCOUNT, true, ExamRoomStatus.CLOSED)
                .subscribeOn(Schedulers.boundedElastic());
        Mono<?> answer = answerService.updateForAccount(ANSWER,
                new SimulationAnswerRequest(SIMULATION, QUESTION, null, "new", null), ACCOUNT)
                .subscribeOn(Schedulers.boundedElastic());

        Mono.when(close.onErrorResume(error -> Mono.empty()),
                answer.onErrorResume(error -> Mono.empty())).block(Duration.ofSeconds(10));

        assertThat(value("SELECT status FROM exam_rooms WHERE id = " + ROOM)).isEqualTo("CLOSED");
        assertThat(value("SELECT answer_text FROM simulation_answers WHERE id = " + ANSWER)).isIn("old", "new");
    }

    @Test
    void submitAndAnswerWriteSerializeThroughThePublicServices() {
        Mono<?> submit = submissionService.submit(SIMULATION, ACCOUNT, false)
                .subscribeOn(Schedulers.boundedElastic());
        Mono<?> answer = answerService.updateForAccount(ANSWER,
                new SimulationAnswerRequest(SIMULATION, QUESTION, null, "new", null), ACCOUNT)
                .subscribeOn(Schedulers.boundedElastic());

        Mono.when(submit.onErrorResume(error -> Mono.empty()),
                answer.onErrorResume(error -> Mono.empty())).block(Duration.ofSeconds(10));

        assertThat(value("SELECT status FROM simulations WHERE id = " + SIMULATION)).isEqualTo("FINISHED");
        assertThat(value("SELECT answer_text FROM simulation_answers WHERE id = " + ANSWER)).isIn("old", "new");
    }

    private Mono<Void> sql(String statement) {
        return database.sql(statement).then();
    }

    private String value(String statement) {
        return database.sql(statement).map((row, metadata) -> row.get(0, String.class))
                .one().block(Duration.ofSeconds(10));
    }

    private static String env(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }
}
