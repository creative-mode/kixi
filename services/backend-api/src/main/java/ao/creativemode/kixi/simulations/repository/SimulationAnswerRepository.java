package ao.creativemode.kixi.simulations.repository;

import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import java.util.Collection;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SimulationAnswerRepository
    extends ReactiveCrudRepository<SimulationAnswer, Long>
{
    Flux<SimulationAnswer> findAllByDeletedAtIsNull();
    Flux<SimulationAnswer> findAllBySimulationIdInAndDeletedAtIsNull(Collection<Long> simulationIds);
    Flux<SimulationAnswer> findAllByDeletedAtIsNotNull();
    Mono<SimulationAnswer> findByIdAndDeletedAtIsNull(Long id);
    Mono<SimulationAnswer> findByIdAndDeletedAtIsNotNull(Long id);
}
