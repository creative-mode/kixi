package ao.creativemode.kixi.ocr.service;

import ao.creativemode.kixi.ocr.service.OcrJobService.OcrJob;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;

/** One leased OCR consumer across backend replicas; abandoned leases are recoverable. */
@Component
public class OcrJobWorker {

    private static final Logger log = LoggerFactory.getLogger(OcrJobWorker.class);

    private final OcrJobService jobs;
    private final UUID workerId = UUID.randomUUID();
    private final AtomicBoolean polling = new AtomicBoolean();
    private final ConcurrentHashMap<UUID, Disposable> active = new ConcurrentHashMap<>();
    private final AtomicLong lastRenewAt = new AtomicLong();

    public OcrJobWorker(OcrJobService jobs) {
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${app.ocr.jobs.poll-interval-ms:1000}")
    public void poll() {
        if (!polling.compareAndSet(false, true)) return;
        UUID activeJob = active.keySet().stream().findFirst().orElse(null);
        if (activeJob != null) {
            long now = System.nanoTime();
            long previous = lastRenewAt.get();
            if (now - previous < 10_000_000_000L
                    || !lastRenewAt.compareAndSet(previous, now)) {
                polling.set(false);
                return;
            }
            jobs.renewLease(workerId, activeJob)
                .doFinally(signal -> polling.set(false))
                .subscribe(renewed -> {
                    if (!renewed) cancel(activeJob);
                }, error -> {
                    log.error("Could not renew OCR worker lease: type={}", error.getClass().getSimpleName());
                    cancel(activeJob);
                });
            return;
        }

        jobs.claimNext(workerId)
            .doFinally(signal -> polling.set(false))
            .subscribe(this::start, error ->
                log.error("Could not claim OCR job: type={}", error.getClass().getSimpleName()));
    }

    @Scheduled(fixedDelayString = "${app.ocr.jobs.cleanup-interval-ms:3600000}")
    public void cleanupExpired() {
        jobs.cleanupExpired().subscribe(null, error ->
            log.error("Could not clean expired OCR jobs: type={}", error.getClass().getSimpleName()));
    }

    public void cancel(UUID jobId) {
        Disposable processing = active.get(jobId);
        if (processing != null) processing.dispose();
    }

    private void start(OcrJob job) {
        Disposable.Swap slot = Disposables.swap();
        active.put(job.id(), slot);
        Disposable processing = jobs.process(job, workerId)
            .doFinally(signal -> {
                active.remove(job.id());
                jobs.release(workerId, job.id()).subscribe(
                    null,
                    error -> log.error("Could not release OCR job lease: type={}", error.getClass().getSimpleName()));
            })
            .subscribe(null, error -> log.error("OCR worker stopped: requestId={}, type={}",
                job.requestId(), error.getClass().getSimpleName()));
        slot.update(processing);
    }
}
