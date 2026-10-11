package ao.creativemode.kixi.examrooms.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

import ao.creativemode.kixi.examrooms.dto.ExamRoomGradeResponse;
import ao.creativemode.kixi.examrooms.dto.ExamRoomLiveResponse;
import ao.creativemode.kixi.examrooms.service.ExamRoomMonitoringService;
import ao.creativemode.kixi.examrooms.service.ExamRoomService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;

class ExamRoomGradeContractTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesDisplayNameInLiveProgressAndGrades() throws Exception {
        ExamRoomLiveResponse.ParticipantProgress progress =
                new ExamRoomLiveResponse.ParticipantProgress(42L, "Ana Silva", null, null, "NOT_STARTED", 0, 2);
        ExamRoomGradeResponse grade = new ExamRoomGradeResponse(42L, "Ana Silva", null, "NOT_STARTED",
                null, 0, 0, 0, null);

        JsonNode live = json.readTree(json.writeValueAsString(progress));
        JsonNode grades = json.readTree(json.writeValueAsString(grade));
        assertThat(live.get("displayName").asText()).isEqualTo("Ana Silva");
        assertThat(grades.get("displayName").asText()).isEqualTo("Ana Silva");
    }

    @Test
    void exportsNameColumnAndFallbackValueToCsv() throws Exception {
        ExamRoomController controller = new ExamRoomController(mock(ExamRoomService.class),
                mock(CurrentAccountService.class), mock(ExamRoomMonitoringService.class));
        Method toCsv = ExamRoomController.class.getDeclaredMethod("toCsv", List.class);
        toCsv.setAccessible(true);
        String csv = (String) toCsv.invoke(controller, List.of(
                new ExamRoomGradeResponse(42L, "fallback-user", null, "NOT_STARTED", null, 0, 0, 0, null)));

        assertThat(csv).startsWith("accountId,name,simulationId,");
        assertThat(csv).contains("42,fallback-user,,NOT_STARTED,");
    }

    @Test
    void escapesDisplayNameAndNeutralizesFormulaPrefixInCsv() throws Exception {
        ExamRoomController controller = new ExamRoomController(mock(ExamRoomService.class),
                mock(CurrentAccountService.class), mock(ExamRoomMonitoringService.class));
        Method toCsv = ExamRoomController.class.getDeclaredMethod("toCsv", List.class);
        toCsv.setAccessible(true);

        String csv = (String) toCsv.invoke(controller, List.of(
                new ExamRoomGradeResponse(1L, "Doe, Jane", null, "NOT_STARTED", null, 0, 0, 0, null),
                new ExamRoomGradeResponse(2L, "Jane \"JJ\" Doe", null, "NOT_STARTED", null, 0, 0, 0, null),
                new ExamRoomGradeResponse(3L, "Jane\nDoe", null, "NOT_STARTED", null, 0, 0, 0, null),
                new ExamRoomGradeResponse(4L, "=1+1", null, "NOT_STARTED", null, 0, 0, 0, null),
                new ExamRoomGradeResponse(5L, "+Jane", null, "NOT_STARTED", null, 0, 0, 0, null),
                new ExamRoomGradeResponse(6L, "-Jane", null, "NOT_STARTED", null, 0, 0, 0, null),
                new ExamRoomGradeResponse(7L, "@Jane", null, "NOT_STARTED", null, 0, 0, 0, null)));

        assertThat(csv).contains("1,\"Doe, Jane\",,NOT_STARTED,");
        assertThat(csv).contains("2,\"Jane \"\"JJ\"\" Doe\",,NOT_STARTED,");
        assertThat(csv).contains("3,\"Jane\nDoe\",,NOT_STARTED,");
        assertThat(csv).contains("4,'=1+1,,NOT_STARTED,");
        assertThat(csv).contains("5,'+Jane,,NOT_STARTED,");
        assertThat(csv).contains("6,'-Jane,,NOT_STARTED,");
        assertThat(csv).contains("7,'@Jane,,NOT_STARTED,");
    }
}
