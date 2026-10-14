package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.shared.service.SimulationShowcaseReader;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class SimulationShowcase implements SimulationShowcaseReader {

    private final SimulationResultService results;
    private final SimulationRepository simulations;
    private final StatementRepository statements;

    public SimulationShowcase(
            SimulationResultService results, SimulationRepository simulations, StatementRepository statements) {
        this.results = results;
        this.simulations = simulations;
        this.statements = statements;
    }

    /** {@code findResult} already answers 404 for another account's simulation and 409 for an unfinished one. */
    @Override
    public Mono<Showcase> showcaseOf(Long simulationId, Long accountId) {
        return results.findResult(simulationId, accountId, false).flatMap(result ->
                simulations.findByIdAndAccountIdAndDeletedAtIsNull(simulationId, accountId).flatMap(simulation ->
                        statements.findById(simulation.getStatementId()).map(statement -> new Showcase(
                                simulationId, statement.getTitle(), result.finalScore(), statement.getTotalMaxScore(),
                                result.correctAnswers(), result.totalQuestions(), result.timeSpentSeconds()))));
    }
}
