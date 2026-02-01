package ao.creativemode.kixi.repository;


import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import ao.creativemode.kixi.model.Class;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ClassRepository  extends ReactiveCrudRepository<Class,String> {

    Flux<Class> findAllByDeletedAtIsNull();
    Flux<Class> findAllByDeletedAtIsNotNull();
    Mono<Class> findByCodeAndDeletedAtIsNull(String code);
    Mono<Class> findByCodeAndDeletedAtIsNotNull(String code);
}
