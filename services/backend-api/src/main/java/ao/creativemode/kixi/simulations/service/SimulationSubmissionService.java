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
import ao.creativemode.kixi.shared.service.ExamRoomAccess;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final SimulationDeadlineService deadlineService;
    private final Clock clock;
    private final ExamRoomAccess examRooms;

    public SimulationSubmissionService(SimulationRepository simulations,
            SimulationAnswerRepository answers, QuestionRepository questions,
            QuestionOptionRepository options, StatementRepository statements,
            TeachingAssignmentAuthorizer teachingAuthorizer) {
        this(simulations, answers, questions, options, statements, teachingAuthorizer, null,
                Clock.systemDefaultZone(), null);
    }

    public SimulationSubmissionService(SimulationRepository simulations,
            SimulationAnswerRepository answers, QuestionRepository questions,
            QuestionOptionRepository options, StatementRepository statements,
            TeachingAssignmentAuthorizer teachingAuthorizer,
            SimulationDeadlineService deadlineService) {
        this(simulations, answers, questions, options, statements, teachingAuthorizer, deadlineService,
                Clock.systemDefaultZone(), null);
    }

    public SimulationSubmissionService(SimulationRepository simulations,
            SimulationAnswerRepository answers, QuestionRepository questions,
            QuestionOptionRepository options, StatementRepository statements,
            TeachingAssignmentAuthorizer teachingAuthorizer,
            SimulationDeadlineService deadlineService, Clock clock) {
        this(simulations, answers, questions, options, statements, teachingAuthorizer, deadlineService, clock, null);
    }

    @Autowired
    public SimulationSubmissionService(SimulationRepository simulations,
            SimulationAnswerRepository answers, QuestionRepository questions,
            QuestionOptionRepository options, StatementRepository statements,
            TeachingAssignmentAuthorizer teachingAuthorizer,
            SimulationDeadlineService deadlineService, Clock clock,
            ExamRoomAccess examRooms) {
        this.simulations = simulations;
        this.answers = answers;
        this.questions = questions;
        this.options = options;
        this.statements = statements;
        this.teachingAuthorizer = teachingAuthorizer;
        this.deadlineService = deadlineService;
        this.clock = clock;
        this.examRooms = examRooms;
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
                     // Every room mutation takes the room lock before the simulation lock.
                     return lockForFinalization(simulation).then(simulations.claimSubmission(id)).flatMap(claimed -> {
                        if (claimed != 1) {
                            return Mono.error(ApiException.conflict("Simulation has already been submitted"));
                        }
                        simulation.setStatus(SimulationStatus.FINISHED);
                        return correct(simulation);
                    });
                });
    }

    private Mono<Void> lockForFinalization(Simulation simulation) {
        Mono<Void> roomLock = Mono.justOrEmpty(simulation.getExamRoomId())
                .flatMap(roomId -> examRooms == null ? Mono.empty() : examRooms.lockRoomForSimulation(roomId))
                .then();
        Mono<Simulation> simulationLock = simulations.lockForAnswerWrite(simulation.getId());
        return roomLock.then(simulationLock == null ? Mono.empty() : simulationLock.then());
    }

    private Mono<Simulation> simulationForAnswer(Long simulationId) {
        Mono<Simulation> simulation = simulations.findByIdAndDeletedAtIsNull(simulationId);
        if (simulation != null) return simulation;
        Simulation placeholder = new Simulation();
        placeholder.setId(simulationId);
        return Mono.just(placeholder);
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
        LocalDateTime finishedAt = LocalDateTime.now(clock);
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
            .flatMap(answer -> simulationForAnswer(answer.getSimulationId())
                .switchIfEmpty(Mono.error(ApiException.notFound("Simulation not found")))
                .flatMap(simulation -> lockForFinalization(simulation).thenReturn(answer))
                .flatMap(lockedAnswer -> questions.findByIdAndDeletedAtIsNull(lockedAnswer.getQuestionId())
                .switchIfEmpty(Mono.error(ApiException.notFound("Question not found")))
                .flatMap(question -> authorizeGrading(question, accountId, admin).then(Mono.defer(() -> {
                    if (lockedAnswer.getReviewStatus() != SimulationAnswerStatus.PENDING_REVIEW) {
                        return Mono.error(ApiException.conflict("Only answers pending review can be graded"));
                    }
                    if (question.getMaxScore() != null && score > question.getMaxScore()) {
                        return Mono.error(ApiException.badRequest("Score cannot exceed the question maximum"));
                    }
                    lockedAnswer.setScoreObtained(score.floatValue());
                    lockedAnswer.setIsCorrect(question.getMaxScore() != null && score >= question.getMaxScore());
                    lockedAnswer.setReviewStatus(SimulationAnswerStatus.GRADED);
                    lockedAnswer.setUpdatedAt(LocalDateTime.now());
                    return answers.save(lockedAnswer).flatMap(saved -> refreshFinalScore(saved.getSimulationId())
                            .then(toResponse(saved)));
                })))));
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

    private Mono<SimulationAnswerResponse> toResponse(SimulationAnswer answer) {
        return simulationForAnswer(answer.getSimulationId()).flatMap(simulation -> {
            Mono<Boolean> visible = simulation.getExamRoomId() == null || examRooms == null
                    ? Mono.just(true)
                    : examRooms.answerKeyVisible(simulation.getExamRoomId()).defaultIfEmpty(false);
            return visible.map(answerKeyVisible -> new SimulationAnswerResponse(answer.getId(), answer.getSimulationId(),
                    answer.getQuestionId(), answer.getSelectedOptionId(), answer.getAnswerText(),
                    answerKeyVisible ? answer.getScoreObtained() : null,
                    answerKeyVisible ? answer.getIsCorrect() : null,
                    answerKeyVisible ? answer.getReviewStatus() : null, answer.getAnsweredAt(), answer.getCreatedAt(),
                    answer.getUpdatedAt(), answer.getDeletedAt()));
        });
    }
}
