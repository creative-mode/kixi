package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.ExamRoomAccess;
import ao.creativemode.kixi.simulations.dto.simulationresult.SimulationResultResponse;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

/**
 * The result of a finished simulation, and the only place a student is shown
 * which option was right.
 *
 * <p>Until now the answer key reached a student through
 * {@code GET /statements/{id}/full}, and #106 took it out of there. This is where
 * it went instead: a simulation that has not finished has no result, so there is
 * nothing to read, and the answers appear only once the paper is done.
 *
 * <p>The key is read from the statement the simulation points at, not copied onto
 * the simulation. A teacher who corrects a statement in between corrects what the
 * student is shown, which is the same paper they sat.
 */
@Service
public class SimulationResultService {

    private final SimulationRepository simulations;
    private final SimulationAnswerRepository answers;
    private final QuestionRepository questions;
    private final QuestionOptionRepository options;
    private final ExamRoomAccess examRooms;

    public SimulationResultService(
        SimulationRepository simulations,
        SimulationAnswerRepository answers,
        QuestionRepository questions,
        QuestionOptionRepository options
    ) {
        this(simulations, answers, questions, options, null);
    }

    @Autowired
    public SimulationResultService(
        SimulationRepository simulations,
        SimulationAnswerRepository answers,
        QuestionRepository questions,
        QuestionOptionRepository options,
        ExamRoomAccess examRooms
    ) {
        this.simulations = simulations;
        this.answers = answers;
        this.questions = questions;
        this.options = options;
        this.examRooms = examRooms;
    }

    /**
     * The result of a simulation, for whoever may see it.
     *
     * <p>{@code staff} decides ownership, not content: a teacher or administrator
     * reaches any simulation, anyone else only their own. The content is the same
     * either way, because it is a student's own corrected paper — and a teacher who
     * wants the key before the student is done already has it on
     * {@code /statements/{id}/full}, so withholding it here would only make the
     * route harder to reason about.
     */
    public Mono<SimulationResultResponse> findResult(Long simulationId, Long accountId, boolean staff) {
        return Mono.defer(() -> staff
                ? simulations.findByIdAndDeletedAtIsNull(simulationId)
                : simulations.findByIdAndAccountIdAndDeletedAtIsNull(simulationId, accountId))
            // A simulation that belongs to somebody else is not found, the same
            // answer as one that does not exist.
            .switchIfEmpty(Mono.error(ApiException.notFound(
                "Simulation not found: " + simulationId)))
            .flatMap(this::requireFinished)
            .flatMap(simulation -> answerKeyVisible(simulation)
                .flatMap(visible -> render(simulation, visible)));
    }

    /**
     * A result only exists once the simulation is over.
     *
     * <p>Not a 403: the caller is allowed to have a result, they have not earned
     * it yet, and saying so in a conflict leaves the door open for them to come
     * back and read it when they have.
     */
    private Mono<Simulation> requireFinished(Simulation simulation) {
        if (SimulationStatus.FINISHED == simulation.getStatus()) {
            return Mono.just(simulation);
        }
        return Mono.error(ApiException.conflict(
            "The result is only available once the simulation is finished; this one is "
                + simulation.getStatus()));
    }

    private Mono<SimulationResultResponse> render(Simulation simulation, boolean answerKeyVisible) {
        return Mono.zip(
            questions.findAllByStatementIdAndDeletedAtIsNull(simulation.getStatementId())
                .collectList(),
            answers.findAllBySimulationIdInAndDeletedAtIsNull(List.of(simulation.getId()))
                .collectList()
        )
            .flatMap(tuple -> withOptions(simulation, tuple.getT1(), tuple.getT2(), answerKeyVisible));
    }

