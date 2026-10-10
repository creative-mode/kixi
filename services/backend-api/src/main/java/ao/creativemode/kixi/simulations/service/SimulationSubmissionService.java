package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.TeachingAssignmentAuthorizer;
import ao.creativemode.kixi.simulations.dto.simulationanswer.SimulationAnswerResponse;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import ao.creativemode.kixi.simulations.model.SimulationAnswerStatus;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class SimulationSubmissionService {

    private final SimulationRepository simulations;
    private final SimulationAnswerRepository answers;
    private final QuestionRepository questions;
    private final QuestionOptionRepository options;
    private final StatementRepository statements;
    private final TeachingAssignmentAuthorizer teachingAuthorizer;

    public SimulationSubmissionService(SimulationRepository simulations,
            SimulationAnswerRepository answers, QuestionRepository questions,
            QuestionOptionRepository options, StatementRepository statements,
            TeachingAssignmentAuthorizer teachingAuthorizer) {
        this.simulations = simulations;
        this.answers = answers;
        this.questions = questions;
        this.options = options;
        this.statements = statements;
        this.teachingAuthorizer = teachingAuthorizer;
    }

    @Transactional
    public Mono<Simulation> submit(Long id, Long accountId, boolean staff) {
        Mono<Simulation> lookup = staff
                ? simulations.findByIdAndDeletedAtIsNull(id)
                : simulations.findByIdAndAccountIdAndDeletedAtIsNull(id, accountId);
        return lookup.switchIfEmpty(Mono.error(ApiException.notFound("Simulation not found: " + id)))
                .flatMap(simulation -> {
                    if (simulation.getStatus() != SimulationStatus.IN_PROGRESS) {
                        return Mono.error(ApiException.conflict("Simulation has already been submitted"));
                    }
                    // The conditional update serializes simultaneous submissions; the transaction
                    // holds the row lock until answer scores and the final score are saved.
                    return simulations.claimSubmission(id).flatMap(claimed -> {
                        if (claimed != 1) {
                            return Mono.error(ApiException.conflict("Simulation has already been submitted"));
                        }
                        simulation.setStatus(SimulationStatus.FINISHED);
                        return correct(simulation);
                    });
                });
    }

    private Mono<Simulation> correct(Simulation simulation) {
        return Mono.zip(
                questions.findAllByStatementIdAndDeletedAtIsNull(simulation.getStatementId()).collectList(),
                answers.findAllBySimulationIdInAndDeletedAtIsNull(List.of(simulation.getId())).collectList())
            .flatMap(tuple -> correctQuestions(simulation, tuple.getT1(), tuple.getT2()));
    }

    private Mono<Simulation> correctQuestions(Simulation simulation, List<Question> questions,
            List<SimulationAnswer> existingAnswers) {
        if (questions.isEmpty()) {
            return finish(simulation, List.of());
        }
        return options.findAllByQuestionIdInAndDeletedAtIsNull(questions.stream().map(Question::getId).toList())
            .collectList()
            .flatMap(allOptions -> {
                List<SimulationAnswer> corrected = questions.stream().map(question -> {
                    SimulationAnswer answer = existingAnswers.stream()
                            .filter(candidate -> candidate.getQuestionId().equals(question.getId()))
                            .findFirst().orElseGet(() -> {
                                SimulationAnswer blank = new SimulationAnswer();
                                blank.setSimulationId(simulation.getId());
                                blank.setQuestionId(question.getId());
                                return blank;
                            });
                    List<QuestionOption> questionOptions = allOptions.stream()
                            .filter(option -> option.getQuestionId().equals(question.getId())).toList();
                    // The approval gate defines objective questions by the existence of
                    // active alternatives; question_type may be "open" or unset.
                    boolean objective = !questionOptions.isEmpty();
                    if (!objective) {
                        answer.setScoreObtained(null);
                        answer.setIsCorrect(null);
                        answer.setReviewStatus(SimulationAnswerStatus.PENDING_REVIEW);
                    } else {
                        boolean isCorrect = answer.getSelectedOptionId() != null && questionOptions.stream()
                                .anyMatch(option -> option.getId().equals(answer.getSelectedOptionId())
                                        && Boolean.TRUE.equals(option.getIsCorrect()));
                        answer.setIsCorrect(isCorrect);
                        answer.setScoreObtained(isCorrect ? (question.getMaxScore() == null ? 0f
                                : question.getMaxScore().floatValue()) : 0f);
                        answer.setReviewStatus(SimulationAnswerStatus.AUTO_GRADED);
                    }
                    answer.setUpdatedAt(LocalDateTime.now());
                    return answer;
                }).toList();
                return Flux.fromIterable(corrected).concatMap(answers::save).collectList()
                    .then(finish(simulation, corrected));
            });
    }

    private Mono<Simulation> finish(Simulation simulation, List<SimulationAnswer> corrected) {
        double score = corrected.stream().filter(answer -> answer.getScoreObtained() != null)
                .mapToDouble(SimulationAnswer::getScoreObtained).sum();
        LocalDateTime finishedAt = LocalDateTime.now();
        simulation.setFinalScore(score);
        simulation.setFinishedAt(finishedAt);
        simulation.setTimeSpentSeconds((int) Math.max(0,
                Duration.between(simulation.getStartedAt(), finishedAt).getSeconds()));
        simulation.setStatus(SimulationStatus.FINISHED);
        simulation.setUpdatedAt(finishedAt);
        return simulations.save(simulation);
    }

    @Transactional
    public Mono<SimulationAnswerResponse> grade(Long answerId, Double score,
            Long accountId, boolean admin) {
        return answers.findByIdAndDeletedAtIsNull(answerId)
            .switchIfEmpty(Mono.error(ApiException.notFound("Simulation answer not found")))
            .flatMap(answer -> questions.findByIdAndDeletedAtIsNull(answer.getQuestionId())
                .switchIfEmpty(Mono.error(ApiException.notFound("Question not found")))
                .flatMap(question -> authorizeGrading(question, accountId, admin).then(Mono.defer(() -> {
                    if (answer.getReviewStatus() != SimulationAnswerStatus.PENDING_REVIEW) {
                        return Mono.error(ApiException.conflict("Only answers pending review can be graded"));
                    }
                    if (question.getMaxScore() != null && score > question.getMaxScore()) {
                        return Mono.error(ApiException.badRequest("Score cannot exceed the question maximum"));
                    }
                    answer.setScoreObtained(score.floatValue());
                    answer.setIsCorrect(question.getMaxScore() != null && score >= question.getMaxScore());
                    answer.setReviewStatus(SimulationAnswerStatus.GRADED);
                    answer.setUpdatedAt(LocalDateTime.now());
                    return answers.save(answer).flatMap(saved -> refreshFinalScore(saved.getSimulationId())
                            .thenReturn(toResponse(saved)));
                }))));
    }

    private Mono<Void> authorizeGrading(Question question, Long accountId, boolean admin) {
        if (admin) {
            return Mono.empty();
        }
        return statements.findByIdAndDeletedAtIsNull(question.getStatementId())
            .switchIfEmpty(Mono.error(ApiException.notFound("Statement not found")))
            .flatMap(statement -> {
                if (statement.getClassId() != null) {
                    return teachingAuthorizer.requireAssignedTo(
                        accountId, false, statement.getClassId(), statement.getSubjectId());
                }
                if (statement.getInstitutionId() != null) {
                    return teachingAuthorizer.requireCanAuthor(
                        accountId, false, statement.getInstitutionId(), statement.getSubjectId());
                }
                return Mono.error(ApiException.forbidden(
                    "Only administrators can grade statements without a class or institution"));
            });
    }

    private Mono<Void> refreshFinalScore(Long simulationId) {
        return Mono.zip(simulations.findByIdAndDeletedAtIsNull(simulationId),
                answers.findAllBySimulationIdInAndDeletedAtIsNull(List.of(simulationId)).collectList())
            .flatMap(tuple -> {
                Simulation simulation = tuple.getT1();
                if (simulation.getStatus() != SimulationStatus.FINISHED) {
                    return Mono.empty();
                }
                double score = tuple.getT2().stream().filter(answer -> answer.getScoreObtained() != null)
                        .mapToDouble(SimulationAnswer::getScoreObtained).sum();
                simulation.setFinalScore(score);
                return simulations.save(simulation).then();
            });
    }

    private SimulationAnswerResponse toResponse(SimulationAnswer answer) {
        return new SimulationAnswerResponse(answer.getId(), answer.getSimulationId(), answer.getQuestionId(),
                answer.getSelectedOptionId(), answer.getAnswerText(), answer.getScoreObtained(), answer.getIsCorrect(),
                answer.getReviewStatus(), answer.getAnsweredAt(), answer.getCreatedAt(), answer.getUpdatedAt(),
                answer.getDeletedAt());
    }
}
