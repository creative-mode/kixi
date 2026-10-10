package ao.creativemode.kixi.simulations.repository;

import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SimulationAnswerRepository
    extends ReactiveCrudRepository<SimulationAnswer, Long>
{
    Flux<SimulationAnswer> findAllByDeletedAtIsNull();
    Flux<SimulationAnswer> findAllBySimulationIdInAndDeletedAtIsNull(Collection<Long> simulationIds);
    Flux<SimulationAnswer> findAllBySimulationIdInAndDeletedAtIsNotNull(Collection<Long> simulationIds);
    Flux<SimulationAnswer> findAllByDeletedAtIsNotNull();
    Mono<SimulationAnswer> findByIdAndDeletedAtIsNull(Long id);
    Mono<SimulationAnswer> findByIdAndDeletedAtIsNotNull(Long id);

    @Query("""
        INSERT INTO simulation_answers
            (simulation_id, question_id, selected_option_id, answer_text, answered_at,
             created_at, updated_at)
        SELECT :simulationId, :questionId, :selectedOptionId, :answerText, :answeredAt,
               CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        FROM simulations
        WHERE id = :simulationId AND status = 'IN_PROGRESS' AND deleted_at IS NULL
          AND EXISTS (SELECT 1 FROM questions q
                      WHERE q.id = :questionId AND q.statement_id = simulations.statement_id
                        AND q.deleted_at IS NULL)
          AND (:selectedOptionId IS NULL OR EXISTS (
              SELECT 1 FROM question_options o
              WHERE o.id = :selectedOptionId AND o.question_id = :questionId
                AND o.deleted_at IS NULL))
        RETURNING *
        """)
    Mono<SimulationAnswer> insertIfInProgress(
            @Param("simulationId") Long simulationId,
            @Param("questionId") Long questionId,
            @Param("selectedOptionId") Long selectedOptionId,
            @Param("answerText") String answerText,
            @Param("answeredAt") java.time.LocalDateTime answeredAt);

    @Query("""
        UPDATE simulation_answers AS answer
        SET simulation_id = :simulationId,
            question_id = :questionId,
            selected_option_id = :selectedOptionId,
            answer_text = :answerText,
            answered_at = :answeredAt,
            updated_at = CURRENT_TIMESTAMP
        WHERE answer.id = :id
          AND answer.simulation_id = :oldSimulationId
          AND answer.deleted_at IS NULL
          AND EXISTS (
              SELECT 1 FROM simulations
              WHERE id = :oldSimulationId AND status = 'IN_PROGRESS' AND deleted_at IS NULL
          )
           AND EXISTS (
               SELECT 1 FROM simulations
               WHERE id = :simulationId AND status = 'IN_PROGRESS' AND deleted_at IS NULL
           )
           AND EXISTS (SELECT 1 FROM questions q
                       JOIN simulations target ON target.id = :simulationId
                       WHERE q.id = :questionId AND q.statement_id = target.statement_id
                         AND q.deleted_at IS NULL)
           AND (:selectedOptionId IS NULL OR EXISTS (
               SELECT 1 FROM question_options o
               WHERE o.id = :selectedOptionId AND o.question_id = :questionId
                 AND o.deleted_at IS NULL))
        RETURNING answer.*
        """)
    Mono<SimulationAnswer> updateIfInProgress(
            @Param("id") Long id,
            @Param("oldSimulationId") Long oldSimulationId,
            @Param("simulationId") Long simulationId,
            @Param("questionId") Long questionId,
            @Param("selectedOptionId") Long selectedOptionId,
            @Param("answerText") String answerText,
            @Param("answeredAt") java.time.LocalDateTime answeredAt);
}
