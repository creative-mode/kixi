package ao.creativemode.kixi.academic.dto.classe;

import jakarta.validation.constraints.NotNull;

/**
 * A class belongs to one school, which the backend requires to be the school of its
 * course: there is no school to choose here, because choosing one that disagrees with
 * the course would be a contradiction. Send the course and the year.
 */
public record ClassRequest(
    @NotNull(message = "Code cannot be null") String code,
    @NotNull(message = "Grade is required") Integer grade,
    @NotNull(message = "course id cannot be null") Long courseId,
    @NotNull(message = "school year id cannot be null") Long schoolYearId
) {}
