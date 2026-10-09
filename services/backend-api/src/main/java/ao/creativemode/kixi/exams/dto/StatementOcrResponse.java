package ao.creativemode.kixi.exams.dto;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.service.StatementWithQuestions;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Full statement response including its questions and options. Shared by
 * StatementController (GET .../full) and the OCR-based creation endpoints
 * in the ocr module, both of which need to render a StatementWithQuestions.
 *
 * <p>{@code needsReview} and, inside the questions, {@code isCorrect} and
 * {@code needsReview} are the answer key and the editorial state. They are only
 * filled in for a caller who may change the statement. See {@link #from} for why
 * that started mattering when it did.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
    /**
     * Renders a statement with its questions and options.
     *
     * <p>{@code staff} decides whether the answer key travels. Until the question
     * and option routes of #104 existed there was nothing to hide here: the OCR
     * wrote {@code is_correct = false} on every option and no teacher could mark
     * an answer, so a student reading this route saw an answer key that was the
     * same for every paper. That PR gave teachers a way to set the real one, which
     * made this response leak something worth leaking — while the routes written
     * alongside it withheld it carefully. Two routes for the same questions, one
     * that hides the answer and one that did not.
     *
     * <p>Staff still see everything, which is the same rule as in
     * {@code StatementController.readableStatementWithQuestions}: an
     * administrator and a teacher reach the full paper, anyone else only reaches
     * a published one.
     */
    public static StatementOcrResponse from(StatementWithQuestions result, boolean staff) {
        Statement s = result.statement();
        List<Question> questions = result.questions();
        List<QuestionOption> allOptions = result.options();

        List<QuestionResponse> questionResponses = questions
            .stream()
            .map(q -> {
                List<OptionResponse> options = allOptions
                    .stream()
                    .filter(opt -> opt.getQuestionId().equals(q.getId()))
                    .map(opt -> OptionResponse.from(opt, staff))
                    .toList();
                return QuestionResponse.from(q, options, staff);
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
            staff ? s.getNeedsReview() : null,
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
     * Renders a statement the caller has just written or corrected, so the answer
     * key is theirs to see.
     */
    public static StatementOcrResponse from(StatementWithQuestions result) {
        return from(result, true);
    }

/**
 * Question response DTO.
 *
 * <p>{@code NON_NULL} is repeated here and on {@link OptionResponse} because an
 * annotation on the enclosing record governs only that record's own properties:
 * without it on the nested types the withheld fields arrive as null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
            List<OptionResponse> options,
            boolean staff
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
                staff ? q.getNeedsReview() : null,
                options
            );
        }
    }

    /**
     * Option response DTO.
     *
     * <p>{@code isCorrect} is the answer, and only staff get it. For anyone else
     * the field is absent from the JSON rather than null: a null invites a client
     * to read meaning into a value that carries none.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OptionResponse(
        Long id,
        String optionLabel,
        String optionText,
        Boolean isCorrect,
        Integer orderIndex,
        Double ocrConfidence
    ) {
        public static OptionResponse from(QuestionOption o, boolean staff) {
            return new OptionResponse(
                o.getId(),
                o.getOptionLabel(),
                o.getOptionText(),
                staff ? o.getIsCorrect() : null,
                o.getOrderIndex(),
                o.getOcrConfidence()
            );
        }
    }
}
