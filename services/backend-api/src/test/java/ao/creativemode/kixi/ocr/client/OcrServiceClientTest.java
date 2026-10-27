package ao.creativemode.kixi.ocr.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.multipart.FilePart;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import reactor.core.publisher.Flux;
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
    void streamsMeasuredPageEventsWithoutPuttingCredentialsInTheEventBody() throws Exception {
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("event: progress\ndata: {\"page\":1,\"pageCount\":2,\"timings\":{\"ocrMs\":12.5}}\n\n"));

        FilePart file = org.mockito.Mockito.mock(FilePart.class);
        org.mockito.Mockito.when(file.filename()).thenReturn("exam.pdf");
        org.mockito.Mockito.when(file.headers()).thenReturn(new HttpHeaders());
        org.mockito.Mockito.when(file.content()).thenReturn(Flux.just(
            DefaultDataBufferFactory.sharedInstance.wrap(new byte[] {1, 2, 3})));

        StepVerifier.create(client(0, "internal-ocr-key").extractTextStream(List.of(file), 234.0)
                .flatMapMany(response -> response.getBody()))
            .assertNext(event -> {
                assertThat(event.event()).isEqualTo("progress");
                assertThat(event.data()).contains("\"page\":1", "\"ocrMs\":12.5");
                assertThat(event.data()).doesNotContain("internal-ocr-key");
            })
            .verifyComplete();

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getPath()).isEqualTo("/ocr/v1/extract/stream");
        assertThat(request.getHeader("Accept")).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(request.getHeader("X-OCR-API-Key")).isEqualTo("internal-ocr-key");
        assertThat(request.getHeader("X-OCR-Upload-Ms")).isEqualTo("234.0");
    }

    @Test
    void streamsFromBufferedUploadBytesWithoutChangingTheSseEvents() throws Exception {
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("event: prepared\ndata: {\"pages\":2}\n\nevent: progress\ndata: {\"page\":1}\n\n"));
        OcrUploadedFile file = new OcrUploadedFile(
            "exam.pdf", MediaType.APPLICATION_PDF, new byte[] {1, 2, 3});
        String requestId = "ad8f8b31-35a0-4f76-ae46-692ec432601e";

        StepVerifier.create(client(0, "").extractTextStreamFromUploadedFiles(List.of(file), null, requestId)
                .flatMapMany(response -> response.getBody()))
            .assertNext(event -> {
                assertThat(event.event()).isEqualTo("prepared");
                assertThat(event.data()).isEqualTo("{\"pages\":2}");
            })
            .assertNext(event -> {
                assertThat(event.event()).isEqualTo("progress");
                assertThat(event.data()).isEqualTo("{\"page\":1}");
            })
            .verifyComplete();

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/ocr/v1/extract/stream");
        assertThat(request.getHeader("X-OCR-Request-ID")).isEqualTo(requestId);
        assertThat(request.getBody().readUtf8()).contains("filename=\"exam.pdf\"");
    }

    @Test
    void preservesHttpStatusWhenStreamIsRejectedBeforeSseStarts() {
        server.enqueue(new MockResponse().setResponseCode(413).setBody("upload too large"));
        FilePart file = org.mockito.Mockito.mock(FilePart.class);
        org.mockito.Mockito.when(file.filename()).thenReturn("exam.pdf");
        org.mockito.Mockito.when(file.content()).thenReturn(Flux.just(
            DefaultDataBufferFactory.sharedInstance.wrap(new byte[] {1})));

        StepVerifier.create(client(0, "").extractTextStream(List.of(file))
                .flatMapMany(response -> response.getBody()))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(OcrServiceClient.OcrClientException.class);
                assertThat(((OcrServiceClient.OcrClientException) error).getStatusCode()).isEqualTo(413);
            })
            .verify();
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
    void doesNotRetryAContentErrorThatArrivesAsFiveHundred() {
        server.enqueue(
            new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "status": "error",
                      "requestId": "req-no-text",
                      "questions": [],
                      "imagesToUpload": [],
                      "unmappedContent": [],
                      "warnings": []
                    }
                    """)
        );

        OcrServiceClient client = client(2);

        StepVerifier.create(client.extractTextFromBytes(new byte[] {1}, "exam.png"))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(OcrServiceClient.OcrServerException.class);
                assertThat(((OcrServiceClient.OcrServerException) error).isContentError()).isTrue();
            })
            .verify();

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void retriesAFiveHundredWithoutTheContentStatus() {
        server.enqueue(
            new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detail\":\"OCR processing failed\"}")
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

        StepVerifier.create(client.extractTextFromBytes(new byte[] {1}, "exam.png"))
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

    private OcrServiceClient client(int maxRetries, String apiKey) {
        return new OcrServiceClient(server.url("/").toString(), 5000, maxRetries, apiKey);
    }

    private MockResponse jsonResponse(String body) {
        return new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body);
    }
}
