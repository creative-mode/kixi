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
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.AccountRoleRepository;
import ao.creativemode.kixi.identity.repository.RoleRepository;
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
    private QuestionOptionRepository options;
    private QuestionRepository questions;
    private ExamRoomService service;
    private final LocalDateTime start = LocalDateTime.now().minusMinutes(30);

    @BeforeEach
    void setUp() {
        rooms = mock(ExamRoomRepository.class);
        participants = mock(ExamRoomParticipantRepository.class);
        simulations = mock(SimulationRepository.class);
        accounts = mock(AccountRepository.class);
        statements = mock(StatementRepository.class);
        classes = mock(ClassRepository.class);
        enrollments = mock(EnrollmentRepository.class);
        options = mock(QuestionOptionRepository.class);
        questions = mock(QuestionRepository.class);
        service = new ExamRoomService(rooms, participants, simulations, accounts, statements, classes, enrollments,
                null, null, null, questions, options);
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
        when(simulations.insertExamRoomSimulation(any(), any(), any(), any())).thenAnswer(invocation -> {
            Simulation simulation = new Simulation();
            simulation.setId(80L);
            return Mono.just(simulation);
        });
        when(simulations.findByExamRoomIdAndAccountIdAndDeletedAtIsNull(20L, 42L)).thenReturn(Mono.empty());
        when(participants.save(any(ExamRoomParticipant.class))).thenAnswer(invocation ->
                Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.join(20L, 42L))
                .assertNext(response -> assertThat(response.simulationId()).isEqualTo(80L))
                .verifyComplete();
        verify(simulations).insertExamRoomSimulation(42L, 5L, 20L, 90);
    }

    @Test
    void joiningTwiceReusesTheExistingSimulation() {
        ExamRoomParticipant participant = participant(20L, 42L);
        participant.setSimulationId(80L);
        when(rooms.findById(20L)).thenReturn(Mono.just(room(20L, ExamRoomStatus.OPEN)));
        when(participants.findByExamRoomIdAndAccountId(20L, 42L)).thenReturn(Mono.just(participant));
        when(simulations.findById(80L)).thenReturn(Mono.just(simulation(80L)));

        StepVerifier.create(service.join(20L, 42L))
                .expectNextMatches(response -> response.simulationId().equals(80L))
                .verifyComplete();
        verify(simulations).findById(80L);
    }

    @Test
    void studentViewIncludesOptionsWithoutTheAnswerKey() {
        when(rooms.findById(20L)).thenReturn(Mono.just(room(20L, ExamRoomStatus.RUNNING)));
        ExamRoomParticipant participant = participant(20L, 42L);
        participant.setSimulationId(80L);
        when(participants.findByExamRoomIdAndAccountId(20L, 42L)).thenReturn(Mono.just(participant));
        Question question = new Question();
        question.setId(12L);
        question.setStatementId(5L);
        question.setText("2 + 2?");
        when(questions.findAllByStatementIdOrderedByOrderIndex(5L)).thenReturn(Flux.just(question));
        QuestionOption option = new QuestionOption();
        option.setId(99L);
        option.setQuestionId(12L);
        option.setIsCorrect(true);
        option.setOptionText("4");
        when(options.findAllByQuestionIdOrderedByOrderIndex(12L)).thenReturn(Flux.just(option));

        StepVerifier.create(service.studentView(20L, 42L))
                .assertNext(view -> assertThat(view.questions().get(0).options().get(0).isCorrect()).isNull())
                .verifyComplete();
    }

    @Test
    void studentViewPreservesQuestionAndOptionOrder() {
        when(rooms.findById(20L)).thenReturn(Mono.just(room(20L, ExamRoomStatus.RUNNING)));
        ExamRoomParticipant participant = participant(20L, 42L);
        participant.setSimulationId(80L);
        when(participants.findByExamRoomIdAndAccountId(20L, 42L)).thenReturn(Mono.just(participant));
        Question first = question(12L, 2);
        Question second = question(11L, 1);
        when(questions.findAllByStatementIdOrderedByOrderIndex(5L)).thenReturn(Flux.just(second, first));
        when(options.findAllByQuestionIdOrderedByOrderIndex(12L)).thenReturn(Flux.just(option(101L, 12L, 1), option(102L, 12L, 2)));
        when(options.findAllByQuestionIdOrderedByOrderIndex(11L)).thenReturn(Flux.just(option(201L, 11L, 1)));

        StepVerifier.create(service.studentView(20L, 42L))
                .assertNext(view -> {
                    assertThat(view.questions()).extracting(q -> q.id()).containsExactly(11L, 12L);
                    assertThat(view.questions().get(1).options()).extracting(o -> o.id()).containsExactly(101L, 102L);
                }).verifyComplete();
    }

    @Test
    void classInvitationValidatesAllStudentsBeforeWritingAnyParticipant() {
        AccountRoleRepository accountRoles = mock(AccountRoleRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        ExamRoomService scopedService = new ExamRoomService(rooms, participants, simulations, accounts, statements,
                classes, enrollments, null, accountRoles, roles, questions, options);
        ExamRoom room = room(20L, ExamRoomStatus.DRAFT);
        room.setClassId(3L);
        when(rooms.findByIdAndTeacherAccountId(20L, 7L)).thenReturn(Mono.just(room));
        when(classes.findByIdAndDeletedAtIsNull(3L)).thenReturn(Mono.just(new ao.creativemode.kixi.academic.model.Class()));
        when(enrollments.findAllByClassIdAndDeletedAtIsNull(3L)).thenReturn(Flux.just(
                new ao.creativemode.kixi.institutions.model.Enrollment(41L, 3L, 1L),
                new ao.creativemode.kixi.institutions.model.Enrollment(42L, 3L, 1L)));
        when(accounts.findById(anyLong())).thenAnswer(invocation -> Mono.just(account(invocation.getArgument(0))));
        ao.creativemode.kixi.identity.model.Role studentRole = new ao.creativemode.kixi.identity.model.Role();
        studentRole.setId(9L);
        when(roles.findByNameAndDeletedAtIsNull("STUDENT")).thenReturn(Mono.just(studentRole));
        when(accountRoles.existsByAccountIdAndRoleIdAndDeletedAtIsNull(anyLong(), anyLong()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0, Long.class).equals(41L)));
        StepVerifier.create(scopedService.addParticipant(20L, new ExamRoomParticipantRequest(null, 3L), 7L, false))
                .expectError(ApiException.class).verify();
        verify(participants, never()).save(any(ExamRoomParticipant.class));
    }

    @Test
    void rejectsJoiningBeforeTheRoomWindow() {
        ExamRoom room = room(20L, ExamRoomStatus.OPEN);
        room.setStartsAt(LocalDateTime.now().plusMinutes(5));
        when(rooms.findById(20L)).thenReturn(Mono.just(room));
        when(participants.findByExamRoomIdAndAccountId(20L, 42L)).thenReturn(Mono.just(participant(20L, 42L)));

        StepVerifier.create(service.join(20L, 42L))
                .expectError(ApiException.class).verify();
        verifyNoInteractions(simulations);
    }

    @Test
    void rejectsJoiningAtOrAfterTheRoomEnd() {
        ExamRoom room = room(20L, ExamRoomStatus.RUNNING);
        room.setEndsAt(LocalDateTime.now().minusSeconds(1));
        when(rooms.findById(20L)).thenReturn(Mono.just(room));
        when(participants.findByExamRoomIdAndAccountId(20L, 42L)).thenReturn(Mono.just(participant(20L, 42L)));

        StepVerifier.create(service.join(20L, 42L))
                .expectError(ApiException.class).verify();
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

    private static Question question(Long id, int orderIndex) {
        Question question = new Question();
        question.setId(id);
        question.setStatementId(5L);
        question.setOrderIndex(orderIndex);
        return question;
    }

    private static QuestionOption option(Long id, Long questionId, int orderIndex) {
        QuestionOption option = new QuestionOption();
        option.setId(id);
        option.setQuestionId(questionId);
        option.setOrderIndex(orderIndex);
        return option;
    }

    private static ExamRoom room(Long id, ExamRoomStatus status) {
        ExamRoom room = new ExamRoom();
        room.setId(id);
        room.setStatementId(5L);
        room.setTeacherAccountId(7L);
        room.setStartsAt(LocalDateTime.of(2026, 10, 10, 10, 0));
        room.setEndsAt(LocalDateTime.now().plusMinutes(90));
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

    private static Simulation simulation(Long id) {
        Simulation simulation = new Simulation();
        simulation.setId(id);
        simulation.setDeletedAt(null);
        return simulation;
    }
}
