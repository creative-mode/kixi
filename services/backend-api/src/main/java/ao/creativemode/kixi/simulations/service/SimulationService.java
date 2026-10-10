package ao.creativemode.kixi.simulations.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.identity.dto.accounts.AccountBasicResponse;
import ao.creativemode.kixi.academic.dto.schoolyears.SchoolYearResponse;
import ao.creativemode.kixi.simulations.dto.simulation.SimulationRequest;
import ao.creativemode.kixi.simulations.dto.simulation.SimulationResponse;
import ao.creativemode.kixi.exams.dto.statement.StatementBasicResponse;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import ao.creativemode.kixi.shared.service.ExamRoomAccess;
import ao.creativemode.kixi.exams.service.StatementWriteAccessService;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class SimulationService {

    private final SimulationRepository repository;
    private final AccountRepository accountRepository;
    private final SchoolYearRepository schoolYearRepository;
    private final StatementRepository statementRepository;
    private final SimulationDeadlineService deadlineService;
    private final ExamRoomAccess examRoomAccess;
    private final StatementWriteAccessService statementWriteAccess;

    @Autowired
    public SimulationService(
            SimulationRepository repository,
            AccountRepository accountRepository,
            SchoolYearRepository schoolYearRepository,
            StatementRepository statementRepository,
            SimulationDeadlineService deadlineService,
            ExamRoomAccess examRoomAccess,
            StatementWriteAccessService statementWriteAccess
    ) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.schoolYearRepository = schoolYearRepository;
        this.statementRepository = statementRepository;
        this.deadlineService = deadlineService;
        this.examRoomAccess = examRoomAccess;
        this.statementWriteAccess = statementWriteAccess;
    }

    public SimulationService(
            SimulationRepository repository, AccountRepository accountRepository,
            SchoolYearRepository schoolYearRepository, StatementRepository statementRepository,
            SimulationDeadlineService deadlineService) {
        this(repository, accountRepository, schoolYearRepository, statementRepository,
                deadlineService, null, null);
    }

    public Flux<SimulationResponse> findAllActive() {
        return repository.findByDeletedAtIsNull()
                .flatMap(this::toResponse);
    }

    public Flux<SimulationResponse> findAllActiveForAccount(Long accountId) {
        return repository.findByAccountIdAndDeletedAtIsNull(accountId)
                .flatMap(this::toResponse);
    }

    public Flux<SimulationResponse> findAllTrashed() {
        return repository.findByDeletedAtIsNotNull()
                .flatMap(this::toResponse);
    }

    public Mono<SimulationResponse> findById(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .flatMap(this::toResponse);
    }

    public Mono<SimulationResponse> findByIdForAccount(Long id, Long accountId) {
        return repository.findByIdAndAccountIdAndDeletedAtIsNull(id, accountId)
                .flatMap(this::toResponse);
    }

    public Mono<Simulation> authorize(Long id, Long accountId, boolean admin, boolean teacher) {
        return authorize(id, accountId, admin, teacher, false);
    }

    public Mono<Simulation> authorize(Long id, Long accountId, boolean admin, boolean teacher,
            boolean includeDeleted) {
        Mono<Simulation> lookup = includeDeleted ? repository.findById(id)
                : repository.findByIdAndDeletedAtIsNull(id);
        return lookup
                .switchIfEmpty(Mono.error(ApiException.notFound("Simulation not found: " + id)))
                .flatMap(simulation -> {
                    if (admin)
                        return Mono.just(simulation);
                    if (simulation.getExamRoomId() == null && teacher && statementWriteAccess != null)
                        return statementWriteAccess.requireCanWrite(simulation.getStatementId(), accountId, false)
                                .thenReturn(simulation);
                    if (simulation.getExamRoomId() == null && accountId.equals(simulation.getAccountId())) {
                        return Mono.just(simulation);
                    }
                    if (examRoomAccess == null) {
                        return Mono.error(ApiException.notFound("Simulation not found: " + id));
                    }
                    return examRoomAccess.canAccessSimulation(simulation.getExamRoomId(), simulation.getId(),
                                    simulation.getAccountId(), accountId, admin, teacher)
                            .flatMap(allowed -> allowed ? Mono.just(simulation)
                                    : Mono.error(ApiException.notFound("Simulation not found: " + id)));
                });
    }

    public Flux<SimulationResponse> findAllAuthorized(Long accountId, boolean admin, boolean teacher) {
        return repository.findByDeletedAtIsNull()
                .flatMap(simulation -> authorize(simulation.getId(), accountId, admin, teacher)
                        .flatMap(this::toResponse)
                        .onErrorResume(ignored -> Mono.empty()));
    }

    public Flux<SimulationResponse> findAllTrashedAuthorized(Long accountId, boolean admin, boolean teacher) {
        return repository.findByDeletedAtIsNotNull()
                .flatMap(simulation -> authorize(simulation.getId(), accountId, admin, teacher, true)
                        .flatMap(this::toResponse)
                        .onErrorResume(ignored -> Mono.empty()));
    }

    @Transactional
    public Mono<SimulationResponse> create(SimulationRequest dto) {
        return create(dto, dto.accountId(), true);
    }

    @Transactional
    public Mono<SimulationResponse> create(SimulationRequest dto, Long callerAccountId, boolean admin) {
        return create(dto, callerAccountId, admin, true);
    }

    private Mono<SimulationResponse> create(SimulationRequest dto, Long callerAccountId, boolean admin,
            boolean authorizeStatement) {
        return accountRepository.findById(dto.accountId())
                .switchIfEmpty(Mono.error(ApiException.notFound("Account not found")))
                .then(statementLookup(dto.statementId())
                        .switchIfEmpty(Mono.error(ApiException.notFound("Statement not found")))
                        .flatMap(statement -> !authorizeStatement
                                ? Mono.just(statement)
                                : statementWriteAccess == null
                                ? admin ? Mono.just(statement) : Mono.error(ApiException.forbidden(
                                        "Statement authorization is unavailable"))
                                : statementWriteAccess.requireCanWrite(statement.getId(), callerAccountId, admin))
                         .flatMap(statement -> {
                             if (examRoomAccess == null) {
                                 return Mono.just(statement);
                             }
                             Mono<Void> statementLock = examRoomAccess.lockStatementForSimulation(statement.getId());
                             if (statementLock == null) statementLock = Mono.empty();
                             Mono<Boolean> activeRoom = examRoomAccess.hasOpenOrRunningRoom(statement.getId());
                             if (activeRoom == null) activeRoom = Mono.just(false);
                             return statementLock
                                     .then(activeRoom)
                                     .defaultIfEmpty(false)
                                     .flatMap(active -> active
                                             ? Mono.error(ApiException.conflict(
                                                     "The statement is currently assigned to an exam room"))
                                             : Mono.just(statement));
                         })
                        .then(Mono.defer(() -> {
                    if (dto.schoolYearId() != null) {
                        return schoolYearRepository.findById(dto.schoolYearId())
                                .switchIfEmpty(Mono.error(ApiException.notFound("SchoolYear not found")))
                                .then(Mono.just(true));
                    }
                    return Mono.just(true);
                })))
                .then(Mono.defer(() -> {
                    Simulation simulation = new Simulation();
                    simulation.setAccountId(dto.accountId());
                    simulation.setSchoolYearId(dto.schoolYearId());
                    simulation.setStatementId(dto.statementId());
                    // The clock starts on the server; a client must not choose its own elapsed time.
                    simulation.setStartedAt(LocalDateTime.now());
                    simulation.setStatus(SimulationStatus.IN_PROGRESS);
                    return repository.save(simulation);
                }))
                .flatMap(this::toResponse);
    }

    private Mono<Statement> statementLookup(Long statementId) {
        Mono<Statement> active = statementRepository.findByIdAndDeletedAtIsNull(statementId);
        return active == null ? statementRepository.findById(statementId) : active;
    }

    @Transactional
    public Mono<SimulationResponse> createForAccount(SimulationRequest dto, Long accountId) {
        if (!accountId.equals(dto.accountId())) {
            return Mono.error(ApiException.forbidden("A simulation can only be created for the authenticated account"));
        }
        return create(dto, accountId, false, false);
    }

    public Mono<SimulationResponse> update(Long id, SimulationRequest dto) {
        return updateExisting(repository.findByIdAndDeletedAtIsNull(id), dto);
    }

    public Mono<SimulationResponse> updateForAccount(Long id, SimulationRequest dto, Long accountId) {
        return updateExisting(repository.findByIdAndAccountIdAndDeletedAtIsNull(id, accountId), dto);
    }

    private Mono<SimulationResponse> updateExisting(Mono<Simulation> simulationMono, SimulationRequest dto) {
        return simulationMono
                .switchIfEmpty(Mono.error(ApiException.notFound("Simulation not found!")))
                .flatMap(simulation -> {
                    if (!SimulationStatus.IN_PROGRESS.equals(simulation.getStatus())) {
                        return Mono.error(ApiException.badRequest("Simulation cannot be updated"));
                    }

                    if (dto.status() != null && dto.status() != SimulationStatus.FINISHED
                            && dto.status() != SimulationStatus.CANCELLED) {
                        return Mono.error(ApiException.badRequest("Invalid status"));
                    }

                    if (dto.status() == SimulationStatus.FINISHED) {
                        return Mono.error(ApiException.badRequest(
                                "Use POST /simulations/{id}/submit to finish a simulation"));
                    }

                    if (dto.status() != null) {
                        simulation.setStatus(dto.status());
                    }
                    simulation.setUpdatedAt(LocalDateTime.now());

                    return repository.save(simulation);
                })
                .flatMap(this::toResponse);
    }

    public Mono<Void> softDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Simulation not found!")))
                .flatMap(simulation -> {
                    if (simulation.getExamRoomId() != null) {
                        return Mono.error(ApiException.conflict(
                                "Exam-room simulations cannot be moved to trash; close or submit the room instead"));
                    }
                    simulation.markAsDelete();
                    return repository.save(simulation);
                })
                .then();
    }

    public Mono<Void> restore(Long id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Simulation not found")))
                .flatMap(simulation -> {
                    if (simulation.getDeletedAt() == null) {
                        return Mono.error(ApiException.conflict("Simulation is not deleted"));
                    }
                    if (simulation.getExamRoomId() != null) {
                        return Mono.error(ApiException.conflict(
                                "Exam-room simulations cannot be restored after deletion"));
                    }
                    simulation.restore();
                    return repository.save(simulation);
                })
                .then();
    }

    public Mono<Void> hardDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Simulation not found or not in trash")))
                .flatMap(repository::delete).then();
    }

    private Mono<SimulationResponse> toResponse(Simulation simulation) {
        Mono<Account> accountMono = accountRepository.findById(simulation.getAccountId())
                .switchIfEmpty(Mono.fromSupplier(() -> accountWithIdOnly(simulation.getAccountId())));

        Mono<Statement> statementMono = simulation.getStatementId() != null
                ? statementRepository.findById(simulation.getStatementId())
                    .defaultIfEmpty(new Statement())
                : Mono.just(new Statement());

        Mono<SchoolYearResponse> schoolYearMono = simulation.getSchoolYearId() != null
                ? schoolYearRepository.findById(simulation.getSchoolYearId())
                    .map(this::toSchoolYearResponse)
                    .switchIfEmpty(Mono.just(new SchoolYearResponse(null, null, null, null, null, null)))
                : Mono.just(new SchoolYearResponse(null, null, null, null, null, null));

        Mono<LocalDateTime> deadlineLookup = deadlineService.deadline(simulation);
        if (deadlineLookup == null) deadlineLookup = Mono.empty();
        Mono<LocalDateTime> finalDeadlineLookup = deadlineLookup;
        Mono<Boolean> visibility = simulation.getExamRoomId() == null || examRoomAccess == null
                ? (examRoomAccess == null ? Mono.just(true)
                        : Mono.defer(() -> {
                            Mono<Boolean> active = examRoomAccess.hasOpenOrRunningRoom(simulation.getStatementId());
                            return (active == null ? Mono.just(false) : active)
                                    .defaultIfEmpty(false).map(value -> !value);
                        }))
                : Mono.justOrEmpty(examRoomAccess.answerKeyVisible(simulation.getExamRoomId()))
                        .flatMap(value -> value).defaultIfEmpty(false);
        return Mono.zip(accountMono, statementMono, schoolYearMono, visibility)
                .flatMap(tuple -> finalDeadlineLookup
                    .switchIfEmpty(Mono.justOrEmpty(deadlineService.effectiveDeadlineFor(
                        simulation.getStartedAt(),
                        simulation.getExamRoomDurationMinutes() != null
                                ? simulation.getExamRoomDurationMinutes() : tuple.getT2().getDurationMinutes(),
                        Boolean.TRUE.equals(tuple.getT1().getAccessibilityExtraTime()))))
                    .map(deadline -> toResponse(simulation, tuple, deadline, tuple.getT4()))
                    .switchIfEmpty(Mono.fromSupplier(() -> toResponse(simulation, tuple, null, tuple.getT4()))));
    }

    private SimulationResponse toResponse(Simulation simulation,
            reactor.util.function.Tuple4<Account, Statement, SchoolYearResponse, Boolean> tuple,
            LocalDateTime deadline, boolean answerKeyVisible) {
                    Account account = tuple.getT1();
                    Statement statement = tuple.getT2();
                    return new SimulationResponse(
                        simulation.getId(),
                        toAccountResponse(account),
                        toStatementResponse(statement),
                        tuple.getT3(),
                        simulation.getStartedAt(),
                        simulation.getFinishedAt(),
                        deadline,
                        simulation.getTimeSpentSeconds(),
                        answerKeyVisible ? simulation.getFinalScore() : null,
                        simulation.getStatus(),
                        simulation.getCreatedAt(),
                        simulation.getUpdatedAt(),
                        simulation.getDeletedAt()
                    );
    }

    private Account accountWithIdOnly(Long accountId) {
        Account account = new Account();
        account.setId(accountId);
        return account;
    }

    private AccountBasicResponse toAccountResponse(Account account) {
        return new AccountBasicResponse(account.getId(), account.getUsername(), account.getEmail());
    }

    private StatementBasicResponse toStatementResponse(Statement statement) {
        return new StatementBasicResponse(
                statement.getId(),
                statement.getExamType(),
                statement.getVariant(),
                statement.getTitle(),
                statement.getDurationMinutes(),
                statement.getTotalMaxScore()
        );
    }

    private SchoolYearResponse toSchoolYearResponse(SchoolYear schoolYear) {
        return new SchoolYearResponse(
                schoolYear.getId(),
                schoolYear.getStartYear(),
                schoolYear.getEndYear(),
                schoolYear.getCreatedAt(),
                schoolYear.getUpdatedAt(),
                schoolYear.getDeletedAt()
        );
    }
}
