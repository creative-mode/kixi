package ao.creativemode.kixi.ocr.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

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

    @JsonProperty("unmapped_content") List<OcrResponse.UnmappedContent> unmappedContent,

    // Processing metadata
    @JsonProperty("request_id") String requestId,

    @JsonProperty("processing_time_ms") Integer processingTimeMs,

    @JsonProperty("overall_confidence") Double overallConfidence,

    @JsonProperty("needs_review") Boolean needsReview,

    List<WarningData> warnings,

    String status,

    @JsonProperty("errorMessage") String errorMessage,

    Map<String, Double> timings
) {
    /**
     * Create from OcrResponse.
     */
    public static ExamExtractionResponse fromOcrResponse(OcrResponse response) {
        return fromOcrResponse(response, null);
    }

    /**
     * Create from an OCR response, with the crop (a data URL) of each region in {@code crops}, by position.
     */
    public static ExamExtractionResponse fromOcrResponse(OcrResponse response, List<String> crops) {
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
                ? java.util.stream.IntStream.range(0, response.imagesToUpload().size())
                    .mapToObj(i -> ImageToUploadData.fromImageToUpload(
                        response.imagesToUpload().get(i),
                        crops != null && i < crops.size() ? crops.get(i) : null))
                    .toList()
                : List.of(),
            response.unmappedContent() != null ? response.unmappedContent() : List.of(),
            response.requestId(),
            response.processingTimeMs(),
            response.overallConfidence(),
            response.needsReview(),
            response.warnings() != null
                ? response.warnings().stream()
                    .map(WarningData::fromOcrWarning)
                    .toList()
                : List.of(),
            response.status(),
            response.errorMessage(),
            response.timings()
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

        Double confidence,

        /** The alíneas, each with its own type; empty when the question has none. */
        @JsonProperty("subitems_content") List<SubitemData> subitemsContent,

        /** Formulas recognized in the statement, alíneas or options (#262); empty when none. */
        List<FormulaData> formulas,

        @JsonProperty("rawText") String rawText,

        @JsonProperty("groupLayout") String groupLayout
    ) {
        /** The shape before the alíneas were exposed. */
        public QuestionData(String number, List<String> subitems, String text, String type, Double cotacao,
                List<OptionData> options, Boolean hasImage, String imageDescription, Double confidence) {
            this(number, subitems, text, type, cotacao, options, hasImage, imageDescription, confidence, List.of(), List.of(), null, null);
        }

        /** The shape before formula recognition (#262) was exposed. */
        public QuestionData(String number, List<String> subitems, String text, String type, Double cotacao,
                List<OptionData> options, Boolean hasImage, String imageDescription, Double confidence,
                List<SubitemData> subitemsContent) {
            this(number, subitems, text, type, cotacao, options, hasImage, imageDescription, confidence,
                subitemsContent, List.of(), null, null);
        }

        /** Preserve the response shape with formula evidence before raw OCR was exposed. */
        public QuestionData(String number, List<String> subitems, String text, String type, Double cotacao,
                List<OptionData> options, Boolean hasImage, String imageDescription, Double confidence,
                List<SubitemData> subitemsContent, List<FormulaData> formulas) {
            this(number, subitems, text, type, cotacao, options, hasImage, imageDescription, confidence,
                subitemsContent, formulas, null, null);
        }

        public QuestionData(String number, List<String> subitems, String text, String type, Double cotacao,
                List<OptionData> options, Boolean hasImage, String imageDescription, Double confidence,
                List<SubitemData> subitemsContent, List<FormulaData> formulas, String rawText) {
            this(number, subitems, text, type, cotacao, options, hasImage, imageDescription, confidence,
                subitemsContent, formulas, rawText, null);
        }

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
                q.confidence(),
                q.subitemsContent() != null
                    ? q.subitemsContent().stream().map(SubitemData::from).toList()
                    : List.of(),
                q.formulas() != null
                    ? q.formulas().stream().map(FormulaData::fromFormulaSegment).toList()
                    : List.of(),
                q.rawText(),
                q.groupLayout()
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
    /** One alínea of a question: a), b)... with its own type. */
    public record SubitemData(
        String label,
        String text,
        Double cotacao,
        String type,
        Double confidence,
        List<FormulaData> formulas,
        @JsonProperty("rawText") String rawText
    ) {
        public SubitemData(String label, String text, Double cotacao, String type, Double confidence,
                List<FormulaData> formulas) {
            this(label, text, cotacao, type, confidence, formulas, null);
        }

        /** Preserve the response constructor used before per-alínea OCR metadata was exposed. */
        public SubitemData(String label, String text, Double cotacao, String type) {
            this(label, text, cotacao, type, null, List.of(), null);
        }

        public static SubitemData from(OcrResponse.SubitemContent item) {
            if (item == null) return null;
            return new SubitemData(
                item.label(),
                item.text(),
                item.cotacao(),
                item.type(),
                item.confidence(),
                item.formulas() != null
                    ? item.formulas().stream().map(FormulaData::fromFormulaSegment).toList()
                    : List.of(),
                item.rawText()
            );
        }
    }

    /** One formula recognized in a question's text (#262). {@code kind} is "formula" when there is LaTeX
     * of enough confidence, or "unrecognized" when the span looked like maths but none was produced. */
    public record FormulaData(
            String raw,
            String latex,
            Double confidence,
            String kind,
            Double ocrConfidence,
            Double layoutConfidence
    ) {
        public FormulaData(String raw, String latex, Double confidence, String kind) {
            this(raw, latex, confidence, kind, null, null);
        }

        public static FormulaData fromFormulaSegment(OcrResponse.FormulaSegment f) {
            if (f == null) return null;
            return new FormulaData(f.raw(), f.latex(), f.confidence(), f.kind(), f.ocrConfidence(), f.layoutConfidence());
        }
    }

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

        String region,

        @JsonProperty("page_index") Integer pageIndex,

        List<Integer> bbox,

        @JsonProperty("source_width") Integer sourceWidth,

        @JsonProperty("source_height") Integer sourceHeight,

        @JsonProperty("source_file_index") Integer sourceFileIndex,

        @JsonProperty("contract_version") Integer contractVersion,

        /** figure, table or formula; null for the header and signature crops. */
        String kind,

        /** The crop as a {@code data:image/png;base64,...} URL, so the builder can show it with its question. */
        @JsonProperty("data_url") String dataUrl
    ) {
        /**
         * Create from ImageToUpload.
         */
        public static ImageToUploadData fromImageToUpload(OcrResponse.ImageToUpload img) {
            return fromImageToUpload(img, null);
        }

        /**
         * Create from ImageToUpload, with its crop when there is one.
         */
        public static ImageToUploadData fromImageToUpload(OcrResponse.ImageToUpload img, String dataUrl) {
            if (img == null) return null;

            return new ImageToUploadData(
                img.suggestedFilename(),
                img.description(),
                img.region(),
                img.pageIndex(),
                img.bbox(),
                img.sourceWidth(),
                img.sourceHeight(),
                img.sourceFileIndex(),
                img.contractVersion(),
                img.kind(),
                dataUrl
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
