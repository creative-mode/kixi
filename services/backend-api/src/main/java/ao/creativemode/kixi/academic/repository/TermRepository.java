package ao.creativemode.kixi.academic.repository;

import ao.creativemode.kixi.academic.model.Term;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TermRepository extends ReactiveCrudRepository<Term, Long> {
    Flux<Term> findAllByDeletedAtIsNull();
    Flux<Term> findAllByDeletedAtIsNotNull();
    Mono<Term> findByIdAndDeletedAtIsNull(Long id);
    Mono<Term> findByIdAndDeletedAtIsNotNull(Long id);
}