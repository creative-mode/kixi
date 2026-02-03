package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.Course;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CourseRepository extends ReactiveCrudRepository<Course, Long> {

    Mono<Course> findByIdAndDeletedAtIsNull(Long id);

    Flux<Course> findAllByDeletedAtIsNull();

    Flux<Course> findAllByDeletedAtIsNotNull();

    Mono<Course> findByIdAndDeletedAtIsNotNull(Long id);

    Mono<Course> findByCodeAndDeletedAtIsNull(String code);

    Mono<Course> findByCodeAndIdNotAndDeletedAtIsNull(String code, Long id);
}
