package ao.creativemode.kixi.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.accounts.AccountBasicResponse;
import ao.creativemode.kixi.dto.schoolyears.SchoolYearResponse;
import ao.creativemode.kixi.dto.simulation.SimulationRequest;
import ao.creativemode.kixi.dto.simulation.SimulationResponse;
import ao.creativemode.kixi.dto.statement.StatementBasicResponse;
import ao.creativemode.kixi.model.*;
import ao.creativemode.kixi.repository.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class SimulationService {

    private final SimulationRepository repository;
    private final AccountRepository accountRepository;
    private final SchoolYearRepository schoolYearRepository;
    private final StatementRepository statementRepository;

    public SimulationService(
            SimulationRepository repository,
            AccountRepository accountRepository,
            SchoolYearRepository schoolYearRepository,
            StatementRepository statementRepository
    ) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.schoolYearRepository = schoolYearRepository;
        this.statementRepository = statementRepository;
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

    public Mono<SimulationResponse> create(SimulationRequest dto) {
        return accountRepository.findById(dto.accountId())
                .switchIfEmpty(Mono.error(ApiException.notFound("Account not found")))
                .then(Mono.defer(() -> {
                    if (dto.schoolYearId() != null) {
                        return schoolYearRepository.findById(dto.schoolYearId())
                                .switchIfEmpty(Mono.error(ApiException.notFound("SchoolYear not found")))
                                .then(Mono.just(true));
                    }
                    return Mono.just(true);
                }))
                .then(Mono.defer(() -> {
                    return statementRepository.findById(dto.statementId())
                            .switchIfEmpty(Mono.error(ApiException.notFound("Statement not found")))
                            .then(Mono.just(true));
                }))
                .then(Mono.defer(() -> {
                    Simulation simulation = new Simulation();
                    simulation.setAccountId(dto.accountId());
                    simulation.setSchoolYearId(dto.schoolYearId());
                    simulation.setStatementId(dto.statementId());
                    simulation.setStartedAt(dto.startedAt() != null ? dto.startedAt() : LocalDateTime.now());
                    simulation.setStatus(SimulationStatus.IN_PROGRESS);
                    return repository.save(simulation);
                }))
                .flatMap(this::toResponse);
    }

    public Mono<SimulationResponse> createForAccount(SimulationRequest dto, Long accountId) {
        if (!accountId.equals(dto.accountId())) {
            return Mono.error(ApiException.forbidden("A simulation can only be created for the authenticated account"));
        }
        return create(dto);
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
                        if (dto.finishedAt() == null || dto.timeSpentSeconds() == null) {
                            return Mono.error(ApiException.badRequest(
                                    "finishedAt and timeSpentSeconds are required"
                            ));
                        }
                        simulation.setFinishedAt(dto.finishedAt());
                        simulation.setTimeSpentSeconds(dto.timeSpentSeconds());
                        simulation.setFinalScore(dto.finalScore());
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
        Mono<AccountBasicResponse> accountMono = accountRepository.findById(simulation.getAccountId())
                .map(this::toAccountResponse)
                .switchIfEmpty(Mono.just(new AccountBasicResponse(simulation.getAccountId(), null, null)));

        Mono<StatementBasicResponse> statementMono = simulation.getStatementId() != null
                ? statementRepository.findById(simulation.getStatementId())
                    .map(this::toStatementResponse)
                    .switchIfEmpty(Mono.just(new StatementBasicResponse(null, null, null, null, null, null)))
                : Mono.just(new StatementBasicResponse(null, null, null, null, null, null));

        Mono<SchoolYearResponse> schoolYearMono = simulation.getSchoolYearId() != null
                ? schoolYearRepository.findById(simulation.getSchoolYearId())
                    .map(this::toSchoolYearResponse)
                    .switchIfEmpty(Mono.just(new SchoolYearResponse(null, null, null, null, null, null)))
                : Mono.just(new SchoolYearResponse(null, null, null, null, null, null));

        return Mono.zip(accountMono, statementMono, schoolYearMono)
                .map(tuple -> new SimulationResponse(
                        simulation.getId(),
                        tuple.getT1(),
                        tuple.getT2(),
                        tuple.getT3(),
                        simulation.getStartedAt(),
                        simulation.getFinishedAt(),
                        simulation.getTimeSpentSeconds(),
                        simulation.getFinalScore(),
                        simulation.getStatus(),
                        simulation.getCreatedAt(),
                        simulation.getUpdatedAt(),
                        simulation.getDeletedAt()
                ));
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
