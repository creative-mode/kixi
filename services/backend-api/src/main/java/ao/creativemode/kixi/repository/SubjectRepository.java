package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.Subject;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SubjectRepository extends ReactiveCrudRepository<Subject, String> {

    Flux<Subject> findAllByDeletedAtIsNull();
    Flux<Subject> findAllByDeletedAtIsNotNull();
    Mono<Subject> findByCodeAndDeletedAtIsNull(String id);
    Mono<Subject> findByCodeAndDeletedAtIsNotNull(String id);
}
