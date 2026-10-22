package ao.creativemode.kixi.examrooms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ao.creativemode.kixi.examrooms.model.ExamRoom;
import ao.creativemode.kixi.examrooms.model.ExamRoomParticipant;
import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import ao.creativemode.kixi.examrooms.repository.ExamRoomParticipantRepository;
import ao.creativemode.kixi.examrooms.repository.ExamRoomRepository;
import ao.creativemode.kixi.simulations.model.SimulationIntegrityEvent;
import ao.creativemode.kixi.simulations.model.SimulationIntegrityEventKind;
import ao.creativemode.kixi.simulations.repository.SimulationIntegrityEventRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ExamRoomIntegrityServiceTest {
    private ExamRoomRepository rooms;
    private ExamRoomParticipantRepository participants;
    private SimulationIntegrityEventRepository events;
    private ExamRoomIntegrityService service;

    @BeforeEach
    void setUp() {
        rooms = org.mockito.Mockito.mock(ExamRoomRepository.class);
        participants = org.mockito.Mockito.mock(ExamRoomParticipantRepository.class);
        events = org.mockito.Mockito.mock(SimulationIntegrityEventRepository.class);
        service = new ExamRoomIntegrityService(rooms, participants, events,
                Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void returnsParticipantsAndPersistedEventsOnlyForTheAuthorizedRoom() {
        ExamRoom room = new ExamRoom();
        room.setId(5L);
        room.setTeacherAccountId(9L);
        room.setStatus(ExamRoomStatus.RUNNING);
        ExamRoomParticipant participant = new ExamRoomParticipant();
        participant.setAccountId(7L);
        participant.setExamRoomId(5L);
        participant.setSimulationId(12L);
        SimulationIntegrityEvent event = new SimulationIntegrityEvent();
        event.setId(31L);
        event.setSimulationId(12L);
        event.setKind(SimulationIntegrityEventKind.TAB_HIDDEN);
        event.setAt(LocalDateTime.of(2026, 10, 21, 9, 59));
        when(rooms.findByIdAndTeacherAccountId(5L, 9L)).thenReturn(Mono.just(room));
        when(participants.findByExamRoomId(5L)).thenReturn(Flux.just(participant));
        when(events.findRecentBySimulationIdIn(java.util.Set.of(12L), 1_000)).thenReturn(Flux.just(event));

        StepVerifier.create(service.list(5L, 9L, false))
                .assertNext(result -> {
                    assertThat(result.roomId()).isEqualTo(5L);
                    assertThat(result.serverTime()).isEqualTo(LocalDateTime.of(2026, 10, 21, 10, 0));
                    assertThat(result.participants()).hasSize(1);
                    assertThat(result.participants().get(0).accountId()).isEqualTo(7L);
                    assertThat(result.participants().get(0).events()).extracting("kind")
                            .containsExactly(SimulationIntegrityEventKind.TAB_HIDDEN);
                })
                .verifyComplete();
    }

    @Test
    void rejectsTeachersWhoDoNotOwnTheRoom() {
        when(rooms.findByIdAndTeacherAccountId(5L, 10L)).thenReturn(Mono.empty());

        StepVerifier.create(service.list(5L, 10L, false))
                .expectErrorMatches(error -> error.getMessage().contains("room owner"))
                .verify();
    }

    @Test
    void returnsNotFoundForAnAdminRequestToAMissingRoom() {
        when(rooms.findById(404L)).thenReturn(Mono.empty());

        StepVerifier.create(service.list(404L, 1L, true))
                .expectErrorMatches(error -> error instanceof ao.creativemode.kixi.shared.exception.ApiException api
                        && api.getStatusCode() == 404)
                .verify();
    }

    @Test
    void groupsRecentEventsByEachParticipantSimulation() {
        ExamRoom room = new ExamRoom();
        room.setId(5L);
        ExamRoomParticipant first = participant(7L, 12L);
        ExamRoomParticipant second = participant(8L, 13L);
        SimulationIntegrityEvent firstEvent = event(31L, 12L, SimulationIntegrityEventKind.TAB_HIDDEN);
        SimulationIntegrityEvent secondEvent = event(32L, 13L, SimulationIntegrityEventKind.WINDOW_BLUR);

        when(rooms.findById(5L)).thenReturn(Mono.just(room));
        when(participants.findByExamRoomId(5L)).thenReturn(Flux.just(first, second));
        when(events.findRecentBySimulationIdIn(java.util.Set.of(12L, 13L), 1_000))
                .thenReturn(Flux.just(firstEvent, secondEvent));

        StepVerifier.create(service.list(5L, 1L, true))
                .assertNext(result -> {
                    assertThat(result.participants()).extracting("accountId").containsExactly(7L, 8L);
                    assertThat(result.participants().get(0).events()).extracting("id").containsExactly(31L);
                    assertThat(result.participants().get(1).events()).extracting("id").containsExactly(32L);
                })
                .verifyComplete();
    }

    private static ExamRoomParticipant participant(Long accountId, Long simulationId) {
        ExamRoomParticipant participant = new ExamRoomParticipant();
        participant.setAccountId(accountId);
        participant.setSimulationId(simulationId);
        return participant;
    }

    private static SimulationIntegrityEvent event(Long id, Long simulationId, SimulationIntegrityEventKind kind) {
        SimulationIntegrityEvent event = new SimulationIntegrityEvent();
        event.setId(id);
        event.setSimulationId(simulationId);
        event.setKind(kind);
        event.setAt(LocalDateTime.of(2026, 10, 21, 9, 59));
        return event;
    }
}
