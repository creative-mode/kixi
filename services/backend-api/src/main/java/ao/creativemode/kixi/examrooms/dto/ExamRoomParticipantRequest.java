package ao.creativemode.kixi.examrooms.dto;

import jakarta.validation.constraints.AssertTrue;

public record ExamRoomParticipantRequest(Long accountId, Long classId) {
    @AssertTrue(message = "Account ID or class ID is required")
    public boolean hasTarget() { return accountId != null || classId != null; }
}
