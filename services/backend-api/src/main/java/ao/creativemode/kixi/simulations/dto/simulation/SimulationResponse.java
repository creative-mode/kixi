package ao.creativemode.kixi.simulations.dto.simulation;

import ao.creativemode.kixi.identity.dto.accounts.AccountBasicResponse;
import ao.creativemode.kixi.academic.dto.schoolyears.SchoolYearResponse;
import ao.creativemode.kixi.exams.dto.statement.StatementBasicResponse;
import ao.creativemode.kixi.simulations.model.CorrectionStatus;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SimulationResponse(
        Long id,
        AccountBasicResponse account,
        StatementBasicResponse statement,
        SchoolYearResponse schoolYear,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        /**
         * Issue #107: the scheduled effective instant calculated from duration,
         * accessibility extra time, and tolerance. Room closure is an internal
         * rejection signal and never changes this public, serializable value.
         * Null when the simulation has no deadline.
         */
        LocalDateTime deadline,
        Integer timeSpentSeconds,
        Double finalScore,
        SimulationStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt,
        /** Só num treino montado: as questões escolhidas. Ausente numa simulação normal. */
        java.util.List<Long> selectedQuestionIds,
        /** kixi#609: o estado da correção das respostas abertas; ausente enquanto a simulação não foi entregue. */
        CorrectionStatus correctionStatus,
        /** kixi#609: quando a última resposta aberta ficou corrigida; só com {@code DONE} e pelo menos uma aberta. */
        LocalDateTime correctedAt
) {

    /** A mesma simulação com o estado da correção, que só se sabe olhando para as respostas. */
    public SimulationResponse withCorrection(CorrectionStatus status, LocalDateTime correctedAtInstant) {
        return new SimulationResponse(id, account, statement, schoolYear, startedAt, finishedAt, deadline, timeSpentSeconds, finalScore,
                this.status, createdAt, updatedAt, deletedAt, selectedQuestionIds, status, correctedAtInstant);
    }
}
