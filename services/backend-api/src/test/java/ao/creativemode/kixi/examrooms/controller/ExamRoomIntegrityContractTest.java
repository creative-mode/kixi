package ao.creativemode.kixi.examrooms.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import ao.creativemode.kixi.examrooms.dto.ExamRoomIntegrityResponse;
import ao.creativemode.kixi.simulations.dto.integrity.SimulationIntegrityEventResponse;
import ao.creativemode.kixi.simulations.model.SimulationIntegrityEventKind;

class ExamRoomIntegrityContractTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesDisplayNameInParticipantIntegrity() throws Exception {
        ExamRoomIntegrityResponse response = new ExamRoomIntegrityResponse(7L,
                LocalDateTime.of(2026, 11, 14, 10, 0),
                List.of(new ExamRoomIntegrityResponse.ParticipantIntegrity(42L, "Ana Silva", 12L,
                        List.of(new SimulationIntegrityEventResponse(31L, 12L,
                                SimulationIntegrityEventKind.TAB_HIDDEN, LocalDateTime.of(2026, 11, 14, 9, 59), null)))));

        JsonNode body = json.readTree(json.writeValueAsString(response));
        JsonNode participant = body.get("participants").get(0);

        assertThat(participant.get("accountId").asLong()).isEqualTo(42L);
        assertThat(participant.get("displayName").asText()).isEqualTo("Ana Silva");
        assertThat(participant.get("simulationId").asLong()).isEqualTo(12L);
        assertThat(participant.get("events").get(0).get("kind").asText()).isEqualTo("TAB_HIDDEN");
    }
}
