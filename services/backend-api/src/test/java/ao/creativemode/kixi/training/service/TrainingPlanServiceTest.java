package ao.creativemode.kixi.training.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.training.dto.MatrixResponse;
import ao.creativemode.kixi.training.service.TrainingPoolReader.PoolRow;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** O plano de treino (kixi#729): acesso, o que se pergunta ao conjunto de questões e como se resume. */
class TrainingPlanServiceTest {

    private static final long STUDENT = 12L;
    private static final long ROOM = 40L;
    private static final long ROOM_STATEMENT = 500L;

    private final TrainingRoomReader rooms = mock(TrainingRoomReader.class);
    private final MatrixReader matrices = mock(MatrixReader.class);
    private final TrainingPoolReader pool = mock(TrainingPoolReader.class);
    private final TrainingPlanService service = new TrainingPlanService(rooms, matrices, pool);

    private static TrainingRoomReader.RoomInfo room(boolean participant, Long subject) {
        return new TrainingRoomReader.RoomInfo(ROOM, 7L, ROOM_STATEMENT, subject, "Matemática", "P1 2026",
                LocalDateTime.of(2026, 12, 1, 8, 0), 90, participant);
    }

    private static MatrixResponse matrix() {
        return new MatrixResponse(ROOM, "Matriz", 20.0, List.of(
                new MatrixResponse.Item(1L, 5L, "Funções", 3, 8.0, "KNOWLEDGE", List.of()),
                new MatrixResponse.Item(2L, 6L, "Geometria", 2, 12.0, null, List.of())));
    }

    @Test
    void summarisesAvailabilitySourcesAndTheStudentsHistory() {
        when(rooms.find(ROOM, STUDENT)).thenReturn(Mono.just(room(true, 3L)));
        when(matrices.read(ROOM)).thenReturn(Mono.just(matrix()));
        when(pool.read(STUDENT, 3L, ROOM_STATEMENT, List.of(5L, 6L))).thenReturn(Mono.just(List.of(
                new PoolRow(5, 101, 77, "P1 2023", 2023, true, true),
                new PoolRow(5, 102, 77, "P1 2023", 2023, true, false),
                new PoolRow(5, 103, 78, "P2 2024", 2024, false, false),
                new PoolRow(5, 104, 78, "P2 2024", 2024, false, false),
                new PoolRow(5, 105, 78, "P2 2024", 2024, false, false))));

        StepVerifier.create(service.plan(ROOM, STUDENT)).assertNext(plan -> {
            assertThat(plan.roomId()).isEqualTo(ROOM);
            assertThat(plan.subjectId()).isEqualTo(3L);
            assertThat(plan.subjectName()).isEqualTo("Matemática");
            assertThat(plan.statementTitle()).isEqualTo("P1 2026");
            assertThat(plan.durationMinutes()).isEqualTo(90);
            assertThat(plan.totalPoints()).isEqualTo(20.0);
            assertThat(plan.items()).hasSize(2);
            var functions = plan.items().get(0);
            assertThat(functions.topicId()).isEqualTo(5L);
            assertThat(functions.name()).isEqualTo("Funções");
            assertThat(functions.questionCount()).isEqualTo(3);
            assertThat(functions.points()).isEqualTo(8.0);
            assertThat(functions.cognitiveLevel()).isEqualTo("KNOWLEDGE");
            assertThat(functions.available()).isEqualTo(5);
            assertThat(functions.attempted()).isEqualTo(2);
            assertThat(functions.correct()).isEqualTo(1);
            // os enunciados mais recentes primeiro, com o n.º de questões de cada
            assertThat(functions.sources()).extracting(s -> s.statementId(), s -> s.year(), s -> s.count())
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(78L, 2024, 3), org.assertj.core.groups.Tuple.tuple(77L, 2023, 2));
            var geometry = plan.items().get(1);
            assertThat(geometry.available()).isZero();
            assertThat(geometry.sources()).isEmpty();
            assertThat(geometry.attempted()).isZero();
        }).verifyComplete();
    }

    @Test
    void thePoolIsAskedToLeaveOutTheRoomsOwnStatement() {
        when(rooms.find(ROOM, STUDENT)).thenReturn(Mono.just(room(true, 3L)));
        when(matrices.read(ROOM)).thenReturn(Mono.just(matrix()));
        when(pool.read(STUDENT, 3L, ROOM_STATEMENT, List.of(5L))).thenReturn(Mono.just(List.of()));

        StepVerifier.create(service.context(ROOM, STUDENT, Set.of(5L))).expectNextCount(1).verifyComplete();

        verify(pool).read(STUDENT, 3L, ROOM_STATEMENT, List.of(5L));
    }

    @Test
    void aStudentWhoWasNotInvitedGetsNotFound() {
        when(rooms.find(ROOM, STUDENT)).thenReturn(Mono.just(room(false, 3L)));

        StepVerifier.create(service.plan(ROOM, STUDENT)).expectErrorSatisfies(error ->
                assertThat(((ApiException) error).getStatusCode()).isEqualTo(404)).verify();
        verify(pool, never()).read(any(), any(), any(), any());
    }

    @Test
    void noRoomAndNoMatrixAreNotFound() {
        when(rooms.find(ROOM, STUDENT)).thenReturn(Mono.empty());
        StepVerifier.create(service.plan(ROOM, STUDENT)).expectErrorSatisfies(error ->
                assertThat(((ApiException) error).getStatusCode()).isEqualTo(404)).verify();

        when(rooms.find(ROOM, STUDENT)).thenReturn(Mono.just(room(true, 3L)));
        when(matrices.read(ROOM)).thenReturn(Mono.empty());
        StepVerifier.create(service.plan(ROOM, STUDENT)).expectErrorSatisfies(error -> {
            assertThat(((ApiException) error).getStatusCode()).isEqualTo(404);
            assertThat(error.getMessage()).contains("ainda não tem matriz");
        }).verify();
    }

    @Test
    void aPaperWithoutASubjectHasNoPool() {
        when(rooms.find(ROOM, STUDENT)).thenReturn(Mono.just(room(true, null)));
        when(matrices.read(ROOM)).thenReturn(Mono.just(matrix()));

        StepVerifier.create(service.plan(ROOM, STUDENT)).expectErrorSatisfies(error ->
                assertThat(((ApiException) error).getStatusCode()).isEqualTo(409)).verify();
    }
}
