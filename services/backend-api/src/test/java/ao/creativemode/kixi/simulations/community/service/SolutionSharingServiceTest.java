package ao.creativemode.kixi.simulations.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.institutions.dto.enrollment.MeResponse;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.service.MeService;
import ao.creativemode.kixi.shared.dto.PageResponse;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.community.dto.ShareSolutionRequest;
import ao.creativemode.kixi.simulations.community.dto.SharedSolutionResponse;
import ao.creativemode.kixi.simulations.community.model.SolutionShare;
import ao.creativemode.kixi.simulations.community.repository.SolutionShareRepository;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
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

class SolutionSharingServiceTest {

    private static final Long STATEMENT_ID = 10L;
    private static final Long SIMULATION_ID = 50L;
    private static final Long ACCOUNT_ID = 12L;
    private static final Long SCHOOL_ID = 5L;

    private SolutionShareRepository shares;
    private SimulationRepository simulations;
    private SimulationAnswerRepository answers;
    private QuestionRepository questions;
    private QuestionOptionRepository options;
    private InstitutionRepository institutions;
    private MeService meService;
    private CommunityStatsService community;
    private SolutionSharingService service;

    @BeforeEach
    void setUp() {
        shares = mock(SolutionShareRepository.class);
        simulations = mock(SimulationRepository.class);
        answers = mock(SimulationAnswerRepository.class);
        questions = mock(QuestionRepository.class);
        options = mock(QuestionOptionRepository.class);
        institutions = mock(InstitutionRepository.class);
        meService = mock(MeService.class);
        community = mock(CommunityStatsService.class);
        service = new SolutionSharingService(shares, simulations, answers, questions, options,
                institutions, meService, community);
        when(shares.save(any(SolutionShare.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    // ── Consent validation ────────────────────────────────────────────────

    @Test
    void missingBodySharesAnonymously() {
        SolutionSharingService.Consent consent = SolutionSharingService.validated(null);

        assertThat(consent.anonymous()).isTrue();
        assertThat(consent.displayName()).isNull();
    }

    @Test
    void namedShareWithoutANameIsRejected() {
        try {
            SolutionSharingService.validated(new ShareSolutionRequest(false, "  "));
            assertThat(false).as("expected a bad request").isTrue();
        } catch (ApiException error) {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ── share ─────────────────────────────────────────────────────────────

    @Test
    void somebodyElsesSimulationIsNotFound() {
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(SIMULATION_ID, ACCOUNT_ID))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.share(SIMULATION_ID, ACCOUNT_ID,
                        new ShareSolutionRequest(true, null)))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    // The id belongs to somebody else: same answer as not existing.
                    assertThat(error.getMessage()).contains(String.valueOf(SIMULATION_ID));
                })
                .verify();

        verify(shares, never()).save(any());
    }

    @Test
    void unfinishedSimulationHasNothingToShareYet() {
        Simulation running = finishedSimulation();
        running.setStatus(SimulationStatus.IN_PROGRESS);
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(SIMULATION_ID, ACCOUNT_ID))
                .thenReturn(Mono.just(running));

        StepVerifier.create(service.share(SIMULATION_ID, ACCOUNT_ID,
                        new ShareSolutionRequest(true, null)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();

        verify(shares, never()).save(any());
    }

    @Test
    void shareFreezesTheSchoolSnapshotAndStaysAnonymous() {
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(SIMULATION_ID, ACCOUNT_ID))
                .thenReturn(Mono.just(finishedSimulation()));
        when(meService.getMe(ACCOUNT_ID)).thenReturn(Mono.just(meWithSchool(SCHOOL_ID)));
        when(shares.findBySimulationIdAndDeletedAtIsNull(SIMULATION_ID))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.share(SIMULATION_ID, ACCOUNT_ID,
                        new ShareSolutionRequest(null, "Ignored when anonymous")))
                .expectNextMatches(receipt -> receipt.simulationId().equals(SIMULATION_ID)
                        && Boolean.TRUE.equals(receipt.anonymous())
                        && receipt.displayName() == null)
                .verifyComplete();

        verify(shares).save(any(SolutionShare.class));
    }

    @Test
    void sharingTwiceRefreshesConsentInsteadOfDuplicating() {
        SolutionShare existing = existingShare(true, null);
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(SIMULATION_ID, ACCOUNT_ID))
                .thenReturn(Mono.just(finishedSimulation()));
        when(meService.getMe(ACCOUNT_ID)).thenReturn(Mono.just(meWithSchool(null)));
        when(shares.findBySimulationIdAndDeletedAtIsNull(SIMULATION_ID))
                .thenReturn(Mono.just(existing));

        StepVerifier.create(service.share(SIMULATION_ID, ACCOUNT_ID,
                        new ShareSolutionRequest(false, "Ana")))
                .expectNextMatches(receipt -> receipt.shareId().equals(77L)
                        && Boolean.FALSE.equals(receipt.anonymous())
                        && "Ana".equals(receipt.displayName()))
                .verifyComplete();
    }

