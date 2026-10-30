package ao.creativemode.kixi.register;

import ao.creativemode.kixi.shared.exception.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * O registo escolar que o motor SchoolOps escreve (kixi#501): notas por prova, linhas das pautas, estado da pauta e
 * reclamações. Cada escrita substitui por chave, por isso o motor pode repeti-la sem efeito. O que o motor manda vem
 * como {@code Map}: as colunas guardam o que se consulta (nota, estado) e o {@code payload} o resto, para o contrato
 * do motor poder crescer sem migração.
 */
@Service
public class SchoolRegisterService {

    private static final BigDecimal MAX = new BigDecimal("20");

    private final DatabaseClient db;
    private final ObjectMapper mapper;

    public SchoolRegisterService(DatabaseClient db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    // ---- notas por prova ----

    /** Substitui as notas da prova pelas recebidas. Um aluno que já não vem é retirado (a prova é refeita por inteiro). */
    @Transactional
    public Mono<Void> replaceExamGrades(Long statementId, List<Map<String, Object>> grades) {
        Mono<Void> clear = db.sql("DELETE FROM register_exam_grades WHERE statement_id = :s")
                .bind("s", statementId).fetch().rowsUpdated().then();
        return clear.thenMany(Flux.fromIterable(grades).concatMap(grade -> upsertExamGrade(statementId, grade))).then();
    }

    private Mono<Void> upsertExamGrade(Long statementId, Map<String, Object> grade) {
        Long account = accountOf(grade.get("alunoId"));
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO register_exam_grades (statement_id, account_id, student_number, student_name, score, status, payload)
                VALUES (:s, :a, :n, :name, :score, :status, CAST(:p AS jsonb))
                ON CONFLICT (statement_id, account_id) DO UPDATE SET student_number = EXCLUDED.student_number,
                    student_name = EXCLUDED.student_name, score = EXCLUDED.score, status = EXCLUDED.status,
                    payload = EXCLUDED.payload, updated_at = CURRENT_TIMESTAMP
                """).bind("s", statementId).bind("a", account).bind("p", json(grade));
        spec = bindNullable(spec, "n", integer(grade.get("numero")), Integer.class);
        spec = bindNullable(spec, "name", text(grade.get("nome")), String.class);
        spec = bindNullable(spec, "score", score(grade.get("nota")), BigDecimal.class);
        spec = bindNullable(spec, "status", text(grade.get("situacao")), String.class);
        return spec.fetch().rowsUpdated().then();
    }

    public Flux<Map<String, Object>> examGrades(Long statementId) {
        return db.sql("""
                SELECT account_id, student_number, student_name, score, status FROM register_exam_grades
                WHERE statement_id = :s ORDER BY student_number NULLS LAST, student_name
                """).bind("s", statementId).map((row, meta) -> gradeRow(row)).all();
    }

    private static Map<String, Object> gradeRow(io.r2dbc.spi.Readable row) {
        Map<String, Object> grade = new java.util.LinkedHashMap<>();
        grade.put("alunoId", String.valueOf(row.get("account_id", Long.class)));
        grade.put("numero", row.get("student_number", Integer.class));
        grade.put("nome", row.get("student_name", String.class));
        BigDecimal score = row.get("score", BigDecimal.class);
        grade.put("nota", plain(score));
        grade.put("situacao", row.get("status", String.class));
        return grade;
    }

    // ---- reclamações ----

    /** Regista a reclamação como pendente. Elegível só quem tem nota nesta prova; repetir não a volta a abrir se já foi tratada. */
    @Transactional
    public Mono<Map<String, Object>> openAppeal(Long statementId, String appealId, Map<String, Object> appeal) {
        Long account = accountOf(appeal.get("alunoId"));
        return db.sql("SELECT created_by FROM statements WHERE id = :s AND deleted_at IS NULL").bind("s", statementId)
                .map((row, meta) -> row.get("created_by", Long.class)).one()
                .switchIfEmpty(Mono.error(ApiException.notFound("Statement not found: " + statementId)))
                .flatMap(author -> db.sql("SELECT COUNT(*) AS n FROM register_exam_grades WHERE statement_id = :s AND account_id = :a AND score IS NOT NULL")
                        .bind("s", statementId).bind("a", account).map((row, meta) -> row.get("n", Long.class)).one()
                        .flatMap(graded -> {
                            Map<String, Object> answer = new java.util.LinkedHashMap<>();
                            answer.put("elegivel", graded > 0);
                            answer.put("motivoInelegibilidade", graded > 0 ? null : "O aluno não tem nota nesta prova");
                            answer.put("professorId", author == null ? null : String.valueOf(author));
                            if (graded == 0) {
                                return Mono.just(answer);
                            }
                            return db.sql("""
                                    INSERT INTO register_appeals (appeal_id, statement_id, account_id, payload)
                                    VALUES (:id, :s, :a, CAST(:p AS jsonb)) ON CONFLICT (appeal_id) DO NOTHING
                                    """).bind("id", appealId).bind("s", statementId).bind("a", account).bind("p", json(appeal))
                                    .fetch().rowsUpdated().thenReturn(answer);
                        }));
    }

    public Mono<Long> pendingAppeals(Long statementId) {
        return db.sql("SELECT COUNT(*) AS n FROM register_appeals WHERE statement_id = :s AND status = 'PENDENTE'")
                .bind("s", statementId).map((row, meta) -> row.get("n", Long.class)).one();
    }

    public Mono<Void> resolveAppeal(Long statementId, String appealId) {
        return db.sql("""
                UPDATE register_appeals SET status = 'TRATADA', resolved_at = COALESCE(resolved_at, CURRENT_TIMESTAMP)
                WHERE appeal_id = :id AND statement_id = :s
                """).bind("id", appealId).bind("s", statementId).fetch().rowsUpdated().then();
    }

    /**
     * Aplica a decisão a uma reclamação: a diferença de pontos da questão reflecte-se na nota da prova, limitada a 0..20,
     * arredondada a inteiro como o motor sempre fez. Repetir com os mesmos pontos finais dá o mesmo resultado, porque a
     * decisão fica guardada e só se aplica uma vez.
     */
    @Transactional
    public Mono<Void> rectify(Long statementId, String appealId, Long accountId, BigDecimal currentPoints, BigDecimal finalPoints, String decision) {
        return db.sql("SELECT status, decision FROM register_appeals WHERE appeal_id = :id AND statement_id = :s FOR UPDATE")
                .bind("id", appealId).bind("s", statementId)
                .map((row, meta) -> row.get("decision", String.class) != null).one()
                .defaultIfEmpty(false)
                .flatMap(alreadyApplied -> {
                    if (alreadyApplied) {
                        return Mono.<Void>empty();
                    }
                    BigDecimal delta = finalPoints.subtract(currentPoints);
                    return db.sql("""
                            UPDATE register_exam_grades
                            SET score = LEAST(:max, GREATEST(0, ROUND(score + :delta))), updated_at = CURRENT_TIMESTAMP
                            WHERE statement_id = :s AND account_id = :a AND score IS NOT NULL
                            """).bind("max", MAX).bind("delta", delta).bind("s", statementId).bind("a", accountId)
                            .fetch().rowsUpdated()
                            .then(db.sql("UPDATE register_appeals SET decision = :d WHERE appeal_id = :id")
                                    .bind("d", decision == null ? "" : decision).bind("id", appealId).fetch().rowsUpdated())
                            .then();
                });
    }

    // ---- pautas ----

    @Transactional
    public Mono<Void> registerTermSheet(String sheetId, Map<String, Object> context, Map<String, Object> statistics,
            List<Map<String, Object>> entries) {
        return db.sql("""
                INSERT INTO register_term_sheets (sheet_id, context, statistics) VALUES (:id, CAST(:c AS jsonb), CAST(:s AS jsonb))
                ON CONFLICT (sheet_id) DO UPDATE SET context = EXCLUDED.context, statistics = EXCLUDED.statistics,
                    updated_at = CURRENT_TIMESTAMP
                """).bind("id", sheetId).bind("c", json(context)).bind("s", json(statistics)).fetch().rowsUpdated()
                .thenMany(Flux.fromIterable(entries).concatMap(entry -> upsertTermEntry(sheetId, "MT", entry))).then();
    }

    @Transactional
    public Mono<Void> registerComponent(String sheetId, String component, List<Map<String, Object>> entries) {
        return Flux.fromIterable(entries).concatMap(entry -> upsertTermEntry(sheetId, component, entry)).then();
    }

    private Mono<Void> upsertTermEntry(String sheetId, String component, Map<String, Object> entry) {
        Object value = entry.containsKey("nota") ? entry.get("nota") : entry.containsKey("mt") ? entry.get("mt") : entry.get("mac");
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO register_term_entries (sheet_id, component, account_id, score, status, payload)
                VALUES (:id, :c, :a, :score, :status, CAST(:p AS jsonb))
                ON CONFLICT (sheet_id, component, account_id) DO UPDATE SET score = EXCLUDED.score, status = EXCLUDED.status,
                    payload = EXCLUDED.payload, updated_at = CURRENT_TIMESTAMP
                """).bind("id", sheetId).bind("c", component).bind("a", accountOf(entry.get("alunoId"))).bind("p", json(entry));
        spec = bindNullable(spec, "score", score(value), BigDecimal.class);
        spec = bindNullable(spec, "status", text(entry.get("situacao")), String.class);
        return spec.fetch().rowsUpdated().then();
    }

    /** FECHADA e ANULADA são finais: uma repetição tardia de REGISTADA não as reabre. */
    public Mono<Void> setSheetStatus(String sheetId, String status) {
        if (!List.of("FECHADA", "ANULADA").contains(status)) {
            return Mono.error(ApiException.badRequest("status must be FECHADA or ANULADA"));
        }
        return db.sql("""
                UPDATE register_term_sheets SET status = :st, updated_at = CURRENT_TIMESTAMP
                WHERE sheet_id = :id AND (status = 'REGISTADA' OR status = :st)
                """).bind("st", status).bind("id", sheetId).fetch().rowsUpdated()
                .flatMap(updated -> updated > 0 ? Mono.<Void>empty() : db.sql("SELECT 1 FROM register_term_sheets WHERE sheet_id = :id")
                        .bind("id", sheetId).fetch().first()
                        .switchIfEmpty(Mono.error(ApiException.notFound("Term sheet not found: " + sheetId)))
                        .then(Mono.error(ApiException.conflict("Term sheet is already " + "closed or annulled"))));
    }

    public Mono<Map<String, Object>> termSheet(String sheetId) {
        return db.sql("SELECT status, CAST(context AS text) AS c, CAST(statistics AS text) AS s FROM register_term_sheets WHERE sheet_id = :id")
                .bind("id", sheetId).map((row, meta) -> {
                    Map<String, Object> sheet = new java.util.LinkedHashMap<>();
                    sheet.put("pautaId", sheetId);
                    sheet.put("estado", row.get("status", String.class));
                    sheet.put("contexto", parse(row.get("c", String.class)));
                    sheet.put("estatisticas", parse(row.get("s", String.class)));
                    return sheet;
                }).one().switchIfEmpty(Mono.error(ApiException.notFound("Term sheet not found: " + sheetId)));
    }

    // ---- ajudas ----

    private static <T> DatabaseClient.GenericExecuteSpec bindNullable(DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private static Long accountOf(Object value) {
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw ApiException.badRequest("alunoId must be the account id");
        }
    }

    /** 15.00 sai como 15 e 12.50 como 12.5 (e 10 não como 1E+1). */
    private static BigDecimal plain(BigDecimal score) {
        if (score == null) {
            return null;
        }
        BigDecimal stripped = score.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal score(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            BigDecimal score = new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP);
            if (score.signum() < 0 || score.compareTo(MAX) > 0) {
                throw ApiException.badRequest("A score must be between 0 and 20");
            }
            return score;
        } catch (NumberFormatException error) {
            throw ApiException.badRequest("A score must be a number");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw ApiException.badRequest("Invalid payload");
        }
    }

    private Object parse(String json) {
        try {
            return mapper.readValue(json, Object.class);
        } catch (JsonProcessingException error) {
            return Map.of();
        }
    }
}
