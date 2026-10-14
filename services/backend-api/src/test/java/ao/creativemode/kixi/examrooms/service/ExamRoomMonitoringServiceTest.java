package ao.creativemode.kixi.examrooms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ao.creativemode.kixi.examrooms.model.ExamRoom;
import ao.creativemode.kixi.examrooms.model.ExamRoomParticipant;
import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import ao.creativemode.kixi.examrooms.dto.ExamRoomLiveResponse;
import ao.creativemode.kixi.examrooms.repository.ExamRoomParticipantRepository;
import ao.creativemode.kixi.examrooms.repository.ExamRoomRepository;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.model.User;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.UserRepository;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
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
    private AccountRepository accounts;
    private UserRepository users;
    private ExamRoomMonitoringService service;
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 11, 14, 0);

    @BeforeEach
    void setUp() {
        rooms = mock(ExamRoomRepository.class);
        participants = mock(ExamRoomParticipantRepository.class);
        simulations = mock(SimulationRepository.class);
        answers = mock(SimulationAnswerRepository.class);
        questions = mock(QuestionRepository.class);
        accounts = mock(AccountRepository.class);
        users = mock(UserRepository.class);
        when(accounts.findAllByIdInAndDeletedAtIsNull(any())).thenReturn(Flux.empty());
        when(users.findByAccountIdInAndDeletedAtIsNull(any())).thenReturn(Flux.empty());
        service = new ExamRoomMonitoringService(rooms, participants, simulations, answers, questions,
                Clock.fixed(Instant.parse("2026-10-11T13:00:00Z"), ZoneOffset.UTC), accounts, users);
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

    /**
     * The room has just opened and the class is invited, but nobody has entered: the guests have
     * no simulation, and neither does the room. That used to be a NullPointerException
     * (Map.of().get(null)) and a 500 on the live panel.
     */
    @Test
    void invitedGuestsWithoutASimulationAreNotStartedInsteadOfFailing() {
        ExamRoom room = room(7L, 99L, ExamRoomStatus.OPEN);
        ExamRoomParticipant guest = new ExamRoomParticipant();
        guest.setExamRoomId(7L);
        guest.setAccountId(1L);
        when(rooms.findByIdAndTeacherAccountId(7L, 99L)).thenReturn(Mono.just(room));
        when(participants.findByExamRoomId(7L)).thenReturn(Flux.just(guest));
        when(simulations.findByExamRoomIdAndDeletedAtIsNull(7L)).thenReturn(Flux.empty());
        when(questions.findAllByStatementIdAndDeletedAtIsNull(5L)).thenReturn(Flux.just(new Question()));

        StepVerifier.create(service.live(7L, 99L, false))
                .assertNext(snapshot -> {
                    assertThat(snapshot.totalParticipants()).isEqualTo(1);
                    assertThat(snapshot.joinedParticipants()).isZero();
                    assertThat(snapshot.participants().get(0).status()).isEqualTo("NOT_STARTED");
                    assertThat(snapshot.participants().get(0).answeredQuestions()).isZero();
                })
                .verifyComplete();
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

    @Test
    void countsOnlyEffectiveAnswersInProgress() {
        ExamRoom room = room(7L, 99L, ExamRoomStatus.RUNNING);
        when(rooms.findByIdAndTeacherAccountId(7L, 99L)).thenReturn(Mono.just(room));
        when(participants.findByExamRoomId(7L)).thenReturn(Flux.just(participant(1L, 1L, true)));
        when(simulations.findByExamRoomIdAndDeletedAtIsNull(7L)).thenReturn(Flux.just(simulation(1L, SimulationStatus.IN_PROGRESS)));
        when(answers.findAllBySimulationIdInAndDeletedAtIsNull(any())).thenReturn(Flux.just(
                answer(1L, 10L, 2L, null), answer(1L, 11L, null, null)));
        when(questions.findAllByStatementIdAndDeletedAtIsNull(5L)).thenReturn(Flux.just(new Question(), new Question()));

        StepVerifier.create(service.live(7L, 99L, false))
                .assertNext(snapshot -> {
                    assertThat(snapshot.participants().get(0).answeredQuestions()).isEqualTo(1);
                    assertThat(snapshot.progressPercentage()).isEqualTo(50d);
                })
                .verifyComplete();
    }

    @Test
    void exposesProfileNameAndFallsBackToUsernameInLiveProgress() {
        ExamRoom room = room(7L, 99L, ExamRoomStatus.RUNNING);
        when(rooms.findByIdAndTeacherAccountId(7L, 99L)).thenReturn(Mono.just(room));
        when(participants.findByExamRoomId(7L)).thenReturn(Flux.just(participant(1L, 1L, true), participant(2L, 2L, true)));
        when(simulations.findByExamRoomIdAndDeletedAtIsNull(7L)).thenReturn(Flux.empty());
        when(questions.findAllByStatementIdAndDeletedAtIsNull(5L)).thenReturn(Flux.empty());
        Account named = new Account();
        named.setId(1L);
        named.setUsername("named-user");
        Account fallback = new Account();
        fallback.setId(2L);
        fallback.setUsername("fallback-user");
        User profile = new User(1L, "Ana", "Silva");
        when(accounts.findAllByIdInAndDeletedAtIsNull(any())).thenReturn(Flux.just(named, fallback));
        when(users.findByAccountIdInAndDeletedAtIsNull(any())).thenReturn(Flux.just(profile));

        StepVerifier.create(service.live(7L, 99L, false))
                .assertNext(snapshot -> assertThat(snapshot.participants())
                        .extracting(ExamRoomLiveResponse.ParticipantProgress::displayName)
                        .containsExactly("Ana Silva", "fallback-user"))
                .verifyComplete();
    }

    @Test
    void slowSnapshotsAreSerializedAndRemainInOrder() {
        ExamRoom room = room(7L, 99L, ExamRoomStatus.RUNNING);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        when(rooms.findByIdAndTeacherAccountId(7L, 99L)).thenReturn(Mono.just(room));
        when(participants.findByExamRoomId(7L)).thenReturn(Flux.just(participant(1L, 1L, true)));
        when(simulations.findByExamRoomIdAndDeletedAtIsNull(7L)).thenReturn(Flux.empty());
        when(questions.findAllByStatementIdAndDeletedAtIsNull(5L)).thenAnswer(ignored -> {
            int call = started.incrementAndGet();
            int current = active.incrementAndGet();
            maximumActive.updateAndGet(previous -> Math.max(previous, current));
            Duration delay = call == 1 ? Duration.ofSeconds(5) : Duration.ofSeconds(1);
            return Mono.delay(delay)
                    .doOnSuccess(ignoredValue -> active.decrementAndGet())
                    .thenMany(Flux.range(1, call));
        });

        StepVerifier.create(service.stream(7L, 99L, false))
                .expectNextMatches(snapshot -> snapshot.participants().get(0).totalQuestions() == 1)
                .expectNextMatches(snapshot -> snapshot.participants().get(0).totalQuestions() == 2)
                .thenCancel()
                .verify(Duration.ofSeconds(15));

        assertThat(maximumActive).hasValue(1);
        assertThat(started).hasValue(2);
    }

    @Test
    void gradesForManyParticipantsUseBatchQueries() {
        // #188: one simulation, one answer and one question query for the whole room.
        ExamRoom room = room(7L, 99L, ExamRoomStatus.CLOSED);
        List<ExamRoomParticipant> participantRows = new ArrayList<>();
        List<Simulation> simulationRows = new ArrayList<>();
        List<SimulationAnswer> answerRows = new ArrayList<>();
        for (long account = 1; account <= 120; account++) {
            participantRows.add(participant(account, account, true));
            Simulation simulation = simulation(account, SimulationStatus.FINISHED);
            simulation.setFinalScore((double) account);
            simulationRows.add(simulation);
            SimulationAnswer right = answer(account, 10L, 2L, null);
            right.setIsCorrect(true);
            answerRows.add(right);
            if (account % 2 == 0) {
                SimulationAnswer open = answer(account, 11L, null, "texto");
                open.setReviewStatus(ao.creativemode.kixi.simulations.model.SimulationAnswerStatus.PENDING_REVIEW);
                answerRows.add(open);
            }
        }
        participantRows.add(participantWithoutSimulation(121L));
        when(rooms.findByIdAndTeacherAccountId(7L, 99L)).thenReturn(Mono.just(room));
        when(participants.findByExamRoomId(7L)).thenReturn(Flux.fromIterable(participantRows));
        when(simulations.findAllById(anyIterable())).thenReturn(Flux.fromIterable(simulationRows));
        when(answers.findAllBySimulationIdInAndDeletedAtIsNull(any())).thenReturn(Flux.fromIterable(answerRows));
        when(questions.findAllByStatementIdAndDeletedAtIsNull(5L)).thenReturn(Flux.just(new Question(), new Question()));

        StepVerifier.create(service.grades(7L, 99L, false))
                .assertNext(grades -> {
                    assertThat(grades).hasSize(121);
                    assertThat(grades.get(0).correctAnswers()).isEqualTo(1);
                    assertThat(grades.get(0).pendingReviewCount()).isZero();
                    assertThat(grades.get(1).pendingReviewCount()).isEqualTo(1);
                    assertThat(grades.get(1).finalScore()).isEqualTo(2d);
                    assertThat(grades.get(119).totalQuestions()).isEqualTo(2);
                    assertThat(grades.get(120).status()).isEqualTo("NOT_STARTED");
                })
                .verifyComplete();
        verify(simulations, times(1)).findAllById(anyIterable());
        verify(simulations, never()).findByIdAndDeletedAtIsNull(any());
        verify(answers, times(1)).findAllBySimulationIdInAndDeletedAtIsNull(argThat(ids -> ids.size() == 120));
        verify(questions, times(1)).findAllByStatementIdAndDeletedAtIsNull(5L);
    }

    @Test
    void aParticipantWithAMissingSimulationStillFailsTheGrades() {
        ExamRoom room = room(7L, 99L, ExamRoomStatus.CLOSED);
        when(rooms.findByIdAndTeacherAccountId(7L, 99L)).thenReturn(Mono.just(room));
        when(participants.findByExamRoomId(7L)).thenReturn(Flux.just(participant(1L, 1L, true), participant(2L, 2L, true)));
        Simulation gone = simulation(2L, SimulationStatus.FINISHED);
        gone.setDeletedAt(now);
        when(simulations.findAllById(anyIterable())).thenReturn(Flux.just(simulation(1L, SimulationStatus.FINISHED), gone));

        StepVerifier.create(service.grades(7L, 99L, false))
                .expectErrorMatches(error -> error.getMessage().contains("Simulation not found"))
                .verify();
    }

    @Test
    void theStreamOfAnAuthorizedRoomBuildsTheFirstSnapshotOnlyOnce() {
        // #188: the endpoint authorizes with the room alone, so the first event
        // costs one snapshot and one room lookup, not two of each.
        ExamRoom room = room(7L, 99L, ExamRoomStatus.RUNNING);
        when(rooms.findByIdAndTeacherAccountId(7L, 99L)).thenReturn(Mono.just(room));
        when(participants.findByExamRoomId(7L)).thenReturn(Flux.just(participant(1L, 1L, true)));
        when(simulations.findByExamRoomIdAndDeletedAtIsNull(7L)).thenReturn(Flux.empty());
        when(questions.findAllByStatementIdAndDeletedAtIsNull(5L)).thenReturn(Flux.just(new Question()));

        StepVerifier.create(service.authorize(7L, 99L, false)
                        .flatMapMany(authorized -> service.stream(authorized, 99L, false)).take(1))
                .assertNext(snapshot -> assertThat(snapshot.totalParticipants()).isEqualTo(1))
                .verifyComplete();

        verify(rooms, times(1)).findByIdAndTeacherAccountId(7L, 99L);
        verify(participants, times(1)).findByExamRoomId(7L);
        verify(questions, times(1)).findAllByStatementIdAndDeletedAtIsNull(5L);
    }

    @Test
    void anotherTeacherCannotStartTheStream() {
        when(rooms.findByIdAndTeacherAccountId(7L, 41L)).thenReturn(Mono.empty());

        StepVerifier.create(service.authorize(7L, 41L, false))
                .expectErrorMatches(error -> error.getMessage().contains("room owner"))
                .verify();
        verifyNoInteractions(participants);
    }

    private ExamRoomParticipant participantWithoutSimulation(long accountId) {
        ExamRoomParticipant participant = participant(accountId, 0L, false);
        participant.setSimulationId(null);
        return participant;
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

    private SimulationAnswer answer(long simulationId, long questionId, Long selectedOptionId, String answerText) {
        SimulationAnswer answer = new SimulationAnswer();
        answer.setSimulationId(simulationId);
        answer.setQuestionId(questionId);
        answer.setSelectedOptionId(selectedOptionId);
        answer.setAnswerText(answerText);
        return answer;
    }
}
