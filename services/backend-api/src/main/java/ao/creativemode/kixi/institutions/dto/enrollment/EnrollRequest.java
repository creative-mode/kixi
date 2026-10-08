package ao.creativemode.kixi.institutions.dto.enrollment;

import jakarta.validation.constraints.NotNull;

public record EnrollRequest(
        /**
         * Target account. Null (or equal to the caller) means "myself".
         * Only ADMIN/TEACHER may enroll another account.
         */
        Long accountId,
        @NotNull(message = "Class is required")
        Long classId,
        @NotNull(message = "School year is required")
        Long schoolYearId
) { }
