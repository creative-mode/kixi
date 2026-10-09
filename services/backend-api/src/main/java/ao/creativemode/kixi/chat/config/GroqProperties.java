package ao.creativemode.kixi.chat.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Groq connection settings for the AI tutor (issue #114).
 *
 * The API key is optional on purpose: the application must start without it
 * (compose never fails on a missing key) and the tutor endpoints answer a
 * clear 503 until one is configured. Bound from the environment through
 * Spring's relaxed binding, e.g. {@code APP_GROQ_API_KEY} → {@code app.groq.api-key}.
 */
@Component
@ConfigurationProperties(prefix = "app.groq")
@Validated
public class GroqProperties {

    /** Empty = unconfigured; the tutor answers 503 until this is set. */
    private String apiKey = "";

    private String baseUrl = "https://api.groq.com/openai/v1";

    private String model = "llama-3.3-70b-versatile";

    @Min(1_000)
    private long timeoutMs = 30_000;

    @Min(1)
    private int maxTokens = 1024;

    @Min(0)
    @Max(2)
    private double temperature = 0.3;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
}
