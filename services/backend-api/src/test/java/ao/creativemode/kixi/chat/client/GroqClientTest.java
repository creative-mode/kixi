package ao.creativemode.kixi.chat.client;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.chat.config.GroqProperties;
import ao.creativemode.kixi.shared.exception.ApiException;

import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The tutor's provider conversation, against a fake Groq — the CI must never
 * need a real key (issue #114 acceptance criterion).
 */
class GroqClientTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void streamsDeltasInOrderAndDropsTheDoneSentinel() {
        server.enqueue(sseResponse("""
                data: {"choices":[{"delta":{"role":"assistant"}}]}

                data: {"choices":[{"delta":{"content":"Ol"}}]}

                data: {"choices":[{"delta":{"content":"á"}}]}

                data: [DONE]

                """));

        StepVerifier.create(client("test-key").streamChat(List.of(userMessage())))
                .expectNext("Ol", "á")
                .verifyComplete();
    }

    @Test
    void sendsTheOpenAiWireShapeWithTheKeyInTheHeader() throws Exception {
        server.enqueue(sseResponse("""
                data: {"choices":[{"delta":{"content":"ok"}}]}

                data: [DONE]

                """));

        StepVerifier.create(client("secret-key").streamChat(List.of(userMessage())))
                .expectNext("ok")
                .verifyComplete();

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer secret-key");
        assertThat(request.getHeader("Accept")).isEqualTo("text/event-stream");
        assertThat(request.getBody().readUtf8())
                .contains("\"stream\":true")
                .contains("\"model\":\"test-model\"")
                .contains("\"messages\"");
    }

    @Test
    void refusesToCallTheProviderWithoutAKey() {
        StepVerifier.create(client("").streamChat(List.of(userMessage())))
                .expectErrorSatisfies(error -> assertApiStatus(error, 503))
                .verify();

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void mapsProviderFailuresTo502() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("upstream boom"));

        StepVerifier.create(client("test-key").streamChat(List.of(userMessage())))
                .expectErrorSatisfies(error -> assertApiStatus(error, 502))
                .verify();
    }

    @Test
    void mapsAStalledProviderTo504() {
        server.enqueue(sseResponse("data: {\"choices\":[{\"delta\":{\"content\":\"slow\"}}]}\n\n")
                .setBodyDelay(2, TimeUnit.SECONDS));

        StepVerifier.create(client("test-key", 100).streamChat(List.of(userMessage())))
                .expectErrorSatisfies(error -> assertApiStatus(error, 504))
                .verify();
    }

    @Test
    void unreadableFragmentsBecome502InsteadOfAFailedParse() {
        server.enqueue(sseResponse("data: {not json at all}\n\n"));

        StepVerifier.create(client("test-key").streamChat(List.of(userMessage())))
                .expectErrorSatisfies(error -> assertApiStatus(error, 502))
                .verify();
    }

    private static void assertApiStatus(Throwable error, int status) {
        assertThat(error).isInstanceOf(ApiException.class);
        assertThat(((ApiException) error).getStatusCode()).isEqualTo(status);
    }

    private static GroqMessage userMessage() {
        return new GroqMessage("user", "olá");
    }

    private static MockResponse sseResponse(String body) {
        return new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body);
    }

    private GroqClient client(String apiKey) {
        return client(apiKey, 5_000);
    }

    private GroqClient client(String apiKey, long timeoutMs) {
        GroqProperties properties = new GroqProperties();
        properties.setApiKey(apiKey);
        properties.setBaseUrl(server.url("/").toString());
        properties.setModel("test-model");
        properties.setTimeoutMs(timeoutMs);
        return new GroqClient(properties, new ObjectMapper());
    }
}
