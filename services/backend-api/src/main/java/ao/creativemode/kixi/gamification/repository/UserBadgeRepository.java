package ao.creativemode.kixi.gamification.repository;

import ao.creativemode.kixi.gamification.model.UserBadge;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface UserBadgeRepository extends ReactiveCrudRepository<UserBadge, Long> {
    Flux<UserBadge> findAllByAccountIdOrderByUnlockedAtDesc(Long accountId);
}
