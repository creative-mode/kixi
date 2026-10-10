package ao.creativemode.kixi.simulations.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * How the server enforces the time of a simulation (issue #107: "BE-11
 * servidor impõe o tempo da prova"). Bound from the environment as e.g.
 * {@code APP_SIMULATIONS_TOLERANCE_SECONDS} →
 * {@code app.simulations.tolerance-seconds}.
 */
@Component
@ConfigurationProperties(prefix = "app.simulations")
@Validated
public class SimulationTimeLimitProperties {

    /**
     * Slack granted after the deadline: answers are still accepted inside it
     * and the expiration job does not close the simulation yet. It absorbs the
     * round trip of an answer that left the student before the clock ran out.
     */
    @Min(0)
    private int toleranceSeconds = 10;

    /**
     * How often the expiration job sweeps the simulations still in progress.
     */
    @Min(1)
    private long expiryPollMs = 60000;

    /**
     * Turns the automatic closing of expired simulations off, keeping the
     * answer gate in place.
     */
    private boolean expiryEnabled = true;

    public int getToleranceSeconds() {
        return toleranceSeconds;
    }

    public void setToleranceSeconds(int toleranceSeconds) {
        this.toleranceSeconds = toleranceSeconds;
    }

    public long getExpiryPollMs() {
        return expiryPollMs;
    }

    public void setExpiryPollMs(long expiryPollMs) {
        this.expiryPollMs = expiryPollMs;
    }

    public boolean isExpiryEnabled() {
        return expiryEnabled;
    }

    public void setExpiryEnabled(boolean expiryEnabled) {
        this.expiryEnabled = expiryEnabled;
    }
}