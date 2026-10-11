package ao.creativemode.kixi.simulations.community.service;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.institutions.service.MeService;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.community.dto.CommunityStatsResponse;
import ao.creativemode.kixi.simulations.community.dto.CommunityStatsResponse.MineAnswer;
import ao.creativemode.kixi.simulations.community.dto.CommunityStatsResponse.MineSummary;
import ao.creativemode.kixi.simulations.community.dto.CommunityStatsResponse.QuestionStats;
import ao.creativemode.kixi.simulations.community.dto.CommunityStatsResponse.ScoreBucket;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * The community aggregates behind {@code GET /statements/{id}/community}
 * (issue #119): grade distribution, per-question hit rate, average time, and
 * the caller's own attempt for the "you vs. the average" comparison.
 *
 * <p>The population is finished simulations with a grade
 * ({@code finalScore IS NOT NULL}) on a visible statement. Per-question
 * denominators only count answers that can answer the question being asked:
 * known outcomes for the hit rate, graded answers for the average score.
 * Pending review is "not yet known", never "wrong".
 */
@Service
public class CommunityStatsService {

    static final int SCORE_BUCKETS = 5;

    private final StatementRepository statements;
    private final QuestionRepository questions;
    private final SimulationRepository simulations;
    private final SimulationAnswerRepository answers;
    private final MeService meService;

    public CommunityStatsService(
            StatementRepository statements,
            QuestionRepository questions,
            SimulationRepository simulations,
            SimulationAnswerRepository answers,
            MeService meService
    ) {
        this.statements = statements;
        this.questions = questions;
        this.simulations = simulations;
        this.answers = answers;
        this.meService = meService;
    }

    public Mono<CommunityStatsResponse> findCommunity(Long statementId, Long accountId) {
        return readAccess(statementId, accountId)
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

    private Mono<CommunityStatsResponse> withAnswers(
            Statement statement,
            List<Question> paper,
            List<Simulation> finished,
            Long accountId
    ) {
        List<Long> simulationIds = finished.stream().map(Simulation::getId).toList();
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
            return mineAnswers.map(mineList -> assemble(statement, paper, finished, tuple.getT1(),
                    mineOrEmpty, mineList));
        });
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
        List<Simulation> graded = finished.stream()
                .filter(simulation -> simulation.getFinalScore() != null)
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
