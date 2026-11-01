package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.exams.dto.question.QuestionRequest;
import ao.creativemode.kixi.exams.dto.question.QuestionResponse;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Creating, editing, reordering and removing the questions of a statement.
 *
 * <p>Every question is reached through the statement that owns it, so every
 * write goes through {@link StatementWriteAccessService} and a question id from
 * another statement is a 404 rather than something that could be edited.
 */
@Service
public class QuestionService {

    private static final String DEFAULT_TYPE = "open";

    private final QuestionRepository questions;
    private final QuestionOptionRepository options;
    private final StatementRepository statements;
    private final StatementWriteAccessService writeAccess;

    public QuestionService(
        QuestionRepository questions,
        QuestionOptionRepository options,
        StatementRepository statements,
        StatementWriteAccessService writeAccess
    ) {
        this.questions = questions;
        this.options = options;
        this.statements = statements;
        this.writeAccess = writeAccess;
    }

    /**
     * Reading is open to any authenticated caller, like the statement routes,
     * but not to the same extent, and the difference is the answer key rather
     * than the statement.
     *
     * <p>An account that is not staff only reaches a published statement. Staff
     * reach any statement that is not in the trash, which is the same split the
     * statement routes make — a teacher already lists every active statement,
     * not only the classes they teach — so the rule is not narrower than
     * elsewhere in this API and is not narrower here either.
     *
     * <p>What a non-staff caller never gets is the answer key: {@code staff}
     * reaches {@link #toResponse(Question, boolean)}, which is where modelAnswer
     * and needsReview are withheld.
     */
    private Mono<Void> requireReadableStatement(Long statementId, boolean staff) {
        return Mono.defer(() -> staff
                ? statements.findByIdAndDeletedAtIsNull(statementId)
                // Same 404 as "does not exist", so an unpublished statement of
                // another class is not confirmed to exist by the status code.
                : statements.findByIdAndVisibleTrueAndDeletedAtIsNull(statementId))
            .switchIfEmpty(Mono.error(ApiException.notFound("Statement not found: " + statementId)))
            .then();
    }

