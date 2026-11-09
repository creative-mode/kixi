package ao.creativemode.kixi.training.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.training.dto.MatrixItemRequest;
import ao.creativemode.kixi.training.dto.MatrixRequest;
import ao.creativemode.kixi.training.dto.MatrixResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Quem escreve e quem lê a matriz (kixi#729); as regras de conteúdo estão em {@code MatrixValidatorTest}. */
class MatrixServiceAccessTest {

    private static final long OWNER = 7L;
    private static final long OTHER_TEACHER = 8L;
    private static final long STUDENT = 12L;
    private static final long ROOM = 40L;

    private final DatabaseClient db = mock(DatabaseClient.class);
    private final TrainingRoomReader rooms = mock(TrainingRoomReader.class);
    private final MatrixReader reader = mock(MatrixReader.class);
    private final MatrixService service = new MatrixService(db, rooms, reader, mock(TopicService.class));

    private static TrainingRoomReader.RoomInfo room(boolean participant) {
        return new TrainingRoomReader.RoomInfo(ROOM, OWNER, 500L, 3L, "Matemática", "P1",
                LocalDateTime.of(2026, 12, 1, 8, 0), 90, participant);
    }

    private static MatrixResponse matrix() {
        return new MatrixResponse(ROOM, null, 20.0, List.of());
    }

    private static MatrixRequest validRequest() {
        return new MatrixRequest(null, null, List.of(new MatrixItemRequest(5L, null, 2, null, null, null)));
    }

    private static void assertStatus(Throwable error, int status) {
        assertThat(error).isInstanceOf(ApiException.class);
        assertThat(((ApiException) error).getStatusCode()).isEqualTo(status);
    }

    @Test
    void aTeacherWhoDoesNotOwnTheRoomCannotWriteOrDelete() {
        when(rooms.find(ROOM, OTHER_TEACHER)).thenReturn(Mono.just(room(false)));

        StepVerifier.create(service.put(ROOM, validRequest(), OTHER_TEACHER, false))
                .expectErrorSatisfies(error -> assertStatus(error, 403)).verify();
        StepVerifier.create(service.delete(ROOM, OTHER_TEACHER, false))
                .expectErrorSatisfies(error -> assertStatus(error, 403)).verify();
        StepVerifier.create(service.importCsv(ROOM, "Funções;3", null, null, OTHER_TEACHER, false))
                .expectErrorSatisfies(error -> assertStatus(error, 403)).verify();
        verifyNoInteractions(db);
    }

    @Test
    void anUnknownRoomIs404() {
        when(rooms.find(ROOM, OWNER)).thenReturn(Mono.empty());
        StepVerifier.create(service.put(ROOM, validRequest(), OWNER, false))
                .expectErrorSatisfies(error -> assertStatus(error, 404)).verify();
    }

    @Test
    void aBadMatrixIsRefusedBeforeAnyLookup() {
        StepVerifier.create(service.put(ROOM, new MatrixRequest(null, null, List.of()), OWNER, false))
                .expectErrorSatisfies(error -> assertStatus(error, 400)).verify();
        StepVerifier.create(service.importCsv(ROOM, "Funções;abc", null, null, OWNER, false))
                .expectErrorSatisfies(error -> {
                    assertStatus(error, 400);
                    assertThat(error.getMessage()).contains("Linha 1");
                }).verify();
        verifyNoInteractions(rooms);
    }

    @Test
    void theOwnerTheAdminAndAnInvitedStudentRead() {
        when(reader.read(ROOM)).thenReturn(Mono.just(matrix()));
        when(rooms.find(ROOM, OWNER)).thenReturn(Mono.just(room(false)));
        when(rooms.find(ROOM, 1L)).thenReturn(Mono.just(room(false)));
        when(rooms.find(ROOM, STUDENT)).thenReturn(Mono.just(room(true)));

        StepVerifier.create(service.get(ROOM, OWNER, false)).expectNextCount(1).verifyComplete();
        StepVerifier.create(service.get(ROOM, 1L, true)).expectNextCount(1).verifyComplete();
        StepVerifier.create(service.get(ROOM, STUDENT, false)).expectNextCount(1).verifyComplete();
    }

    @Test
    void aStrangerAndAMissingMatrixAreBothNotFound() {
        when(rooms.find(ROOM, OTHER_TEACHER)).thenReturn(Mono.just(room(false)));
        StepVerifier.create(service.get(ROOM, OTHER_TEACHER, false))
                .expectErrorSatisfies(error -> assertStatus(error, 404)).verify();

        when(rooms.find(ROOM, STUDENT)).thenReturn(Mono.just(room(true)));
        when(reader.read(ROOM)).thenReturn(Mono.empty());
        StepVerifier.create(service.get(ROOM, STUDENT, false))
                .expectErrorSatisfies(error -> {
                    assertStatus(error, 404);
                    assertThat(error.getMessage()).contains("ainda não tem matriz");
                }).verify();
    }
}
