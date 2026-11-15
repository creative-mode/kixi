package ao.creativemode.kixi.examrooms.service;

import ao.creativemode.kixi.examrooms.repository.ExamRoomRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import ao.creativemode.kixi.shared.event.ExamRoomClosed;
import reactor.core.publisher.Mono;

@Component
public class ExamRoomExpirationJob {
    private static final Logger log = LoggerFactory.getLogger(ExamRoomExpirationJob.class);
    private static final Duration PAGE_TIMEOUT = Duration.ofMinutes(5);
    private final ExamRoomRepository rooms;
    private final ExamRoomService service;
    private final Clock clock;
    private final AtomicLong cursor = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean();
    @Autowired(required = false)
    private ApplicationEventPublisher events;

    public ExamRoomExpirationJob(ExamRoomRepository rooms, ExamRoomService service, Clock clock) {
        this.rooms = rooms;
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.exam-rooms.expiry-poll-ms:60000}",
            initialDelayString = "${app.exam-rooms.expiry-poll-ms:60000}")
    public void closeExpiredRooms() {
        if (!running.compareAndSet(false, true)) return;
        sweep()
                .doFinally(signal -> running.set(false))
                .subscribe(null, error -> log.error("Room expiration sweep failed: type={}",
                        error.getClass().getSimpleName()));
    }

    public Mono<Void> sweep() {
        return Mono.defer(() -> {
            LocalDateTime now = LocalDateTime.now(clock);
            return closePage(now, now.atZone(clock.getZone()).toOffsetDateTime(), cursor.get());
        });
    }

    private Mono<Void> closePage(LocalDateTime now, OffsetDateTime instant, long afterId) {
        return rooms.findExpiredIds(instant, afterId).collectList().timeout(PAGE_TIMEOUT).flatMap(ids -> {
            if (ids.isEmpty()) {
                cursor.set(0);
                return Mono.empty();
            }
            return reactor.core.publisher.Flux.fromIterable(ids)
                    .concatMap(id -> service.closeIfExpired(id, now)
                            .doOnNext(room -> {
                                if (room.transitioned() && events != null) events.publishEvent(new ExamRoomClosed(id));
                            })
                            .onErrorResume(error -> {
                                log.warn("Could not close expired room {}: type={}", id,
                                        error.getClass().getSimpleName());
                                return Mono.empty();
                            }).then().doOnSuccess(ignored -> cursor.set(id)))
                    .then(Mono.defer(() -> closePage(now, instant, ids.get(ids.size() - 1))));
        });
    }
}
