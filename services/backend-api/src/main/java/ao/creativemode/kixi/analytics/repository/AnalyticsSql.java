package ao.creativemode.kixi.analytics.repository;

/**
 * The analytics queries. Pure strings, so the scope rules can be checked without a
 * database (AnalyticsSqlTest) and run against one (AnalyticsPostgresTest).
 *
 * <p>Every query starts from {@link #SCOPE}, which narrows the data to what the
 * caller may see. An ADMIN ({@code :admin = true}) sees everything. A TEACHER sees
 * the classes of their teaching assignments and, inside them, only the statements
 * of the exact class and subject pair they teach: the same pair that
 * {@code TeachingAssignmentService.requireTeaches} demands before a teacher may
 * write a statement. Simulations follow their statement. A statement without a
 * class belongs to no teacher's numbers.</p>
 *
 * <p>Optional filters narrow the scope further, never widen it:
 * {@code :filterInstitution} and {@code :filterClass} (Long, 0 for "all"). {@code :wide}
 * (Boolean) is an ADMIN with no filter: the only case where school-wide counts that do
 * not go through a class (students by role, every teacher) apply.</p>
 *
 * <p>Binds: {@code :accountId} (Long), {@code :admin}, {@code :wide} (Boolean) and the
 * two filters on every query,
 * plus the ones each query names.</p>
 */
public final class AnalyticsSql {

    private AnalyticsSql() {
    }

    /** A score counts as a pass from half of the statement's maximum. */
    public static final int PASS_PERCENT = 50;

    static final String SCOPE = """
            WITH scope_pairs AS (
                SELECT ta.class_id, ta.subject_id
                FROM teaching_assignments ta
                JOIN teachers t ON t.id = ta.teacher_id
                WHERE ta.deleted_at IS NULL
                  AND t.deleted_at IS NULL
                  AND t.account_id = :accountId
            ),
            scoped_classes AS (
                SELECT c.*
                FROM classes c
                WHERE c.deleted_at IS NULL
                  AND (:admin OR c.id IN (SELECT class_id FROM scope_pairs))
                  AND (CAST(:filterInstitution AS bigint) = 0 OR c.institution_id = :filterInstitution)
                  AND (CAST(:filterClass AS bigint) = 0 OR c.id = :filterClass)
            ),
            scoped_statements AS (
                SELECT s.*, COALESCE(s.institution_id, cl.institution_id) AS owner_institution_id
                FROM statements s
                LEFT JOIN classes cl ON cl.id = s.class_id
                WHERE s.deleted_at IS NULL
                  AND (:admin OR EXISTS (
                      SELECT 1 FROM scope_pairs p
                      WHERE p.class_id = s.class_id AND p.subject_id = s.subject_id))
                  AND (CAST(:filterInstitution AS bigint) = 0
                       OR COALESCE(s.institution_id, cl.institution_id) = :filterInstitution)
                  AND (CAST(:filterClass AS bigint) = 0 OR s.class_id = :filterClass)
            ),
            scored AS (
                SELECT sim.id, sim.account_id, sim.statement_id, sim.status, sim.finished_at,
                       sim.time_spent_seconds, ss.class_id, ss.owner_institution_id,
                       CASE WHEN sim.status = 'FINISHED' AND sim.final_score IS NOT NULL AND m.max_score > 0
                            THEN sim.final_score / m.max_score * 100 END AS pct
                FROM simulations sim
                JOIN scoped_statements ss ON ss.id = sim.statement_id
                LEFT JOIN LATERAL (
                    SELECT COALESCE(NULLIF(ss.total_max_score, 0),
                                    (SELECT SUM(q.max_score) FROM questions q
                                     WHERE q.statement_id = ss.id AND q.deleted_at IS NULL)) AS max_score
                ) m ON TRUE
                WHERE sim.deleted_at IS NULL
                  AND EXISTS (SELECT 1 FROM accounts sa WHERE sa.id = sim.account_id AND sa.deleted_at IS NULL)
            )
            """;

    /** A question with active options and none of them marked correct. */
    private static final String MISSING_KEY = """
            EXISTS (
                SELECT 1 FROM questions q
                WHERE q.statement_id = ss.id AND q.deleted_at IS NULL
                  AND EXISTS (SELECT 1 FROM question_options o
                              WHERE o.question_id = q.id AND o.deleted_at IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM question_options o
                                  WHERE o.question_id = q.id AND o.deleted_at IS NULL AND o.is_correct))
            """;

