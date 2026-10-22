package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.exams.dto.statement.ManualStatementRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** JSON codec for the small, validated formula-provenance array stored with a question. */
public final class QuestionOcrEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<ManualStatementRequest.Formula>> FORMULAS = new TypeReference<>() {};

    private QuestionOcrEvidence() {}

    public static String encode(List<ManualStatementRequest.Formula> formulas) {
        if (formulas == null || formulas.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(formulas);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("Could not serialize OCR formula evidence", exception);
        }
    }

    public static List<ManualStatementRequest.Formula> decode(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, FORMULAS);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Stored OCR formula evidence is invalid", exception);
        }
    }

    public static boolean needsReview(Double confidence, List<ManualStatementRequest.Formula> formulas,
            Boolean reviewed) {
        boolean machineReview = confidence != null && confidence < 0.8
            || formulas != null && formulas.stream().anyMatch(formula ->
            "unrecognized".equals(formula.kind()) || formula.latex() == null || formula.latex().isBlank()
                || formula.confidence() != null && formula.confidence() < 0.8
                || formula.ocrConfidence() != null && formula.ocrConfidence() < 0.8
                || formula.layoutConfidence() != null && formula.layoutConfidence() < 0.8);
        return machineReview && !Boolean.TRUE.equals(reviewed);
    }
}
