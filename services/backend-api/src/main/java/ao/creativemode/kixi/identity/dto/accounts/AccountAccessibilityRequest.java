package ao.creativemode.kixi.identity.dto.accounts;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Issue #107: the accessibility extra time of an account (+25% on the
 * statement duration). Only an administrator writes it, so it does not ride
 * on the full account PUT, which re-encodes the password and requires every
 * field.
 */
public record AccountAccessibilityRequest(
    @JsonProperty("accessibility_extra_time")
    @NotNull(message = "accessibility_extra_time is required") Boolean accessibilityExtraTime
) {}
