package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.ExamRoomAccess;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Correção questão a questão no treino (kixi#729).
 *
 * <p>O modo prova esconde a chave de correção até ao fim, e isso não muda: este serviço só responde para uma
 * <em>simulação livre</em> (sem sala de prova), do próprio aluno, ainda em curso, sobre uma prova que não tem
 * nenhuma sala por fechar. Qualquer outra situação é 403, para que uma sala de prova nunca possa ser
 * consultada por esta via.</p>
 */
@Service
public class QuestionFeedbackService {

    /** O que se mostra ao aluno depois de responder. {@code correct} é null numa questão aberta (sem alternativas). */
    public record Feedback(Long questionId, Boolean correct, Long correctOptionId, String explanation) {}

    private final SimulationRepository simulations;
    private final QuestionRepository questions;
    private final QuestionOptionRepository options;
    private final ExamRoomAccess examRooms;

    @Autowired
    public QuestionFeedbackService(SimulationRepository simulations, QuestionRepository questions,
                                   QuestionOptionRepository options, ExamRoomAccess examRooms) {
        this.simulations = simulations;
        this.questions = questions;
        this.options = options;
        this.examRooms = examRooms;
    }

    public Mono<Feedback> check(Long simulationId, Long questionId, Long selectedOptionId, Long accountId) {
        return simulations.findByIdAndDeletedAtIsNull(simulationId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Simulation not found")))
                .flatMap(simulation -> requireTrainingOf(simulation, accountId))
                .flatMap(simulation -> questions.findByIdAndDeletedAtIsNull(questionId)
                        .filter(question -> simulation.getStatementId().equals(question.getStatementId()))
                        .switchIfEmpty(Mono.error(ApiException.notFound("Question not found")))
                        .flatMap(question -> feedbackOf(question, selectedOptionId)));
    }

    private Mono<Simulation> requireTrainingOf(Simulation simulation, Long accountId) {
        if (!simulation.getAccountId().equals(accountId)) {
            return Mono.error(ApiException.notFound("Simulation not found"));
        }
        if (simulation.getExamRoomId() != null) {
            return Mono.error(ApiException.forbidden("Controlled exams do not show the answer key"));
        }
        if (simulation.getStatus() != SimulationStatus.IN_PROGRESS) {
            return Mono.error(ApiException.conflict("The simulation is not in progress"));
        }
        return examRooms.hasNonClosedRoom(simulation.getStatementId())
                .defaultIfEmpty(false)
                .flatMap(active -> active
                        ? Mono.<Simulation>error(ApiException.forbidden("This statement has an active exam room"))
                        : Mono.just(simulation));
    }

    private Mono<Feedback> feedbackOf(Question question, Long selectedOptionId) {
        return options.findAllByQuestionIdAndDeletedAtIsNull(question.getId()).collectList().map(all -> {
            if (all.isEmpty()) {
                return new Feedback(question.getId(), null, null, question.getModelAnswer());
            }
            List<QuestionOption> right = all.stream().filter(o -> Boolean.TRUE.equals(o.getIsCorrect())).toList();
            Long rightId = right.isEmpty() ? null : right.get(0).getId();
            boolean correct = selectedOptionId != null && right.stream().anyMatch(o -> o.getId().equals(selectedOptionId));
            return new Feedback(question.getId(), correct, rightId, question.getModelAnswer());
        });
    }
}