    /** The trashed questions are for whoever corrects the paper, not for a reader. */
    private Mono<Void> requireStatementForTrash(Long statementId, boolean staff) {
        return requireReadableStatement(statementId, staff)
            .then(Mono.defer(() -> {
                if (staff) {
                    return Mono.empty();
                }
                return Mono.error(ApiException.notFound("Statement not found: " + statementId));
            }));
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    /**
     * Ordered by the display position, not by the number.
     *
     * <p>{@code number} is the stable ordinal a question keeps for good; the
     * order the teacher sets is what {@code reorder} writes and what a listing
     * has to honour. Sorting by the number would make every reordering invisible.
     */
    public Flux<QuestionResponse> findAllActive(Long statementId, boolean staff) {
        return requireReadableStatement(statementId, staff)
            // Deferred so the listing is never even asked for when the statement
            // turns out not to be there.
            .thenMany(Flux.defer(() -> questions.findAllByStatementIdOrderedByOrderIndex(statementId)))
            .map(question -> toResponse(question, staff));
    }

    public Flux<QuestionResponse> findAllDeleted(Long statementId, boolean staff) {
        return requireStatementForTrash(statementId, staff)
            .thenMany(Flux.defer(() -> questions.findAllByStatementIdAndDeletedAtIsNotNull(statementId)))
            .map(question -> toResponse(question, staff));
    }

    public Mono<QuestionResponse> findById(Long statementId, Long questionId, boolean staff) {
        return requireReadableStatement(statementId, staff)
            .then(Mono.defer(() -> findActiveQuestion(statementId, questionId)))
            .map(question -> toResponse(question, staff));
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    public Mono<QuestionResponse> create(
        Long statementId,
        QuestionRequest data,
        Long accountId,
        boolean admin
    ) {
        return writeAccess.requireCanWrite(statementId, accountId, admin)
            .then(Mono.defer(() -> data.parentId() == null
                ? createWithNumbering(statementId, data, null, List.of())
                : findActiveQuestion(statementId, data.parentId())
                    .filter(Question::isGroup)
                    .switchIfEmpty(Mono.error(ApiException.badRequest("An item must belong to an active group question")))
                    .flatMap(parent -> questions.findAllChildren(parent.getId()).collectList()
                        .flatMap(children -> createWithNumbering(statementId, data, parent, children)))))
            .map(question -> toResponse(question, true));
    }

    private Mono<Question> createWithNumbering(Long statementId, QuestionRequest data, Question parent, List<Question> children) {
        return Mono.zip(
            Mono.defer(() -> questions.findNextQuestionNumber(statementId)),
            Mono.defer(() -> questions.findNextOrderIndex(statementId))
        ).flatMap(numbering -> {
                int number = numbering.getT1();
                String label = null;
                if (parent != null) {
                    int base = parent.getNumber() * 100;
                    int next = children.stream().map(Question::getNumber)
                        .filter(value -> value > base && value < base + 100)
                        .mapToInt(value -> value - base).max().orElse(0) + 1;
                    if (next > 99) {
                        return Mono.error(ApiException.badRequest("A group cannot have more than 99 items"));
                    }
                    number = base + next;
                    label = data.label() == null || data.label().isBlank()
                        ? itemLabel(next)
                        : data.label().trim().toLowerCase(java.util.Locale.ROOT);
                }
                Question question = new Question(
                    statementId,
                    number,
                    data.text().trim(),
                    blankToDefault(data.questionType(), DEFAULT_TYPE));
                question.setMaxScore(data.maxScore());
                question.setOrderIndex(numbering.getT2());
                question.setPageIndex(data.pageIndex() == null ? 0 : data.pageIndex());
                question.setModelAnswer(blankToNull(data.modelAnswer()));
                question.setNeedsReview(false);
                question.setParentId(parent == null ? null : parent.getId());
                question.setLabel(label);
                if (data.groupLayout() != null && question.isGroup()) {
                    question.setGroupLayout(data.groupLayout());
                }
                return Mono.defer(() -> questions.save(question));
            });
    }

    private static String itemLabel(int ordinal) {
        StringBuilder label = new StringBuilder();
        int value = ordinal;
        while (value > 0) {
            value--;
            label.insert(0, (char) ('a' + value % 26));
            value /= 26;
        }
        return label.toString();
    }

    public Mono<QuestionResponse> update(
        Long statementId,
        Long questionId,
        QuestionRequest data,
        Long accountId,
        boolean admin
    ) {
        return writeAccess.requireCanWrite(statementId, accountId, admin)
            .then(Mono.defer(() -> findActiveQuestion(statementId, questionId)))
            .flatMap(question -> {
                question.setText(data.text().trim());
                if (data.questionType() != null && !data.questionType().isBlank()) {
                    question.setQuestionType(data.questionType().trim());
                }
                question.setMaxScore(data.maxScore());
                if (data.pageIndex() != null) {
                    question.setPageIndex(data.pageIndex());
                }
                question.setModelAnswer(blankToNull(data.modelAnswer()));
                if (data.ocrReviewed() != null) {
                    question.setOcrReviewed(data.ocrReviewed());
                    question.setNeedsReview(QuestionOcrEvidence.needsReview(
                        question.getOcrConfidence(), QuestionOcrEvidence.decode(question.getOcrFormulas()),
                        data.ocrReviewed()));
                }
                if (data.groupLayout() != null) {
                    if (!question.isGroup()) {
                        return Mono.error(ApiException.badRequest("Only a group question can define a layout"));
                    }
                    question.setGroupLayout(data.groupLayout());
                }
                return Mono.defer(() -> questions.save(question));
            })
            .map(question -> toResponse(question, true));
    }

    /**
     * Sets the display order of the statement's questions.
     *
     * <p>Only {@code order_index} moves: {@code number} is the stable ordinal the
     * questions were numbered in, it is what the unique constraint covers, and
     * renumbering on every reorder would hand a number to a question that may
     * already hold it.
     */
    @Transactional
    public Flux<QuestionResponse> reorder(
        Long statementId,
        List<Long> questionIds,
        Long accountId,
        boolean admin
    ) {
        return writeAccess.requireCanWrite(statementId, accountId, admin)
            .then(Mono.defer(() ->
                questions.findAllByStatementIdAndDeletedAtIsNull(statementId).collectList()))
            .flatMapMany(active -> applyOrder(active, questionIds))
            .map(question -> toResponse(question, true));
    }

    private Flux<Question> applyOrder(List<Question> active, List<Long> requested) {
        boolean coversExactlyTheActiveOnes = requested != null
            && requested.size() == active.size()
            && requested.stream().distinct().count() == requested.size()
            && active.stream().allMatch(question -> requested.contains(question.getId()));

        if (!coversExactlyTheActiveOnes) {
            return Flux.error(ApiException.unprocessableEntity(
                "The reorder list must name every active question of the statement once"));
        }

        Map<Long, Question> byId = new HashMap<>();
        active.forEach(question -> byId.put(question.getId(), question));

        AtomicInteger position = new AtomicInteger();
        return Flux.fromIterable(requested)
            .concatMap(questionId -> {
                Question question = byId.get(questionId);
                question.setOrderIndex(position.getAndIncrement());
                return Mono.defer(() -> questions.save(question));
            });
    }

    @Transactional
    public Mono<Void> softDelete(Long statementId, Long questionId, Long accountId, boolean admin) {
        return writeAccess.requireCanWrite(statementId, accountId, admin)
            .then(Mono.defer(() -> findActiveQuestion(statementId, questionId)))
            .flatMap(question -> {
                LocalDateTime deletedAt = LocalDateTime.now();
                question.setDeletedAt(deletedAt);
                // The options go with it: is_correct is looked up among the
                // active ones, and leaving them active would leave a deleted
                // question still carrying an answer key.
                return Mono.defer(() -> questions.save(question))
                    .then(Mono.defer(() ->
                        options.softDeleteAllByQuestionIdAndDeletedAt(questionId, deletedAt)))
                    // The items of a group go with it: left active they would be questions of a
                    // paper whose common statement is gone.
                    .thenMany(Flux.defer(() -> question.isGroup()
                        ? questions.findActiveChildren(questionId) : Flux.<Question>empty()))
                    .concatMap(child -> {
                        child.setDeletedAt(deletedAt);
                        return Mono.defer(() -> questions.save(child))
                            .then(Mono.defer(() -> options.softDeleteAllByQuestionIdAndDeletedAt(child.getId(), deletedAt)));
                    })
                    .then();
            });
    }

    /**
     * Brings a removed question back, with the options its own removal took.
     *
     * <p>Transactional for the same reason {@link #softDelete} is: the two
     * writes have to stand or fall together. Restored alone, a multiple-choice
     * question comes back with no active option, the approval gate then refuses
     * the statement, and nothing anywhere says the paper is missing its answers.
     */
    @Transactional
    public Mono<Void> restore(Long statementId, Long questionId, Long accountId, boolean admin) {
        return writeAccess.requireCanWrite(statementId, accountId, admin)
            .then(Mono.defer(() -> findDeletedQuestion(statementId, questionId)))
            .flatMap(question -> {
                LocalDateTime deletedAt = question.getDeletedAt();
                question.restore();
                return Mono.defer(() -> questions.save(question))
                    .then(Mono.defer(() ->
                        options.restoreAllDeletedByQuestionIdAndDeletedAt(questionId, deletedAt)))
                    .thenMany(Flux.defer(() -> question.isGroup()
                        ? questions.findChildrenDeletedAt(questionId, deletedAt) : Flux.<Question>empty()))
                    .concatMap(child -> {
                        child.restore();
                        return Mono.defer(() -> questions.save(child))
                            .then(Mono.defer(() -> options.restoreAllDeletedByQuestionIdAndDeletedAt(child.getId(), deletedAt)));
                    })
                    .then();
            });
    }

    /**
     * Removes a question for good. Its options go with it through the
     * {@code fk_question_options_question} cascade.
     */
    public Mono<Void> hardDelete(Long statementId, Long questionId, Long accountId, boolean admin) {
        return writeAccess.requireCanWrite(statementId, accountId, admin)
            .then(Mono.defer(() -> findDeletedQuestion(statementId, questionId)))
            // The items reference the group, so they go first (their options cascade).
            .flatMap(question -> Flux.defer(() -> question.isGroup()
                    ? questions.findAllChildren(question.getId()) : Flux.<Question>empty())
                .concatMap(child -> Mono.defer(() -> questions.delete(child)))
                .then(Mono.defer(() -> questions.delete(question))));
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private Mono<Question> findActiveQuestion(Long statementId, Long questionId) {
        return Mono.defer(() -> questions.findByIdAndDeletedAtIsNull(questionId))
            .filter(question -> question.getStatementId().equals(statementId))
            .switchIfEmpty(Mono.error(ApiException.notFound(
                "Question not found: " + questionId + " on statement " + statementId)));
    }

    private Mono<Question> findDeletedQuestion(Long statementId, Long questionId) {
        return Mono.defer(() -> questions.findByIdAndDeletedAtIsNotNull(questionId))
            .filter(question -> question.getStatementId().equals(statementId))
            .switchIfEmpty(Mono.error(ApiException.notFound(
                "Only a deleted question of this statement can be restored or removed: " + questionId)));
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * The answer key and the review flag only travel with a question to someone
     * who may write the statement. For anyone else they come back null, and the
     * response omits them.
     */
    private static QuestionResponse toResponse(Question question, boolean staff) {
        return new QuestionResponse(
            question.getId(),
            question.getStatementId(),
            question.getNumber(),
            question.getText(),
            question.getQuestionType(),
            question.getMaxScore(),
            question.getOrderIndex(),
            question.getPageIndex(),
            staff ? question.getModelAnswer() : null,
            staff ? question.getNeedsReview() : null,
            question.getCreatedAt(),
            question.getUpdatedAt(),
            staff ? question.getDeletedAt() : null,
            question.getParentId(),
            question.getLabel(),
            question.getGroupLayout(),
            staff ? question.getRawText() : null,
            staff ? question.getOcrConfidence() : null,
            staff ? question.getOcrReviewed() : null,
            staff ? responseFormulas(question) : null
        );
    }

    private static List<QuestionResponse.FormulaEvidence> responseFormulas(Question question) {
        List<ao.creativemode.kixi.exams.dto.statement.ManualStatementRequest.Formula> formulas =
            QuestionOcrEvidence.decode(question.getOcrFormulas());
        return formulas == null ? null : formulas.stream()
            .map(formula -> new QuestionResponse.FormulaEvidence(
                formula.raw(), formula.latex(), formula.confidence(), formula.kind(),
                formula.ocrConfidence(), formula.layoutConfidence()))
            .toList();
    }
}
