package ao.creativemode.kixi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

/**
 * The "Simular" brake of issue #610, without a database: the decision must be
 * the same one {@code SimulationDeadlineService} makes, or an expired attempt
 * would hold the tutor hostage until the sweep catches up.
 */
class TutorExamGuardTest {

    private static final OffsetDateTime START = OffsetDateTime.parse("2026-11-15T10:00:00Z");
    private static final int TOLERANCE = 10;

    @Test
    void aClosedRoomNeverBlocks() {
        assertThat(TutorExamGuard.live(START, 60, 60, false, "CLOSED", null, TOLERANCE, START))
                .isFalse();
    }

    @Test
    void anAttemptInsideTheDeadlineIsLive() {
        assertThat(TutorExamGuard.live(START, 60, 60, false, "RUNNING", null, TOLERANCE,
                START.plusMinutes(59))).isTrue();
        // The tolerance is the last sliver of the window, exactly like answers.
        assertThat(TutorExamGuard.live(START, 60, 60, false, "RUNNING", null, TOLERANCE,
                START.plusMinutes(60).plusSeconds(9))).isTrue();
        assertThat(TutorExamGuard.live(START, 60, 60, false, "RUNNING", null, TOLERANCE,
                START.plusMinutes(60).plusSeconds(10))).isFalse();
    }

    @Test
    void theRoomSnapshotWinsOverTheStatementDuration() {
        // A 30-minute room of a 60-minute paper ends at 30 minutes plus tolerance.
        assertThat(TutorExamGuard.live(START, 30, 60, false, "RUNNING", null, TOLERANCE,
                START.plusMinutes(30).plusSeconds(TOLERANCE))).isFalse();
        assertThat(TutorExamGuard.live(START, 30, 60, false, "RUNNING", null, TOLERANCE,
                START.plusMinutes(29))).isTrue();
    }

    @Test
    void accessibilityExtraTimeIsTwentyFivePercentMore() {
        // 60 minutes at 75 seconds per minute = 75 minutes.
        assertThat(TutorExamGuard.live(START, null, 60, true, "RUNNING", null, TOLERANCE,
                START.plusMinutes(74))).isTrue();
        assertThat(TutorExamGuard.live(START, null, 60, true, "RUNNING", null, TOLERANCE,
                START.plusMinutes(75).plusSeconds(TOLERANCE))).isFalse();
    }

    @Test
    void theRoomEndCapsTheDeadline() {
        OffsetDateTime roomEnds = START.plusMinutes(10);
        assertThat(TutorExamGuard.live(START, 60, 60, false, "OPEN", roomEnds, TOLERANCE,
                START.plusMinutes(9))).isTrue();
        assertThat(TutorExamGuard.live(START, 60, 60, false, "OPEN", roomEnds, TOLERANCE,
                START.plusMinutes(10))).isFalse();
    }

    @Test
    void anAttemptWithoutStartOrDurationHasNoDeadline() {
        assertThat(TutorExamGuard.live(null, 60, 60, false, "RUNNING", null, TOLERANCE, START))
                .isTrue();
        assertThat(TutorExamGuard.live(START, null, null, false, "RUNNING", null, TOLERANCE,
                START.plusYears(1))).isTrue();
    }
}
