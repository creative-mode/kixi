package ao.creativemode.kixi.chat.repository;

import ao.creativemode.kixi.chat.model.ChatMessage;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface ChatMessageRepository extends ReactiveCrudRepository<ChatMessage, Long> {

    /**
     * Newest first, ties broken by id so messages written in the same
     * microsecond still keep insertion order when the caller reverses them.
     */
    Flux<ChatMessage> findBySessionIdOrderByCreatedAtDescIdDesc(Long sessionId);
}
