package ao.creativemode.kixi.exams.dto.questionoption;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

/**
 * An option as read back.
 *
 * <p>{@code isCorrect} is the answer key, so it is only filled in for an account
 * that may write the statement. A student gets it as null and {@code NON_NULL}
 * keeps it out of the JSON, which is the whole point of the route: reading the
 * alternatives of a question must not read which one is the answer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuestionOptionResponse(
        Long id,
        Long questionId,
        String optionLabel,
        String optionText,
        Boolean isCorrect,
        Integer orderIndex,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) { }
