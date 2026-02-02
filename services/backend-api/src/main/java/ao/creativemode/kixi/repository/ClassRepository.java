package ao.creativemode.kixi.repository;


import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import ao.creativemode.kixi.model.Class;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ClassRepository  extends ReactiveCrudRepository<Class,Long> {

    Flux<Class> findAllByDeletedAtIsNull();
    Flux<Class> findAllByDeletedAtIsNotNull();
    Mono<Class> findByIdAndDeletedAtIsNull(Long id);
    Mono<Class> findByIdAndDeletedAtIsNotNull(Long id);
}
