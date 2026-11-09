package ao.creativemode.kixi.shared.service;

import reactor.core.publisher.Mono;

/**
 * What the feed may say about a finished simulation, for the card a student
 * publishes after an exam (issue #238).
 *
 * <p>A door rather than a direct call because simulations live in
 * {@code simulations} and the feed lives in {@code feed}, for the same reason
 * {@code EnrollmentSeatReader} exists. The figures are read here, on the
 * server, and never taken from the request: a student cannot publish a grade
 * they did not get.</p>
 */
public interface SimulationShowcaseReader {

    /**
     * The result of the account's own simulation. A simulation that is not theirs
     * is not found; one that is still running is a 409, as for the result itself.
     */
    Mono<Showcase> showcaseOf(Long simulationId, Long accountId);

    record Showcase(
            Long simulationId,
            String statementTitle,
            Double finalScore,
            Double maxScore,
            Integer correctAnswers,
            Integer totalQuestions,
            Integer timeSpentSeconds,
            /** Open answers still awaiting correction when the card is published (kixi#464). */
            Integer pendingReviewCount,
            /** O cabeçalho da folha, lido do enunciado (kixi#587): o {@code examType} («Teste», «Exame»...). */
            String paperKind,
            /** A classe do enunciado por extenso, como «12ª Classe»; nulo se o enunciado não tem classe. */
            String grade,
            /** O nome da disciplina do enunciado. */
            String subject,
            Integer durationMinutes,
            /** O ano letivo do enunciado, como «2025/2026». */
            String schoolYear,
            /** A série da prova (A, B...). */
            String variant) {

        /** The showcase as it was before the paper header existed. */
        public Showcase(
                Long simulationId,
                String statementTitle,
                Double finalScore,
                Double maxScore,
                Integer correctAnswers,
                Integer totalQuestions,
                Integer timeSpentSeconds,
                Integer pendingReviewCount) {
            this(simulationId, statementTitle, finalScore, maxScore, correctAnswers, totalQuestions,
                    timeSpentSeconds, pendingReviewCount, null, null, null, null, null, null);
        }

        /** The showcase as it was before the pending count existed. */
        public Showcase(
                Long simulationId,
                String statementTitle,
                Double finalScore,
                Double maxScore,
                Integer correctAnswers,
                Integer totalQuestions,
                Integer timeSpentSeconds) {
            this(simulationId, statementTitle, finalScore, maxScore, correctAnswers,
                    totalQuestions, timeSpentSeconds, null, null, null, null, null, null, null);
        }
    }
}
