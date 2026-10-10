package ao.creativemode.kixi.examrooms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.examrooms.dto.ExamRoomParticipantRequest;
import ao.creativemode.kixi.examrooms.dto.ExamRoomRequest;
import ao.creativemode.kixi.examrooms.model.*;
import ao.creativemode.kixi.examrooms.repository.*;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.institutions.repository.EnrollmentRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ExamRoomServiceTest {
    private ExamRoomRepository rooms;
    private ExamRoomParticipantRepository participants;
    private SimulationRepository simulations;
    private AccountRepository accounts;
    private StatementRepository statements;
    private ClassRepository classes;
    private EnrollmentRepository enrollments;
    private ExamRoomService service;
    private final LocalDateTime start = LocalDateTime.of(2026, 10, 10, 10, 0);

    @BeforeEach
    void setUp() {
        rooms = mock(ExamRoomRepository.class);
        participants = mock(ExamRoomParticipantRepository.class);
        simulations = mock(SimulationRepository.class);
        accounts = mock(AccountRepository.class);
        statements = mock(StatementRepository.class);
        classes = mock(ClassRepository.class);
        enrollments = mock(EnrollmentRepository.class);
        service = new ExamRoomService(rooms, participants, simulations, accounts, statements, classes, enrollments);
    }

    @Test
    void rejectsAnInvalidWindowBeforeWriting() {
        ExamRoomRequest request = new ExamRoomRequest(5L, start, start, 60);

        StepVerifier.create(service.create(request, 9L))
                .expectErrorMatches(error -> error instanceof ApiException api
                        && api.getStatus() == HttpStatus.BAD_REQUEST)
                .verify();
        verifyNoInteractions(statements, accounts, rooms);
    }

    @Test
    void createsRoomOwnedByAuthenticatedTeacher() {
        when(statements.findByIdAndDeletedAtIsNull(5L)).thenReturn(Mono.just(new Statement()));
        when(accounts.findById(9L)).thenReturn(Mono.just(account(9L)));
        when(rooms.save(any(ExamRoom.class))).thenAnswer(invocation -> {
            ExamRoom room = invocation.getArgument(0);
            room.setId(20L);
            return Mono.just(room);
        });

        StepVerifier.create(service.create(new ExamRoomRequest(5L, start, start.plusHours(2), 90), 9L))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(20L);
                    assertThat(response.teacherAccountId()).isEqualTo(9L);
                    assertThat(response.status()).isEqualTo(ExamRoomStatus.DRAFT);
                }).verifyComplete();
    }

    @Test
    void nonParticipantIsForbiddenAndCannotCreateSimulation() {
        ExamRoom room = room(20L, ExamRoomStatus.OPEN);
        when(rooms.findById(20L)).thenReturn(Mono.just(room));
        when(participants.findByExamRoomIdAndAccountId(20L, 42L)).thenReturn(Mono.empty());

        StepVerifier.create(service.join(20L, 42L))
                .expectErrorMatches(error -> error instanceof ApiException api
                        && api.getStatus() == HttpStatus.FORBIDDEN)
                .verify();
        verifyNoInteractions(simulations);
    }

    @Test
    void invitedStudentGetsOneSimulationWithRoomConditions() {
        ExamRoom room = room(20L, ExamRoomStatus.RUNNING);
        ExamRoomParticipant participant = participant(20L, 42L);
        when(rooms.findById(20L)).thenReturn(Mono.just(room));
        when(participants.findByExamRoomIdAndAccountId(20L, 42L)).thenReturn(Mono.just(participant));
        when(simulations.save(any(Simulation.class))).thenAnswer(invocation -> {
            Simulation simulation = invocation.getArgument(0);
            simulation.setId(80L);
            return Mono.just(simulation);
        });
        when(participants.save(any(ExamRoomParticipant.class))).thenAnswer(invocation ->
                Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.join(20L, 42L))
                .assertNext(response -> assertThat(response.simulationId()).isEqualTo(80L))
                .verifyComplete();
        verify(simulations).save(argThat(simulation -> simulation.getExamRoomId().equals(20L)
                && simulation.getExamRoomDurationMinutes().equals(90)
                && simulation.getStartedAt().equals(start)));
    }

    @Test
    void joiningTwiceReusesTheExistingSimulation() {
        ExamRoomParticipant participant = participant(20L, 42L);
        participant.setSimulationId(80L);
        when(rooms.findById(20L)).thenReturn(Mono.just(room(20L, ExamRoomStatus.OPEN)));
        when(participants.findByExamRoomIdAndAccountId(20L, 42L)).thenReturn(Mono.just(participant));

        StepVerifier.create(service.join(20L, 42L))
                .expectNextMatches(response -> response.simulationId().equals(80L))
                .verifyComplete();
        verifyNoInteractions(simulations);
    }

    @Test
    void teacherCannotManageAnotherTeachersRoom() {
        when(rooms.findByIdAndTeacherAccountId(20L, 7L)).thenReturn(Mono.empty());

        StepVerifier.create(service.transition(20L, 7L, false, ExamRoomStatus.OPEN))
                .expectErrorMatches(error -> error instanceof ApiException api
                        && api.getStatus() == HttpStatus.FORBIDDEN)
                .verify();
        verify(rooms, never()).transition(any(), any(), any());
    }

    @Test
    void addsEveryActiveStudentFromAClass() {
        when(rooms.findByIdAndTeacherAccountId(20L, 7L)).thenReturn(Mono.just(room(20L, ExamRoomStatus.DRAFT)));
        ao.creativemode.kixi.academic.model.Class klass = new ao.creativemode.kixi.academic.model.Class();
        klass.setId(3L);
        when(classes.findByIdAndDeletedAtIsNull(3L)).thenReturn(Mono.just(klass));
        when(enrollments.findAllByClassIdAndDeletedAtIsNull(3L)).thenReturn(Flux.just(
                new ao.creativemode.kixi.institutions.model.Enrollment(41L, 3L, 1L),
                new ao.creativemode.kixi.institutions.model.Enrollment(42L, 3L, 1L)));
        when(participants.findByExamRoomIdAndAccountId(any(), any())).thenReturn(Mono.empty());
        when(participants.save(any(ExamRoomParticipant.class))).thenAnswer(invocation ->
                Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.addParticipant(20L, new ExamRoomParticipantRequest(null, 3L), 7L, false))
                .expectNextCount(1).verifyComplete();
        verify(participants, times(2)).save(any(ExamRoomParticipant.class));
    }

    private static Account account(Long id) {
        Account account = new Account();
        account.setId(id);
        return account;
    }

    private static ExamRoom room(Long id, ExamRoomStatus status) {
        ExamRoom room = new ExamRoom();
        room.setId(id);
        room.setStatementId(5L);
        room.setTeacherAccountId(7L);
        room.setStartsAt(LocalDateTime.of(2026, 10, 10, 10, 0));
        room.setEndsAt(LocalDateTime.of(2026, 10, 10, 12, 0));
        room.setDurationMinutes(90);
        room.setStatus(status);
        return room;
    }

    private static ExamRoomParticipant participant(Long roomId, Long accountId) {
        ExamRoomParticipant participant = new ExamRoomParticipant();
        participant.setExamRoomId(roomId);
        participant.setAccountId(accountId);
        return participant;
    }
}
