package ao.creativemode.kixi.gamification.repository;

import ao.creativemode.kixi.gamification.model.Badge;
import ao.creativemode.kixi.gamification.model.UserBadge;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface BadgeRepository extends ReactiveCrudRepository<Badge, Long> {
    Mono<Badge> findByCode(String code);
    Flux<UserBadge> findAllByAccountIdOrderByUnlockedAtDesc(Long accountId);
}
