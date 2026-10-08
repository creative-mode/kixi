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

    public Flux<QuestionOptionResponse> findAll(Long statementId, Long questionId) {
        return requireQuestion(statementId, questionId)
            // Deferred so the listing is never even asked for when the question
            // turns out not to be there.
            .thenMany(Flux.defer(() -> options.findAllByQuestionIdOrderedByOrderIndex(questionId)))
            .map(QuestionOptionService::toResponse);
    }

    public Flux<QuestionOptionResponse> findAllDeleted(Long statementId, Long questionId) {
        return requireQuestion(statementId, questionId)
            .thenMany(Flux.defer(() -> options.findAllByQuestionIdAndDeletedAtIsNotNull(questionId)))
            .map(QuestionOptionService::toResponse);
    }

    public Mono<QuestionOptionResponse> findById(Long statementId, Long questionId, Long optionId) {
        return requireQuestion(statementId, questionId)
            .then(Mono.defer(() -> findActiveOption(questionId, optionId)))
            .map(QuestionOptionService::toResponse);
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    /**
     * Adds an option, or brings a removed one back under the same label.
     *
     * <p>{@code uk_question_options_label} is unique on (question_id,
     * option_label) and does not know about deleted_at, so a label that was
     * removed stays taken. The label is an identity the teacher chose rather
     * than a position, so reusing it restores the option instead of colliding.
     */
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
                    return Mono.defer(() -> options.save(existing));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    QuestionOption option = new QuestionOption(
                        questionId, data.optionLabel().trim(), data.optionText().trim());
                    option.setOrderIndex(nextOrder);
                    if (Boolean.TRUE.equals(data.isCorrect())) {
                        option.markAsCorrect();
                    }
                    return Mono.defer(() -> options.save(option));
                })))
            .map(QuestionOptionService::toResponse);
    }

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
            .map(QuestionOptionService::toResponse);
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
            .flatMapMany(active -> applyOrder(questionId, active, optionIds))
            .map(QuestionOptionService::toResponse);
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

    public Mono<Void> restore(Long statementId, Long questionId, Long optionId, Long accountId, boolean admin) {
        return writeAccess.requireCanWrite(statementId, accountId, admin)
            .then(Mono.defer(() -> requireQuestion(statementId, questionId)))
            .then(Mono.defer(() -> Mono.defer(() -> options.findByIdAndDeletedAtIsNotNull(optionId))
                .filter(option -> option.getQuestionId().equals(questionId))
                .switchIfEmpty(Mono.error(ApiException.notFound(
                    "Only a deleted option of this question can be restored: " + optionId)))))
            .flatMap(option -> {
                option.restore();
                return Mono.defer(() -> options.save(option)).then();
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
            .map(QuestionOptionService::toResponse);
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private Flux<QuestionOption> applyOrder(
        Long questionId,
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

    private Mono<Void> requireQuestion(Long statementId, Long questionId) {
        return Mono.defer(() -> statements.findByIdAndDeletedAtIsNull(statementId))
            .switchIfEmpty(Mono.error(ApiException.notFound("Statement not found: " + statementId)))
            .then(Mono.defer(() -> questions.findByIdAndDeletedAtIsNull(questionId)))
            .filter(question -> question.getStatementId().equals(statementId))
            .switchIfEmpty(Mono.error(ApiException.notFound(
                "Question not found: " + questionId + " on statement " + statementId)))
            .then();
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

    private static QuestionOptionResponse toResponse(QuestionOption option) {
        return new QuestionOptionResponse(
            option.getId(),
            option.getQuestionId(),
            option.getOptionLabel(),
            option.getOptionText(),
            option.getIsCorrect(),
            option.getOrderIndex(),
            option.getCreatedAt(),
            option.getUpdatedAt()
        );
    }
}