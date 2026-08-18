package ao.creativemode.kixi.dto.ocr;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * OCR Service Response DTO
 *
 * Maps the JSON response from the OCR microservice to Java objects.
 * Optimized for Angolan exam papers (12ª classe).
 */
public record OcrResponse(
    String status,

    @JsonProperty("requestId") String requestId,

    @JsonProperty("processingTimeMs") Integer processingTimeMs,

    @JsonProperty("overallConfidence") Double overallConfidence,

    DocumentInfo document,

    OcrMetadata metadata,

    List<ExtractedQuestion> questions,

    @JsonProperty("imagesToUpload") List<ImageToUpload> imagesToUpload,

    @JsonProperty("unmappedContent") List<UnmappedContent> unmappedContent,

    List<OcrWarning> warnings,

    @JsonProperty("errorMessage") String errorMessage
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
        return (
            isPartial() ||
            (overallConfidence != null && overallConfidence < 0.8) ||
            (warnings != null && !warnings.isEmpty())
        );
    }

    /**
     * Document information from OCR.
     */
    public record DocumentInfo(
        @JsonProperty("pageCount") Integer pageCount,
        @JsonProperty("mainLanguage") String mainLanguage,
        @JsonProperty("hasTables") Boolean hasTables
    ) {}

    /**
     * Extracted metadata with confidence scores - Angolan exam format.
     */
    public record OcrMetadata(
        // New structured fields
        @JsonProperty("examType") ConfidenceField<String> examType,

        @JsonProperty("durationMinutes")
        ConfidenceField<Integer> durationMinutes,

        @JsonProperty("variant") ConfidenceField<String> variant,

        @JsonProperty("title") ConfidenceField<String> title,

        @JsonProperty("instructions") ConfidenceField<String> instructions,

        @JsonProperty("schoolYearStart")
        ConfidenceField<Integer> schoolYearStart,

        @JsonProperty("schoolYearEnd") ConfidenceField<Integer> schoolYearEnd,

        @JsonProperty("classGrade") ConfidenceField<String> classGrade,

        @JsonProperty("courseName") ConfidenceField<String> courseName,

        @JsonProperty("subjectName") ConfidenceField<String> subjectName,

        @JsonProperty("totalMaxScore") ConfidenceField<Double> totalMaxScore,

        // Legacy fields for compatibility
        @JsonProperty("schoolYear") ConfidenceField<String> schoolYear,

        @JsonProperty("term") ConfidenceField<String> term,

        @JsonProperty("subject") ConfidenceField<String> subject,

        @JsonProperty("course") ConfidenceField<String> course,

        @JsonProperty("class") ConfidenceField<String> classInfo
    ) {
        /**
         * Get the school year start value, returning null if not present.
         */
        public Integer getSchoolYearStartValue() {
            return schoolYearStart != null ? schoolYearStart.value() : null;
        }

        /**
         * Get the school year end value, returning null if not present.
         */
        public Integer getSchoolYearEndValue() {
            return schoolYearEnd != null ? schoolYearEnd.value() : null;
        }

        /**
         * Get the class grade value, returning null if not present.
         */
        public String getClassGradeValue() {
            return classGrade != null ? classGrade.value() : null;
        }

        /**
         * Get the course name value, returning null if not present.
         */
        public String getCourseNameValue() {
            return courseName != null ? courseName.value() : null;
        }

        /**
         * Get the subject name value, returning null if not present.
         */
        public String getSubjectNameValue() {
            return subjectName != null ? subjectName.value() : null;
        }

        /**
         * Get the total max score value, returning null if not present.
         */
        public Double getTotalMaxScoreValue() {
            return totalMaxScore != null ? totalMaxScore.value() : null;
        }
    }

    /**
     * Generic confidence field for any value type.
     */
    public record ConfidenceField<T>(T value, Double confidence) {
        /**
         * Check if the field has a value with sufficient confidence.
         */
        public boolean isConfident(double threshold) {
            return (
                value != null && confidence != null && confidence >= threshold
            );
        }

        /**
         * Check if the field has low confidence (needs review).
         */
        public boolean isLowConfidence(double threshold) {
            return (
                value != null && confidence != null && confidence < threshold
            );
        }
    }

    /**
     * Extracted question with all components - Angolan exam format.
     */
    public record ExtractedQuestion(
        @JsonProperty("number") String number,

        Double confidence,

        @JsonProperty("subitems") List<String> subitems,

        @JsonProperty("subitemsContent") List<SubitemContent> subitemsContent,

        ConfidenceField<String> text,

        @JsonProperty("type") String type,

        @JsonProperty("cotacao") Double cotacao,

        List<ExtractedOption> options,

        @JsonProperty("hasImage") Boolean hasImage,

        @JsonProperty("imageDescription") String imageDescription,

        @JsonProperty("pageIndex") Integer pageIndex,

        @JsonProperty("startY") Integer startY,

        @JsonProperty("endY") Integer endY
    ) {
        /**
         * Check if this is a multiple choice question.
         */
        public boolean isMultipleChoice() {
            return options != null && !options.isEmpty();
        }

        /**
         * Check if this is a dissertativa (essay/development) question.
         */
        public boolean isDissertativa() {
            return "dissertativa".equals(type);
        }

        /**
         * Get the question type value.
         */
        public String getTypeValue() {
            return type != null ? type : "unknown";
        }

        /**
         * Get the text value, returning empty string if not present.
         */
        public String getTextValue() {
            return text != null && text.value() != null ? text.value() : "";
        }

        /**
         * Get the cotação (score) as a Double, returning null if not present.
         */
        public Double getCotacaoValue() {
            return cotacao;
        }

        /**
         * Check if the question has visual content.
         */
        public boolean hasVisualContent() {
            return hasImage != null && hasImage;
        }
    }

    /**
     * Subitem content with label, text and optional cotação.
     */
    public record SubitemContent(String label, String text, Double cotacao) {}

    /**
     * Extracted question option.
     */
    public record ExtractedOption(
        @JsonProperty("optionLabel") String optionLabel,
        @JsonProperty("optionText") String optionText,
        Double confidence
    ) {}

    /**
     * Image region to be uploaded.
     */
    public record ImageToUpload(
        @JsonProperty("suggestedFilename") String suggestedFilename,

        String description,

        String region,

        @JsonProperty("pageIndex") Integer pageIndex,

        List<Integer> bbox,

        @JsonProperty("sourceWidth") Integer sourceWidth,

        @JsonProperty("sourceHeight") Integer sourceHeight,

        @JsonProperty("contractVersion") Integer contractVersion,

        @JsonProperty("sourceFileIndex") Integer sourceFileIndex
    ) {
        /**
         * Check if this is a header/logo image.
         */
        public boolean isHeader() {
            return "cabecalho".equals(region);
        }

        /**
         * Check if this is a footer/coordination signature image.
         */
        public boolean isFooter() {
            return "rodape".equals(region);
        }

        /**
         * Check if this is a question-related image.
         */
        public boolean isQuestionImage() {
            return region != null && region.startsWith("questao_");
        }

        /**
         * Get the question number if this is a question image.
         */
        public String getQuestionNumber() {
            if (isQuestionImage() && region.length() > 8) {
                return region.substring(8);
            }
            return null;
        }
    }

    /**
     * Unmapped content that couldn't be categorized.
     */
    public record UnmappedContent(
        @JsonProperty("pageIndex") Integer pageIndex,
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
