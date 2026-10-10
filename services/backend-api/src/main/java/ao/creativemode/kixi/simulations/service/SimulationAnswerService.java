package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.dto.simulationanswer.SimulationAnswerRequest;
import ao.creativemode.kixi.simulations.dto.simulationanswer.SimulationAnswerResponse;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import java.time.LocalDateTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class SimulationAnswerService {

    private static final String EXPIRED_MESSAGE =
        "The time allowed for this simulation has expired";

    private final SimulationAnswerRepository repository;
    private final SimulationRepository simulationRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final SimulationDeadlineService deadlineService;
    private final SimulationService simulationService;

    @Autowired
    public SimulationAnswerService(
        SimulationAnswerRepository repository,
        SimulationRepository simulationRepository,
        QuestionRepository questionRepository,
        QuestionOptionRepository optionRepository,
        SimulationDeadlineService deadlineService,
        SimulationService simulationService
    ) {
        this.repository = repository;
        this.simulationRepository = simulationRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.deadlineService = deadlineService;
        this.simulationService = simulationService;
    }

    public SimulationAnswerService(
        SimulationAnswerRepository repository, SimulationRepository simulationRepository,
        QuestionRepository questionRepository, SimulationDeadlineService deadlineService) {
        this(repository, simulationRepository, questionRepository, null, deadlineService, null);
    }

    public Mono<Void> authorizeAnswer(Long answerId, Long accountId, boolean admin, boolean teacher) {
        return authorizeAnswer(answerId, accountId, admin, teacher, false);
    }

    public Mono<Void> authorizeAnswer(Long answerId, Long accountId, boolean admin, boolean teacher,
            boolean includeDeleted) {
        Mono<SimulationAnswer> lookup = includeDeleted ? repository.findById(answerId)
            : repository.findByIdAndDeletedAtIsNull(answerId);
        return lookup
            .switchIfEmpty(Mono.error(ApiException.notFound("Simulation answer not found")))
            .flatMap(answer -> authorizeSimulation(answer.getSimulationId(), accountId, admin, teacher,
                includeDeleted));
    }

    public Mono<Void> authorizeSimulation(Long simulationId, Long accountId, boolean admin, boolean teacher) {
        return authorizeSimulation(simulationId, accountId, admin, teacher, false);
    }

    private Mono<Void> authorizeSimulation(Long simulationId, Long accountId, boolean admin, boolean teacher,
            boolean includeDeleted) {
        return simulationService == null ? Mono.empty()
            : simulationService.authorize(simulationId, accountId, admin, teacher, includeDeleted).then();
    }

    public Flux<SimulationAnswerResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull().map(this::toResponse);
    }

    public Flux<SimulationAnswerResponse> findAllActiveForStaff(Long accountId, boolean admin) {
        return repository.findAllByDeletedAtIsNull()
            .flatMap(answer -> admin || simulationService == null
                ? Mono.just(toResponse(answer))
                : simulationService.authorize(answer.getSimulationId(), accountId, false, true)
                    .map(ignored -> toResponse(answer)).onErrorResume(ignored -> Mono.empty()));
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

    public Flux<SimulationAnswerResponse> findAllDeletedForStaff(Long accountId, boolean admin) {
        return repository.findAllByDeletedAtIsNotNull()
            .flatMap(answer -> simulationService.authorize(answer.getSimulationId(), accountId, admin, !admin, true)
                .map(ignored -> toResponse(answer)).onErrorResume(ignored -> Mono.empty()));
    }

    public Flux<SimulationAnswerResponse> findAllDeletedForAccount(Long accountId) {
        return simulationRepository.findByAccountId(accountId)
            .map(Simulation::getId).collectList()
            .flatMapMany(ids -> ids.isEmpty() ? Flux.empty() : repository.findAllBySimulationIdInAndDeletedAtIsNotNull(ids))
            .map(this::toResponse);
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

    @Transactional
    public Mono<SimulationAnswerResponse> create(
        SimulationAnswerRequest request
    ) {
        return requireSimulationAndQuestion(request.simulationId(), request.questionId(), request.selectedOptionId())
             .then(Mono.defer(() -> lockEditableSimulations(request.simulationId())
                 .then(Mono.defer(() -> {
                 SimulationAnswer answer = new SimulationAnswer();
                answer.setSimulationId(request.simulationId());
                answer.setQuestionId(request.questionId());
                answer.setSelectedOptionId(request.selectedOptionId());
                answer.setAnswerText(request.answerText());
                answer.setAnsweredAt(request.answeredAt());

                return repository
                    .insertIfInProgress(answer.getSimulationId(), answer.getQuestionId(),
                        answer.getSelectedOptionId(), answer.getAnswerText(), answer.getAnsweredAt())
                    .switchIfEmpty(Mono.error(ApiException.conflict("Simulation no longer accepts answers")))
                    .map(this::toResponse)
                    .onErrorMap(DataIntegrityViolationException.class, e ->
                        ApiException.conflict(
                            "This question has already been answered in this simulation."
                        )
                    );
                  }))));
    }

    private Mono<Void> requireSimulationAndQuestion(Long simulationId, Long questionId, Long optionId) {
        return simulationRepository.findByIdAndDeletedAtIsNull(simulationId)
            .switchIfEmpty(Mono.error(ApiException.badRequest("Simulation not found: " + simulationId)))
            .flatMap(simulation -> {
                if (simulation.getStatus() != ao.creativemode.kixi.simulations.model.SimulationStatus.IN_PROGRESS) {
                    return Mono.error(ApiException.conflict("Simulation no longer accepts answers"));
                }
                return requireNotExpired(simulation)
                        .then(questionLookup(questionId)
                        .switchIfEmpty(Mono.error(ApiException.badRequest("Question not found: " + questionId)))
                        .flatMap(question -> {
                            if (simulation.getStatementId() != null
                                    && !simulation.getStatementId().equals(question.getStatementId())) {
                                return Mono.error(ApiException.badRequest("Question does not belong to the simulation statement"));
                            }
                            return validateSelectedOption(question, optionId);
                        }));
            })
            .then();
    }

    private Mono<Void> validateSelectedOption(ao.creativemode.kixi.exams.model.Question question, Long optionId) {
        if (optionId == null) return Mono.empty();
        if ("open".equalsIgnoreCase(question.getQuestionType())
                || "development".equalsIgnoreCase(question.getQuestionType())) {
            return Mono.error(ApiException.badRequest("Open questions cannot select an option"));
        }
        if (optionRepository == null) return Mono.empty();
        return optionRepository.findByIdAndDeletedAtIsNull(optionId)
            .filter(option -> question.getId().equals(option.getQuestionId()))
            .switchIfEmpty(Mono.error(ApiException.badRequest("Option does not belong to the question")))
            .then();
    }

    private Mono<ao.creativemode.kixi.exams.model.Question> questionLookup(Long questionId) {
        Mono<ao.creativemode.kixi.exams.model.Question> result =
            questionRepository.findByIdAndDeletedAtIsNull(questionId);
        return result == null ? questionRepository.findById(questionId) : result;
    }

    /**
     * Issue #107: the server clock alone decides. The answeredAt the client
     * sends is never read here — the elapsed time belongs to the simulation,
     * not to whoever is submitting the answer. A simulation with no deadline
     * (no statement, no start, or a statement without a duration) has no time
     * to run out of and is always let through.
     */
    private Mono<Simulation> requireNotExpired(Simulation simulation) {
        return deadlineService.expired(simulation, deadlineService.now())
            .flatMap(expired -> Boolean.TRUE.equals(expired)
                ? Mono.<Simulation>error(ApiException.conflict(EXPIRED_MESSAGE))
                : Mono.just(simulation));
    }

    @Transactional
    public Mono<SimulationAnswerResponse> createForAccount(
        SimulationAnswerRequest request,
        Long accountId
    ) {
        return requireSimulationOwner(request.simulationId(), accountId)
            .then(create(request));
    }

    @Transactional
    public Mono<SimulationAnswerResponse> update(
        Long id,
        SimulationAnswerRequest request
    ) {
        return repository
            .findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(
                Mono.error(ApiException.notFound("Simulation answer not found"))
            )
            .flatMap(answer -> requireEditableSimulation(answer.getSimulationId())
                .then(requireSimulationAndQuestion(request.simulationId(), request.questionId(), request.selectedOptionId()))
                 .then(Mono.defer(() -> lockEditableSimulations(answer.getSimulationId(), request.simulationId())
                     .then(Mono.defer(() -> {
                     return repository.updateIfInProgress(answer.getId(), answer.getSimulationId(),
                            request.simulationId(), request.questionId(), request.selectedOptionId(),
                            request.answerText(), request.answeredAt())
                        .switchIfEmpty(Mono.error(ApiException.conflict("Simulation no longer accepts answers")));
                     })))))
            .map(this::toResponse)
            .onErrorMap(DataIntegrityViolationException.class, e ->
                ApiException.conflict(
                    "This question has already been answered in this simulation."
                )
            );
    }

    @Transactional
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
                    "This question has already been answered in this simulation."
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
            entity.getReviewStatus(),
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
        return requireEditableSimulation(answer.getSimulationId())
            .then(requireSimulationAndQuestion(request.simulationId(), request.questionId(), request.selectedOptionId()))
            .then(Mono.defer(() -> lockEditableSimulations(answer.getSimulationId(), request.simulationId())
                .then(Mono.defer(() -> {
                return repository.updateIfInProgress(answer.getId(), answer.getSimulationId(),
                        request.simulationId(), request.questionId(), request.selectedOptionId(),
                        request.answerText(), request.answeredAt())
                    .switchIfEmpty(Mono.error(ApiException.conflict("Simulation no longer accepts answers")));
                }))));
    }

    private Mono<Void> lockEditableSimulations(Long... simulationIds) {
        return Flux.fromArray(simulationIds)
            .distinct()
            .sort()
            .concatMap(id -> simulationRepository.lockForAnswerWrite(id)
                .switchIfEmpty(Mono.error(ApiException.conflict("Simulation no longer accepts answers")))
                .flatMap(simulation -> simulation.getStatus()
                    == ao.creativemode.kixi.simulations.model.SimulationStatus.IN_PROGRESS
                    ? requireNotExpired(simulation)
                    : Mono.error(ApiException.conflict("Simulation no longer accepts answers"))))
            .then();
    }

    private Mono<Void> requireEditableSimulation(Long simulationId) {
        return simulationRepository.findByIdAndDeletedAtIsNull(simulationId)
            .switchIfEmpty(Mono.error(ApiException.notFound("Simulation not found")))
            .flatMap(simulation -> simulation.getStatus()
                    == ao.creativemode.kixi.simulations.model.SimulationStatus.IN_PROGRESS
                ? requireNotExpired(simulation).then()
                : Mono.error(ApiException.conflict("Simulation no longer accepts answers")));
    }
}
