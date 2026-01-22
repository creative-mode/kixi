package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.SchoolYear;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SchoolYearRepository extends ReactiveCrudRepository<SchoolYear, Long> {

    Flux<SchoolYear> findAllByDeletedAtIsNull();

    Flux<SchoolYear> findAllByDeletedAtIsNotNull();

    Mono<SchoolYear> findByIdAndDeletedAtIsNull(Long id);

    Mono<SchoolYear> findByIdAndDeletedAtIsNotNull(Long id);
}