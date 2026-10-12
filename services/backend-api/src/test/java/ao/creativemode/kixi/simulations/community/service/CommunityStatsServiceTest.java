package ao.creativemode.kixi.simulations.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.institutions.dto.enrollment.MeResponse;
import ao.creativemode.kixi.institutions.service.MeService;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.ExamRoomAccess;
import ao.creativemode.kixi.simulations.community.dto.CommunityStatsResponse;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import ao.creativemode.kixi.simulations.model.SimulationAnswerStatus;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CommunityStatsServiceTest {

    private static final Long STATEMENT_ID = 10L;
    private static final Long ACCOUNT_ID = 12L;
    private static final Long OTHER_ACCOUNT_ID = 99L;
    private static final Long SCHOOL_ID = 5L;

    private StatementRepository statements;
    private QuestionRepository questions;
    private SimulationRepository simulations;
    private SimulationAnswerRepository answers;
    private MeService meService;
    private AccountRepository accounts;
    private ExamRoomAccess examRooms;
    private CommunityStatsService service;
    private CommunityStatsService guardedService;

    @BeforeEach
    void setUp() {
        statements = mock(StatementRepository.class);
        questions = mock(QuestionRepository.class);
        simulations = mock(SimulationRepository.class);
        answers = mock(SimulationAnswerRepository.class);
        meService = mock(MeService.class);
        accounts = mock(AccountRepository.class);
        examRooms = mock(ExamRoomAccess.class);
        service = new CommunityStatsService(statements, questions, simulations, answers, meService);
        guardedService = new CommunityStatsService(statements, questions, simulations, answers,
                meService, accounts, examRooms);
    }

    // ── Pure assembly ─────────────────────────────────────────────────────

    @Test
    void onlyGradedSimulationsCountAndBucketsFollowThePaperScale() {
        Statement statement = statement(20.0);
        List<Simulation> finished = List.of(
                simulation(1L, 4.0, 600),
                simulation(2L, 12.0, 900),
                simulation(3L, 16.0, null),
                ungraded(4L));

        CommunityStatsResponse response = CommunityStatsService.assemble(
                statement, List.of(), finished, List.of(), null, List.of());

        assertThat(response.sampleSize()).isEqualTo(3);
        assertThat(response.meanScore()).isEqualTo((4.0 + 12.0 + 16.0) / 3);
        assertThat(response.medianScore()).isEqualTo(12.0);
        // Scale 0–20 in 5 buckets of 4: 4.0 → bucket 1, 12.0 → bucket 3, 16.0 → bucket 4.
        assertThat(response.scoreDistribution()).hasSize(5);
        assertThat(response.scoreDistribution().get(1).count()).isEqualTo(1);
        assertThat(response.scoreDistribution().get(3).count()).isEqualTo(1);
        assertThat(response.scoreDistribution().get(4).count()).isEqualTo(1);
        assertThat(response.scoreDistribution().get(0).from()).isEqualTo(0.0);
        assertThat(response.scoreDistribution().get(4).to()).isEqualTo(20.0);
        // Only the two non-null times average.
        assertThat(response.avgTimeSpentSeconds()).isEqualTo(750.0);
        assertThat(response.timeSampleSize()).isEqualTo(2);
        assertThat(response.mine()).isNull();
    }

    @Test
    void perQuestionHitRateIgnoresPendingReviewAndAveragesOnlyGraded() {
        Question question = question(20L, 1, 5.0);
        List<SimulationAnswer> questionAnswers = List.of(
                answer(1L, 20L, true, 5.0f),
                answer(2L, 20L, false, 0.0f),
                // Still waiting for review: known outcome missing, so it must
                // neither count as wrong nor drag the average.
                answer(3L, 20L, null, null));

        CommunityStatsResponse response = CommunityStatsService.assemble(
                statement(20.0), List.of(question), List.of(), questionAnswers, null, List.of());

        CommunityStatsResponse.QuestionStats stats = response.questions().get(0);
        assertThat(stats.attempts()).isEqualTo(2);
        assertThat(stats.correctCount()).isEqualTo(1);
        assertThat(stats.pctCorrect()).isEqualTo(50.0);
        assertThat(stats.avgScore()).isEqualTo(2.5);
    }

    @Test
    void mineBlockCarriesTheLatestAttemptWithoutInferringOpenText() {
        Question question = question(20L, 1, 5.0);
        Simulation mine = simulation(9L, 14.0, 700);
        mine.setAccountId(ACCOUNT_ID);
        SimulationAnswer mineAnswer = answer(9L, 20L, null, 3.5f);

        CommunityStatsResponse response = CommunityStatsService.assemble(
                statement(20.0), List.of(question), List.of(mine), List.of(),
                mine, List.of(mineAnswer));

        assertThat(response.mine().simulationId()).isEqualTo(9L);
        assertThat(response.mine().finalScore()).isEqualTo(14.0);
        // Graded but objective outcome unknown: score yes, correctness untouched.
        assertThat(response.questions().get(0).mine().scoreObtained()).isEqualTo(3.5f);
        assertThat(response.questions().get(0).mine().isCorrect()).isNull();
    }

    @Test
    void emptyCommunityRendersNullsInsteadOfDividingByZero() {
        CommunityStatsResponse response = CommunityStatsService.assemble(
                statement(null), List.of(), List.of(), List.of(), null, List.of());

        assertThat(response.sampleSize()).isZero();
        assertThat(response.meanScore()).isNull();
        assertThat(response.medianScore()).isNull();
        assertThat(response.avgTimeSpentSeconds()).isNull();
        assertThat(response.timeSampleSize()).isZero();
    }

    // ── Read access ───────────────────────────────────────────────────────

    @Test
    void invisibleStatementIsNotFound() {
        when(statements.findByIdAndVisibleTrueAndDeletedAtIsNull(STATEMENT_ID))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.findCommunity(STATEMENT_ID, ACCOUNT_ID))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verifyNoInteractions(meService, questions, simulations, answers);
    }

    @Test
    void paperScopedToAnotherSchoolReadsAsNotFound() {
        givenVisibleStatement();
        when(statements.findEffectiveInstitutionId(STATEMENT_ID)).thenReturn(Mono.just(7L));
        when(meService.getMe(ACCOUNT_ID)).thenReturn(Mono.just(meWithSchool(SCHOOL_ID)));

        StepVerifier.create(service.findCommunity(STATEMENT_ID, ACCOUNT_ID))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verifyNoInteractions(questions, simulations, answers);
    }

    @Test
    void ownSchoolAndUnscopedPapersAreReadable() {
        givenVisibleStatement();
        when(statements.findEffectiveInstitutionId(STATEMENT_ID)).thenReturn(Mono.just(SCHOOL_ID));
        when(meService.getMe(ACCOUNT_ID)).thenReturn(Mono.just(meWithSchool(SCHOOL_ID)));
        givenEmptyPaper();

        StepVerifier.create(service.findCommunity(STATEMENT_ID, ACCOUNT_ID))
                .expectNextMatches(response -> response.statementId().equals(STATEMENT_ID)
                        && response.sampleSize() == 0)
                .verifyComplete();
    }

    @Test
    void paperWithoutASchoolIsOpenToEverySchool() {
        givenVisibleStatement();
        // COALESCE over a paper with no school anywhere resolves to NULL.
        when(statements.findEffectiveInstitutionId(STATEMENT_ID)).thenReturn(Mono.empty());
        givenEmptyPaper();

        StepVerifier.create(service.findCommunity(STATEMENT_ID, ACCOUNT_ID))
                .expectNextCount(1)
                .verifyComplete();

        verifyNoInteractions(meService);
    }

    @Test
    void accountWithoutASchoolSeesEverySchoolLikeTheCatalog() {
        givenVisibleStatement();
        when(statements.findEffectiveInstitutionId(STATEMENT_ID)).thenReturn(Mono.just(7L));
        when(meService.getMe(ACCOUNT_ID)).thenReturn(Mono.just(meWithSchool(null)));
        givenEmptyPaper();

        StepVerifier.create(service.findCommunity(STATEMENT_ID, ACCOUNT_ID))
                .expectNextCount(1)
                .verifyComplete();
    }

    // ── Exam rooms: the answer-key rule ───────────────────────────────────

    @Test
    void openRoomShutsTheCommunityWithConflict() {
        givenVisibleStatement();
        when(statements.findEffectiveInstitutionId(STATEMENT_ID)).thenReturn(Mono.empty());
        when(examRooms.hasNonClosedRoom(STATEMENT_ID)).thenReturn(Mono.just(true));

        StepVerifier.create(guardedService.findCommunity(STATEMENT_ID, ACCOUNT_ID))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getMessage()).contains("exam rooms");
                })
                .verify();

        // No figure is computed while a room is open — not even a sample size.
        verifyNoInteractions(questions, simulations, answers);
    }

    @Test
    void closedRoomsLetTheCommunityThrough() {
        givenVisibleStatement();
        when(statements.findEffectiveInstitutionId(STATEMENT_ID)).thenReturn(Mono.empty());
        when(examRooms.hasNonClosedRoom(STATEMENT_ID)).thenReturn(Mono.just(false));
        givenEmptyPaper();

        StepVerifier.create(guardedService.findCommunity(STATEMENT_ID, ACCOUNT_ID))
                .expectNextCount(1)
                .verifyComplete();
    }

    // ── Deleted accounts and partial grades ───────────────────────────────

    @Test
    void simulationsOfDeletedAccountsLeaveTheCommunity() {
        givenVisibleStatement();
        when(statements.findEffectiveInstitutionId(STATEMENT_ID)).thenReturn(Mono.empty());
        when(examRooms.hasNonClosedRoom(STATEMENT_ID)).thenReturn(Mono.just(false));
        Simulation kept = simulation(1L, 10.0, 600);
        kept.setAccountId(ACCOUNT_ID);
        Simulation gone = simulation(2L, 20.0, 600);
        gone.setAccountId(OTHER_ACCOUNT_ID);
        when(simulations.findAllByStatementIdAndStatusAndDeletedAtIsNull(
                        eq(STATEMENT_ID), eq(SimulationStatus.FINISHED)))
                .thenReturn(Flux.just(kept, gone));
        Account active = new Account();
        active.setId(ACCOUNT_ID);
        when(accounts.findAllById(List.of(ACCOUNT_ID, OTHER_ACCOUNT_ID)))
                .thenReturn(Flux.just(active));
        when(questions.findAllByStatementIdOrderedByOrderIndex(STATEMENT_ID))
                .thenReturn(Flux.empty());
        when(answers.findAllBySimulationIdInAndDeletedAtIsNull(List.of(1L)))
                .thenReturn(Flux.empty());
        when(simulations.findFirstByStatementIdAndAccountIdAndStatusAndDeletedAtIsNullOrderByIdDesc(
                        eq(STATEMENT_ID), eq(ACCOUNT_ID), eq(SimulationStatus.FINISHED)))
                .thenReturn(Mono.empty());

        StepVerifier.create(guardedService.findCommunity(STATEMENT_ID, ACCOUNT_ID))
                .expectNextMatches(response -> response.sampleSize() == 1
                        && response.meanScore() == 10.0)
                .verifyComplete();
    }

    @Test
    void simulationsAwaitingReviewSitOutTheDistributionButNotTheQuestions() {
        Question question = question(20L, 1, 10.0);
        Simulation partial = simulation(1L, 6.0, 600);
        SimulationAnswer decided = answer(1L, 20L, true, 6.0f);
        decided.setReviewStatus(SimulationAnswerStatus.AUTO_GRADED);
        SimulationAnswer pending = answer(1L, 21L, null, null);
        pending.setReviewStatus(SimulationAnswerStatus.PENDING_REVIEW);

        CommunityStatsResponse response = CommunityStatsService.assemble(
                statement(20.0), List.of(question), List.of(partial),
                List.of(decided, pending), null, List.of());

        // The grade is partial until the review lands: out of the figures…
        assertThat(response.sampleSize()).isZero();
        assertThat(response.meanScore()).isNull();
        // …but the decided answer still teaches the per-question rate.
        assertThat(response.questions().get(0).attempts()).isEqualTo(1);
        assertThat(response.questions().get(0).pctCorrect()).isEqualTo(100.0);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private void givenVisibleStatement() {
        Statement statement = statement(20.0);
        when(statements.findByIdAndVisibleTrueAndDeletedAtIsNull(STATEMENT_ID))
                .thenReturn(Mono.just(statement));
    }

    private void givenEmptyPaper() {
        when(questions.findAllByStatementIdOrderedByOrderIndex(STATEMENT_ID))
                .thenReturn(Flux.empty());
        when(simulations.findAllByStatementIdAndStatusAndDeletedAtIsNull(
                        eq(STATEMENT_ID), eq(SimulationStatus.FINISHED)))
                .thenReturn(Flux.empty());
        when(simulations.findFirstByStatementIdAndAccountIdAndStatusAndDeletedAtIsNullOrderByIdDesc(
                        eq(STATEMENT_ID), eq(ACCOUNT_ID), eq(SimulationStatus.FINISHED)))
                .thenReturn(Mono.empty());
    }

    private static Statement statement(Double totalMaxScore) {
        Statement statement = new Statement();
        statement.setId(STATEMENT_ID);
        statement.setVisible(true);
        statement.setTotalMaxScore(totalMaxScore);
        return statement;
    }

    private static Simulation simulation(Long id, Double finalScore, Integer seconds) {
        Simulation simulation = new Simulation();
        simulation.setId(id);
        simulation.setAccountId(99L);
        simulation.setStatementId(STATEMENT_ID);
        simulation.setFinalScore(finalScore);
        simulation.setTimeSpentSeconds(seconds);
        simulation.setStatus(SimulationStatus.FINISHED);
        return simulation;
    }

    private static Simulation ungraded(Long id) {
        return simulation(id, null, 300);
    }

    private static Question question(Long id, int number, Double maxScore) {
        Question question = new Question();
        question.setId(id);
        question.setStatementId(STATEMENT_ID);
        question.setNumber(number);
        question.setMaxScore(maxScore);
        return question;
    }

    private static SimulationAnswer answer(
            Long simulationId, Long questionId, Boolean correct, Float score
    ) {
        SimulationAnswer answer = new SimulationAnswer();
        answer.setSimulationId(simulationId);
        answer.setQuestionId(questionId);
        answer.setIsCorrect(correct);
        answer.setScoreObtained(score);
        return answer;
    }

    private static MeResponse meWithSchool(Long schoolId) {
        MeResponse.SchoolInfo school = schoolId == null
                ? null
                : new MeResponse.SchoolInfo(schoolId, "ITEL", "Instituto de Telecomunicações");
        return new MeResponse(ACCOUNT_ID, "aluno1", "aluno1@kixi.ao", "Ana", "Manuel", null,
                List.of("STUDENT"), school, null, null, null);
    }
}
