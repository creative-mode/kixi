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
    private static final java.util.Set<String> GROUP_LAYOUTS = java.util.Set.of("brace", "lettered");
    /** At most 99 items per group, like the manual creation path: numbers are parent*100 + k. */
    private static final int MAX_ITEM_OFFSET = 99;

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

    /**
     * Adds a top-level question, or an alínea of an existing group.
     *
     * <p>Transactional because creating an alínea writes more than once: the
     * display positions make room, then the item is saved. A failure between
     * the two would otherwise leave a gap in the paper order.
     */
    @Transactional
    public Mono<QuestionResponse> create(
        Long statementId,
        QuestionRequest data,
        Long accountId,
        boolean admin
    ) {
        return writeAccess.requireCanWrite(statementId, accountId, admin)
            .then(Mono.defer(() -> data.parentId() == null
                ? createTopLevel(statementId, data)
                : createItem(statementId, data)));
    }

    private Mono<QuestionResponse> createTopLevel(Long statementId, QuestionRequest data) {
        if (data.label() != null) {
            return Mono.error(ApiException.badRequest("Only an item of a group has a label"));
        }
        return Mono.zip(
                Mono.defer(() -> questions.findNextQuestionNumber(statementId)),
                Mono.defer(() -> questions.findNextOrderIndex(statementId)))
            .flatMap(numbering -> {
                Question question = new Question(
                    statementId,
                    numbering.getT1(),
                    data.text().trim(),
                    blankToDefault(data.questionType(), DEFAULT_TYPE));
                question.setMaxScore(data.maxScore());
                question.setOrderIndex(numbering.getT2());
                question.setPageIndex(data.pageIndex() == null ? 0 : data.pageIndex());
                question.setModelAnswer(blankToNull(data.modelAnswer()));
                question.setNeedsReview(false);
                return withGroupLayout(question, data.groupLayout());
            })
            .flatMap(question -> Mono.defer(() -> questions.save(question)))
            .map(question -> toResponse(question, true));
    }

    /**
     * An alínea of an existing, active group: numbered {@code parent * 100 + k}
     * and labelled a, b, c... like the manual creation path, inserted right
     * after the group's last item so the paper order stays group, items, next
     * question.
     *
     * <p>The number is taken from every row in the group's block, deleted ones
     * included: a removed item keeps its number for good, so counting only the
     * active rows would hand out one that is already taken.
     */
    private Mono<QuestionResponse> createItem(Long statementId, QuestionRequest data) {
        String type = normalized(data.questionType());
        if (type == null) {
            type = DEFAULT_TYPE;
        }
        if (Question.GROUP.equals(type)) {
            return Mono.error(ApiException.badRequest("An item cannot be a group"));
        }
        if (data.label() != null && data.label().isBlank()) {
            return Mono.error(ApiException.badRequest("The item label must not be blank"));
        }
        if (data.groupLayout() != null) {
            return Mono.error(ApiException.badRequest("Only a group question has a layout"));
        }
        final String itemType = type;
        final String wantedLabel = data.label() == null ? null : data.label().trim().toLowerCase(java.util.Locale.ROOT);
        if (wantedLabel != null && wantedLabel.length() > 10) {
            return Mono.error(ApiException.badRequest("The item label must not exceed 10 characters"));
        }
        return Mono.defer(() -> findActiveQuestion(statementId, data.parentId()))
            .filter(Question::isGroup)
            .switchIfEmpty(Mono.error(ApiException.badRequest(
                "An item must belong to an active group question: " + data.parentId())))
            .flatMap(parent -> Mono.defer(() -> questions.findActiveChildren(parent.getId()).collectList())
                .map(children -> new GroupSlot(parent, children)))
            .flatMap(slot -> {
                String label = wantedLabel;
                if (label == null) {
                    java.util.Set<String> taken = new java.util.HashSet<>();
                    for (Question child : slot.children()) {
                        if (child.getLabel() != null) {
                            taken.add(child.getLabel().trim().toLowerCase(java.util.Locale.ROOT));
                        }
                    }
                    int k = 1;
                    do {
                        label = itemLabel(k++);
                    } while (taken.contains(label));
                } else {
                    final String given = label;
                    boolean clash = slot.children().stream()
                        .filter(child -> child.getLabel() != null)
                        .anyMatch(child -> given.equals(
                            child.getLabel().trim().toLowerCase(java.util.Locale.ROOT)));
                    if (clash) {
                        return Mono.<PlacedItem>error(ApiException.badRequest(
                            "This group already has an item labelled '" + given + "'"));
                    }
                }
                final String itemLabel = label;
                int base = slot.group().getNumber() * 100;
                return Mono.defer(() -> questions.maxNumberInItemRange(statementId, base, base + 100))
                    .flatMap(max -> {
                        int number = max + 1;
                        if (number > base + MAX_ITEM_OFFSET) {
                            return Mono.<PlacedItem>error(ApiException.badRequest("This group is already full"));
                        }
                        int lastOrder = slot.group().getOrderIndex() == null ? 0 : slot.group().getOrderIndex();
                        for (Question child : slot.children()) {
                            if (child.getOrderIndex() != null && child.getOrderIndex() > lastOrder) {
                                lastOrder = child.getOrderIndex();
                            }
                        }
                        final int after = lastOrder;
                        return Mono.defer(() -> questions.shiftOrderIndexesAfter(statementId, after))
                            // Zero rows moved is the normal case for the last item
                            // on the paper; the count must not decide whether the
                            // item is saved.
                            .defaultIfEmpty(0)
                            .map(shifted -> new PlacedItem(number, itemLabel, after + 1));
                    });
            })
            .flatMap(placed -> {
                Question question = new Question(statementId, placed.number(), data.text().trim(), itemType);
                question.setMaxScore(data.maxScore());
                question.setOrderIndex(placed.orderIndex());
                question.setPageIndex(data.pageIndex() == null ? 0 : data.pageIndex());
                question.setModelAnswer(blankToNull(data.modelAnswer()));
                question.setNeedsReview(false);
                question.setParentId(data.parentId());
                question.setLabel(placed.label());
                return Mono.defer(() -> questions.save(question));
            })
            .map(question -> toResponse(question, true));
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
            .then(Mono.defer(() -> data.parentId() == null
                ? findActiveQuestion(statementId, questionId)
                : Mono.<Question>error(ApiException.badRequest(
                    "A question's group is set on creation and cannot be moved"))))
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
                return withGroupLayout(question, data.groupLayout());
            })
            .flatMap(question -> Mono.defer(() -> questions.save(question)))
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

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** The group and its active items, needed together to place a new alínea. */
    private record GroupSlot(Question group, List<Question> children) { }

    /** A numbered, labelled alínea and where it goes in the paper order. */
    private record PlacedItem(int number, String label, int orderIndex) { }

    /**
     * Applies a group layout, or leaves the stored one alone when none was
     * sent. Only a group has one: anything else is rejected rather than
     * silently dropped.
     */
    private Mono<Question> withGroupLayout(Question question, String raw) {
        if (raw == null) {
            return Mono.just(question);
        }
        String value = normalized(raw);
        if (value == null || !GROUP_LAYOUTS.contains(value)) {
            return Mono.error(ApiException.badRequest(
                "Unknown group layout '" + raw + "': use one of brace, lettered"));
        }
        if (!question.isGroup()) {
            return Mono.error(ApiException.badRequest("Only a group question has a layout"));
        }
        question.setGroupLayout(value);
        return Mono.just(question);
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
