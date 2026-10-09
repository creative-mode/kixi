package ao.creativemode.kixi.chat.repository;

import ao.creativemode.kixi.chat.model.ChatSession;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface ChatSessionRepository extends ReactiveCrudRepository<ChatSession, Long> {

    /**
     * Scoped by owner on purpose: a session id that belongs to another account
     * reads as "not found" instead of leaking that it exists (404 over 403).
     */
    Mono<ChatSession> findByIdAndAccountId(Long id, Long accountId);
}
