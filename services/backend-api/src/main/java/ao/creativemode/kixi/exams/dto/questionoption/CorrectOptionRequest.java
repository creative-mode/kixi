package ao.creativemode.kixi.exams.dto.questionoption;

import jakarta.validation.constraints.NotNull;

/**
 * Which option of a question is the answer.
 *
 * <p>The option has to belong to the question in the path. The query that marks
 * it correct rewrites {@code is_correct} on every option of that question, so a
 * foreign id would quietly leave the question with no answer at all instead of
 * failing.
 */
public record CorrectOptionRequest(
    @NotNull(message = "The option id is required")
    Long optionId
) { }