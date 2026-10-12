package ao.creativemode.kixi.simulations.community.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

/** Receipt of an opt-in share: what the student agreed to show, and when. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SolutionShareResponse(
        Long shareId,
        Long simulationId,
        Boolean anonymous,
        String displayName,
        LocalDateTime consentAt
) { }