    static String overview() {
        return SCOPE + """
                SELECT
                  (SELECT COUNT(*) FROM institutions i WHERE i.deleted_at IS NULL
                      AND (:wide OR i.id IN (SELECT institution_id FROM scoped_classes))) AS institutions,
                  (SELECT COUNT(*) FROM scoped_classes) AS classes,
                  (SELECT CASE WHEN :wide THEN
                      (SELECT COUNT(DISTINCT a.id) FROM accounts a
                       JOIN account_roles ar ON ar.account_id = a.id AND ar.deleted_at IS NULL
                       JOIN roles r ON r.id = ar.role_id AND r.name = 'STUDENT' AND r.deleted_at IS NULL
                       WHERE a.deleted_at IS NULL)
                    ELSE (SELECT COUNT(DISTINCT e.account_id) FROM enrollments e
                          WHERE e.deleted_at IS NULL AND e.status = 'ACTIVE' AND e.class_id IN (SELECT id FROM scoped_classes)) END) AS students,
                  (SELECT CASE WHEN :wide THEN (SELECT COUNT(*) FROM teachers t WHERE t.deleted_at IS NULL)
                    ELSE (SELECT COUNT(DISTINCT ta.teacher_id) FROM teaching_assignments ta
                          WHERE ta.deleted_at IS NULL AND ta.class_id IN (SELECT id FROM scoped_classes)) END) AS teachers,
                  (SELECT COUNT(*) FROM scoped_statements) AS statements_total,
                  (SELECT COUNT(*) FROM scoped_statements WHERE visible) AS statements_published,
                  (SELECT COUNT(*) FROM scoped_statements WHERE needs_review) AS statements_review,
                  (SELECT COUNT(*) FROM scoped_statements ss WHERE\s""" + MISSING_KEY + """
                  ) AS statements_missing_key,
                  (SELECT COUNT(*) FROM scored) AS simulations_total,
                  (SELECT COUNT(*) FROM scored WHERE status = 'FINISHED') AS simulations_finished,
                  (SELECT COUNT(*) FROM scored WHERE status = 'IN_PROGRESS') AS simulations_in_progress,
                  (SELECT COUNT(*) FROM scored WHERE status = 'CANCELLED') AS simulations_cancelled,
                  (SELECT COUNT(DISTINCT account_id) FROM scored) AS simulations_students,
                  (SELECT ROUND(AVG(pct), 1) FROM scored) AS average_pct,
                  (SELECT ROUND(100.0 * COUNT(*) FILTER (WHERE pct >=\s""" + PASS_PERCENT + """
                      ) / NULLIF(COUNT(pct), 0), 1) FROM scored) AS pass_pct,
                  (SELECT ROUND(AVG(time_spent_seconds)) FROM scored
                      WHERE status = 'FINISHED' AND time_spent_seconds IS NOT NULL) AS average_time
                """;
    }

    /** Binds {@code :days}: the last N UTC days, today included, with empty days as zero. */
    static String activity() {
        return SCOPE + """
                , days AS (
                    SELECT generate_series(
                        (now() AT TIME ZONE 'UTC')::date - (CAST(:days AS integer) - 1),
                        (now() AT TIME ZONE 'UTC')::date,
                        INTERVAL '1 day')::date AS day
                )
                SELECT d.day AS day,
                       COUNT(sc.id) AS finished,
                       ROUND(AVG(sc.pct), 1) AS average_pct
                FROM days d
                LEFT JOIN scored sc ON sc.status = 'FINISHED'
                     AND (sc.finished_at AT TIME ZONE 'UTC')::date = d.day
                GROUP BY d.day
                ORDER BY d.day
                """;
    }

    static String institutions() {
        return SCOPE + """
                SELECT i.id, i.code, i.name,
                  (SELECT COUNT(*) FROM courses co WHERE co.institution_id = i.id AND co.deleted_at IS NULL
                      AND (:wide OR co.id IN (SELECT course_id FROM scoped_classes))) AS courses,
                  (SELECT COUNT(*) FROM scoped_classes c WHERE c.institution_id = i.id) AS classes,
                  (SELECT COUNT(DISTINCT e.account_id) FROM enrollments e
                      JOIN scoped_classes c ON c.id = e.class_id
                      WHERE e.deleted_at IS NULL AND e.status = 'ACTIVE' AND c.institution_id = i.id) AS students,
                  (SELECT COUNT(*) FROM scored sc
                      WHERE sc.owner_institution_id = i.id AND sc.status = 'FINISHED') AS finished,
                  (SELECT ROUND(AVG(sc.pct), 1) FROM scored sc WHERE sc.owner_institution_id = i.id) AS average_pct,
                  (SELECT ROUND(AVG(sc.time_spent_seconds)) FROM scored sc
                      WHERE sc.owner_institution_id = i.id AND sc.status = 'FINISHED') AS average_time
                FROM institutions i
                WHERE i.deleted_at IS NULL
                  AND (:wide OR i.id IN (SELECT institution_id FROM scoped_classes))
                ORDER BY i.name
                """;
    }

