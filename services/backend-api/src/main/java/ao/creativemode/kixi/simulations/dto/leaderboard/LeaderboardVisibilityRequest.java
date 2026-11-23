package ao.creativemode.kixi.simulations.dto.leaderboard;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * One scope's answer to "how do my peers see me in the ranking?".
 *
 * <p>The scope is in the path, not the body, and the account comes from the token. Neither
 * is optional, because a body that could name another student would turn a preference into
 * a way to write somebody else's privacy setting.</p>
 *
 * @param rankingVisibility {@code visible} (the masked name used today), {@code anonymous}
 *                          (the neutral label "Um colega") or {@code out} (not shown to
 *                          peers). The student still sees their own position either way
 */
public record LeaderboardVisibilityRequest(
        @NotBlank(message = "rankingVisibility is required")
        @Pattern(regexp = "visible|anonymous|out",
                message = "rankingVisibility must be visible, anonymous or out")
        String rankingVisibility
) { }
