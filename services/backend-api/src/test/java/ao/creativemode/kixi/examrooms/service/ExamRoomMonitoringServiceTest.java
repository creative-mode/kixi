package ao.creativemode.kixi.examrooms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ao.creativemode.kixi.examrooms.model.ExamRoom;
import ao.creativemode.kixi.examrooms.model.ExamRoomParticipant;
import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import ao.creativemode.kixi.examrooms.repository.ExamRoomParticipantRepository;
import ao.creativemode.kixi.examrooms.repository.ExamRoomRepository;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ExamRoomMonitoringServiceTest {
    private ExamRoomRepository rooms;
    private ExamRoomParticipantRepository participants;
    private SimulationRepository simulations;
    private SimulationAnswerRepository answers;
    private QuestionRepository questions;
    private ExamRoomMonitoringService service;
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 11, 14, 0);

    @BeforeEach
    void setUp() {
        rooms = mock(ExamRoomRepository.class);
        participants = mock(ExamRoomParticipantRepository.class);
        simulations = mock(SimulationRepository.class);
        answers = mock(SimulationAnswerRepository.class);
        questions = mock(QuestionRepository.class);
        service = new ExamRoomMonitoringService(rooms, participants, simulations, answers, questions,
                Clock.fixed(Instant.parse("2026-10-11T13:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void buildsOneSnapshotForMoreThanOneHundredParticipants() {
        ExamRoom room = room(7L, 99L, ExamRoomStatus.RUNNING);
        List<ExamRoomParticipant> participantRows = new ArrayList<>();
        List<Simulation> simulationRows = new ArrayList<>();
        for (long account = 1; account <= 120; account++) {
            ExamRoomParticipant participant = participant(account, account, account <= 100);
            participantRows.add(participant);
            Simulation simulation = simulation(account, account <= 40 ? SimulationStatus.FINISHED : SimulationStatus.IN_PROGRESS);
            simulationRows.add(simulation);
        }
        when(rooms.findByIdAndTeacherAccountId(7L, 99L)).thenReturn(Mono.just(room));
        when(participants.findByExamRoomId(7L)).thenReturn(Flux.fromIterable(participantRows));
        when(simulations.findByExamRoomIdAndDeletedAtIsNull(7L)).thenReturn(Flux.fromIterable(simulationRows));
        when(answers.findAllBySimulationIdInAndDeletedAtIsNull(any())).thenReturn(Flux.empty());
        when(questions.findAllByStatementIdAndDeletedAtIsNull(5L)).thenReturn(Flux.just(new Question(), new Question()));

        StepVerifier.create(service.live(7L, 99L, false))
                .assertNext(snapshot -> {
                    assertThat(snapshot.totalParticipants()).isEqualTo(120);
                    assertThat(snapshot.joinedParticipants()).isEqualTo(100);
                    assertThat(snapshot.submittedParticipants()).isEqualTo(40);
                    assertThat(snapshot.progressPercentage()).isZero();
                    assertThat(snapshot.participants()).hasSize(120);
                }).verifyComplete();
        verify(answers).findAllBySimulationIdInAndDeletedAtIsNull(argThat(ids -> ids.size() == 120));
    }

    @Test
    void gradesAreOnlyAvailableToTheRoomOwnerOrAdmin() {
        ExamRoom room = room(7L, 99L, ExamRoomStatus.CLOSED);
        when(rooms.findByIdAndTeacherAccountId(7L, 41L)).thenReturn(Mono.empty());
        when(rooms.findById(7L)).thenReturn(Mono.just(room));
        when(participants.findByExamRoomId(7L)).thenReturn(Flux.empty());

        StepVerifier.create(service.grades(7L, 41L, false))
                .expectErrorMatches(error -> error.getMessage().contains("room owner"))
                .verify();
        StepVerifier.create(service.grades(7L, 41L, true))
                .assertNext(grades -> assertThat(grades).isEmpty())
                .verifyComplete();
        verify(participants, times(2)).findByExamRoomId(7L);
    }

    private ExamRoom room(Long id, Long teacherId, ExamRoomStatus status) {
        ExamRoom room = new ExamRoom();
        room.setId(id);
        room.setStatementId(5L);
        room.setTeacherAccountId(teacherId);
        room.setStatus(status);
        room.setStartsAt(now.minusHours(1));
        room.setEndsAt(now.plusHours(1));
        return room;
    }

    private ExamRoomParticipant participant(long accountId, long simulationId, boolean joined) {
        ExamRoomParticipant participant = new ExamRoomParticipant();
        participant.setExamRoomId(7L);
        participant.setAccountId(accountId);
        participant.setSimulationId(simulationId);
        participant.setJoinedAt(joined ? now.minusMinutes(10) : null);
        return participant;
    }

    private Simulation simulation(long id, SimulationStatus status) {
        Simulation simulation = new Simulation();
        simulation.setId(id);
        simulation.setStatementId(5L);
        simulation.setStatus(status);
        simulation.setFinishedAt(status == SimulationStatus.FINISHED ? now : null);
        return simulation;
    }
}
