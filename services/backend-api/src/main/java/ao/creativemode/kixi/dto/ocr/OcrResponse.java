package ao.creativemode.kixi.dto.ocr;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * OCR Service Response DTO
 *
 * Maps the JSON response from the OCR microservice to Java objects.
 * This is the main response wrapper containing all extracted data.
 */
public record OcrResponse(
    String status,

    @JsonProperty("requestId")
    String requestId,

    @JsonProperty("processingTimeMs")
    Integer processingTimeMs,

    @JsonProperty("overallConfidence")
    Double overallConfidence,

    DocumentInfo document,

    OcrMetadata metadata,

    List<ExtractedQuestion> questions,

    @JsonProperty("unmappedContent")
    List<UnmappedContent> unmappedContent,

    List<OcrWarning> warnings,

    @JsonProperty("errorMessage")
    String errorMessage
) {
    /**
     * Check if the OCR processing was successful.
     */
    public boolean isSuccess() {
        return "success".equals(status);
    }

    /**
     * Check if the OCR processing was partial (some data extracted with low confidence).
     */
    public boolean isPartial() {
        return "partial".equals(status);
    }

    /**
     * Check if the OCR processing failed.
     */
    public boolean isError() {
        return "error".equals(status);
    }

    /**
     * Check if the result needs human review (low confidence or warnings).
     */
    public boolean needsReview() {
        return isPartial() ||
               (overallConfidence != null && overallConfidence < 0.8) ||
               (warnings != null && !warnings.isEmpty());
    }

    /**
     * Document information from OCR.
     */
    public record DocumentInfo(
        @JsonProperty("pageCount")
        Integer pageCount,

        @JsonProperty("mainLanguage")
        String mainLanguage,

        @JsonProperty("hasTables")
        Boolean hasTables
    ) {}

    /**
     * Extracted metadata with confidence scores.
     */
    public record OcrMetadata(
        @JsonProperty("schoolYear")
        ConfidenceField<String> schoolYear,

        @JsonProperty("term")
        ConfidenceField<String> term,

        @JsonProperty("subject")
        ConfidenceField<String> subject,

        @JsonProperty("course")
        ConfidenceField<String> course,

        @JsonProperty("class")
        ConfidenceField<String> classInfo,

        @JsonProperty("examType")
        ConfidenceField<String> examType,

        @JsonProperty("durationMinutes")
        ConfidenceField<Integer> durationMinutes,

        @JsonProperty("variant")
        ConfidenceField<String> variant,

        @JsonProperty("title")
        ConfidenceField<String> title,

        @JsonProperty("instructions")
        ConfidenceField<String> instructions
    ) {}

    /**
     * Generic confidence field for any value type.
     */
    public record ConfidenceField<T>(
        T value,
        Double confidence
    ) {
        /**
         * Check if the field has a value with sufficient confidence.
         */
        public boolean isConfident(double threshold) {
            return value != null && confidence != null && confidence >= threshold;
        }

        /**
         * Check if the field has low confidence (needs review).
         */
        public boolean isLowConfidence(double threshold) {
            return value != null && confidence != null && confidence < threshold;
        }
    }

    /**
     * Extracted question with all components.
     */
    public record ExtractedQuestion(
        Integer number,

        Double confidence,

        ConfidenceField<String> text,

        @JsonProperty("questionType")
        ConfidenceField<String> questionType,

        @JsonProperty("maxScore")
        ConfidenceField<Double> maxScore,

        List<ExtractedOption> options,

        @JsonProperty("pageIndex")
        Integer pageIndex,

        @JsonProperty("startY")
        Integer startY,

        @JsonProperty("endY")
        Integer endY
    ) {
        /**
         * Check if this is a multiple choice question.
         */
        public boolean isMultipleChoice() {
            return options != null && !options.isEmpty();
        }

        /**
         * Get the question type value, defaulting to "unknown".
         */
        public String getQuestionTypeValue() {
            return questionType != null && questionType.value() != null
                ? questionType.value()
                : "unknown";
        }
    }

    /**
     * Extracted question option.
     */
    public record ExtractedOption(
        @JsonProperty("optionLabel")
        String optionLabel,

        @JsonProperty("optionText")
        String optionText,

        Double confidence
    ) {}

    /**
     * Unmapped content that couldn't be categorized.
     */
    public record UnmappedContent(
        @JsonProperty("pageIndex")
        Integer pageIndex,

        String text,

        Double confidence
    ) {}

    /**
     * Processing warning from OCR service.
     */
    public record OcrWarning(
        String code,
        String field,
        Double confidence,
        String message
    ) {
        /**
         * Check if this is a low confidence warning.
         */
        public boolean isLowConfidence() {
            return "LOW_CONFIDENCE".equals(code);
        }

        /**
         * Check if this is an unknown question type warning.
         */
        public boolean isUnknownQuestionType() {
            return "UNKNOWN_QUESTION_TYPE".equals(code);
        }
    }
}
