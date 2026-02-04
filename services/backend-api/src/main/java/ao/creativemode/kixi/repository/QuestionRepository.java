package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.Question;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface QuestionRepository extends ReactiveCrudRepository<Question, Long> {

    Flux<Question> findAllByDeletedAtIsNull();

    Flux<Question> findAllByDeletedAtIsNotNull();

    Mono<Question> findByIdAndDeletedAtIsNull(Long id);

    Mono<Question> findByIdAndDeletedAtIsNotNull(Long id);
}