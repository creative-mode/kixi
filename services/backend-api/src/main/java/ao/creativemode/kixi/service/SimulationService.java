package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.simulation.SimulationRequest;
import ao.creativemode.kixi.dto.simulation.SimulationResponse;
import ao.creativemode.kixi.dto.simulation.SimulationUpdateRequest;
import ao.creativemode.kixi.model.Simulation;
import ao.creativemode.kixi.model.SimulationStatus;
import ao.creativemode.kixi.repository.SimulationRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class SimulationService {

    private final SimulationRepository repository;

    public SimulationService(SimulationRepository repository) {
        this.repository = repository;
    }

    public Flux<SimulationResponse> findAllActive(){
        return repository.findByDeletedAtIsNull().map(this::toResponse);
    }



    public Flux<SimulationResponse> findAllTrached(){
        return repository.findByDeletedAtIsNotNull().map(this::toResponse);
    }

    public Mono<Simulation> findById(Long id){
        return repository.findByIdAndDeletedAtIsNull(id);
    }
    public Mono<SimulationResponse> create(SimulationRequest dto){

        Simulation simulation = new Simulation();
        simulation.setAccountId(dto.accountId());
        simulation.setSchoolYearId(dto.schoolYearId());
        simulation.setStatementId(dto.statementId());
        simulation.setStartedAt(dto.startedAt());
        return repository.save(simulation).map(this::toResponse);
    }

    public Mono<SimulationResponse> update(Long id, SimulationUpdateRequest dto) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Simulation not found!")))
                .flatMap(simulation -> {

                    // just may update if it is IN_PROGRESS
                    if (!SimulationStatus.IN_PROGRESS.equals(simulation.getStatus())) {
                        return Mono.error(ApiException.badRequest("Simulation cannot be updated"));
                    }

                    // status invalid
                    if (dto.status() != SimulationStatus.FINISHED
                            && dto.status() != SimulationStatus.CANCELLED) {
                        return Mono.error(ApiException.badRequest("Invalid status"));
                    }

                    // finish simulation
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

                    // update status e updatedAt
                    simulation.setStatus(dto.status());
                    simulation.setUpdatedAt(LocalDateTime.now());

                    return repository.save(simulation);
                })
                .map(this::toResponse);
    }







    public Mono<Void> softDelete(Long id){

        return repository.findByIdAndDeletedAtIsNull(id).switchIfEmpty(Mono.error(ApiException.notFound("Simulation not found!"))).flatMap(
                simulation -> {
                    simulation.markAsDelete();
                    return repository.save(simulation);
                }

        ).then();
    }

    public Mono<Void> restore(Long id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Simulation not found")))
                .flatMap(simulation -> {

                    if (simulation.getDeletedAt() == null) {
                        return Mono.error(
                                ApiException.conflict("Simulation is not deleted")
                        );
                    }

                    simulation.restore();
                    return repository.save(simulation);
                })
                .then();
    }


    public Mono<Void> hardDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Simulation not found or not trashed")))
                .flatMap(repository::delete).then();
    }


    private SimulationResponse toResponse(Simulation simulation) {

        return new SimulationResponse(
                simulation.getId(),
                simulation.getAccountId(),
                simulation.getStatementId(),
                simulation.getSchoolYearId(),
                simulation.getStartedAt(),
                simulation.getFinishedAt(),
                simulation.getTimeSpentSeconds(),
                simulation.getFinalScore(),
                simulation.getStatus(),
                simulation.getCreatedAt(),
                simulation.getUpdatedAt(),
                simulation.getDeletedAt()

        );
    }
}
