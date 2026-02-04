package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.Statement;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface StatementRepository extends ReactiveCrudRepository<Statement, Long> {

    Flux<Statement> findByDeletedAtIsNull();
    Flux<Statement> findByDeletedAtIsNotNull();
    Mono<Statement> findByIdAndDeletedAtIsNull(Long id);
    Mono<Boolean> existsByTitleAndDeletedAtIsNull(String title);
    Flux<Statement> findBySchoolYearIdAndDeletedAtIsNull(Long schoolYearId);
    Flux<Statement> findBySubjectIdAndDeletedAtIsNull(Long subjectId);
    Flux<Statement> findByClassIdAndDeletedAtIsNull(Long classId);

    @Query("SELECT COUNT(*) FROM statement WHERE delete_at IS NULL")
    Mono<Long> countActive();
}
