package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.exams.dto.questionoption.QuestionOptionRequest;
import ao.creativemode.kixi.exams.dto.questionoption.QuestionOptionResponse;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The options of a question, and the answer key.
 *
 * <p>Reached through the statement that owns the question, so every write goes
 * through {@link StatementWriteAccessService} and an option or question id from
 * another statement is a 404 rather than something that could be edited.
 */
@Service
public class QuestionOptionService {

    private final QuestionOptionRepository options;
    private final QuestionRepository questions;
    private final StatementRepository statements;
    private final StatementWriteAccessService writeAccess;

    public QuestionOptionService(
        QuestionOptionRepository options,
        QuestionRepository questions,
        StatementRepository statements,
        StatementWriteAccessService writeAccess
    ) {
        this.options = options;
        this.questions = questions;
        this.statements = statements;
        this.writeAccess = writeAccess;
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    public Flux<QuestionOptionResponse> findAll(Long statementId, Long questionId, boolean staff) {
        return requireReadableQuestion(statementId, questionId, staff)
            // Deferred so the listing is never even asked for when the question
            // turns out not to be there.
            .thenMany(Flux.defer(() -> options.findAllByQuestionIdOrderedByOrderIndex(questionId)))
            .map(option -> toResponse(option, staff));
    }

    public Flux<QuestionOptionResponse> findAllDeleted(Long statementId, Long questionId, boolean staff) {
        return requireReadableQuestion(statementId, questionId, staff)
            .then(Mono.defer(() -> staff
                ? Mono.<Void>empty()
                : Mono.<Void>error(ApiException.notFound("Statement not found: " + statementId))))
            .thenMany(Flux.defer(() -> options.findAllByQuestionIdAndDeletedAtIsNotNull(questionId)))
            .map(option -> toResponse(option, staff));
    }

    public Mono<QuestionOptionResponse> findById(Long statementId, Long questionId, Long optionId, boolean staff) {
        return requireReadableQuestion(statementId, questionId, staff)
            .then(Mono.defer(() -> findActiveOption(questionId, optionId)))
            .map(option -> toResponse(option, staff));
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    /**
     * Adds an option, or brings a removed one back under the same label.
     *
     * <p>{@code uk_question_options_label} is unique on (question_id,
     * option_label) and does not know about deleted_at, so a label that was
     * removed stays taken. The label is an identity the teacher chose rather
     * than a position, so reusing it restores the option instead of colliding.
     *
     * <p>Transactional because bringing one back writes twice: the option, then
     * the rewrite of {@code is_correct} if the request asked for it to be the
     * answer. Without it, a failure between the two leaves an option created and
     * no answer key to match.
     */
    @Transactional
    public Mono<QuestionOptionResponse> create(
        Long statementId,
        Long questionId,
        QuestionOptionRequest data,
        Long accountId,
        boolean admin
    ) {
        return writeAccess.requireCanWrite(statementId, accountId, admin)
            .then(Mono.defer(() -> requireQuestion(statementId, questionId)))
            .then(Mono.defer(() -> options.findNextOrderIndex(questionId)))
            .flatMap(nextOrder -> Mono.defer(() ->
                options.findByQuestionIdAndOptionLabel(questionId, data.optionLabel().trim()))
                .flatMap(existing -> {
                    if (!existing.isDeleted()) {
                        return Mono.<QuestionOption>error(ApiException.conflict(
                            "This question already has an option labelled " + data.optionLabel()));
                    }
                    existing.restore();
                    apply(existing, data);
                    existing.setOrderIndex(nextOrder);
                    // The row came out of the trash carrying whatever it was
                    // marked as back then. Saving that back would give the
                    // question a second answer whenever another option is
                    // already correct, so it comes back unmarked and only
                    // markCorrectIfAsked puts it back.
                    existing.setIsCorrect(false);
                    return Mono.defer(() -> options.save(existing));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    QuestionOption option = new QuestionOption(
                        questionId, data.optionLabel().trim(), data.optionText().trim());
                    option.setOrderIndex(nextOrder);
                    return Mono.defer(() -> options.save(option));
                })))
            // Saving the flag on the insert alone would leave a second option
            // still marked correct. Rewriting the column afterwards is what
            // keeps the question's single answer a single answer.
            .flatMap(option -> markCorrectIfAsked(option, data))
            .map(option -> toResponse(option, true));
    }

    private Mono<QuestionOption> markCorrectIfAsked(QuestionOption option, QuestionOptionRequest data) {
        if (!Boolean.TRUE.equals(data.isCorrect())) {
            return Mono.just(option);
        }
        return Mono.defer(() -> options.setCorrectOption(option.getQuestionId(), option.getId()))
            .then(Mono.defer(() -> options.findByIdAndDeletedAtIsNull(option.getId())))
            .switchIfEmpty(Mono.just(option));
    }

    /**
     * Changes the text, and marks the option as the answer if the body says so.
     *
     * <p>Transactional because the two are two writes: the option, then the
     * rewrite of {@code is_correct}. {@code isCorrect} used to be accepted and
     * quietly dropped here, which returned 200 for a body that asked for
     * something and did nothing about it. It is applied the same way as on
     * create — through the query, so the question keeps one answer.
     */
    @Transactional
    public Mono<QuestionOptionResponse> update(
        Long statementId,
        Long questionId,
        Long optionId,
        QuestionOptionRequest data,
        Long accountId,
        boolean admin
    ) {
        return writeAccess.requireCanWrite(statementId, accountId, admin)
            .then(Mono.defer(() -> requireQuestion(statementId, questionId)))
            .then(Mono.defer(() -> findActiveOption(questionId, optionId)))
            .flatMap(option -> {
                apply(option, data);
                return Mono.defer(() -> options.save(option));
            })
            .flatMap(option -> markCorrectIfAsked(option, data))
            .map(option -> toResponse(option, true));
    }

    @Transactional
    public Flux<QuestionOptionResponse> reorder(
        Long statementId,
        Long questionId,
        List<Long> optionIds,
        Long accountId,
        boolean admin
    ) {
        return writeAccess.requireCanWrite(statementId, accountId, admin)
            .then(Mono.defer(() -> requireQuestion(statementId, questionId)))
            .then(Mono.defer(() ->
                options.findAllByQuestionIdAndDeletedAtIsNull(questionId).collectList()))
            .flatMapMany(active -> applyOrder(active, optionIds))
            .map(option -> toResponse(option, true));
    }

    public Mono<Void> softDelete(Long statementId, Long questionId, Long optionId, Long accountId, boolean admin) {
        return writeAccess.requireCanWrite(statementId, accountId, admin)
            .then(Mono.defer(() -> requireQuestion(statementId, questionId)))
            .then(Mono.defer(() -> findActiveOption(questionId, optionId)))
            .flatMap(option -> {
                option.markAsDeleted();
                return Mono.defer(() -> options.save(option)).then();
            });
    }

    /**
     * Brings a removed option back.
     *
     * <p>Saved with {@code is_correct} cleared rather than with whatever it
     * carried while it sat in the trash, and then the answer key is rewritten if
     * it had been marked correct. Both halves matter: {@code setCorrectOption}
     * only touches the active rows, so a correct option in the trash keeps its
     * flag, and saving the loaded entity straight back would resurrect that flag
     * next to whichever option is correct now — two answers to one question, with
     * {@code findCorrectOptionByQuestionId} answering with a {@code LIMIT 1}.
     * Rewriting through the query is what puts a single one back.
     */
    @Transactional
    public Mono<Void> restore(Long statementId, Long questionId, Long optionId, Long accountId, boolean admin) {
        return writeAccess.requireCanWrite(statementId, accountId, admin)
            .then(Mono.defer(() -> requireQuestion(statementId, questionId)))
            .then(Mono.defer(() -> Mono.defer(() -> options.findByIdAndDeletedAtIsNotNull(optionId))
                .filter(option -> option.getQuestionId().equals(questionId))
                .switchIfEmpty(Mono.error(ApiException.notFound(
                    "Only a deleted option of this question can be restored: " + optionId)))))
            .flatMap(option -> {
                boolean wasTheAnswer = Boolean.TRUE.equals(option.getIsCorrect());
                option.restore();
                option.setIsCorrect(false);
                return Mono.defer(() -> options.save(option))
                    .then(Mono.defer(() -> wasTheAnswer
                        ? options.setCorrectOption(questionId, optionId)
                        : Mono.<Integer>empty()))
                    .then();
            });
    }

    public Mono<Void> hardDelete(Long statementId, Long questionId, Long optionId, Long accountId, boolean admin) {
        return writeAccess.requireCanWrite(statementId, accountId, admin)
            .then(Mono.defer(() -> requireQuestion(statementId, questionId)))
            .then(Mono.defer(() -> options.findByIdAndDeletedAtIsNotNull(optionId))
                .filter(option -> option.getQuestionId().equals(questionId))
                .switchIfEmpty(Mono.error(ApiException.notFound(
                    "Only a deleted option of this question can be removed: " + optionId))))
            .flatMap(option -> Mono.defer(() -> options.delete(option)).then());
    }

    // ── The answer key ──────────────────────────────────────────────────────

    /**
     * Marks one option of the question as the answer, clearing the others.
     *
     * <p>{@code setCorrectOption} rewrites {@code is_correct} across every option
     * of the question, so it is checked that the option really belongs to it
     * first: a foreign id would otherwise leave the question with no answer at
     * all, without an error to show for it.
     */
    @Transactional
    public Mono<QuestionOptionResponse> setCorrectOption(
        Long statementId,
        Long questionId,
        Long optionId,
        Long accountId,
        boolean admin
    ) {
        return writeAccess.requireCanWrite(statementId, accountId, admin)
            .then(Mono.defer(() -> requireQuestion(statementId, questionId)))
            .then(Mono.defer(() -> findActiveOption(questionId, optionId)))
            .flatMap(option -> Mono.defer(() -> options.setCorrectOption(questionId, optionId))
                .then(Mono.defer(() -> options.findByIdAndDeletedAtIsNull(option.getId())))
                .switchIfEmpty(Mono.error(ApiException.notFound("Option not found: " + optionId))))
            .map(option -> toResponse(option, true));
    }

    /**
     * The same thing reached by question id alone, which is the path the issue
     * spells out: {@code PUT /api/v1/questions/{id}/correct-option}.
     *
     * <p>A question id is enough to find the statement it belongs to, so nothing
     * is given up by not asking for it — but the statement has to be looked up
     * anyway, because the write rule weighs the statement rather than the
     * question. Going through the question is what leaves no statement id in the
     * request to get wrong.
     *
     * <p>A question id that is not there, or that is in the trash, is a 404 and
     * not a 403. The resource being addressed is the one that is missing, and
     * the same answer covers "does not exist" and "not yours" without telling the
     * caller which of the two it was.
     */
    @Transactional
    public Mono<QuestionOptionResponse> setCorrectOptionOfQuestion(
        Long questionId,
        Long optionId,
        Long accountId,
        boolean admin
    ) {
        return Mono.defer(() -> questions.findByIdAndDeletedAtIsNull(questionId))
            .switchIfEmpty(Mono.error(ApiException.notFound("Question not found: " + questionId)))
            .flatMap(question -> writeAccess.requireCanWrite(question.getStatementId(), accountId, admin)
                .then(Mono.defer(() -> findActiveOption(questionId, optionId))))
            .flatMap(option -> Mono.defer(() -> options.setCorrectOption(questionId, optionId))
                .then(Mono.defer(() -> options.findByIdAndDeletedAtIsNull(option.getId())))
                .switchIfEmpty(Mono.error(ApiException.notFound("Option not found: " + optionId))))
            .map(option -> toResponse(option, true));
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private Flux<QuestionOption> applyOrder(
        List<QuestionOption> active,
        List<Long> requested
    ) {
        boolean coversExactlyTheActiveOnes = requested != null
            && requested.size() == active.size()
            && requested.stream().distinct().count() == requested.size()
            && active.stream().allMatch(option -> requested.contains(option.getId()));

        if (!coversExactlyTheActiveOnes) {
            return Flux.error(ApiException.unprocessableEntity(
                "The reorder list must name every active option of the question once"));
        }

        Map<Long, QuestionOption> byId = new HashMap<>();
        active.forEach(option -> byId.put(option.getId(), option));

        AtomicInteger position = new AtomicInteger();
        return Flux.fromIterable(requested)
            .concatMap(optionId -> {
                QuestionOption option = byId.get(optionId);
                option.setOrderIndex(position.getAndIncrement());
                return Mono.defer(() -> options.save(option));
            });
    }

    /**
     * The question has to be there and belong to the statement. On a read the
     * statement also has to be reachable by this caller: a non-staff caller
     * only reaches a published one, and the answer key never travels to them.
     *
     * <p>Staff reach any statement that is not in the trash, the same as in the
     * statement routes.
     */
    private Mono<Void> requireReadableQuestion(Long statementId, Long questionId, boolean staff) {
        return Mono.defer(() -> staff
                ? statements.findByIdAndDeletedAtIsNull(statementId)
                : statements.findByIdAndVisibleTrueAndDeletedAtIsNull(statementId))
            .switchIfEmpty(Mono.error(ApiException.notFound("Statement not found: " + statementId)))
            .then(Mono.defer(() -> questions.findByIdAndDeletedAtIsNull(questionId)))
            .filter(question -> question.getStatementId().equals(statementId))
            .switchIfEmpty(Mono.error(ApiException.notFound(
                "Question not found: " + questionId + " on statement " + statementId)))
            .then();
    }

    private Mono<Void> requireQuestion(Long statementId, Long questionId) {
        return requireReadableQuestion(statementId, questionId, true);
    }

    private Mono<QuestionOption> findActiveOption(Long questionId, Long optionId) {
        return Mono.defer(() -> options.findByIdAndDeletedAtIsNull(optionId))
            .filter(option -> option.getQuestionId().equals(questionId))
            .switchIfEmpty(Mono.error(ApiException.notFound(
                "Option not found: " + optionId + " on question " + questionId)));
    }

    private void apply(QuestionOption option, QuestionOptionRequest data) {
        option.setOptionText(data.optionText().trim());
    }

    /** {@code isCorrect} is the answer key, so it only travels to staff. */
    private static QuestionOptionResponse toResponse(QuestionOption option, boolean staff) {
        return new QuestionOptionResponse(
            option.getId(),
            option.getQuestionId(),
            option.getOptionLabel(),
            option.getOptionText(),
            staff ? option.getIsCorrect() : null,
            option.getOrderIndex(),
            option.getCreatedAt(),
            option.getUpdatedAt(),
            staff ? option.getDeletedAt() : null
        );
    }
}
