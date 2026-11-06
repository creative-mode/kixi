package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.dto.simulation.SimulationRequest;
import ao.creativemode.kixi.simulations.dto.simulation.SimulationResponse;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * «Montar treino» (kixi#729): uma simulação livre sobre um enunciado, mas só com N das suas questões.
 *
 * <p>Cria a simulação pelo caminho normal (mesmas regras: enunciado visível, sem sala de prova ativa) e depois grava
 * as questões escolhidas. A correção, a nota máxima e o resultado passam a contar só essas.</p>
 */
@Service
public class PracticeService {

    static final int MIN_QUESTIONS = 1;

    private final SimulationService simulations;
    private final SimulationRepository repository;
    private final QuestionRepository questions;
    private final Random random;

    @Autowired
    public PracticeService(SimulationService simulations, SimulationRepository repository, QuestionRepository questions) {
        this(simulations, repository, questions, new Random());
    }

    PracticeService(SimulationService simulations, SimulationRepository repository, QuestionRepository questions, Random random) {
        this.simulations = simulations;
        this.repository = repository;
        this.questions = questions;
        this.random = random;
    }

    @Transactional
    public Mono<SimulationResponse> start(Long accountId, Long statementId, int questionCount) {
        if (questionCount < MIN_QUESTIONS) {
            return Mono.error(ApiException.badRequest("questionCount must be at least " + MIN_QUESTIONS));
        }
        return simulations.createForAccount(
                        new SimulationRequest(accountId, statementId, null, null, null, null, null, null), accountId)
                .flatMap(created -> questions.findAllByStatementIdAndDeletedAtIsNull(statementId).collectList()
                        .flatMap(all -> select(all, questionCount)
                                .map(chosen -> repository.findById(created.id())
                                        .flatMap(simulation -> {
                                            simulation.setSelectedQuestionIds(chosen.stream().map(String::valueOf).collect(Collectors.joining(",")));
                                            return repository.save(simulation);
                                        })
                                        .then(Mono.defer(() -> simulations.findById(created.id()))))
                                .orElse(Mono.just(created))));
    }

    /** As N questões escolhidas, por ordem do enunciado; vazio quando N chega para o enunciado todo. */
    java.util.Optional<List<Long>> select(List<Question> all, int count) {
        List<Question> answerable = all.stream().filter(Question::isAnswerable).collect(Collectors.toCollection(ArrayList::new));
        if (count >= answerable.size()) {
            return java.util.Optional.empty();
        }
        Collections.shuffle(answerable, random);
        List<Long> chosen = answerable.subList(0, count).stream()
                .sorted(java.util.Comparator.comparing(q -> q.getOrderIndex() == null ? Integer.MAX_VALUE : q.getOrderIndex()))
                .map(Question::getId)
                .toList();
        return java.util.Optional.of(chosen);
    }
}
