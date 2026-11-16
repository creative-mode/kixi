package ao.creativemode.kixi.chat.service;

import ao.creativemode.kixi.shared.exception.ApiException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * The "Simular" brake for the tutor outside one paper (issue #610): while the
 * student has a live simulation of a subject, the tutor of that subject stays
 * closed. Studying another subject, or coming back after submitting, stays
 * open — the rule is the discipline of the paper that is running, not the
 * tutor as a whole.
 *
 * <p>The tutor cannot lean on {@code simulations} (the architecture keeps
 * {@code chat} on {@code shared} and {@code exams}), so the check is one SQL
 * read plus the same deadline arithmetic the simulation service uses: the
 * room's end when there is a room, the room snapshot or the paper's duration,
 * the accessibility extra time and the configured tolerance. That keeps an
 * expired attempt from holding the tutor hostage while the sweep catches up.</p>
 */
@Service
public class TutorExamGuard {

    private static final long SECONDS_PER_MINUTE = 60;
    private static final long EXTRA_TIME_SECONDS_PER_MINUTE = 75;

    private static final String LIVE_IN_SUBJECT = """
            SELECT s.started_at,
                   s.exam_room_duration_minutes,
                   st.duration_minutes AS statement_duration_minutes,
                   a.accessibility_extra_time,
                   r.status AS room_status,
                   r.ends_at AS room_ends_at
              FROM simulations s
              JOIN statements st ON st.id = s.statement_id
              LEFT JOIN accounts a ON a.id = s.account_id
              LEFT JOIN exam_rooms r ON r.id = s.exam_room_id
             WHERE s.account_id = :account
               AND s.status = 'IN_PROGRESS'
               AND s.deleted_at IS NULL
               AND st.subject_id = :subject
               -- A practice copy (kixi#729) is training, not a paper in progress: it must not close the subject tutor.
               AND st.practice_owner_account_id IS NULL
            """;

    private final DatabaseClient db;
    private final Clock clock;
    private final int toleranceSeconds;

    public TutorExamGuard(DatabaseClient db, Clock clock,
            @Value("${app.simulations.tolerance-seconds:10}") int toleranceSeconds) {
        this.db = db;
        this.clock = clock;
        this.toleranceSeconds = toleranceSeconds;
    }

    /** Fails with 409 when an attempt of this subject is still accepting answers. */
    public Mono<Void> requireNoLiveExamInSubject(Long accountId, Long subjectId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return db.sql(LIVE_IN_SUBJECT)
                .bind("account", accountId)
                .bind("subject", subjectId)
                .map((row, meta) -> live(row.get("started_at", OffsetDateTime.class),
                        row.get("exam_room_duration_minutes", Integer.class),
                        row.get("statement_duration_minutes", Integer.class),
                        Boolean.TRUE.equals(row.get("accessibility_extra_time", Boolean.class)),
                        row.get("room_status", String.class),
                        row.get("room_ends_at", OffsetDateTime.class),
                        toleranceSeconds, now))
                .all()
                .filter(Boolean.TRUE::equals)
                .next()
                .flatMap(live -> Mono.error(new ApiException(HttpStatus.CONFLICT, "Conflict",
                        "O tutor desta disciplina está fechado enquanto a prova decorre. "
                                + "Entrega a prova para o reabrir.",
                        "CHAT_TUTOR_BLOCKED")))
                .then();
    }

    /**
     * The same decision {@code SimulationDeadlineService} makes, without the
     * simulation module: a closed room ends the attempt; otherwise the deadline
     * is the duration (the room's snapshot when there is one, the paper's
     * duration otherwise) plus extra time and tolerance, capped by the room's
     * end. A simulation without a start or a duration has no deadline.
     */
    static boolean live(OffsetDateTime startedAt, Integer roomDuration, Integer statementDuration,
            boolean extraTime, String roomStatus, OffsetDateTime roomEndsAt, int toleranceSeconds,
            OffsetDateTime now) {
        if ("CLOSED".equals(roomStatus)) {
            return false;
        }
        Integer minutes = roomDuration != null ? roomDuration : statementDuration;
        if (startedAt == null || minutes == null) {
            return true;
        }
        long secondsPerMinute = extraTime ? EXTRA_TIME_SECONDS_PER_MINUTE : SECONDS_PER_MINUTE;
        OffsetDateTime deadline = startedAt.plus(Duration.ofSeconds(
                minutes * secondsPerMinute + toleranceSeconds));
        if (roomEndsAt != null && roomEndsAt.isBefore(deadline)) {
            deadline = roomEndsAt;
        }
        return now.isBefore(deadline);
    }
}
