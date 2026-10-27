package ao.creativemode.kixi.moderation.repository;

import ao.creativemode.kixi.moderation.model.ModerationReport;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface ModerationReportRepository extends ReactiveCrudRepository<ModerationReport, Long> {}
