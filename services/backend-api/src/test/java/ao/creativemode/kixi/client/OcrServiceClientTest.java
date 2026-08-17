package ao.creativemode.kixi.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import reactor.test.StepVerifier;

class OcrServiceClientTest {

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
    void rejectsEmptyImageBytesBeforeMakingARequest() {
        OcrServiceClient client = client(0);

        StepVerifier.create(client.extractTextFromBytes(new byte[0], "exam.png"))
            .expectErrorSatisfies(error -> {
                assertThat(error)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Image bytes cannot be empty");
            })
            .verify();

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void sendsMultipartImageAndMapsSuccessfulResponse() throws Exception {
        server.enqueue(jsonResponse("""
            {
              "status": "success",
              "requestId": "req-1",
              "processingTimeMs": 42,
              "overallConfidence": 0.91,
              "questions": [],
              "imagesToUpload": [],
              "unmappedContent": [],
              "warnings": []
            }
            """));

        OcrServiceClient client = client(0);

        StepVerifier.create(client.extractTextFromBytes(new byte[] {1, 2, 3}, "exam.png"))
            .assertNext(response -> {
                assertThat(response.isSuccess()).isTrue();
                assertThat(response.requestId()).isEqualTo("req-1");
                assertThat(response.processingTimeMs()).isEqualTo(42);
                assertThat(response.overallConfidence()).isEqualTo(0.91);
            })
            .verifyComplete();

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/ocr/v1/extract");
        assertThat(request.getHeader("Content-Type"))
            .startsWith("multipart/form-data;");
        assertThat(request.getBody().readUtf8()).contains("filename=\"exam.png\"");
    }

    @Test
    void sendsInternalApiKeyWhenConfigured() throws Exception {
        server.enqueue(jsonResponse("""
            {
              "status": "success",
              "requestId": "req-authenticated",
              "questions": [],
              "imagesToUpload": [],
              "unmappedContent": [],
              "warnings": []
            }
            """));

        OcrServiceClient client = new OcrServiceClient(
            server.url("/").toString(), 5000, 0, "internal-ocr-key");

        client.extractTextFromBytes(new byte[] {1, 2, 3}, "exam.png").block();

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getHeader("X-OCR-API-Key")).isEqualTo("internal-ocr-key");
    }

    @Test
    void mapsClientErrorWithoutRetrying() {
        server.enqueue(
            new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detail\":\"unsupported file\"}")
        );

        OcrServiceClient client = client(2);

        assertThatThrownBy(() -> client.extractTextFromBytes(new byte[] {1}, "exam.png").block())
            .isInstanceOf(OcrServiceClient.OcrClientException.class)
            .hasMessageContaining("unsupported file");

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void retriesServerErrorAndReturnsTheNextSuccessfulResponse() {
        server.enqueue(
            new MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detail\":\"temporarily unavailable\"}")
        );
        server.enqueue(jsonResponse("""
            {
              "status": "success",
              "requestId": "req-after-retry",
              "questions": [],
              "imagesToUpload": [],
              "unmappedContent": [],
              "warnings": []
            }
            """));

        OcrServiceClient client = client(1);

        StepVerifier.create(client.extractTextFromBytes(new byte[] {1}, "exam.jpg"))
            .assertNext(response -> assertThat(response.requestId()).isEqualTo("req-after-retry"))
            .verifyComplete();

        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void healthCheckReturnsFalseForUnavailableService() {
        OcrServiceClient client = client(0);

        StepVerifier.create(client.healthCheck())
            .expectNext(false)
            .verifyComplete();
    }

    private OcrServiceClient client(int maxRetries) {
        return new OcrServiceClient(server.url("/").toString(), 5000, maxRetries);
    }

    private MockResponse jsonResponse(String body) {
        return new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body);
    }
}