    /**
     * One query for the options of the whole paper.
     *
     * <p>Fetching them question by question would be a round-trip each, and the
     * question ids are already in hand. An empty paper asks for nothing: an
     * {@code IN ()} with no ids is a syntax error in SQL, not an empty result.
     */
    private Mono<SimulationResultResponse> withOptions(
        Simulation simulation,
        List<Question> questions,
        List<SimulationAnswer> answers,
        boolean answerKeyVisible
    ) {
        if (questions.isEmpty()) {
            return Mono.just(build(simulation, questions, answers, List.of(), answerKeyVisible));
        }
        List<Long> questionIds = questions.stream().map(Question::getId).toList();
        return options.findAllByQuestionIdInAndDeletedAtIsNull(questionIds)
            .collectList()
            .map(allOptions -> build(simulation, questions, answers, allOptions, answerKeyVisible));
    }

    private SimulationResultResponse build(
        Simulation simulation,
        List<Question> questions,
        List<SimulationAnswer> answers,
        List<QuestionOption> allOptions,
        boolean answerKeyVisible
    ) {
        Map<Long, List<QuestionOption>> optionsByQuestion = allOptions.stream()
            .collect(Collectors.groupingBy(QuestionOption::getQuestionId));

        Map<Long, SimulationAnswer> answerByQuestion = answers.stream()
            .collect(Collectors.toMap(
                SimulationAnswer::getQuestionId,
                Function.identity(),
                (first, later) -> later));

        List<SimulationResultResponse.QuestionResult> rendered = questions.stream()
            .map(question -> renderQuestion(
                question,
                optionsByQuestion.getOrDefault(question.getId(), List.of()),
                answerByQuestion.get(question.getId()), answerKeyVisible))
            .toList();

        return new SimulationResultResponse(
            simulation.getId(),
            simulation.getStatus(),
            simulation.getFinalScore(),
            (int) rendered.stream().filter(question -> Boolean.TRUE.equals(question.isCorrect())).count(),
            rendered.size(),
            (int) rendered.stream().filter(question -> question.reviewStatus()
                    == ao.creativemode.kixi.simulations.model.SimulationAnswerStatus.PENDING_REVIEW).count(),
            simulation.getTimeSpentSeconds(),
            simulation.getFinishedAt(),
            rendered
        );
    }

    private SimulationResultResponse.QuestionResult renderQuestion(
        Question question,
        List<QuestionOption> questionOptions,
        SimulationAnswer answer,
        boolean answerKeyVisible
    ) {
        List<SimulationResultResponse.OptionResult> optionResults = questionOptions.stream()
            .map(option -> new SimulationResultResponse.OptionResult(
                option.getId(),
                option.getOptionLabel(),
                option.getOptionText(),
                answerKeyVisible ? option.getIsCorrect() : null))
            .toList();

        // Null when no option is marked correct. The approval gate asks for at
        // least one and only for questions that have options, so an open question
        // reaches here with none and its model answer stands on its own.
        Long correctOptionId = optionResults.stream()
            .filter(option -> Boolean.TRUE.equals(option.isCorrect()))
            .map(SimulationResultResponse.OptionResult::id)
            .findFirst()
            .orElse(null);

        return new SimulationResultResponse.QuestionResult(
            question.getId(),
            question.getNumber(),
            question.getText(),
            question.getQuestionType(),
            question.getMaxScore(),
            answerKeyVisible ? question.getModelAnswer() : null,
            answerKeyVisible ? correctOptionId : null,
            answer == null ? null : answer.getSelectedOptionId(),
            answer == null ? null : answer.getAnswerText(),
            answer == null ? null : answer.getScoreObtained(),
            answerKeyVisible && answer != null ? answer.getIsCorrect() : null,
            answer == null ? null : answer.getReviewStatus(),
            optionResults
        );
    }

    private Mono<Boolean> answerKeyVisible(Simulation simulation) {
        if (examRooms == null || simulation.getExamRoomId() == null) {
            return Mono.just(true);
        }
        return examRooms.answerKeyVisible(simulation.getExamRoomId()).defaultIfEmpty(false);
    }
}
