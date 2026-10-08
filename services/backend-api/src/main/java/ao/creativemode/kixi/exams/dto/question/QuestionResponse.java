package ao.creativemode.kixi.exams.dto.question;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

/**
 * A question as read back.
 *
 * <p>{@code modelAnswer} and {@code needsReview} are the answer key, and they are
 * only filled in for an account that may write the statement. A student reading
 * this route gets them as null, and {@code NON_NULL} keeps them out of the JSON
 * altogether rather than shipping a field that is quietly always empty: that way
 * a client is never left deciding what a missing key meant.
 *
 * <p>The same omission applies to a staff answer when a question genuinely has
 * no model answer. That is the trade for not sending a field whose emptiness
 * carries no information either way.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuestionResponse(
        Long id,
        Long statementId,
        Integer number,
        String text,
        String questionType,
        Double maxScore,
        Integer orderIndex,
        Integer pageIndex,
        String modelAnswer,
        Boolean needsReview,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) { }
