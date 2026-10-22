package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionImageRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Applies the same statement visibility and teacher assignment rules before image URLs are issued. */
@Service
public class QuestionImageReadAccessService {

    private final QuestionImageRepository images;
    private final QuestionRepository questions;
    private final StatementRepository statements;
    private final StatementReadAccessService readAccess;
    private final CurrentAccountService currentAccount;
    private final StatementWriteAccessService writeAccess;

    public QuestionImageReadAccessService(
            QuestionImageRepository images,
            QuestionRepository questions,
            StatementRepository statements,
            StatementReadAccessService readAccess,
            StatementWriteAccessService writeAccess,
            CurrentAccountService currentAccount) {
        this.images = images;
        this.questions = questions;
        this.statements = statements;
        this.readAccess = readAccess;
        this.writeAccess = writeAccess;
        this.currentAccount = currentAccount;
    }

    public Mono<Void> requireQuestion(Long questionId) {
        return currentAccount.requiredAccountId().flatMap(accountId ->
                currentAccount.hasAnyRole("ADMIN").flatMap(admin ->
                        currentAccount.hasAnyRole("TEACHER").flatMap(teacher ->
                                questions.findByIdAndDeletedAtIsNull(questionId)
                                        .switchIfEmpty(Mono.error(ApiException.notFound("Question not found")))
                                        .flatMap(question -> requireStatement(question, accountId, admin, teacher)))));
    }

    public Mono<Void> requireImage(Long imageId) {
        return images.findByIdAndDeletedAtIsNull(imageId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Question image not found")))
                .flatMap(image -> requireQuestion(image.getQuestionId()));
    }

    public Mono<Void> requireWritableQuestion(Long questionId) {
        return Mono.zip(currentAccount.requiredAccountId(), currentAccount.hasAnyRole("ADMIN"))
                .flatMap(actor -> questions.findByIdAndDeletedAtIsNull(questionId)
                        .switchIfEmpty(Mono.error(ApiException.notFound("Question not found")))
                        .flatMap(question -> writeAccess.requireCanWrite(
                                question.getStatementId(), actor.getT1(), actor.getT2())))
                .then();
    }

    public Mono<Void> requireWritableImage(Long imageId, boolean deleted) {
        Mono<ao.creativemode.kixi.exams.model.QuestionImage> lookup = deleted
                ? images.findByIdAndDeletedAtIsNotNull(imageId)
                : images.findByIdAndDeletedAtIsNull(imageId);
        return lookup.switchIfEmpty(Mono.error(ApiException.notFound("Question image not found")))
                .flatMap(image -> requireWritableQuestion(image.getQuestionId()));
    }

    private Mono<Void> requireStatement(Question question, Long accountId, boolean admin, boolean teacher) {
        Mono<Statement> statement = admin
                ? statements.findByIdAndDeletedAtIsNull(question.getStatementId())
                : teacher
                        ? statements.findByIdAndDeletedAtIsNull(question.getStatementId())
                        : statements.findByIdAndVisibleTrueAndDeletedAtIsNull(question.getStatementId());
        return statement.switchIfEmpty(Mono.error(ApiException.notFound("Statement not found")))
                .flatMap(found -> {
                    if (admin || !teacher) {
                        return Mono.empty();
                    }
                    return readAccess.teacherScope(accountId)
                            .filter(scope -> scope.allows(found))
                            .switchIfEmpty(Mono.error(ApiException.forbidden(
                                    "Teacher is not assigned to this statement")))
                            .then();
                });
    }
}
