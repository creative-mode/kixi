package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.simulationanswer.SimulationAnswerRequest;
import ao.creativemode.kixi.dto.simulationanswer.SimulationAnswerResponse;
import ao.creativemode.kixi.model.SimulationAnswer;
import ao.creativemode.kixi.repository.SimulationAnswerRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SimulationAnswerService {

    public final SimulationAnswerRepository repository;

    public SimulationAnswerService(SimulationAnswerRepository repository) {
        this.repository = repository;
    }

    public Flux<SimulationAnswerResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull()
                .map(this::toResponse);
    }

    public Mono<List<SimulationAnswerResponse>> listTrashed() {
        return repository.findAllByDeletedAtIsNotNull()
                .map(this::toResponse)
                .collectList();
    }

    public Flux<SimulationAnswerResponse> findByIdActive(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("School year not found")))
                .map(this::toResponse);
    }

    public Mono<SimulationAnswerResponse> create(SimulationAnswerRequest request) {
        SimulationAnswer answer = new SimulationAnswer();
        answer.setSimulationId(request.simulationId());
        answer.setQuestionId(request.questionId());
        answer.setSelectedOptionId(request.selectedOptionId());
        answer.setAnsweredAt(request.answeredAt());
        return repository.save(answer).map(this::toResponse).onErrorMap(DataIntegrityViolationException.class,
                e -> ApiException.conflict("A Simulation answer with this parameter already exists."));
    }


    public Flux<SimulationAnswerResponse> update(Long id, SimulationAnswerRequest request) {

        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(
                        ApiException.badRequest("SimulationAnswer not found or deleted")
                ))
                .flatMap(answer -> {

                    answer.setSelectedOptionId(request.selectedOptionId());
                    answer.setAnsweredAt(request.answeredAt());
                    answer.setUpdatedAt(LocalDateTime.now());

                    return repository.save(answer);
                })
                .map(this::toResponse);
    }


    public Mono<Void> hardDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(
                        Mono.error(ApiException.badRequest("Only deleted Simulation answer can be permanently removed")))
                .flatMap(repository::delete)
                .then();
    }

    public Mono<Void> restore(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("School year is not deleted")))
                .flatMap(entity -> {
                    entity.restore();
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> softDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("School year not found")))
                .flatMap(entity -> {
                    entity.markAsDeleted();
                    return repository.save(entity);
                })
                .then();
    }

    private SimulationAnswerResponse toResponse(SimulationAnswer entity) {
        return new SimulationAnswerResponse(
                entity.getId(),
                entity.getSimulationId(),
                entity.getQuestionId(),
                entity.getSelectedOptionId(),
                entity.getScoreObtained(),
                entity.getIsCorrect(),
                entity.getAnsweredAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt());
    }
}
