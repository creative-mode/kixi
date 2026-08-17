package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.simulationanswer.SimulationAnswerRequest;
import ao.creativemode.kixi.dto.simulationanswer.SimulationAnswerResponse;
import ao.creativemode.kixi.model.SimulationAnswer;
import ao.creativemode.kixi.model.Simulation;
import ao.creativemode.kixi.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.repository.SimulationRepository;
import java.time.LocalDateTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class SimulationAnswerService {

    private final SimulationAnswerRepository repository;
    private final SimulationRepository simulationRepository;

    public SimulationAnswerService(
        SimulationAnswerRepository repository,
        SimulationRepository simulationRepository
    ) {
        this.repository = repository;
        this.simulationRepository = simulationRepository;
    }

    public Flux<SimulationAnswerResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull().map(this::toResponse);
    }

    public Flux<SimulationAnswerResponse> findAllActiveForAccount(Long accountId) {
        return simulationRepository.findByAccountIdAndDeletedAtIsNull(accountId)
            .map(Simulation::getId)
            .collectList()
            .flatMapMany(simulationIds -> simulationIds.isEmpty()
                ? Flux.empty()
                : repository.findAllBySimulationIdInAndDeletedAtIsNull(simulationIds))
            .map(this::toResponse);
    }

    public Flux<SimulationAnswerResponse> findAllDeleted() {
        return repository.findAllByDeletedAtIsNotNull().map(this::toResponse);
    }

    public Mono<SimulationAnswerResponse> findByIdActive(Long id) {
        return repository
            .findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(
                Mono.error(ApiException.notFound("Simulation answer not found"))
            )
            .map(this::toResponse);
    }

    public Mono<SimulationAnswerResponse> findByIdActiveForAccount(Long id, Long accountId) {
        return repository.findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(Mono.error(ApiException.notFound("Simulation answer not found")))
            .flatMap(answer -> requireSimulationOwner(answer.getSimulationId(), accountId)
                .thenReturn(answer))
            .map(this::toResponse);
    }

    public Mono<SimulationAnswerResponse> create(
        SimulationAnswerRequest request
    ) {
        SimulationAnswer answer = new SimulationAnswer();
        answer.setSimulationId(request.simulationId());
        answer.setQuestionId(request.questionId());
        answer.setSelectedOptionId(request.selectedOptionId());
        answer.setAnswerText(request.answerText());
        answer.setAnsweredAt(request.answeredAt());

        return repository
            .save(answer)
            .map(this::toResponse)
            .onErrorMap(DataIntegrityViolationException.class, e ->
                ApiException.conflict(
                    "A simulation answer with this parameter already exists."
                )
            );
    }

    public Mono<SimulationAnswerResponse> createForAccount(
        SimulationAnswerRequest request,
        Long accountId
    ) {
        return requireSimulationOwner(request.simulationId(), accountId)
            .then(create(request));
    }

    public Mono<SimulationAnswerResponse> update(
        Long id,
        SimulationAnswerRequest request
    ) {
        return repository
            .findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(
                Mono.error(ApiException.notFound("Simulation answer not found"))
            )
            .flatMap(answer -> {
                answer.setSimulationId(request.simulationId());
                answer.setQuestionId(request.questionId());
                answer.setSelectedOptionId(request.selectedOptionId());
                answer.setAnswerText(request.answerText());
                answer.setAnsweredAt(request.answeredAt());
                answer.setUpdatedAt(LocalDateTime.now());

                return repository.save(answer);
            })
            .map(this::toResponse)
            .onErrorMap(DataIntegrityViolationException.class, e ->
                ApiException.conflict(
                    "A simulation answer with this parameter already exists."
                )
            );
    }

    public Mono<SimulationAnswerResponse> updateForAccount(
        Long id,
        SimulationAnswerRequest request,
        Long accountId
    ) {
        return requireSimulationOwner(request.simulationId(), accountId)
            .then(repository.findByIdAndDeletedAtIsNull(id))
            .switchIfEmpty(Mono.error(ApiException.notFound("Simulation answer not found")))
            .flatMap(answer -> requireSimulationOwner(answer.getSimulationId(), accountId)
                .then(updateExisting(answer, request)))
            .map(this::toResponse)
            .onErrorMap(DataIntegrityViolationException.class, e ->
                ApiException.conflict(
                    "A simulation answer with this parameter already exists."
                )
            );
    }

    public Mono<Void> softDelete(Long id) {
        return repository
            .findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(
                Mono.error(ApiException.notFound("Simulation answer not found"))
            )
            .flatMap(entity -> {
                entity.markAsDeleted();
                return repository.save(entity);
            })
            .then();
    }

    public Mono<Void> restore(Long id) {
        return repository
            .findByIdAndDeletedAtIsNotNull(id)
            .switchIfEmpty(
                Mono.error(
                    ApiException.badRequest("Simulation answer is not deleted")
                )
            )
            .flatMap(entity -> {
                entity.restore();
                return repository.save(entity);
            })
            .then();
    }

    public Mono<Void> hardDelete(Long id) {
        return repository
            .findByIdAndDeletedAtIsNotNull(id)
            .switchIfEmpty(
                Mono.error(
                    ApiException.badRequest(
                        "Only deleted simulation answers can be permanently removed"
                    )
                )
            )
            .flatMap(repository::delete)
            .then();
    }

    private SimulationAnswerResponse toResponse(SimulationAnswer entity) {
        return new SimulationAnswerResponse(
            entity.getId(),
            entity.getSimulationId(),
            entity.getQuestionId(),
            entity.getSelectedOptionId(),
            entity.getAnswerText(),
            entity.getScoreObtained(),
            entity.getIsCorrect(),
            entity.getAnsweredAt(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            entity.getDeletedAt()
        );
    }

    private Mono<Void> requireSimulationOwner(Long simulationId, Long accountId) {
        return simulationRepository.findByIdAndAccountIdAndDeletedAtIsNull(simulationId, accountId)
            .switchIfEmpty(Mono.error(ApiException.forbidden(
                "The simulation does not belong to the authenticated account"
            )))
            .then();
    }

    private Mono<SimulationAnswer> updateExisting(
        SimulationAnswer answer,
        SimulationAnswerRequest request
    ) {
        answer.setSimulationId(request.simulationId());
        answer.setQuestionId(request.questionId());
        answer.setSelectedOptionId(request.selectedOptionId());
        answer.setAnswerText(request.answerText());
        answer.setAnsweredAt(request.answeredAt());
        answer.setUpdatedAt(LocalDateTime.now());
        return repository.save(answer);
    }
}
