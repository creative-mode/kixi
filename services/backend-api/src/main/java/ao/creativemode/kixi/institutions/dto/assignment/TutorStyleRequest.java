package ao.creativemode.kixi.institutions.dto.assignment;

import jakarta.validation.constraints.Size;

/**
 * The tutor style of one assignment (#206), the only field a teacher may change on
 * their own assignments. Blank clears it.
 */
public record TutorStyleRequest(
    @Size(max = 100, message = "The tutor style must not exceed 100 characters")
    String tutorStyle
) { }
