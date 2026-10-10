package ao.creativemode.kixi.shared.service;

import java.time.LocalDateTime;
import reactor.core.publisher.Mono;

/** Boundary used by simulations without coupling the domain slices together. */
public interface ExamRoomAccess {
    Mono<Boolean> canAccessSimulation(Long roomId, Long simulationId, Long simulationAccountId,
            Long accountId, boolean admin, boolean teacher);
    Mono<LocalDateTime> effectiveRoomDeadline(Long roomId, LocalDateTime durationDeadline);
    Mono<Void> lockRoomForSimulation(Long roomId);
    Mono<Boolean> acceptsSimulationAnswers(Long roomId, LocalDateTime now);
    default Mono<Boolean> answerKeyVisible(Long roomId) {
        return Mono.just(true);
    }

    default Mono<Boolean> hasOpenOrRunningRoom(Long statementId) {
        return Mono.just(false);
    }

    default Mono<Void> lockStatementForSimulation(Long statementId) {
        return Mono.empty();
    }
}
