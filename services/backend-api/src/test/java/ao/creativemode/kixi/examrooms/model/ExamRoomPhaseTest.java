package ao.creativemode.kixi.examrooms.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ExamRoomPhaseTest {
    @Test
    void startIsInclusiveEndIsExclusiveAndManualCloseWins() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 15, 10, 0);
        LocalDateTime end = start.plusHours(1);
        assertThat(ExamRoomPhase.at(ExamRoomStatus.OPEN, start, end, start.minusNanos(1)))
                .isEqualTo(ExamRoomPhase.SCHEDULED);
        assertThat(ExamRoomPhase.at(ExamRoomStatus.OPEN, start, end, start))
                .isEqualTo(ExamRoomPhase.ONGOING);
        assertThat(ExamRoomPhase.at(ExamRoomStatus.RUNNING, start, end, end))
                .isEqualTo(ExamRoomPhase.ENDED);
        assertThat(ExamRoomPhase.at(ExamRoomStatus.CLOSED, start, end, start.minusHours(1)))
                .isEqualTo(ExamRoomPhase.ENDED);
    }
}