    // ── unshare ───────────────────────────────────────────────────────────

    @Test
    void revokingWithoutConsentIsNotFound() {
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(SIMULATION_ID, ACCOUNT_ID))
                .thenReturn(Mono.just(finishedSimulation()));
        when(shares.findBySimulationIdAndDeletedAtIsNull(SIMULATION_ID))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.unshare(SIMULATION_ID, ACCOUNT_ID))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void revokeSoftDeletesTheShareAndKeepsTheGrade() {
        SolutionShare existing = existingShare(false, "Ana");
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(SIMULATION_ID, ACCOUNT_ID))
                .thenReturn(Mono.just(finishedSimulation()));
        when(shares.findBySimulationIdAndDeletedAtIsNull(SIMULATION_ID))
                .thenReturn(Mono.just(existing));

        StepVerifier.create(service.unshare(SIMULATION_ID, ACCOUNT_ID))
                .verifyComplete();

        // Revoking touches the consent only: the simulation itself is never saved here.
        verify(simulations, never()).save(any());
        verify(shares).save(eq(existing));
        assertThat(existing.getDeletedAt()).isNotNull();
    }

    // ── listSolutions ─────────────────────────────────────────────────────

    @Test
    void listingNeedsAPageAndAKnownSchool() {
        Statement statement = new Statement();
        statement.setId(STATEMENT_ID);
        when(community.readAccess(eq(STATEMENT_ID), eq(ACCOUNT_ID)))
                .thenReturn(Mono.just(statement));
        when(institutions.findByIdAndDeletedAtIsNull(4242L)).thenReturn(Mono.empty());

        StepVerifier.create(service.listSolutions(STATEMENT_ID, 4242L, 0, 20, ACCOUNT_ID))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        try {
            service.listSolutions(STATEMENT_ID, null, -1, 20, ACCOUNT_ID);
            assertThat(false).as("expected a bad request").isTrue();
        } catch (ApiException error) {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void listingShowsConsentedSharesAcrossSchoolsAndMasksTheAnonymous() {
        Statement statement = new Statement();
        statement.setId(STATEMENT_ID);
        when(community.readAccess(eq(STATEMENT_ID), eq(ACCOUNT_ID)))
                .thenReturn(Mono.just(statement));

        SolutionShare anonymous = existingShare(true, null);
        anonymous.setId(1L);
        anonymous.setSimulationId(50L);
        anonymous.setInstitutionId(SCHOOL_ID);
        SolutionShare named = existingShare(false, "Ana");
        named.setId(2L);
        named.setSimulationId(51L);
        named.setInstitutionId(null);
        when(shares.findAllByStatementIdAndDeletedAtIsNull(eq(STATEMENT_ID), any()))
                .thenReturn(Flux.just(anonymous, named));
        when(shares.countByStatementIdAndDeletedAtIsNull(STATEMENT_ID))
                .thenReturn(Mono.just(2L));

        Simulation first = finishedSimulation();
        first.setId(50L);
        first.setFinalScore(14.0);
        Simulation second = finishedSimulation();
        second.setId(51L);
        second.setFinalScore(18.0);
        when(simulations.findAllById(List.of(50L, 51L)))
                .thenReturn(Flux.just(first, second));

        Question question = new Question();
        question.setId(20L);
        question.setStatementId(STATEMENT_ID);
        question.setNumber(1);
        when(questions.findAllByStatementIdOrderedByOrderIndex(STATEMENT_ID))
                .thenReturn(Flux.just(question));

        SimulationAnswer picked = new SimulationAnswer();
        picked.setSimulationId(50L);
        picked.setQuestionId(20L);
        picked.setSelectedOptionId(31L);
        picked.setScoreObtained(5.0f);
        SimulationAnswer written = new SimulationAnswer();
        written.setSimulationId(51L);
        written.setQuestionId(20L);
        written.setAnswerText("Porque o denominador não se anula.");
        when(answers.findAllBySimulationIdInAndDeletedAtIsNull(List.of(50L, 51L)))
                .thenReturn(Flux.just(picked, written));

        QuestionOption option = new QuestionOption();
        option.setId(31L);
        option.setQuestionId(20L);
        option.setOptionLabel("B");
        when(options.findAllByQuestionIdInAndDeletedAtIsNull(List.of(20L)))
                .thenReturn(Flux.just(option));

        Institution school = new Institution();
        school.setId(SCHOOL_ID);
        school.setCode("ITEL");
        when(institutions.findAllById(List.of(SCHOOL_ID))).thenReturn(Flux.just(school));

        StepVerifier.create(service.listSolutions(STATEMENT_ID, null, 0, 20, ACCOUNT_ID))
                .expectNextMatches(page -> {
                    assertThat(page.totalElements()).isEqualTo(2);
                    assertThat(page.content()).hasSize(2);
                    SharedSolutionResponse firstRendered = page.content().get(0);
                    SharedSolutionResponse secondRendered = page.content().get(1);
                    return firstRendered.authorName() == null
                            && firstRendered.institution() != null
                            && "ITEL".equals(firstRendered.institution().code())
                            && firstRendered.answers().get(0).selectedLabel().equals("B")
                            && "Ana".equals(secondRendered.authorName())
                            && secondRendered.institution() == null
                            && secondRendered.answers().get(0).answerText()
                                    .contains("denominador");
                })
                .verifyComplete();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private static Simulation finishedSimulation() {
        Simulation simulation = new Simulation();
        simulation.setId(SIMULATION_ID);
        simulation.setAccountId(ACCOUNT_ID);
        simulation.setStatementId(STATEMENT_ID);
        simulation.setFinalScore(14.0);
        simulation.setTimeSpentSeconds(900);
        simulation.setStatus(SimulationStatus.FINISHED);
        return simulation;
    }

    private static SolutionShare existingShare(boolean anonymous, String displayName) {
        SolutionShare share = new SolutionShare();
        share.setId(77L);
        share.setSimulationId(SIMULATION_ID);
        share.setAccountId(ACCOUNT_ID);
        share.setStatementId(STATEMENT_ID);
        share.setAnonymous(anonymous);
        share.setDisplayName(displayName);
        return share;
    }

    private static MeResponse meWithSchool(Long schoolId) {
        MeResponse.SchoolInfo school = schoolId == null
                ? null
                : new MeResponse.SchoolInfo(schoolId, "ITEL", "Instituto de Telecomunicações");
        return new MeResponse(ACCOUNT_ID, "aluno1", "aluno1@kixi.ao", "Ana", "Manuel", null,
                List.of("STUDENT"), school, null, null, null);
    }
}
