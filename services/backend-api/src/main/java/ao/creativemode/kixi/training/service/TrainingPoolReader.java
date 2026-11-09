package ao.creativemode.kixi.training.service;

import java.util.Collection;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * O que um aluno pode treinar por tema (kixi#729): as questões respondíveis com esse tema em enunciados de provas
 * anteriores. O conjunto é fechado em SQL e nunca deixa vazar a prova real:
 * <ul>
 *   <li>só enunciados visíveis, ativos, revistos e da mesma disciplina;</li>
 *   <li>nunca o enunciado da própria sala, nem o de qualquer sala que ainda não esteja encerrada, nem o de uma avaliação
 *       do plano do trimestre ({@code term_assessments}), que pode ainda estar por fazer;</li>
 *   <li>nunca o enunciado virtual de um treino (esses são cópias).</li>
 * </ul>
 * Para cada questão diz-se também se o aluno já a tentou (respondeu numa simulação terminada) e se já a acertou, contando
 * as respostas dadas às cópias de treino através da questão de origem.
 */
@Component
public class TrainingPoolReader {

    /** Uma questão do conjunto, para um tema. */
    public record PoolRow(long topicId, long questionId, long statementId, String statementTitle, int year,
            boolean attempted, boolean correct) {}

    private static final String SQL = """
            WITH pool AS (
                SELECT qt.topic_id, q.id AS question_id, st.id AS statement_id, st.title AS statement_title,
                       COALESCE(sy.start_year, EXTRACT(YEAR FROM st.created_at)::int) AS year
                  FROM question_topics qt
                  JOIN subject_topics tp ON tp.id = qt.topic_id AND tp.deleted_at IS NULL
                  JOIN questions q ON q.id = qt.question_id AND q.deleted_at IS NULL AND q.question_type <> 'group'
                  JOIN statements st ON st.id = q.statement_id
                                    AND st.deleted_at IS NULL AND st.visible = TRUE AND st.needs_review = FALSE
                                    AND st.practice_owner_account_id IS NULL
                                    AND st.subject_id = :subject AND st.id <> :roomStatement
                  LEFT JOIN school_years sy ON sy.id = st.school_year_id
                 WHERE qt.topic_id IN (:topics)
                   AND NOT EXISTS (SELECT 1 FROM exam_rooms r WHERE r.statement_id = st.id AND r.status <> 'CLOSED')
                   AND NOT EXISTS (SELECT 1 FROM term_assessments ta WHERE ta.statement_id = st.id)
            ), mine AS (
                SELECT COALESCE(q.source_question_id, q.id) AS original_id,
                       bool_or(a.selected_option_id IS NOT NULL OR COALESCE(btrim(a.answer_text), '') <> '') AS attempted,
                       bool_or(a.is_correct IS TRUE) AS correct
                  FROM simulation_answers a
                  JOIN simulations s ON s.id = a.simulation_id AND s.account_id = :account
                                    AND s.deleted_at IS NULL AND s.status = 'FINISHED'
                  JOIN questions q ON q.id = a.question_id
                 WHERE a.deleted_at IS NULL
                   AND COALESCE(q.source_question_id, q.id) IN (SELECT question_id FROM pool)
                 GROUP BY 1
            )
            SELECT p.topic_id, p.question_id, p.statement_id, p.statement_title, p.year,
                   COALESCE(m.attempted, FALSE) AS attempted, COALESCE(m.correct, FALSE) AS correct
              FROM pool p LEFT JOIN mine m ON m.original_id = p.question_id
             ORDER BY p.topic_id, p.statement_id, p.question_id
            """;

    private final DatabaseClient db;

    public TrainingPoolReader(DatabaseClient db) {
        this.db = db;
    }

    /** Os treinos desta conta, montados desta sala, que ainda estão em curso. */
    public Mono<Long> openPractices(Long accountId, Long roomId) {
        return db.sql("""
                SELECT COUNT(*) AS total
                  FROM simulations s JOIN statements st ON st.id = s.statement_id
                 WHERE s.account_id = :account AND s.status = 'IN_PROGRESS' AND s.deleted_at IS NULL
                   AND st.practice_owner_account_id = :account AND st.practice_exam_room_id = :room
                """)
                .bind("account", accountId).bind("room", roomId)
                .map((row, meta) -> row.get("total", Long.class)).one().defaultIfEmpty(0L);
    }

    public Mono<List<PoolRow>> read(Long accountId, Long subjectId, Long roomStatementId, Collection<Long> topicIds) {
        if (topicIds.isEmpty()) {
            return Mono.just(List.of());
        }
        return db.sql(SQL)
                .bind("subject", subjectId).bind("roomStatement", roomStatementId).bind("account", accountId)
                .bind("topics", topicIds)
                .map((row, meta) -> new PoolRow(row.get("topic_id", Long.class), row.get("question_id", Long.class),
                        row.get("statement_id", Long.class), row.get("statement_title", String.class),
                        row.get("year", Integer.class), Boolean.TRUE.equals(row.get("attempted", Boolean.class)),
                        Boolean.TRUE.equals(row.get("correct", Boolean.class))))
                .all().collectList();
    }
}
