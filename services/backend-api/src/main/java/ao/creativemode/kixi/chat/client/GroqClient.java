package ao.creativemode.kixi.chat.client;

import ao.creativemode.kixi.chat.config.GroqProperties;
import ao.creativemode.kixi.shared.exception.ApiException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.SynchronousSink;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Streaming client for Groq's OpenAI-compatible {@code /chat/completions}
 * (issue #114 recovers the connection idea from the closed PR #55, rebuilt on
 * the module's current patterns: ApiException errors, explicit timeouts, and
 * no key material anywhere but the environment).
 *
 * The response arrives as server-sent events; each {@code data:} payload is a
 * JSON fragment whose {@code choices[0].delta.content} carries the next piece
 * of the answer, terminated by {@code [DONE]}.
 */
@Component
public class GroqClient {

    private static final Logger log = LoggerFactory.getLogger(GroqClient.class);

    private final WebClient webClient;
    private final GroqProperties properties;
    private final ObjectMapper objectMapper;

    public GroqClient(GroqProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Bearer " + (properties.getApiKey() == null ? "" : properties.getApiKey().trim()))
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(1024 * 1024)) // answers are text
                .build();

        log.info("Groq client initialized: url={}, model={}, configured={}",
                properties.getBaseUrl(), properties.getModel(), isConfigured());
    }

    /** False when no API key is set — the tutor must answer 503 until one is. */
    public boolean isConfigured() {
        return properties.getApiKey() != null && !properties.getApiKey().isBlank();
    }

    public String getModel() {
        return properties.getModel();
    }

    /**
     * Stream the answer as text fragments in order.
     *
     * Errors are always {@link ApiException}s so callers can map them without
     * knowing the provider: 503 when unconfigured, 502 when Groq refuses or
     * fails, 504 when it stops answering within the configured timeout.
     */
    public Flux<String> streamChat(List<GroqMessage> messages) {
        if (!isConfigured()) {
            return Flux.error(ApiException.serviceUnavailable(
                    "AI tutor is not configured: set APP_GROQ_API_KEY."));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("messages", messages);
        body.put("stream", true);
        body.put("temperature", properties.getTemperature());
        body.put("max_tokens", properties.getMaxTokens());

        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(data -> data != null && !data.isBlank() && !"[DONE]".equals(data.trim()))
                .handle((String data, SynchronousSink<String> sink) -> {
                    String delta = deltaOf(data);
                    if (delta != null && !delta.isEmpty()) {
                        sink.next(delta);
                    }
                })
                // No new fragment within the timeout counts as a stall: the
                // provider stopped talking and the student should not wait
                // forever for the rest of the answer.
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .onErrorMap(WebClientResponseException.class, error ->
                        ApiException.badGateway(String.format(
                                "AI tutor provider answered HTTP %d.",
                                error.getStatusCode().value())))
                .onErrorMap(TimeoutException.class, error ->
                        ApiException.gatewayTimeout(
                                "AI tutor timed out waiting for the provider."));
    }

    /** Extract {@code choices[0].delta.content}; null for role-only chunks. */
    private String deltaOf(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode delta = node.path("choices").path(0).path("delta").path("content");
            return delta.isMissingNode() || delta.isNull() ? null : delta.asText();
        } catch (JsonProcessingException exception) {
            // Thrown inside the stream: it propagates as this 502 ApiException
            // (neither onErrorMap below rewrites it), never as an unreadable 500.
            throw ApiException.badGateway(
                    "AI tutor provider sent a response the server could not read.");
        }
    }
}
