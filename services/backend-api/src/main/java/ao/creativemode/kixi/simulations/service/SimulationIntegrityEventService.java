package ao.creativemode.kixi.simulations.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.dto.integrity.SimulationIntegrityEventRequest;
import ao.creativemode.kixi.simulations.dto.integrity.SimulationIntegrityEventResponse;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationIntegrityEvent;
import ao.creativemode.kixi.simulations.model.SimulationIntegrityEventKind;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationIntegrityEventRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import reactor.core.publisher.Mono;

@Service
public class SimulationIntegrityEventService {
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);
    private static final long MAX_EVENTS_PER_MINUTE = 120;

    private final SimulationRepository simulations;
    private final SimulationIntegrityEventRepository events;
    private final QuestionRepository questions;
    private final Clock clock;

    public SimulationIntegrityEventService(SimulationRepository simulations,
            SimulationIntegrityEventRepository events, QuestionRepository questions, Clock clock) {
        this.simulations = simulations;
        this.events = events;
        this.questions = questions;
        this.clock = clock;
    }

    @Transactional
    public Mono<SimulationIntegrityEventResponse> record(Long simulationId, Long accountId,
            SimulationIntegrityEventRequest request) {
        if (request.kind() == SimulationIntegrityEventKind.PASTE_BLOCKED && request.questionId() == null) {
            return Mono.error(ApiException.badRequest("questionId is required for PASTE_BLOCKED"));
        }
        if (request.kind() != SimulationIntegrityEventKind.PASTE_BLOCKED && request.questionId() != null) {
            return Mono.error(ApiException.badRequest("questionId is only accepted for PASTE_BLOCKED"));
        }

        return simulations.lockForIntegrityEventWrite(simulationId, accountId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Simulation not found")))
                .flatMap(simulation -> validateAttempt(simulation, request)
                        .then(Mono.defer(() -> enforceRateLimit(simulationId)))
                        .then(Mono.defer(() -> save(simulationId, request))));
    }

    private Mono<Void> validateAttempt(Simulation simulation, SimulationIntegrityEventRequest request) {
        if (simulation.getStatus() != SimulationStatus.IN_PROGRESS) {
            return Mono.error(ApiException.conflict("Integrity events are only accepted for an active simulation"));
        }
        if (request.questionId() == null) return Mono.empty();

        return questions.findByIdAndDeletedAtIsNull(request.questionId())
                .filter(question -> simulation.getStatementId().equals(question.getStatementId()))
                .switchIfEmpty(Mono.error(ApiException.badRequest("Question does not belong to this simulation")))
                .then();
    }

    private Mono<Void> enforceRateLimit(Long simulationId) {
        LocalDateTime since = LocalDateTime.now(clock).minus(RATE_WINDOW);
        return events.countBySimulationIdAndAtAfter(simulationId, since)
                .defaultIfEmpty(0L)
                .flatMap(count -> count >= MAX_EVENTS_PER_MINUTE
                        ? Mono.error(new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                                "Too many integrity events"))
                        : Mono.empty());
    }

    private Mono<SimulationIntegrityEventResponse> save(Long simulationId,
            SimulationIntegrityEventRequest request) {
        SimulationIntegrityEvent event = new SimulationIntegrityEvent();
        event.setSimulationId(simulationId);
        event.setKind(request.kind());
        event.setQuestionId(request.questionId());
        event.setAt(LocalDateTime.now(clock));
        return events.save(event).map(SimulationIntegrityEventService::response);
    }

    public static SimulationIntegrityEventResponse response(SimulationIntegrityEvent event) {
        return new SimulationIntegrityEventResponse(event.getId(), event.getSimulationId(), event.getKind(),
                event.getAt(), event.getQuestionId());
    }
}
