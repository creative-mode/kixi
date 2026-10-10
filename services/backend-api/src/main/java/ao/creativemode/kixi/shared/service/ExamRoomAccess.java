package ao.creativemode.kixi.shared.service;

import java.time.LocalDateTime;
import reactor.core.publisher.Mono;

/** Boundary used by simulations without coupling the domain slices together. */
public interface ExamRoomAccess {
    Mono<Boolean> canAccessSimulation(Long roomId, Long simulationId, Long simulationAccountId,
            Long accountId, boolean admin, boolean teacher);
    Mono<LocalDateTime> effectiveRoomDeadline(Long roomId, LocalDateTime durationDeadline);
}
