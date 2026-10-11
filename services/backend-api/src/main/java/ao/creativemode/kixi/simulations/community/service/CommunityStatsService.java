package ao.creativemode.kixi.simulations.community.service;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.institutions.service.MeService;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.ExamRoomAccess;
import ao.creativemode.kixi.simulations.community.dto.CommunityStatsResponse;
import ao.creativemode.kixi.simulations.community.dto.CommunityStatsResponse.MineAnswer;
import ao.creativemode.kixi.simulations.community.dto.CommunityStatsResponse.MineSummary;
import ao.creativemode.kixi.simulations.community.dto.CommunityStatsResponse.QuestionStats;
import ao.creativemode.kixi.simulations.community.dto.CommunityStatsResponse.ScoreBucket;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import ao.creativemode.kixi.simulations.model.SimulationAnswerStatus;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * The community aggregates behind {@code GET /statements/{id}/community}
 * (issue #119): grade distribution, per-question hit rate, average time, and
 * the caller's own attempt for the "you vs. the average" comparison.
 *
 * <p>The population is finished simulations with a final grade
 * ({@code finalScore IS NOT NULL} and no answer still awaiting review, whose
 * grade is partial until the review lands) on a visible statement, excluding
 * deleted accounts. Per-question denominators only count answers that can
 * answer the question being asked: known outcomes for the hit rate, graded
 * answers for the average score. Pending review is "not yet known", never
 * "wrong".
 */
@Service
public class CommunityStatsService {

    static final int SCORE_BUCKETS = 5;

    private final StatementRepository statements;
    private final QuestionRepository questions;
    private final SimulationRepository simulations;
    private final SimulationAnswerRepository answers;
    private final MeService meService;
    private final AccountRepository accounts;
    private final ExamRoomAccess examRooms;

    public CommunityStatsService(
            StatementRepository statements,
            QuestionRepository questions,
            SimulationRepository simulations,
            SimulationAnswerRepository answers,
            MeService meService
    ) {
        this(statements, questions, simulations, answers, meService, null, null);
    }

    @Autowired
    public CommunityStatsService(
            StatementRepository statements,
            QuestionRepository questions,
            SimulationRepository simulations,
            SimulationAnswerRepository answers,
            MeService meService,
            AccountRepository accounts,
            ExamRoomAccess examRooms
    ) {
        this.statements = statements;
        this.questions = questions;
        this.simulations = simulations;
        this.answers = answers;
        this.meService = meService;
        this.accounts = accounts;
        this.examRooms = examRooms;
    }

    public Mono<CommunityStatsResponse> findCommunity(Long statementId, Long accountId) {
        return readAccess(statementId, accountId)
                .flatMap(statement -> requireNoOpenRoom(statementId).thenReturn(statement))
                .flatMap(statement -> Mono.zip(
                        questions.findAllByStatementIdOrderedByOrderIndex(statementId).collectList(),
                        simulations.findAllByStatementIdAndStatusAndDeletedAtIsNull(
                                        statementId, SimulationStatus.FINISHED)
                                .collectList())
                        .flatMap(tuple -> withAnswers(statement, tuple.getT1(), tuple.getT2(), accountId)));
    }

    /**
     * A visible statement the caller may read: visibility and soft-delete like
     * everywhere else, plus the catalog's institutional scope — a paper scoped
     * to another school reads as not found, the same answer as one that does
     * not exist. Accounts with no school (staff, not enrolled yet) see every
     * school, exactly like the catalog.
     */
    Mono<Statement> readAccess(Long statementId, Long accountId) {
        return statements.findByIdAndVisibleTrueAndDeletedAtIsNull(statementId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Statement not found: " + statementId)))
                .flatMap(statement -> statements.findEffectiveInstitutionId(statement.getId())
                        .flatMap(effective -> inScope(effective, accountId).thenReturn(statement))
                        // No effective school: a paper for every school.
                        .defaultIfEmpty(statement));
    }

    private Mono<Void> inScope(Long effectiveInstitutionId, Long accountId) {
        return meService.getMe(accountId).flatMap(me -> {
            if (me.school() == null || effectiveInstitutionId.equals(me.school().id())) {
                return Mono.empty();
            }
            return Mono.error(ApiException.notFound("Statement not found"));
        }).then();
    }

    /**
     * The exam-room answer-key rule, shared with the solutions listing: while a
     * room for this statement is not closed, community figures would leak the
     * key to students still sitting the paper (hit rates and scores reveal the
     * right options), so the routes answer 409 — the same status a result that
     * has not been earned yet gets. Null-safe like
     * {@code SimulationResultService}: without rooms wired in, everything is visible.
     */
    static Mono<Void> requireNoOpenRoom(ExamRoomAccess examRooms, Long statementId) {
        if (examRooms == null) {
            return Mono.empty();
        }
        Mono<Boolean> open = examRooms.hasNonClosedRoom(statementId);
        return (open == null ? Mono.just(false) : open)
                .defaultIfEmpty(false)
                .flatMap(hasOpen -> hasOpen
                        ? Mono.error(ApiException.conflict(
                                "The community is only available once the exam rooms for this "
                                        + "statement are closed"))
                        : Mono.empty());
    }

    private Mono<Void> requireNoOpenRoom(Long statementId) {
        return requireNoOpenRoom(examRooms, statementId);
    }

    private Mono<CommunityStatsResponse> withAnswers(
            Statement statement,
            List<Question> paper,
            List<Simulation> finished,
            Long accountId
    ) {
        return withoutDeletedAccounts(finished).flatMap(kept -> {
            List<Long> simulationIds = kept.stream().map(Simulation::getId).toList();
            Mono<List<SimulationAnswer>> allAnswers = simulationIds.isEmpty()
                    ? Mono.just(List.of())
                    : answers.findAllBySimulationIdInAndDeletedAtIsNull(simulationIds).collectList();
            Mono<Simulation> mine = simulations
                    .findFirstByStatementIdAndAccountIdAndStatusAndDeletedAtIsNullOrderByIdDesc(
                            statement.getId(), accountId, SimulationStatus.FINISHED)
                    .defaultIfEmpty(new Simulation());
            return Mono.zip(allAnswers, mine).flatMap(tuple -> {
                Simulation mineOrEmpty = tuple.getT2().getId() == null ? null : tuple.getT2();
                Mono<List<SimulationAnswer>> mineAnswers = mineOrEmpty == null
                        ? Mono.just(List.of())
                        : answers.findAllBySimulationIdAndDeletedAtIsNull(mineOrEmpty.getId()).collectList();
                return mineAnswers.map(mineList -> assemble(statement, paper, kept, tuple.getT1(),
                        mineOrEmpty, mineList));
            });
        });
    }

    /**
     * Grades and resolutions of deleted accounts stay out of the community:
     * leaving is leaving, everywhere. Without accounts wired in (unit tests
     * for the maths) there is nothing to check against.
     */
    private Mono<List<Simulation>> withoutDeletedAccounts(List<Simulation> finished) {
        if (accounts == null || finished.isEmpty()) {
            return Mono.just(finished);
        }
        List<Long> accountIds = finished.stream()
                .map(Simulation::getAccountId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        return accounts.findAllById(accountIds)
                .filter(account -> account.getDeletedAt() == null)
                .map(Account::getId)
                .collect(Collectors.toSet())
                .map(active -> finished.stream()
                        .filter(simulation -> active.contains(simulation.getAccountId()))
                        .toList());
    }

    /**
     * Pure assembly, kept out of the reactive chain so the maths is checkable
     * without a database.
     */
    static CommunityStatsResponse assemble(
            Statement statement,
            List<Question> paper,
            List<Simulation> finished,
            List<SimulationAnswer> allAnswers,
            Simulation mine,
            List<SimulationAnswer> mineAnswers
    ) {
        // Awaiting review means partially graded: finish() writes the sum of
        // what is corrected so far and grade() refreshes it later, so these
        // papers sit out the distribution until their grade is final instead
        // of dragging the mean down. Their decided answers still count below.
        Set<Long> pendingSimulations = allAnswers.stream()
                .filter(answer -> SimulationAnswerStatus.PENDING_REVIEW == answer.getReviewStatus())
                .map(SimulationAnswer::getSimulationId)
                .collect(Collectors.toSet());
        List<Simulation> graded = finished.stream()
                .filter(simulation -> simulation.getFinalScore() != null
                        && !pendingSimulations.contains(simulation.getId()))
                .toList();
        List<Double> scores = graded.stream()
                .map(Simulation::getFinalScore)
                .sorted()
                .toList();

        List<Integer> seconds = graded.stream()
                .map(Simulation::getTimeSpentSeconds)
                .filter(time -> time != null)
                .toList();

        Map<Long, List<SimulationAnswer>> answersByQuestion = allAnswers.stream()
                .filter(answer -> answer.getQuestionId() != null)
                .collect(Collectors.groupingBy(SimulationAnswer::getQuestionId));
        Map<Long, SimulationAnswer> mineByQuestion = mineAnswers.stream()
                .filter(answer -> answer.getQuestionId() != null)
                .collect(Collectors.toMap(SimulationAnswer::getQuestionId, Function.identity(),
                        (first, later) -> later));

        List<QuestionStats> questionStats = paper.stream()
                .map(question -> questionStats(question,
                        answersByQuestion.getOrDefault(question.getId(), List.of()),
                        mineByQuestion.get(question.getId())))
                .toList();

        MineSummary mineSummary = mine == null
                ? null
                : new MineSummary(mine.getId(), mine.getFinalScore());

        return new CommunityStatsResponse(
                statement.getId(),
                graded.size(),
                mean(scores),
                median(scores),
                buckets(statement.getTotalMaxScore(), scores),
                questionStats,
                meanInts(seconds),
                seconds.size(),
                mineSummary);
    }

    private static QuestionStats questionStats(
            Question question, List<SimulationAnswer> questionAnswers, SimulationAnswer mine
    ) {
        List<SimulationAnswer> decided = questionAnswers.stream()
                .filter(answer -> answer.getIsCorrect() != null)
                .toList();
        long correct = decided.stream().filter(answer -> Boolean.TRUE.equals(answer.getIsCorrect())).count();
        Double pctCorrect = decided.isEmpty() ? null : 100.0 * correct / decided.size();

        List<Float> gradedScores = questionAnswers.stream()
                .map(SimulationAnswer::getScoreObtained)
                .filter(score -> score != null)
                .toList();
        Double avgScore = gradedScores.isEmpty() ? null
                : gradedScores.stream().mapToDouble(Float::doubleValue).average().orElse(0.0);

        MineAnswer mineAnswer = mine == null ? null
                : new MineAnswer(mine.getScoreObtained(), mine.getIsCorrect());
        return new QuestionStats(question.getId(), question.getNumber(), question.getMaxScore(),
                decided.size(), correct, pctCorrect, avgScore, mineAnswer);
    }

    /**
     * Five buckets across the paper's scale. A missing or non-positive
     * {@code totalMaxScore} falls back to the best grade observed, so papers
     * without a scale still render instead of dividing by zero; with no grades
     * at all the buckets come back empty but labelled.
     */
    static List<ScoreBucket> buckets(Double totalMaxScore, List<Double> scores) {
        double scale = totalMaxScore != null && totalMaxScore > 0
                ? totalMaxScore
                : scores.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        if (scale <= 0) {
            return List.of(new ScoreBucket(0, 0.0, 0.0, scores.size()));
        }
        List<Long> counts = new ArrayList<>(Collections.nCopies(SCORE_BUCKETS, 0L));
        for (Double score : scores) {
            int index = (int) Math.min(SCORE_BUCKETS - 1,
                    Math.floor(score / scale * SCORE_BUCKETS));
            counts.set(index, counts.get(index) + 1);
        }
        List<ScoreBucket> result = new ArrayList<>(SCORE_BUCKETS);
        for (int index = 0; index < SCORE_BUCKETS; index++) {
            result.add(new ScoreBucket(index,
                    scale * index / SCORE_BUCKETS, scale * (index + 1) / SCORE_BUCKETS,
                    counts.get(index)));
        }
        return result;
    }

    private static Double mean(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static Double meanInts(List<Integer> values) {
        if (values.isEmpty()) {
            return null;
        }
        return values.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }

    private static Double median(List<Double> sorted) {
        if (sorted.isEmpty()) {
            return null;
        }
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }
}
