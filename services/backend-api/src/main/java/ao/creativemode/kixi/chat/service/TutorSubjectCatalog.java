package ao.creativemode.kixi.chat.service;

import ao.creativemode.kixi.chat.dto.TutorSource;
import ao.creativemode.kixi.shared.exception.ApiException;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The material of the tutor outside one paper (issue #610): the questions and
 * answer keys of a subject, or of one topic of its exam matrix, in the papers
 * the student may see.
 *
 * <p>The set is closed in SQL and follows the same rule the training pool
 * already uses (kixi#729): only visible, active, reviewed statements of the
 * subject, never a training statement, never a paper that belongs to an exam
 * room that is not closed, and never a term assessment that may still be
 * ahead. That is what "as provas que o aluno pode ver" means here: the tutor
 * cannot quote a paper the student is about to sit.</p>
 *
 * <p>Unlike the paper tutor, this context does carry the answer key. Issue #610
 * asks for it so the tutor can guide the student from the right answer; the
 * prompt says, before the material, that the key is never to be revealed. The
 * student-facing rule itself is unchanged (issues #106 / R1 / R4).</p>
 */
@Service
public class TutorSubjectCatalog {

    /** Papers read for one context: enough to span the subject, small enough for the prompt. */
    static final int MAX_STATEMENTS = 5;
    /** Questions read for one context, across those papers. */
    static final int MAX_QUESTIONS = 30;

    private static final int MAX_MODEL_ANSWER = 1000;

    private static final String STATEMENTS = """
            SELECT st.id, st.title
              FROM statements st
             WHERE st.deleted_at IS NULL
               AND st.visible = TRUE
               AND st.needs_review = FALSE
               AND st.practice_owner_account_id IS NULL
               AND st.subject_id = :subject
               AND EXISTS (SELECT 1 FROM questions q
                            WHERE q.statement_id = st.id
                              AND q.deleted_at IS NULL
                              AND q.needs_review = FALSE
                              AND q.question_type <> 'group')
               AND NOT EXISTS (SELECT 1 FROM exam_rooms r
                                WHERE r.statement_id = st.id AND r.status <> 'CLOSED')
               AND NOT EXISTS (SELECT 1 FROM term_assessments ta
                                WHERE ta.statement_id = st.id)
            """;

    private static final String TOPIC_STATEMENTS = """
               AND EXISTS (SELECT 1 FROM question_topics qt
                             JOIN questions q ON q.id = qt.question_id
                                            AND q.deleted_at IS NULL
                                            AND q.question_type <> 'group'
                            WHERE qt.topic_id = :topic AND q.statement_id = st.id)
            """;

    private final DatabaseClient db;

    public TutorSubjectCatalog(DatabaseClient db) {
        this.db = db;
    }

    /** A choice as the material shows it; {@code correct} is only read for the answer key. */
    public record Choice(String label, String text, boolean correct) {}

    public record Question(long id, Integer number, String text, String modelAnswer, List<Choice> choices) {}

    public record StatementMaterial(long id, String title, List<Question> questions) {}

    /**
     * The context and what the prompt is built from. {@code topicId} is null for
     * a subject-wide session; {@code topicName} then is null too.
     */
    public record Material(long subjectId, String subjectName, Long topicId, String topicName,
            List<StatementMaterial> statements) {

        public boolean isEmpty() {
            return statements.isEmpty();
        }

        /** The papers the answer came from, for "fonte da resposta". */
        public List<TutorSource> sources() {
            return statements.stream()
                    .map(statement -> TutorSource.statement(statement.id(), statement.title()))
                    .toList();
        }

        /** The whole context as plain text, answer keys included and labelled as never to reveal. */
        public String context() {
            StringBuilder context = new StringBuilder("DISCIPLINA: ").append(subjectName).append('\n');
            if (topicName != null) {
                context.append("TEMA: ").append(topicName).append('\n');
            }
            if (isEmpty()) {
                return context.append("MATERIAL DE ESTUDO: ainda não há provas visíveis com questões desta ")
                        .append(topicName != null ? "matéria." : "disciplina.")
                        .append('\n')
                        .toString();
            }
            context.append("MATERIAL DE ESTUDO (questões e gabaritos de provas que o aluno pode ver)\n");
            for (StatementMaterial statement : statements) {
                context.append("\nENUNCIADO \"").append(orEmpty(statement.title())).append("\"\n");
                for (Question question : statement.questions()) {
                    context.append("Questão ").append(question.number() != null ? question.number() : "?")
                            .append(": ").append(orEmpty(question.text())).append('\n');
                    for (Choice choice : question.choices()) {
                        context.append("  ")
                                .append(choice.label() != null ? choice.label() + ") " : "- ")
                                .append(orEmpty(choice.text())).append('\n');
                    }
                    context.append("  GABARITO (nunca revelar): ").append(answerKeyOf(question)).append('\n');
                }
            }
            return context.toString();
        }

        private static String answerKeyOf(Question question) {
            List<String> correct = question.choices().stream()
                    .filter(Choice::correct)
                    .map(choice -> (choice.label() != null ? choice.label() + ") " : "") + orEmpty(choice.text()))
                    .toList();
            if (!correct.isEmpty()) {
                return String.join("; ", correct);
            }
            String written = question.modelAnswer();
            if (written != null && !written.isBlank()) {
                return written.length() <= MAX_MODEL_ANSWER ? written : written.substring(0, MAX_MODEL_ANSWER);
            }
            return "(não disponível)";
        }
    }

    /**
     * Read the context. A topic carries its subject; when both are sent they must
     * agree. An unknown subject or topic is a 404, never a session with nothing to
     * read.
     */
    public Mono<Material> read(Long subjectId, Long topicId) {
        if (topicId != null) {
            return topic(topicId)
                    .flatMap(found -> {
                        if (subjectId != null && !subjectId.equals(found.subjectId())) {
                            return Mono.error(ApiException.badRequest(
                                    "O tema " + topicId + " não pertence à disciplina " + subjectId + "."));
                        }
                        return material(found.subjectId(), found.subjectName(), topicId, found.name());
                    });
        }
        if (subjectId == null) {
            return Mono.error(ApiException.badRequest("Indique subjectId, topicId ou statementId."));
        }
        return subject(subjectId).flatMap(found ->
                material(found.id(), found.name(), null, null));
    }

    private record SubjectRow(long id, String name) {}

    private record TopicRow(long id, String name, long subjectId, String subjectName) {}

    private Mono<SubjectRow> subject(Long subjectId) {
        return db.sql("SELECT id, name FROM subjects WHERE id = :id AND deleted_at IS NULL")
                .bind("id", subjectId)
                .map((row, meta) -> new SubjectRow(row.get("id", Long.class), row.get("name", String.class)))
                .one()
                .switchIfEmpty(Mono.error(() -> ApiException.notFound("Disciplina não encontrada: " + subjectId)));
    }

    private Mono<TopicRow> topic(Long topicId) {
        return db.sql("""
                        SELECT t.id, t.name, su.id AS subject_id, su.name AS subject_name
                          FROM subject_topics t JOIN subjects su ON su.id = t.subject_id
                         WHERE t.id = :id AND t.deleted_at IS NULL AND su.deleted_at IS NULL
                        """)
                .bind("id", topicId)
                .map((row, meta) -> new TopicRow(row.get("id", Long.class), row.get("name", String.class),
                        row.get("subject_id", Long.class), row.get("subject_name", String.class)))
                .one()
                .switchIfEmpty(Mono.error(() -> ApiException.notFound("Tema não encontrado: " + topicId)));
    }

    private Mono<Material> material(long subjectId, String subjectName, Long topicId, String topicName) {
        return statementsOf(subjectId, topicId).collectList().flatMap(statements -> {
            if (statements.isEmpty()) {
                return Mono.just(new Material(subjectId, subjectName, topicId, topicName, List.of()));
            }
            List<Long> ids = statements.stream().map(StatementRow::id).toList();
            return questionsOf(ids, topicId).collectList().flatMap(questions -> {
                if (questions.isEmpty()) {
                    return Mono.just(new Material(subjectId, subjectName, topicId, topicName, List.of()));
                }
                return choicesOf(questions.stream().map(QuestionRow::id).toList()).collectList()
                        .map(choices -> assemble(subjectId, subjectName, topicId, topicName, statements, questions, choices));
            });
        });
    }

    private Flux<StatementRow> statementsOf(long subjectId, Long topicId) {
        String sql = STATEMENTS + (topicId == null ? "" : TOPIC_STATEMENTS)
                + " ORDER BY st.created_at DESC, st.id DESC LIMIT :limit";
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql)
                .bind("subject", subjectId)
                .bind("limit", MAX_STATEMENTS);
        if (topicId != null) {
            spec = spec.bind("topic", topicId);
        }
        return spec.map((row, meta) -> new StatementRow(row.get("id", Long.class), row.get("title", String.class)))
                .all();
    }

    private record StatementRow(long id, String title) {}

    private Flux<QuestionRow> questionsOf(List<Long> statementIds, Long topicId) {
        String sql = """
                SELECT q.id, q.statement_id, q.number, q.text, q.model_answer
                  FROM questions q
                  JOIN statements st ON st.id = q.statement_id
                 WHERE q.statement_id IN (:statements)
                   AND q.deleted_at IS NULL
                   AND q.needs_review = FALSE
                   AND q.question_type <> 'group'
                """ + (topicId == null ? "" : """
                   AND EXISTS (SELECT 1 FROM question_topics qt
                                WHERE qt.question_id = q.id AND qt.topic_id = :topic)
                """) + """
                 ORDER BY st.created_at DESC, st.id DESC, q.order_index, q.id
                 LIMIT :limit
                """;
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql)
                .bind("statements", statementIds)
                .bind("limit", MAX_QUESTIONS);
        if (topicId != null) {
            spec = spec.bind("topic", topicId);
        }
        return spec.map((row, meta) -> new QuestionRow(row.get("id", Long.class),
                        row.get("statement_id", Long.class), row.get("number", Integer.class),
                        row.get("text", String.class), row.get("model_answer", String.class)))
                .all();
    }

    private record QuestionRow(long id, long statementId, Integer number, String text, String modelAnswer) {}

    private Flux<ChoiceRow> choicesOf(List<Long> questionIds) {
        return db.sql("""
                        SELECT question_id, option_label, option_text, is_correct
                          FROM question_options
                         WHERE question_id IN (:questions) AND deleted_at IS NULL
                         ORDER BY question_id, order_index, id
                        """)
                .bind("questions", questionIds)
                .map((row, meta) -> new ChoiceRow(row.get("question_id", Long.class),
                        row.get("option_label", String.class), row.get("option_text", String.class),
                        Boolean.TRUE.equals(row.get("is_correct", Boolean.class))))
                .all();
    }

    private record ChoiceRow(long questionId, String label, String text, boolean correct) {}

    private static Material assemble(long subjectId, String subjectName, Long topicId, String topicName,
            List<StatementRow> statements, List<QuestionRow> questions, List<ChoiceRow> choices) {
        Map<Long, List<ChoiceRow>> choicesByQuestion = choices.stream()
                .collect(Collectors.groupingBy(ChoiceRow::questionId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<QuestionRow>> questionsByStatement = questions.stream()
                .collect(Collectors.groupingBy(QuestionRow::statementId, LinkedHashMap::new, Collectors.toList()));

        List<StatementMaterial> material = new ArrayList<>();
        for (StatementRow statement : statements) {
            List<QuestionRow> found = questionsByStatement.get(statement.id());
            if (found == null || found.isEmpty()) {
                continue;
            }
            List<Question> ordered = found.stream()
                    .map(question -> new Question(question.id(), question.number(), question.text(),
                            question.modelAnswer(),
                            choicesByQuestion.getOrDefault(question.id(), List.of()).stream()
                                    .map(choice -> new Choice(choice.label(), choice.text(), choice.correct()))
                                    .toList()))
                    .toList();
            material.add(new StatementMaterial(statement.id(), statement.title(), ordered));
        }
        return new Material(subjectId, subjectName, topicId, topicName, List.copyOf(material));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
