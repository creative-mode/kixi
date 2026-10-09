package ao.creativemode.kixi.feed.repository;

import ao.creativemode.kixi.feed.model.PostReport;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface PostReportRepository extends ReactiveCrudRepository<PostReport, Long> {

    Mono<Boolean> existsByPostIdAndReporterAccountId(Long postId, Long reporterAccountId);

    Mono<Long> countByPostId(Long postId);
}