package ao.creativemode.kixi.examrooms.dto;

import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import java.time.LocalDateTime;

/**
 * A student's invitation to an exam room, with what the student needs to decide
 * whether they can enter now: the room's state and window, and the server's own
 * clock. The countdown is computed against {@code serverTime}, never against the
 * device clock, which can be wrong on a phone.
 *
 * <p>The first four fields are the {@link ExamRoomParticipantResponse} this endpoint
 * used to return. {@code statementTitle} is the only thing taken from the statement,
 * and never the questions: those come from {@code /exam-rooms/{id}/student} once the
 * room is running.</p>
 */
public record ExamRoomInvitationResponse(
        Long roomId,
        Long accountId,
        Long simulationId,
        LocalDateTime joinedAt,
        Long statementId,
        String statementTitle,
        ExamRoomStatus status,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        Integer durationMinutes,
        String simulationStatus,
        LocalDateTime serverTime
) { }
