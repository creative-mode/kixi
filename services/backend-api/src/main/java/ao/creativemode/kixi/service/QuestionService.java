package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.question.QuestionRequest;
import ao.creativemode.kixi.dto.question.QuestionResponse;
import ao.creativemode.kixi.model.Question;
import ao.creativemode.kixi.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class QuestionService {

    private final QuestionRepository repository;

    public Flux<QuestionResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull()
                .map(this::toResponse);
    }

    public Flux<QuestionResponse> findAllDeleted() {
        return repository.findAllByDeletedAtIsNotNull()
                .map(this::toResponse);
    }

    public Mono<QuestionResponse> findByIdActive(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Question not found")))
                .map(this::toResponse);
    }

    public Mono<QuestionResponse> create(QuestionRequest dto) {
        Question entity = Question.builder()
                .statementId(dto.statementId())
                .number(dto.number())
                .text(dto.text())
                .questionType(dto.questionType())
                .maxScore(dto.maxScore())
                .orderIndex(dto.orderIndex())
                .build();

        return repository.save(entity)
                .map(this::toResponse)
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> ApiException.conflict("Question with number " + dto.number() + " already exists for this statement."));
    }

    public Mono<QuestionResponse> update(Long id, QuestionRequest dto) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Question not found")))
                .flatMap(entity -> {
                    entity.setNumber(dto.number() != null ? dto.number() : entity.getNumber());
                    entity.setText(dto.text() != null ? dto.text() : entity.getText());
                    entity.setQuestionType(dto.questionType() != null ? dto.questionType() : entity.getQuestionType());
                    entity.setMaxScore(dto.maxScore() != null ? dto.maxScore() : entity.getMaxScore());
                    entity.setOrderIndex(dto.orderIndex() != null ? dto.orderIndex() : entity.getOrderIndex());
                    entity.setUpdatedAt(LocalDateTime.now());

                    return repository.save(entity)
                            .onErrorMap(DataIntegrityViolationException.class,
                                    e -> ApiException.conflict("Update failed: conflict with existing question data."));
                })
                .map(this::toResponse);
    }

    public Mono<Void> softDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Question not found")))
                .flatMap(entity -> {
                    entity.softDelete(); // Usando o método que definimos na Entity
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> restore(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Question is not deleted")))
                .flatMap(entity -> {
                    entity.setDeletedAt(null); // Restore logic
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> hardDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Only deleted questions can be permanently removed")))
                .flatMap(repository::delete)
                .then();
    }

    private QuestionResponse toResponse(Question entity) {
        return new QuestionResponse(
                entity.getId(),
                entity.getStatementId(),
                entity.getNumber(),
                entity.getText(),
                entity.getQuestionType(),
                entity.getMaxScore(),
                entity.getOrderIndex(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }
}