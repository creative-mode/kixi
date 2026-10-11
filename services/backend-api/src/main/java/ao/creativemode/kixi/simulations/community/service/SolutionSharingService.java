package ao.creativemode.kixi.simulations.community.service;

import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.service.MeService;
import ao.creativemode.kixi.shared.dto.PageResponse;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.community.dto.ShareSolutionRequest;
import ao.creativemode.kixi.simulations.community.dto.SharedSolutionResponse;
import ao.creativemode.kixi.simulations.community.dto.SharedSolutionResponse.InstitutionRef;
import ao.creativemode.kixi.simulations.community.dto.SharedSolutionResponse.SharedAnswer;
import ao.creativemode.kixi.simulations.community.dto.SolutionShareResponse;
import ao.creativemode.kixi.simulations.community.model.SolutionShare;
import ao.creativemode.kixi.simulations.community.repository.SolutionShareRepository;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * The opt-in side of social learning (issue #119): a student chooses to show
 * one finished simulation, anonymously or under a name, and may take it back.
 *
 * <p>Only the owner shares, only a finished simulation, and only through an
 * explicit call — listing never reads bare simulations, only active consents.
 * Staff cannot share on a student's behalf.
 */
@Service
public class SolutionSharingService {

    static final int MAX_DISPLAY_NAME = 120;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private final SolutionShareRepository shares;
    private final SimulationRepository simulations;
    private final SimulationAnswerRepository answers;
    private final QuestionRepository questions;
    private final QuestionOptionRepository options;
    private final InstitutionRepository institutions;
    private final MeService meService;
    private final CommunityStatsService community;

    public SolutionSharingService(
            SolutionShareRepository shares,
            SimulationRepository simulations,
            SimulationAnswerRepository answers,
            QuestionRepository questions,
            QuestionOptionRepository options,
            InstitutionRepository institutions,
            MeService meService,
            CommunityStatsService community
    ) {
        this.shares = shares;
        this.simulations = simulations;
        this.answers = answers;
        this.questions = questions;
        this.options = options;
        this.institutions = institutions;
        this.meService = meService;
        this.community = community;
    }

    /**
     * Opt in: mark the caller's finished simulation as shared. Sharing twice
     * refreshes the consent instead of duplicating it.
     */
    public Mono<SolutionShareResponse> share(Long simulationId, Long accountId, ShareSolutionRequest request) {
        return ownedFinished(simulationId, accountId)
                .flatMap(simulation -> snapshotInstitution(accountId)
                        .flatMap(institutionId -> upsert(simulation, validated(request), institutionId)));
    }

    /** Opt out: the share disappears from the community; the grade is untouched. */
    public Mono<Void> unshare(Long simulationId, Long accountId) {
        return ownedFinished(simulationId, accountId)
                .flatMap(simulation -> shares.findBySimulationIdAndDeletedAtIsNull(simulation.getId())
                        .switchIfEmpty(Mono.error(ApiException.notFound(
                                "No active share for simulation: " + simulationId))))
                .flatMap(share -> {
                    share.markAsDeleted();
                    return shares.save(share);
                })
                .then();
    }

    /**
     * Shared resolutions for one statement, newest first. An
     * {@code institutionId} narrows the listing to that school (which must
     * exist); without it the listing spans schools, the point of the issue.
     */
    public Mono<PageResponse<SharedSolutionResponse>> listSolutions(
            Long statementId, Long institutionId, int page, int size, Long accountId
    ) {
        validatePaging(page, size);
        return community.readAccess(statementId, accountId)
                .flatMap(statement -> institutionExists(institutionId)
                        .then(Mono.defer(() -> {
                            PageRequest paging = PageRequest.of(page, size,
                                    Sort.by(Sort.Direction.DESC, "created_at"));
                            Mono<List<SolutionShare>> pageContent = institutionId == null
                                    ? shares.findAllByStatementIdAndDeletedAtIsNull(
                                            statement.getId(), paging).collectList()
                                    : shares.findAllByStatementIdAndInstitutionIdAndDeletedAtIsNull(
                                            statement.getId(), institutionId, paging).collectList();
                            Mono<Long> total = institutionId == null
                                    ? shares.countByStatementIdAndDeletedAtIsNull(statement.getId())
                                    : shares.countByStatementIdAndInstitutionIdAndDeletedAtIsNull(
                                            statement.getId(), institutionId);
                            return Mono.zip(pageContent, total);
                        }))
                        .flatMap(tuple -> render(statement.getId(), tuple.getT1())
                                .map(content -> PageResponse.of(content, page, size, tuple.getT2()))));
    }

    /**
     * The caller's simulation and nobody else's — anyone else's id reads as
     * not found — and only once it is finished. There is no result to share
     * while the paper is still running.
     */
    private Mono<Simulation> ownedFinished(Long simulationId, Long accountId) {
        return simulations.findByIdAndAccountIdAndDeletedAtIsNull(simulationId, accountId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Simulation not found: " + simulationId)))
                .flatMap(simulation -> SimulationStatus.FINISHED == simulation.getStatus()
                        ? Mono.just(simulation)
                        : Mono.error(ApiException.conflict(
                                "Only a finished simulation can be shared; this one is "
                                        + simulation.getStatus())));
    }

    /**
     * The school snapshot frozen onto the share. Empty when the student has no
     * school to freeze — the share then simply carries none.
     */
    private Mono<Optional<Long>> snapshotInstitution(Long accountId) {
        return meService.getMe(accountId)
                .map(me -> Optional.ofNullable(
                        me.school() == null ? null : me.school().id()));
    }

    private Mono<SolutionShareResponse> upsert(
            Simulation simulation, Consent consent, Optional<Long> institutionId
    ) {
        return shares.findBySimulationIdAndDeletedAtIsNull(simulation.getId())
                .defaultIfEmpty(newShare(simulation, institutionId))
                .flatMap(share -> {
                    share.setAnonymous(consent.anonymous());
                    share.setDisplayName(consent.displayName());
                    share.setConsentAt(LocalDateTime.now());
                    share.setUpdatedAt(LocalDateTime.now());
                    return shares.save(share);
                })
                .map(share -> new SolutionShareResponse(share.getId(), share.getSimulationId(),
                        share.getAnonymous(), share.getDisplayName(), share.getConsentAt()));
    }

    private static SolutionShare newShare(Simulation simulation, Optional<Long> institutionId) {
        SolutionShare share = new SolutionShare();
        share.setSimulationId(simulation.getId());
        share.setAccountId(simulation.getAccountId());
        share.setStatementId(simulation.getStatementId());
        share.setInstitutionId(institutionId.orElse(null));
        return share;
    }

    /**
     * Anonymous stays anonymous; a named share must say which name. The name
     * comes only from this request — the profile is never read — so omitting
     * it cannot leak the real one.
     */
    static Consent validated(ShareSolutionRequest request) {
        boolean anonymous = request == null || request.anonymous() == null || request.anonymous();
        String displayName = request == null ? null : trimmed(request.displayName());
        if (!anonymous) {
            if (displayName == null) {
                throw ApiException.badRequest(
                        "A display name is required to share under a name (or share anonymously)");
            }
            if (displayName.length() > MAX_DISPLAY_NAME) {
                throw ApiException.badRequest(
                        "Display name cannot exceed " + MAX_DISPLAY_NAME + " characters");
            }
            return new Consent(false, displayName);
        }
        return new Consent(true, null);
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Mono<Void> institutionExists(Long institutionId) {
        if (institutionId == null) {
            return Mono.empty();
        }
        return institutions.findByIdAndDeletedAtIsNull(institutionId)
                .switchIfEmpty(Mono.error(ApiException.notFound(
                        "Institution not found: " + institutionId)))
                .then();
    }

    private static void validatePaging(int page, int size) {
        if (page < 0) {
            throw ApiException.badRequest("Page cannot be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("Size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    /**
     * One batched read per table, then assembly in memory — the same shape as
     * {@code SimulationResultService}, minus the answer key, which never
     * leaves the owner's own result route.
     */
    private Mono<List<SharedSolutionResponse>> render(Long statementId, List<SolutionShare> page) {
        if (page.isEmpty()) {
            return Mono.just(List.of());
        }
        List<Long> simulationIds = page.stream().map(SolutionShare::getSimulationId).toList();
        return Mono.zip(
                        simulations.findAllById(simulationIds).collectList(),
                        answers.findAllBySimulationIdInAndDeletedAtIsNull(simulationIds).collectList(),
                        questions.findAllByStatementIdOrderedByOrderIndex(statementId).collectList())
                .flatMap(tuple -> {
                    Map<Long, Simulation> simulationById = tuple.getT1().stream()
                            .filter(simulation -> simulation.getDeletedAt() == null
                                    && SimulationStatus.FINISHED == simulation.getStatus())
                            .collect(Collectors.toMap(Simulation::getId, Function.identity(),
                                    (first, later) -> later));
                    Map<Long, List<SimulationAnswer>> answersBySimulation = tuple.getT2().stream()
                            .filter(answer -> simulationById.containsKey(answer.getSimulationId()))
                            .collect(Collectors.groupingBy(SimulationAnswer::getSimulationId));
                    List<Long> questionIds = tuple.getT3().stream()
                            .map(ao.creativemode.kixi.exams.model.Question::getId)
                            .toList();
                    Mono<Map<Long, String>> labelByOptionId = questionIds.isEmpty()
                            ? Mono.just(Map.of())
                            : options.findAllByQuestionIdInAndDeletedAtIsNull(questionIds)
                                    .collectMap(
                                            ao.creativemode.kixi.exams.model.QuestionOption::getId,
                                            ao.creativemode.kixi.exams.model.QuestionOption::getOptionLabel);
                    List<Long> institutionIds = page.stream()
                            .map(SolutionShare::getInstitutionId)
                            .filter(id -> id != null)
                            .distinct()
                            .toList();
                    Mono<Map<Long, String>> codeByInstitutionId = institutionIds.isEmpty()
                            ? Mono.just(Map.of())
                            : institutions.findAllById(institutionIds)
                                    .filter(institution -> institution.getDeletedAt() == null)
                                    .collectMap(
                                            ao.creativemode.kixi.institutions.model.Institution::getId,
                                            ao.creativemode.kixi.institutions.model.Institution::getCode);
                    return Mono.zip(labelByOptionId, codeByInstitutionId)
                            .map(maps -> page.stream()
                                    .filter(share -> simulationById.containsKey(share.getSimulationId()))
                                    .map(share -> renderShare(share,
                                            simulationById.get(share.getSimulationId()),
                                            answersBySimulation.getOrDefault(
                                                    share.getSimulationId(), List.of()),
                                            tuple.getT3(), maps.getT1(), maps.getT2()))
                                    .toList());
                });
    }

    private static SharedSolutionResponse renderShare(
            SolutionShare share,
            Simulation simulation,
            List<SimulationAnswer> simulationAnswers,
            List<ao.creativemode.kixi.exams.model.Question> paper,
            Map<Long, String> labelByOptionId,
            Map<Long, String> codeByInstitutionId
    ) {
        Map<Long, SimulationAnswer> answerByQuestion = simulationAnswers.stream()
                .filter(answer -> answer.getQuestionId() != null)
                .collect(Collectors.toMap(SimulationAnswer::getQuestionId, Function.identity(),
                        (first, later) -> later));
        List<SharedAnswer> rendered = paper.stream()
                .map(question -> {
                    SimulationAnswer answer = answerByQuestion.get(question.getId());
                    String label = answer == null || answer.getSelectedOptionId() == null
                            ? null
                            : labelByOptionId.get(answer.getSelectedOptionId());
                    return new SharedAnswer(question.getId(), question.getNumber(), label,
                            answer == null ? null : answer.getAnswerText(),
                            answer == null ? null : answer.getScoreObtained());
                })
                // Unanswered questions say nothing about the author; skip them.
                .filter(answer -> answer.selectedLabel() != null || answer.answerText() != null)
                .sorted(Comparator.comparing(SharedAnswer::number,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
        InstitutionRef institution = share.getInstitutionId() == null ? null
                : new InstitutionRef(share.getInstitutionId(),
                        codeByInstitutionId.get(share.getInstitutionId()));
        return new SharedSolutionResponse(share.getId(), institution,
                Boolean.TRUE.equals(share.getAnonymous()) ? null : share.getDisplayName(),
                simulation.getFinalScore(), simulation.getTimeSpentSeconds(), rendered);
    }

    record Consent(boolean anonymous, String displayName) { }
}
