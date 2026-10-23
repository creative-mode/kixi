package ao.creativemode.kixi.examrooms.dto;

import java.time.LocalDateTime;
import java.util.List;

import ao.creativemode.kixi.simulations.dto.integrity.SimulationIntegrityEventResponse;

public record ExamRoomIntegrityResponse(
        Long roomId,
        LocalDateTime serverTime,
        List<ParticipantIntegrity> participants) {

    public record ParticipantIntegrity(
            Long accountId,
            Long simulationId,
            List<SimulationIntegrityEventResponse> events) {
    }
}
