package ao.creativemode.kixi.simulations.community.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One resolution a student chose to share (issue #119).
 *
 * <p>Only consented shares are ever rendered, and an anonymous share carries
 * no name at all — the account behind it is never exposed, not even its id.
 * There is no answer key here either: {@code selectedLabel} is what the
 * author chose, never which option is right.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SharedSolutionResponse(
        Long shareId,
        InstitutionRef institution,
        String authorName,
        Double finalScore,
        Integer timeSpentSeconds,
        List<SharedAnswer> answers
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InstitutionRef(Long id, String code) { }

    /**
     * @param selectedLabel the option label the author picked (A, B, …), null
     *                      for free text or when the option has since been removed
     * @param answerText    what the author wrote; sharing communicates that this
     *                      text becomes visible to other students
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SharedAnswer(
            Long questionId,
            Integer number,
            String selectedLabel,
            String answerText,
            Float scoreObtained
    ) { }
}
