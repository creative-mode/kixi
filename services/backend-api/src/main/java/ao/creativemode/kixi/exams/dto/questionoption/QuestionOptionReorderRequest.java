package ao.creativemode.kixi.exams.dto.questionoption;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** The new order of a question's options; same contract as {@code QuestionReorderRequest}. */
public record QuestionOptionReorderRequest(
        @NotEmpty(message = "The list of option ids is required")
        List<Long> optionIds
) { }