package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Questions with alíneas against a migrated database (issue #247): the columns of V47 are
 * written and read back, the items of a group are found in order and by the moment their
 * group was removed, and an item cannot outlive the group row it points to.
 *
 * <p>Ids from 970001, school year 2195-2196, apart from the 960000 block the other
 * Postgres tests clean up.</p>
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
@EnabledIfEnvironmentVariable(named = "KIXI_POSTGRES_TESTS", matches = "true")
class QuestionGroupPostgresTest {

    private static final long STATEMENT = 970001L;
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired QuestionRepository questions;
    @Autowired DatabaseClient database;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String host = env("KIXI_POSTGRES_HOST", "localhost");
        String port = env("KIXI_POSTGRES_PORT", "5433");
        String name = env("KIXI_POSTGRES_DATABASE", "kixi_exam_room_it");
        String user = env("KIXI_POSTGRES_USER", "kixi");
        String password = env("KIXI_POSTGRES_PASSWORD", "kixi_secret");
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + host + ":" + port + "/" + name);
        registry.add("spring.r2dbc.username", () -> user);
        registry.add("spring.r2dbc.password", () -> password);
        registry.add("spring.flyway.url", () -> "jdbc:postgresql://" + host + ":" + port + "/" + name);
        registry.add("spring.flyway.user", () -> user);
        registry.add("spring.flyway.password", () -> password);
    }

    @BeforeEach
    void data() {
        sql("DELETE FROM questions WHERE statement_id = " + STATEMENT);
        sql("DELETE FROM statements WHERE id = " + STATEMENT);
        sql("INSERT INTO statements (id, exam_type, title, visible, created_at) "
                + "VALUES (" + STATEMENT + ", 'Exame', 'Prova de OGI', false, now())");
    }

    /** `position` is the place in the paper: a group, its items, then the next question. */
    private Question save(int number, int position, String type, String text, Long parentId, String label) {
        Question question = new Question(STATEMENT, number, text, type);
        question.setOrderIndex(position);
        question.setParentId(parentId);
        question.setLabel(label);
        return questions.save(question).block(WAIT);
    }

    @Test
    void theItemsOfAGroupAreStoredUnderItAndComeBackInPaperOrder() {
        Question open = save(1, 1, "open", "Questão 1", null, null);
        Question group = save(3, 2, "group", "Verdadeiras ou falsas?", null, null);
        Question a = save(301, 3, "true_false", "Afirmação a", group.getId(), "a");
        Question b = save(302, 4, "true_false", "Afirmação b", group.getId(), "b");
        Question last = save(4, 5, "open", "Questão 4", null, null);

        List<Question> paper = questions.findAllByStatementIdOrderedByOrderIndex(STATEMENT).collectList().block(WAIT);

        // The group comes before its items and the next top-level question after them, which `number`
        // alone could not say (4 < 301): the order of the paper is order_index.
        assertThat(paper).extracting(Question::getNumber).containsExactly(1, 3, 301, 302, 4);
        assertThat(questions.findAllByStatementIdAndDeletedAtIsNull(STATEMENT).collectList().block(WAIT))
                .as("the default listing follows the paper too")
                .extracting(Question::getNumber).containsExactly(1, 3, 301, 302, 4);
        assertThat(paper.get(2).getParentId()).isEqualTo(group.getId());
        assertThat(paper.get(2).getLabel()).isEqualTo("a");
        assertThat(paper.get(0).getParentId()).isNull();
        assertThat(questions.findActiveChildren(group.getId()).collectList().block(WAIT))
                .extracting(Question::getId).containsExactly(a.getId(), b.getId());
        assertThat(questions.calculateTotalMaxScore(STATEMENT).block(WAIT))
                .as("a group has no score and the items here have none either").isZero();
        assertThat(open.getId()).isNotEqualTo(last.getId());
    }

    @Test
    void theItemsAGroupRemovalTookAreFoundByTheMomentItHappened() {
        Question group = save(3, 1, "group", "Enunciado", null, null);
        Question gone = save(301, 2, "open", "Removida antes", group.getId(), "a");
        Question kept = save(302, 3, "open", "Removida com o grupo", group.getId(), "b");
        LocalDateTime before = LocalDateTime.of(2026, 10, 1, 9, 0, 0);
        LocalDateTime withGroup = LocalDateTime.of(2026, 10, 15, 9, 0, 0);
        // Read back before updating, as the services do: the entity in hand does not know the
        // defaults the database filled in (page_index), and writing it back would null them.
        Question goneRow = questions.findById(gone.getId()).block(WAIT);
        goneRow.setDeletedAt(before);
        questions.save(goneRow).block(WAIT);
        Question keptRow = questions.findById(kept.getId()).block(WAIT);
        keptRow.setDeletedAt(withGroup);
        questions.save(keptRow).block(WAIT);

        assertThat(questions.findChildrenDeletedAt(group.getId(), withGroup).collectList().block(WAIT))
                .as("only what the group's own removal took: an item removed earlier stays removed")
                .extracting(Question::getId).containsExactly(kept.getId());
        assertThat(questions.findActiveChildren(group.getId()).collectList().block(WAIT)).isEmpty();
        assertThat(questions.findAllChildren(group.getId()).collectList().block(WAIT)).hasSize(2);
    }

    @Test
    void anItemCannotOutliveTheGroupRowItPointsTo() {
        Question group = save(3, 1, "group", "Enunciado", null, null);
        save(301, 2, "open", "Alínea", group.getId(), "a");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> questions.delete(group).block(WAIT))
                .as("parent_id is a real foreign key: purging the group means purging its items first")
                .hasMessageContaining("parent_id");
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(WAIT);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