    static String classes() {
        return SCOPE + """
                SELECT c.id, c.code, c.grade, co.name AS course, i.name AS institution,
                  (SELECT COUNT(DISTINCT e.account_id) FROM enrollments e
                      WHERE e.class_id = c.id AND e.deleted_at IS NULL AND e.status = 'ACTIVE') AS students,
                  (SELECT COUNT(DISTINCT ta.teacher_id) FROM teaching_assignments ta
                      WHERE ta.class_id = c.id AND ta.deleted_at IS NULL) AS teachers,
                  (SELECT COUNT(*) FROM scoped_statements ss WHERE ss.class_id = c.id) AS statements,
                  (SELECT COUNT(*) FROM scored sc WHERE sc.class_id = c.id AND sc.status = 'FINISHED') AS finished,
                  (SELECT ROUND(AVG(sc.pct), 1) FROM scored sc WHERE sc.class_id = c.id) AS average_pct,
                  (SELECT ROUND(AVG(sc.time_spent_seconds)) FROM scored sc
                      WHERE sc.class_id = c.id AND sc.status = 'FINISHED') AS average_time
                FROM scoped_classes c
                JOIN courses co ON co.id = c.course_id
                LEFT JOIN institutions i ON i.id = c.institution_id
                ORDER BY i.name NULLS LAST, c.grade DESC, c.code
                """;
    }

    /** Binds {@code :limit}. The most attempted first. */
    static String statements() {
        return SCOPE + """
                SELECT ss.id, ss.title, ss.exam_type, cl.code AS class_code, sub.name AS subject,
                  ss.visible AS published, ss.needs_review,
                  NOT\s""" + MISSING_KEY + """
                  AS answer_key_complete,
                  (SELECT COUNT(*) FROM questions q WHERE q.statement_id = ss.id AND q.deleted_at IS NULL) AS questions,
                  COUNT(sc.id) FILTER (WHERE sc.status = 'FINISHED') AS finished,
                  COUNT(DISTINCT sc.account_id) FILTER (WHERE sc.status = 'FINISHED') AS students,
                  ROUND(AVG(sc.pct), 1) AS average_pct,
                  ROUND(100.0 * COUNT(sc.pct) FILTER (WHERE sc.pct >=\s""" + PASS_PERCENT + """
                      ) / NULLIF(COUNT(sc.pct), 0), 1) AS pass_pct,
                  ROUND(AVG(sc.time_spent_seconds) FILTER (WHERE sc.status = 'FINISHED')) AS average_time
                FROM scoped_statements ss
                LEFT JOIN classes cl ON cl.id = ss.class_id
                LEFT JOIN subjects sub ON sub.id = ss.subject_id
                LEFT JOIN scored sc ON sc.statement_id = ss.id
                GROUP BY ss.id, ss.title, ss.exam_type, cl.code, sub.name, ss.visible, ss.needs_review
                ORDER BY finished DESC, ss.id DESC
                LIMIT :limit
                """;
    }

    /** Binds {@code :statementId}. 0 when it does not exist, 1 when it exists but is out of scope, 2 when in scope. */
    static String statementAccess() {
        return SCOPE + """
                SELECT CASE
                  WHEN EXISTS (SELECT 1 FROM scoped_statements WHERE id = :statementId) THEN 2
                  WHEN EXISTS (SELECT 1 FROM statements WHERE id = :statementId AND deleted_at IS NULL) THEN 1
                  ELSE 0 END AS access
                """;
    }

    /** Binds {@code :statementId}. Answers are counted over finished simulations only. */
    static String statementQuestions() {
        return SCOPE + """
                , attempts AS (
                    SELECT sc.id FROM scored sc WHERE sc.statement_id = :statementId AND sc.status = 'FINISHED'
                )
                SELECT q.id, q.number, q.text, q.question_type, q.max_score,
                  (SELECT COUNT(*) FROM attempts) AS attempts,
                  COUNT(a.id) FILTER (WHERE a.selected_option_id IS NOT NULL
                                        OR NULLIF(BTRIM(a.answer_text), '') IS NOT NULL) AS answered,
                  COUNT(a.id) FILTER (WHERE a.is_correct) AS correct,
                  EXISTS (SELECT 1 FROM question_options o
                          WHERE o.question_id = q.id AND o.deleted_at IS NULL) AS auto_graded
                FROM questions q
                LEFT JOIN simulation_answers a ON a.question_id = q.id AND a.deleted_at IS NULL
                     AND a.simulation_id IN (SELECT id FROM attempts)
                WHERE q.statement_id = :statementId AND q.deleted_at IS NULL
                GROUP BY q.id, q.number, q.text, q.question_type, q.max_score
                ORDER BY q.number, q.id
                """;
    }

