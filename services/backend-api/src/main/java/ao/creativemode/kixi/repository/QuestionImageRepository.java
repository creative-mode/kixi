package ao.creativemode.kixi.repository;


import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import ao.creativemode.kixi.model.QuestionImage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface QuestionImageRepository extends ReactiveCrudRepository<QuestionImage, Long> {
    
    Flux<QuestionImage> findAllByDeletedAtIsNull();
    
    Flux<QuestionImage> findAllByDeletedAtIsNotNull();
    
    Mono<QuestionImage> findByIdAndDeletedAtIsNull(Long id);
    
    Mono<QuestionImage> findByIdAndDeletedAtIsNotNull(Long id);

    /**
     * Busca todas as imagens associadas a uma questão específica que não foram deletadas.
     */
    Flux<QuestionImage> findByQuestionIdAndDeletedAtIsNullOrderByOrderIndexAsc(Long questionId);
}