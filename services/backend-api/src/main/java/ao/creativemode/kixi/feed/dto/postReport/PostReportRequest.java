package ao.creativemode.kixi.feed.dto.postReport;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PostReportRequest(
        @NotBlank(message = "Report reason is required")
        @Size(max = 255, message = "Reason cannot exceed 255 characters")
        String reason
) {}