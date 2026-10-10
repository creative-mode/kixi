package ao.creativemode.kixi.examrooms.dto;

import java.time.LocalDateTime;

public record ExamRoomParticipantResponse(
        Long roomId, Long accountId, Long simulationId, LocalDateTime joinedAt
) {}
