package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.exams.dto.statement.ManualStatementRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionOcrEvidenceTest {

    @Test
    void lowSourceFormulaConfidenceRequiresReviewAndSurvivesPersistenceCodec() {
        var formula = new ManualStatementRequest.Formula(
            "x²", "x^2", 0.92, "formula", 0.43, 0.95);
        String encoded = QuestionOcrEvidence.encode(List.of(formula));

        assertThat(QuestionOcrEvidence.needsReview(0.95, QuestionOcrEvidence.decode(encoded), false)).isTrue();
        assertThat(QuestionOcrEvidence.decode(encoded)).containsExactly(formula);
        assertThat(QuestionOcrEvidence.needsReview(0.95, QuestionOcrEvidence.decode(encoded), true)).isFalse();
    }
}
