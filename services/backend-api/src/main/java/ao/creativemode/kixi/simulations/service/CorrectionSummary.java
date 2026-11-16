package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.simulations.model.CorrectionStatus;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import ao.creativemode.kixi.simulations.model.SimulationAnswerStatus;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * O estado da correção de uma simulação e quando ficou corrigida (kixi#609). Só se aplica a uma simulação entregue
 * ({@code FINISHED}); enquanto o aluno ainda a faz não há nada para corrigir e o estado é nulo.
 *
 * @param status      {@code null} se a simulação ainda não foi entregue
 * @param correctedAt quando a última resposta aberta ficou corrigida; só com {@link CorrectionStatus#DONE} e pelo menos uma
 *                    resposta aberta (numa simulação só com objetivas não houve correção)
 */
public record CorrectionSummary(CorrectionStatus status, LocalDateTime correctedAt) {

    public static final CorrectionSummary NONE = new CorrectionSummary(null, null);

    /**
     * Uma resposta aberta é corrigida por uma pessoa ({@code GRADED}) ou pela IA ({@code AI_GRADED}); espera correção
     * enquanto está {@code PENDING_REVIEW}. As objetivas ({@code AUTO_GRADED}) corrigem-se na entrega e não contam.
     */
    public static CorrectionSummary of(Simulation simulation, List<SimulationAnswer> answers) {
        if (simulation.getStatus() != SimulationStatus.FINISHED) {
            return NONE;
        }
        long pending = answers.stream().filter(a -> a.getReviewStatus() == SimulationAnswerStatus.PENDING_REVIEW).count();
        List<SimulationAnswer> corrected = answers.stream()
                .filter(a -> a.getReviewStatus() == SimulationAnswerStatus.GRADED || a.getReviewStatus() == SimulationAnswerStatus.AI_GRADED)
                .toList();
        if (pending == 0) {
            return new CorrectionSummary(CorrectionStatus.DONE, corrected.isEmpty() ? null : correctedAt(corrected));
        }
        if (simulation.getCorrectionFailedAt() != null) {
            return new CorrectionSummary(CorrectionStatus.FAILED, null);
        }
        return new CorrectionSummary(corrected.isEmpty() ? CorrectionStatus.PENDING : CorrectionStatus.IN_PROGRESS, null);
    }

    /** A correção mais recente: a hora da IA, ou a da última alteração quando foi uma pessoa. */
    private static LocalDateTime correctedAt(List<SimulationAnswer> corrected) {
        return corrected.stream().map(a -> a.getAiGradedAt() != null ? a.getAiGradedAt() : a.getUpdatedAt())
                .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }
}
