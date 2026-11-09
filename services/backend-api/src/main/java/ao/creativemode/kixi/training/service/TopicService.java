package ao.creativemode.kixi.training.service;

import ao.creativemode.kixi.exams.service.StatementWriteAccessService;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.training.dto.AutoTagResponse;
import ao.creativemode.kixi.training.dto.TopicRequest;
import ao.creativemode.kixi.training.dto.TopicResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Os temas de uma disciplina e a sua ligação às questões (kixi#729): CRUD, etiquetagem manual e etiquetagem automática
 * por palavras-chave. A etiquetagem automática nunca sobrepõe uma etiqueta manual.
 */
@Service
public class TopicService {

    private static final String SELECT = """
            SELECT t.id, t.subject_id, t.grade, t.name, t.keywords,
                   (SELECT COUNT(*) FROM question_topics qt
                      JOIN questions q ON q.id = qt.question_id AND q.deleted_at IS NULL
                      JOIN statements st ON st.id = q.statement_id AND st.deleted_at IS NULL
                                        AND st.practice_owner_account_id IS NULL
                     WHERE qt.topic_id = t.id) AS question_count
              FROM subject_topics t
            """;

    private final DatabaseClient db;
    private final StatementWriteAccessService writeAccess;

    public TopicService(DatabaseClient db, StatementWriteAccessService writeAccess) {
        this.db = db;
        this.writeAccess = writeAccess;
    }

    // ── Temas ───────────────────────────────────────────────────────────────

    /** Os temas ativos da disciplina; com {@code grade}, os dessa classe e os que valem para todas. */
    public Flux<TopicResponse> list(Long subjectId, Integer grade) {
        return requireSubject(subjectId).thenMany(Flux.defer(() -> {
            String sql = SELECT + " WHERE t.subject_id = :subject AND t.deleted_at IS NULL"
                    + (grade == null ? "" : " AND (t.grade IS NULL OR t.grade = :grade)") + " ORDER BY t.name, t.id";
            DatabaseClient.GenericExecuteSpec spec = db.sql(sql).bind("subject", subjectId);
            if (grade != null) {
                spec = spec.bind("grade", grade);
            }
            return spec.map((row, meta) -> toResponse(row)).all();
        }));
    }

    @Transactional
    public Mono<TopicResponse> create(Long subjectId, TopicRequest request, Long accountId) {
        return requireSubject(subjectId).then(Mono.defer(() -> insert(subjectId, request.name().trim(), request.grade(),
                blankToNull(request.keywords()), accountId)));
    }

    /** O tema da disciplina com este nome (qualquer classe, a de «todas» primeiro), ou um novo para todas as classes. */
    @Transactional
    public Mono<Long> findOrCreate(Long subjectId, String name, Long accountId) {
        String slug = TopicText.slug(name);
        return db.sql("SELECT id FROM subject_topics WHERE subject_id = :subject AND slug = :slug AND deleted_at IS NULL "
                        + "ORDER BY (grade IS NULL) DESC, id LIMIT 1")
                .bind("subject", subjectId).bind("slug", slug)
                .map((row, meta) -> row.get("id", Long.class)).one()
                .switchIfEmpty(Mono.defer(() -> insert(subjectId, name.trim(), null, null, accountId).map(TopicResponse::id)));
    }

    private Mono<TopicResponse> insert(Long subjectId, String name, Integer grade, String keywords, Long accountId) {
        String slug = TopicText.slug(name);
        if (slug.isEmpty()) {
            return Mono.error(ApiException.badRequest("O nome do tema tem de ter letras ou números."));
        }
        if (name.length() > MAX_NAME) {
            return Mono.error(nameTooLong());
        }
        DatabaseClient.GenericExecuteSpec spec = db.sql("INSERT INTO subject_topics (subject_id, grade, name, slug, keywords, created_by) "
                        + "VALUES (:subject, :grade, :name, :slug, :keywords, :by) RETURNING id")
                .bind("subject", subjectId).bind("name", name).bind("slug", slug).bind("by", accountId);
        spec = grade == null ? spec.bindNull("grade", Integer.class) : spec.bind("grade", grade);
        spec = keywords == null ? spec.bindNull("keywords", String.class) : spec.bind("keywords", keywords);
        return spec.map((row, meta) -> row.get("id", Long.class)).one()
                .onErrorMap(DuplicateKeyException.class, e -> duplicate(name))
                .flatMap(this::byId);
    }

    @Transactional
    public Mono<TopicResponse> update(Long id, TopicRequest request, Long accountId, boolean admin) {
        String name = request.name().trim();
        String slug = TopicText.slug(name);
        if (slug.isEmpty()) {
            return Mono.error(ApiException.badRequest("O nome do tema tem de ter letras ou números."));
        }
        if (name.length() > MAX_NAME) {
            return Mono.error(nameTooLong());
        }
        return byId(id).flatMap(current -> requireAuthor(id, accountId, admin).thenReturn(current)).flatMap(current -> {
            DatabaseClient.GenericExecuteSpec spec = db.sql("UPDATE subject_topics SET name = :name, slug = :slug, "
                            + "grade = :grade, keywords = :keywords WHERE id = :id AND deleted_at IS NULL")
                    .bind("id", id).bind("name", name).bind("slug", slug);
            spec = request.grade() == null ? spec.bindNull("grade", Integer.class) : spec.bind("grade", request.grade());
            String keywords = blankToNull(request.keywords());
            spec = keywords == null ? spec.bindNull("keywords", String.class) : spec.bind("keywords", keywords);
            return spec.fetch().rowsUpdated()
                    .onErrorMap(DuplicateKeyException.class, e -> duplicate(name))
                    .then(byId(id));
        });
    }

    /** Apaga o tema (soft delete): as etiquetas ficam, mas deixa de contar e pode ser recriado. */
    @Transactional
    public Mono<Void> delete(Long id, Long accountId, boolean admin) {
        return byId(id).then(requireAuthor(id, accountId, admin))
                .then(Mono.defer(() -> db.sql("SELECT 1 FROM exam_matrix_items WHERE topic_id = :id LIMIT 1").bind("id", id)
                        .map((row, meta) -> 1).one().hasElement()))
                .flatMap(inUse -> inUse
                        ? Mono.<Long>error(ApiException.conflict(
                                "O tema está em uso numa matriz de prova; retire-o da matriz antes de o apagar."))
                        : db.sql("UPDATE subject_topics SET deleted_at = CURRENT_TIMESTAMP WHERE id = :id")
                                .bind("id", id).fetch().rowsUpdated())
                .then();
    }

    /** Os temas são globais: só quem os criou, ou um administrador, os altera ou apaga. */
    private Mono<Void> requireAuthor(Long id, Long accountId, boolean admin) {
        if (admin) {
            return Mono.empty();
        }
        return db.sql("SELECT created_by FROM subject_topics WHERE id = :id").bind("id", id)
                .map((row, meta) -> row.get("created_by", Long.class)).one()
                .flatMap(author -> accountId.equals(author)
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(ApiException.forbidden("Só o autor do tema ou um administrador o pode alterar.")));
    }

    static final int MAX_NAME = 200;

    private static ApiException nameTooLong() {
        return ApiException.badRequest("O nome do tema não pode passar de " + MAX_NAME + " caracteres.");
    }

    private Mono<TopicResponse> byId(Long id) {
        return db.sql(SELECT + " WHERE t.id = :id AND t.deleted_at IS NULL").bind("id", id)
                .map((row, meta) -> toResponse(row)).one()
                .switchIfEmpty(Mono.error(() -> ApiException.notFound("Tema não encontrado: " + id)));
    }

    private Mono<Void> requireSubject(Long subjectId) {
        return db.sql("SELECT 1 FROM subjects WHERE id = :id AND deleted_at IS NULL").bind("id", subjectId)
                .map((row, meta) -> 1).one()
                .switchIfEmpty(Mono.error(() -> ApiException.notFound("Disciplina não encontrada: " + subjectId)))
                .then();
    }

    private static ApiException duplicate(String name) {
        return ApiException.conflict("Já existe um tema «" + name + "» nesta disciplina e classe.");
    }

    private static TopicResponse toResponse(io.r2dbc.spi.Readable row) {
        return new TopicResponse(row.get("id", Long.class), row.get("subject_id", Long.class), row.get("grade", Integer.class),
                row.get("name", String.class), row.get("keywords", String.class), row.get("question_count", Long.class));
    }

    // ── Etiquetas das questões ──────────────────────────────────────────────

    /** Substitui os temas de uma questão pelos indicados (origem MANUAL). Os temas têm de ser da disciplina do enunciado. */
    @Transactional
    public Mono<List<TopicResponse>> setQuestionTopics(Long questionId, List<Long> topicIds, Long accountId, boolean admin) {
        Set<Long> wanted = new LinkedHashSet<>(topicIds == null ? List.<Long>of() : topicIds);
        if (wanted.contains(null)) {
            return Mono.error(ApiException.badRequest("topicIds não pode ter valores vazios."));
        }
        return db.sql("SELECT q.statement_id, s.subject_id FROM questions q JOIN statements s ON s.id = q.statement_id "
                        + "WHERE q.id = :id AND q.deleted_at IS NULL AND s.practice_owner_account_id IS NULL")
                .bind("id", questionId)
                .map((row, meta) -> new Long[] {row.get("statement_id", Long.class), row.get("subject_id", Long.class)}).one()
                .switchIfEmpty(Mono.error(() -> ApiException.notFound("Questão não encontrada: " + questionId)))
                .flatMap(ids -> writeAccess.requireCanWrite(ids[0], accountId, admin).thenReturn(ids))
                .flatMap(ids -> checkTopics(wanted, ids[1])
                        .then(db.sql("DELETE FROM question_topics WHERE question_id = :q").bind("q", questionId)
                                .fetch().rowsUpdated())
                        .thenMany(Flux.fromIterable(wanted).concatMap(topic -> db.sql(
                                        "INSERT INTO question_topics (question_id, topic_id, source) VALUES (:q, :t, 'MANUAL')")
                                .bind("q", questionId).bind("t", topic).fetch().rowsUpdated()))
                        .then(Mono.defer(() -> topicsOfQuestion(questionId))));
    }

    private Mono<Void> checkTopics(Set<Long> topicIds, Long subjectId) {
        if (topicIds.isEmpty()) {
            return Mono.empty();
        }
        DatabaseClient.GenericExecuteSpec spec = db.sql("SELECT id FROM subject_topics WHERE id IN (:ids) AND deleted_at IS NULL"
                        + (subjectId == null ? "" : " AND subject_id = :subject"))
                .bind("ids", topicIds);
        if (subjectId != null) {
            spec = spec.bind("subject", subjectId);
        }
        return spec
                .map((row, meta) -> row.get("id", Long.class)).all().collect(java.util.stream.Collectors.toSet())
                .flatMap(found -> {
                    if (found.size() == topicIds.size()) {
                        return Mono.<Void>empty();
                    }
                    Set<Long> missing = new HashSet<>(topicIds);
                    missing.removeAll(found);
                    return Mono.<Void>error(ApiException.badRequest(
                            "Temas inexistentes ou de outra disciplina: " + missing));
                });
    }

    private Mono<List<TopicResponse>> topicsOfQuestion(Long questionId) {
        return db.sql(SELECT + " JOIN question_topics own ON own.topic_id = t.id WHERE own.question_id = :q "
                        + "AND t.deleted_at IS NULL ORDER BY t.name, t.id")
                .bind("q", questionId).map((row, meta) -> toResponse(row)).all().collectList();
    }

    // ── Etiquetagem automática ──────────────────────────────────────────────

    private record QuestionText(Long id, Long parentId, String text, String type) {}

    /**
     * Etiqueta as questões do enunciado que ainda não têm tema, pelas palavras do texto (e das opções) que coincidem com
     * o nome ou as palavras-chave dos temas da disciplina. Não mexe nas que já têm tema (manual ou automático).
     */
    @Transactional
    public Mono<AutoTagResponse> autoTag(Long statementId, Long accountId, boolean admin) {
        return writeAccess.requireCanWrite(statementId, accountId, admin).flatMap(statement -> {
            if (statement.getSubjectId() == null) {
                return Mono.error(ApiException.badRequest("O enunciado não tem disciplina, por isso não há temas a aplicar."));
            }
            Mono<List<AutoTagger.Topic>> topics = db.sql("SELECT t.id, t.name, t.keywords FROM subject_topics t "
                            + "WHERE t.subject_id = :subject AND t.deleted_at IS NULL "
                            + "AND (t.grade IS NULL OR t.grade = (SELECT c.grade FROM classes c WHERE c.id = :class)) ORDER BY t.id")
                    .bind("subject", statement.getSubjectId()).bind("class", statement.getClassId() == null ? 0L : statement.getClassId())
                    .map((row, meta) -> new AutoTagger.Topic(row.get("id", Long.class), row.get("name", String.class),
                            row.get("keywords", String.class)))
                    .all().collectList();
            Mono<List<QuestionText>> questions = db.sql("SELECT q.id, q.parent_id, q.text, q.question_type FROM questions q "
                            + "WHERE q.statement_id = :s AND q.deleted_at IS NULL "
                            + "AND NOT EXISTS (SELECT 1 FROM question_topics qt WHERE qt.question_id = q.id) ORDER BY q.order_index, q.id")
                    .bind("s", statementId)
                    .map((row, meta) -> new QuestionText(row.get("id", Long.class), row.get("parent_id", Long.class),
                            row.get("text", String.class), row.get("question_type", String.class)))
                    .all().collectList();
            Mono<Map<Long, String>> allTexts = db.sql("SELECT q.id, q.text FROM questions q WHERE q.statement_id = :s AND q.deleted_at IS NULL")
                    .bind("s", statementId)
                    .map((row, meta) -> Map.entry(row.get("id", Long.class), String.valueOf(row.get("text", String.class))))
                    .all().collectMap(Map.Entry::getKey, Map.Entry::getValue);
            Mono<Map<Long, List<String>>> options = db.sql("SELECT o.question_id, o.option_text FROM question_options o "
                            + "JOIN questions q ON q.id = o.question_id WHERE q.statement_id = :s AND o.deleted_at IS NULL")
                    .bind("s", statementId)
                    .map((row, meta) -> Map.entry(row.get("question_id", Long.class), String.valueOf(row.get("option_text", String.class))))
                    .all().collect(java.util.stream.Collectors.groupingBy(Map.Entry::getKey, HashMap::new,
                            java.util.stream.Collectors.mapping(Map.Entry::getValue, java.util.stream.Collectors.toList())));
            return Mono.zip(topics, questions, allTexts, options).flatMap(tuple -> {
                AutoTagger tagger = new AutoTagger(tuple.getT1());
                List<Long[]> toInsert = new ArrayList<>();
                int untagged = 0;
                for (QuestionText question : tuple.getT2()) {
                    if ("group".equals(question.type())) {
                        continue;
                    }
                    String parentText = question.parentId() == null ? null : tuple.getT3().get(question.parentId());
                    String optionsText = String.join(" ", tuple.getT4().getOrDefault(question.id(), List.of()));
                    java.util.Optional<Long> topic = tagger.best(question.text(), optionsText, parentText);
                    if (topic.isPresent()) {
                        toInsert.add(new Long[] {question.id(), topic.get()});
                    } else {
                        untagged++;
                    }
                }
                final int stillUntagged = untagged;
                return Flux.fromIterable(toInsert)
                        .concatMap(pair -> db.sql("INSERT INTO question_topics (question_id, topic_id, source) "
                                        + "VALUES (:q, :t, 'AUTO') ON CONFLICT DO NOTHING")
                                .bind("q", pair[0]).bind("t", pair[1]).fetch().rowsUpdated())
                        .then(Mono.just(new AutoTagResponse(toInsert.size(), stillUntagged)));
            });
        });
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
