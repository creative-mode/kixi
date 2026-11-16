package ao.creativemode.kixi.simulations.service;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.simulations.model.CorrectionStatus;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import ao.creativemode.kixi.simulations.model.SimulationAnswerStatus;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** O estado da correção de uma simulação é derivado das respostas (kixi#609). */
class CorrectionSummaryTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 11, 16, 10, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 11, 16, 10, 5);

    private static Simulation simulation(SimulationStatus status) {
        Simulation simulation = new Simulation();
        simulation.setStatus(status);
        return simulation;
    }

    private static SimulationAnswer answer(SimulationAnswerStatus status, LocalDateTime aiGradedAt, LocalDateTime updatedAt) {
        SimulationAnswer answer = new SimulationAnswer();
        answer.setReviewStatus(status);
        answer.setAiGradedAt(aiGradedAt);
        answer.setUpdatedAt(updatedAt);
        return answer;
    }

    @Test
    void aSimulationStillBeingDoneHasNoCorrectionStatus() {
        CorrectionSummary summary = CorrectionSummary.of(simulation(SimulationStatus.IN_PROGRESS),
                List.of(answer(SimulationAnswerStatus.PENDING_REVIEW, null, null)));

        assertThat(summary.status()).isNull();
        assertThat(summary.correctedAt()).isNull();
    }

    @Test
    void aFinishedSimulationWithOnlyObjectiveAnswersIsDoneWithoutACorrectionTime() {
        CorrectionSummary summary = CorrectionSummary.of(simulation(SimulationStatus.FINISHED),
                List.of(answer(SimulationAnswerStatus.AUTO_GRADED, null, T1), answer(SimulationAnswerStatus.AUTO_GRADED, null, T1)));

        assertThat(summary.status()).isEqualTo(CorrectionStatus.DONE);
        assertThat(summary.correctedAt()).isNull();
    }

    @Test
    void openAnswersNoOneCorrectedYetArePending() {
        CorrectionSummary summary = CorrectionSummary.of(simulation(SimulationStatus.FINISHED),
                List.of(answer(SimulationAnswerStatus.AUTO_GRADED, null, T1), answer(SimulationAnswerStatus.PENDING_REVIEW, null, T1),
                        answer(SimulationAnswerStatus.PENDING_REVIEW, null, T1)));

        assertThat(summary.status()).isEqualTo(CorrectionStatus.PENDING);
        assertThat(summary.correctedAt()).isNull();
    }

    @Test
    void someOpenAnswersCorrectedAndOthersNotIsInProgress() {
        CorrectionSummary summary = CorrectionSummary.of(simulation(SimulationStatus.FINISHED),
                List.of(answer(SimulationAnswerStatus.AI_GRADED, T1, T1), answer(SimulationAnswerStatus.PENDING_REVIEW, null, T1)));

        assertThat(summary.status()).isEqualTo(CorrectionStatus.IN_PROGRESS);
        assertThat(summary.correctedAt()).isNull();
    }

    @Test
    void whenTheLastOpenAnswerIsCorrectedItIsDoneAndTheCorrectionTimeIsTheLatestOne() {
        CorrectionSummary summary = CorrectionSummary.of(simulation(SimulationStatus.FINISHED),
                List.of(answer(SimulationAnswerStatus.AUTO_GRADED, null, T1), answer(SimulationAnswerStatus.AI_GRADED, T1, T1),
                        answer(SimulationAnswerStatus.AI_GRADED, T2, T2)));

        assertThat(summary.status()).isEqualTo(CorrectionStatus.DONE);
        assertThat(summary.correctedAt()).isEqualTo(T2);
    }

    @Test
    void anAnswerCorrectedByAPersonCountsAndItsTimeIsTheLastChange() {
        CorrectionSummary summary = CorrectionSummary.of(simulation(SimulationStatus.FINISHED),
                List.of(answer(SimulationAnswerStatus.AI_GRADED, T1, T1), answer(SimulationAnswerStatus.GRADED, null, T2)));

        assertThat(summary.status()).isEqualTo(CorrectionStatus.DONE);
        assertThat(summary.correctedAt()).isEqualTo(T2);
    }

    @Test
    void aFailureReportedByTheEngineIsFailedWhileThereAreAnswersToCorrect() {
        Simulation simulation = simulation(SimulationStatus.FINISHED);
        simulation.setCorrectionFailedAt(T2);

        assertThat(CorrectionSummary.of(simulation, List.of(answer(SimulationAnswerStatus.PENDING_REVIEW, null, T1))).status())
                .isEqualTo(CorrectionStatus.FAILED);
        // com uma já corrigida e outra por corrigir continua a ser uma falha: a correção não vai sozinha
        assertThat(CorrectionSummary.of(simulation, List.of(answer(SimulationAnswerStatus.AI_GRADED, T1, T1),
                answer(SimulationAnswerStatus.PENDING_REVIEW, null, T1))).status()).isEqualTo(CorrectionStatus.FAILED);
    }

    @Test
    void aFailureNoLongerMattersOnceEveryOpenAnswerIsCorrected() {
        Simulation simulation = simulation(SimulationStatus.FINISHED);
        simulation.setCorrectionFailedAt(T1);

        CorrectionSummary summary = CorrectionSummary.of(simulation, List.of(answer(SimulationAnswerStatus.AI_GRADED, T2, T2)));

        assertThat(summary.status()).isEqualTo(CorrectionStatus.DONE);
        assertThat(summary.correctedAt()).isEqualTo(T2);
    }
}
