package ao.creativemode.kixi.institutions.dto.enrollment;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateEnrollmentCodeRequest(
        @NotNull(message = "Class is required")
        Long classId,
        /** Defaults to 1 (one student per code) when omitted. */
        @Min(value = 1, message = "maxUses must be at least 1")
        Integer maxUses,
        /** Null means the code never expires on its own. */
        LocalDateTime expiresAt
) { }
