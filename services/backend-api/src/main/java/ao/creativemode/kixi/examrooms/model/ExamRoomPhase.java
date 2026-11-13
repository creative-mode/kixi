package ao.creativemode.kixi.examrooms.model;

import java.time.LocalDateTime;

public enum ExamRoomPhase {
    SCHEDULED, ONGOING, ENDED;

    public static ExamRoomPhase at(ExamRoomStatus status, LocalDateTime startsAt, LocalDateTime endsAt,
            LocalDateTime now) {
        if (status == ExamRoomStatus.CLOSED || !now.isBefore(endsAt)) return ENDED;
        return now.isBefore(startsAt) ? SCHEDULED : ONGOING;
    }
}
