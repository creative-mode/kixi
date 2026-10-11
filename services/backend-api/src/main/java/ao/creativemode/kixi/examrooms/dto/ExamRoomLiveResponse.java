package ao.creativemode.kixi.examrooms.dto;

import java.time.LocalDateTime;
import java.util.List;

import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;

public record ExamRoomLiveResponse(
        Long roomId,
        ExamRoomStatus status,
        LocalDateTime serverTime,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        long remainingSeconds,
        int totalParticipants,
        int joinedParticipants,
        int submittedParticipants,
        double progressPercentage,
        List<ParticipantProgress> participants
) {
    public record ParticipantProgress(
            Long accountId,
            String displayName,
            Long simulationId,
            LocalDateTime joinedAt,
            String status,
            int answeredQuestions,
            int totalQuestions
    ) { }
}
