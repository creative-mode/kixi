package ao.creativemode.kixi.exams.repository;


import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import ao.creativemode.kixi.exams.model.QuestionImage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface QuestionImageRepository extends ReactiveCrudRepository<QuestionImage, Long> {
    
    Flux<QuestionImage> findAllByDeletedAtIsNull();
    
    Flux<QuestionImage> findAllByDeletedAtIsNotNull();
    
    Mono<QuestionImage> findByIdAndDeletedAtIsNull(Long id);
    
    Mono<QuestionImage> findByIdAndDeletedAtIsNotNull(Long id);

    /**
     * Outras linhas que apontam para o mesmo ficheiro: as cópias de um treino por temas (kixi#729) partilham o binário
     * da imagem original, que por isso só se apaga do armazenamento quando já ninguém a usa.
     */
    Mono<Long> countByStorageKeyAndIdNot(String storageKey, Long id);

    /**
     * Busca todas as imagens associadas a uma questão específica que não foram deletadas.
     */
    Flux<QuestionImage> findByQuestionIdAndDeletedAtIsNullOrderByOrderIndexAsc(Long questionId);
}