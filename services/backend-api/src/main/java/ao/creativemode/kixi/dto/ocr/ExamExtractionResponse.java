package ao.creativemode.kixi.dto.ocr;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response DTO for structured exam extraction from OCR.
 *
 * Represents the complete extracted exam data in Angolan format,
 * matching the exact structure required for persistence.
 */
public record ExamExtractionResponse(
    @JsonProperty("exam_type") String examType,

    @JsonProperty("duration_minutes") Integer durationMinutes,

    String variant,

    String title,

    String instructions,

    @JsonProperty("school_year_start") Integer schoolYearStart,

    @JsonProperty("school_year_end") Integer schoolYearEnd,

    @JsonProperty("class_grade") String classGrade,

    @JsonProperty("course_name") String courseName,

    @JsonProperty("subject_name") String subjectName,

    @JsonProperty("total_max_score") Double totalMaxScore,

    List<QuestionData> questions,

    @JsonProperty("images_to_upload") List<ImageToUploadData> imagesToUpload,

    // Processing metadata
    @JsonProperty("request_id") String requestId,

    @JsonProperty("processing_time_ms") Integer processingTimeMs,

    @JsonProperty("overall_confidence") Double overallConfidence,

    @JsonProperty("needs_review") Boolean needsReview,

    List<WarningData> warnings
) {
    /**
     * Create from OcrResponse.
     */
    public static ExamExtractionResponse fromOcrResponse(OcrResponse response) {
        if (response == null) {
            return null;
        }

        OcrResponse.OcrMetadata metadata = response.metadata();

        return new ExamExtractionResponse(
            metadata != null && metadata.examType() != null
                ? metadata.examType().value() : null,
            metadata != null && metadata.durationMinutes() != null
                ? metadata.durationMinutes().value() : null,
            metadata != null && metadata.variant() != null
                ? metadata.variant().value() : null,
            metadata != null && metadata.title() != null
                ? metadata.title().value() : null,
            metadata != null && metadata.instructions() != null
                ? metadata.instructions().value() : null,
            metadata != null ? metadata.getSchoolYearStartValue() : null,
            metadata != null ? metadata.getSchoolYearEndValue() : null,
            metadata != null ? metadata.getClassGradeValue() : null,
            metadata != null ? metadata.getCourseNameValue() : null,
            metadata != null ? metadata.getSubjectNameValue() : null,
            metadata != null ? metadata.getTotalMaxScoreValue() : null,
            response.questions() != null
                ? response.questions().stream()
                    .map(QuestionData::fromExtractedQuestion)
                    .toList()
                : List.of(),
            response.imagesToUpload() != null
                ? response.imagesToUpload().stream()
                    .map(ImageToUploadData::fromImageToUpload)
                    .toList()
                : List.of(),
            response.requestId(),
            response.processingTimeMs(),
            response.overallConfidence(),
            response.needsReview(),
            response.warnings() != null
                ? response.warnings().stream()
                    .map(WarningData::fromOcrWarning)
                    .toList()
                : List.of()
        );
    }

    /**
     * Check if extraction was successful.
     */
    public boolean isValid() {
        return subjectName != null && !subjectName.isBlank();
    }

    /**
     * Question data for the response.
     */
    public record QuestionData(
        String number,

        List<String> subitems,

        String text,

        String type,

        Double cotacao,

        List<OptionData> options,

        @JsonProperty("has_image") Boolean hasImage,

        @JsonProperty("image_description") String imageDescription,

        Double confidence
    ) {
        /**
         * Create from ExtractedQuestion.
         */
        public static QuestionData fromExtractedQuestion(OcrResponse.ExtractedQuestion q) {
            if (q == null) return null;

            return new QuestionData(
                q.number(),
                q.subitems() != null ? q.subitems() : List.of(),
                q.getTextValue(),
                q.getTypeValue(),
                q.getCotacaoValue(),
                q.options() != null
                    ? q.options().stream()
                        .map(OptionData::fromExtractedOption)
                        .toList()
                    : null,
                q.hasVisualContent(),
                q.imageDescription(),
                q.confidence()
            );
        }

        /**
         * Check if this is a multiple choice question.
         */
        public boolean isMultipleChoice() {
            return "multipla_escolha".equals(type) ||
                   (options != null && !options.isEmpty());
        }

        /**
         * Check if this is a dissertative question.
         */
        public boolean isDissertativa() {
            return "dissertativa".equals(type);
        }
    }

    /**
     * Option data for multiple choice questions.
     */
    public record OptionData(
        @JsonProperty("option_label") String optionLabel,

        @JsonProperty("option_text") String optionText,

        Double confidence
    ) {
        /**
         * Create from ExtractedOption.
         */
        public static OptionData fromExtractedOption(OcrResponse.ExtractedOption opt) {
            if (opt == null) return null;

            return new OptionData(
                opt.optionLabel(),
                opt.optionText(),
                opt.confidence()
            );
        }
    }

    /**
     * Image to upload data.
     */
    public record ImageToUploadData(
        @JsonProperty("suggested_filename") String suggestedFilename,

        String description,

        String region
    ) {
        /**
         * Create from ImageToUpload.
         */
        public static ImageToUploadData fromImageToUpload(OcrResponse.ImageToUpload img) {
            if (img == null) return null;

            return new ImageToUploadData(
                img.suggestedFilename(),
                img.description(),
                img.region()
            );
        }

        /**
         * Check if this is a header image.
         */
        public boolean isHeader() {
            return "cabecalho".equals(region);
        }

        /**
         * Check if this is a footer/coordination signature.
         */
        public boolean isFooter() {
            return "rodape".equals(region);
        }

        /**
         * Check if this is a question image.
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
     * Warning data.
     */
    public record WarningData(
        String code,

        String field,

        Double confidence,

        String message
    ) {
        /**
         * Create from OcrWarning.
         */
        public static WarningData fromOcrWarning(OcrResponse.OcrWarning w) {
            if (w == null) return null;

            return new WarningData(
                w.code(),
                w.field(),
                w.confidence(),
                w.message()
            );
        }

        /**
         * Check if this is a low confidence warning.
         */
        public boolean isLowConfidence() {
            return "LOW_CONFIDENCE".equals(code);
        }
    }
}
