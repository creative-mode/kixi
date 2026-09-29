package ao.creativemode.kixi.exams.dto;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.service.StatementWithQuestions;
import java.util.List;

/**
 * Full statement response including its questions and options. Shared by
 * StatementController (GET .../full) and the OCR-based creation endpoints
 * in the ocr module, both of which need to render a StatementWithQuestions.
 */
public record StatementOcrResponse(
    Long id,
    String title,
    String examType,
    Integer durationMinutes,
    String variant,
    String instructions,
    Double totalMaxScore,
    Boolean visible,
    Boolean needsReview,
    String source,
    Double ocrConfidence,
    String ocrRequestId,
    Long schoolYearId,
    Long termId,
    Long subjectId,
    Long classId,
    List<QuestionResponse> questions
) {
    public static StatementOcrResponse from(StatementWithQuestions result) {
        Statement s = result.statement();
        List<Question> questions = result.questions();
        List<QuestionOption> allOptions = result.options();

        List<QuestionResponse> questionResponses = questions
            .stream()
            .map(q -> {
                List<OptionResponse> options = allOptions
                    .stream()
                    .filter(opt -> opt.getQuestionId().equals(q.getId()))
                    .map(OptionResponse::from)
                    .toList();
                return QuestionResponse.from(q, options);
            })
            .toList();

        return new StatementOcrResponse(
            s.getId(),
            s.getTitle(),
            s.getExamType(),
            s.getDurationMinutes(),
            s.getVariant(),
            s.getInstructions(),
            s.getTotalMaxScore(),
            s.getVisible(),
            s.getNeedsReview(),
            s.getSource(),
            s.getOcrConfidence(),
            s.getOcrRequestId(),
            s.getSchoolYearId(),
            s.getTermId(),
            s.getSubjectId(),
            s.getClassId(),
            questionResponses
        );
    }

    /**
     * Question response DTO.
     */
    public record QuestionResponse(
        Long id,
        Integer number,
        String text,
        String questionType,
        Double maxScore,
        Integer orderIndex,
        Double ocrConfidence,
        Integer pageIndex,
        Boolean needsReview,
        List<OptionResponse> options
    ) {
        public static QuestionResponse from(
            Question q,
            List<OptionResponse> options
        ) {
            return new QuestionResponse(
                q.getId(),
                q.getNumber(),
                q.getText(),
                q.getQuestionType(),
                q.getMaxScore(),
                q.getOrderIndex(),
                q.getOcrConfidence(),
                q.getPageIndex(),
                q.getNeedsReview(),
                options
            );
        }
    }

    /**
     * Option response DTO.
     */
    public record OptionResponse(
        Long id,
        String optionLabel,
        String optionText,
        Boolean isCorrect,
        Integer orderIndex,
        Double ocrConfidence
    ) {
        public static OptionResponse from(QuestionOption o) {
            return new OptionResponse(
                o.getId(),
                o.getOptionLabel(),
                o.getOptionText(),
                o.getIsCorrect(),
                o.getOrderIndex(),
                o.getOcrConfidence()
            );
        }
    }
}