    /** Binds {@code :classId}. 0 when it does not exist, 1 when out of scope, 2 when in scope. */
    static String classAccess() {
        return SCOPE + """
                SELECT CASE
                  WHEN EXISTS (SELECT 1 FROM scoped_classes WHERE id = :classId) THEN 2
                  WHEN EXISTS (SELECT 1 FROM classes WHERE id = :classId AND deleted_at IS NULL) THEN 1
                  ELSE 0 END AS access
                """;
    }

    /** Binds {@code :classId}. Students with the lowest average first, those with no work before them. */
    static String classStudents() {
        return SCOPE + """
                , roster AS (
                    SELECT DISTINCT e.account_id FROM enrollments e
                    WHERE e.class_id = :classId AND e.deleted_at IS NULL AND e.status = 'ACTIVE'
                )
                SELECT r.account_id,
                  COALESCE(NULLIF(BTRIM(CONCAT_WS(' ', u.first_name, u.last_name)), ''), a.username) AS name,
                  COUNT(sc.id) FILTER (WHERE sc.status = 'FINISHED') AS finished,
                  ROUND(AVG(sc.pct), 1) AS average_pct,
                  MAX(sc.finished_at) AS last_finished_at
                FROM roster r
                JOIN accounts a ON a.id = r.account_id AND a.deleted_at IS NULL
                LEFT JOIN users u ON u.account_id = r.account_id AND u.deleted_at IS NULL
                LEFT JOIN scored sc ON sc.account_id = r.account_id AND sc.class_id = :classId
                GROUP BY r.account_id, u.first_name, u.last_name, a.username
                ORDER BY average_pct ASC NULLS FIRST, name
                """;
    }

    /** Binds {@code :classId} and {@code :weeks}: the last N weeks, Monday-based, UTC. */
    static String classWeekly() {
        return SCOPE + """
                , weeks AS (
                    SELECT generate_series(
                        date_trunc('week', now() AT TIME ZONE 'UTC')::date - 7 * (CAST(:weeks AS integer) - 1),
                        date_trunc('week', now() AT TIME ZONE 'UTC')::date,
                        INTERVAL '7 days')::date AS week
                )
                SELECT w.week AS week, COUNT(sc.id) AS finished, ROUND(AVG(sc.pct), 1) AS average_pct
                FROM weeks w
                LEFT JOIN scored sc ON sc.class_id = :classId AND sc.status = 'FINISHED'
                     AND date_trunc('week', sc.finished_at AT TIME ZONE 'UTC')::date = w.week
                GROUP BY w.week
                ORDER BY w.week
                """;
    }

    /** Binds {@code :classId} and {@code :limit}. Auto-graded questions with attempts, most missed first. */
    static String classHardestQuestions() {
        return SCOPE + """
                , attempts AS (
                    SELECT sc.id, sc.statement_id FROM scored sc
                    WHERE sc.class_id = :classId AND sc.status = 'FINISHED'
                ),
                per_question AS (
                    SELECT q.id, q.number, q.text, q.question_type, q.max_score,
                           ss.id AS statement_id, ss.title AS statement_title,
                      (SELECT COUNT(*) FROM attempts t WHERE t.statement_id = ss.id) AS attempts,
                      (SELECT COUNT(*) FROM simulation_answers a
                          WHERE a.question_id = q.id AND a.deleted_at IS NULL AND a.is_correct
                            AND a.simulation_id IN (SELECT id FROM attempts)) AS correct
                    FROM scoped_statements ss
                    JOIN questions q ON q.statement_id = ss.id AND q.deleted_at IS NULL
                    WHERE ss.class_id = :classId
                      AND EXISTS (SELECT 1 FROM question_options o
                                  WHERE o.question_id = q.id AND o.deleted_at IS NULL)
                )
                SELECT pq.*, ROUND(100.0 * pq.correct / pq.attempts, 1) AS correct_pct
                FROM per_question pq
                WHERE pq.attempts > 0
                ORDER BY correct_pct ASC, pq.attempts DESC, pq.id
                LIMIT :limit
                """;
    }
}
