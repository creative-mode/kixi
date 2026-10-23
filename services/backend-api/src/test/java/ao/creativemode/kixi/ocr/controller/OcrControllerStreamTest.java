package ao.creativemode.kixi.ocr.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.ocr.client.OcrServiceClient;
import ao.creativemode.kixi.ocr.service.OcrImageAssociationService;
import ao.creativemode.kixi.ocr.service.OcrPersistenceService;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.ApiAbuseRateLimiter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OcrControllerStreamTest {

    private static final byte[] VALID_PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Test
    void exposesAuthenticatedRequestScopedSseWithNoBuffering() {
        OcrServiceClient client = mock(OcrServiceClient.class);
        CurrentAccountService accounts = mock(CurrentAccountService.class);
        ApiAbuseRateLimiter limiter = mock(ApiAbuseRateLimiter.class);
        when(accounts.requiredAccountId()).thenReturn(Mono.just(7L));
        when(limiter.checkUpload(7L)).thenReturn(Mono.empty());
        FilePart file = file("exam.png", 100);
        when(client.extractTextStreamFromUploadedFiles(anyList(), any())).thenReturn(Mono.just(
            org.springframework.http.ResponseEntity.ok(Flux.just(
                ServerSentEvent.<String>builder("{\"page\":1}").event("progress").build()))));
        OcrController controller = controller(client, accounts, limiter);

        StepVerifier.create(controller.extractTextStream(Flux.just(file), 234.0))
            .assertNext(response -> {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
                assertThat(response.getHeaders().getCacheControl()).contains("no-cache");
                assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
                StepVerifier.create(response.getBody())
                    .assertNext(event -> {
                        assertThat(event.event()).isEqualTo("progress");
                        assertThat(event.data()).isEqualTo("{\"page\":1}");
                    })
                    .verifyComplete();
            })
            .verifyComplete();
        verify(client).extractTextStreamFromUploadedFiles(anyList(), any());
    }

    @Test
    void mapsOnlyResultToExamExtractionContractAndAttachesPreviewCropsByPosition() throws Exception {
        OcrServiceClient client = mock(OcrServiceClient.class);
        CurrentAccountService accounts = mock(CurrentAccountService.class);
        ApiAbuseRateLimiter limiter = mock(ApiAbuseRateLimiter.class);
        OcrImageAssociationService images = mock(OcrImageAssociationService.class);
        when(accounts.requiredAccountId()).thenReturn(Mono.just(7L));
        when(limiter.checkUpload(7L)).thenReturn(Mono.empty());
        when(images.previewCrops(anyList(), anyList())).thenReturn(List.of("data:image/png;base64,Y3JvcA=="));
        String rawResult = """
            {"status":"success","requestId":"req-1","processingTimeMs":42,
             "overallConfidence":0.91,"timings":{"preparationMs":8,"rasterizationMs":12,"totalMs":42},
             "questions":[{"number":"1","type":"dissertativa",
             "confidence":0.95,"text":{"value":"Calcule x²","confidence":0.95},
             "formulas":[{"raw":"x²","latex":"x^2","confidence":0.9,"kind":"formula"}],
             "subitemsContent":[{"label":"a","text":"x = 2","type":"dissertativa"}]}],
             "imagesToUpload":[{"suggestedFilename":"q1.png","description":"gráfico",
             "region":"questao_1","pageIndex":0,"bbox":[1,2,30,40],"sourceWidth":100,
             "sourceHeight":100,"sourceFileIndex":0,"contractVersion":1,"kind":"figure"}],
             "warnings":[]}
            """;
        when(client.extractTextStreamFromUploadedFiles(anyList(), any())).thenReturn(Mono.just(
            org.springframework.http.ResponseEntity.ok(Flux.just(
                ServerSentEvent.<String>builder("{\"page\":1}").event("prepared").build(),
                ServerSentEvent.<String>builder("{\"page\":1}").event("progress").build(),
                ServerSentEvent.<String>builder(rawResult).event("result").build()))));
        OcrPersistenceService persistence = mock(OcrPersistenceService.class);
        OcrController controller = controller(client, persistence, accounts, limiter, images);
        ObjectMapper mapper = new ObjectMapper();

        StepVerifier.create(controller.extractTextStream(Flux.just(file("exam.png", 100)), null)
                .flatMapMany(response -> response.getBody()))
            .assertNext(event -> {
                assertThat(event.event()).isEqualTo("prepared");
                assertThat(event.data()).isEqualTo("{\"page\":1}");
            })
            .assertNext(event -> {
                assertThat(event.event()).isEqualTo("progress");
                assertThat(event.data()).isEqualTo("{\"page\":1}");
            })
            .assertNext(event -> {
                assertThat(event.event()).isEqualTo("result");
                JsonNode data = readTree(mapper, event.data());
                assertThat(data.at("/questions/0/text").asText()).isEqualTo("Calcule x²");
                assertThat(data.at("/questions/0/formulas/0/latex").asText()).isEqualTo("x^2");
                assertThat(data.at("/questions/0/subitems_content/0/label").asText()).isEqualTo("a");
                assertThat(data.at("/images_to_upload/0/data_url").asText())
                    .isEqualTo("data:image/png;base64,Y3JvcA==");
                assertThat(data.at("/images_to_upload/0/page_index").asInt()).isZero();
                assertThat(data.at("/timings/preparationMs").asDouble()).isEqualTo(8);
                assertThat(data.at("/timings/totalMs").asDouble()).isEqualTo(42);
                assertThat(data.path("status").asText()).isEqualTo("success");
            })
            .verifyComplete();
        verify(images).previewCrops(anyList(), anyList());
        verify(persistence, never()).processAndPersist(anyList(), any());
    }

    @Test
    void preservesTerminalOcrErrorAndMessageInTheNormalizedResult() throws Exception {
        OcrServiceClient client = mock(OcrServiceClient.class);
        CurrentAccountService accounts = mock(CurrentAccountService.class);
        ApiAbuseRateLimiter limiter = mock(ApiAbuseRateLimiter.class);
        when(accounts.requiredAccountId()).thenReturn(Mono.just(7L));
        when(limiter.checkUpload(7L)).thenReturn(Mono.empty());
        when(client.extractTextStreamFromUploadedFiles(anyList(), any())).thenReturn(Mono.just(
            org.springframework.http.ResponseEntity.ok(Flux.just(
                ServerSentEvent.<String>builder("{\"status\":\"error\",\"errorMessage\":\"PDF ilegível\"}")
                    .event("result").build()))));
        OcrController controller = controller(client, accounts, limiter);
        ObjectMapper mapper = new ObjectMapper();

        StepVerifier.create(controller.extractTextStream(Flux.just(file("exam.png", 100)), null)
                .flatMapMany(response -> response.getBody()))
            .assertNext(event -> {
                JsonNode data = readTree(mapper, event.data());
                assertThat(data.path("status").asText()).isEqualTo("error");
                assertThat(data.path("errorMessage").asText()).isEqualTo("PDF ilegível");
            })
            .verifyComplete();
    }

    @Test
    void rejectsDeclaredOversizedStreamFileBeforeCallingOcrService() {
        OcrServiceClient client = mock(OcrServiceClient.class);
        CurrentAccountService accounts = mock(CurrentAccountService.class);
        ApiAbuseRateLimiter limiter = mock(ApiAbuseRateLimiter.class);
        when(accounts.requiredAccountId()).thenReturn(Mono.just(7L));
        when(limiter.checkUpload(7L)).thenReturn(Mono.empty());
        OcrController controller = controller(client, accounts, limiter);

        StepVerifier.create(controller.extractTextStream(Flux.just(file("exam.pdf", 20L * 1024 * 1024 + 1)), null))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ApiException.class);
                assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            })
            .verify();
        verify(client, never()).extractTextStreamFromUploadedFiles(anyList(), any());
    }

    @Test
    void rejectsDeclaredAggregateOver50MbBeforeBuffering() {
        OcrServiceClient client = mock(OcrServiceClient.class);
        CurrentAccountService accounts = mock(CurrentAccountService.class);
        ApiAbuseRateLimiter limiter = mock(ApiAbuseRateLimiter.class);
        when(accounts.requiredAccountId()).thenReturn(Mono.just(7L));
        when(limiter.checkUpload(7L)).thenReturn(Mono.empty());
        OcrController controller = controller(client, accounts, limiter);

        StepVerifier.create(controller.extractTextStream(Flux.just(
                file("one.pdf", 18L * 1024 * 1024),
                file("two.pdf", 18L * 1024 * 1024),
                file("three.pdf", 18L * 1024 * 1024)), null))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ApiException.class);
                assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            })
            .verify();
        verify(client, never()).extractTextStreamFromUploadedFiles(anyList(), any());
    }

    @Test
    void rejectsEmptyFileInsteadOfShiftingSourceFileIndexes() {
        OcrServiceClient client = mock(OcrServiceClient.class);
        CurrentAccountService accounts = mock(CurrentAccountService.class);
        ApiAbuseRateLimiter limiter = mock(ApiAbuseRateLimiter.class);
        when(accounts.requiredAccountId()).thenReturn(Mono.just(7L));
        when(limiter.checkUpload(7L)).thenReturn(Mono.empty());
        FilePart empty = file("empty.png", 0);
        when(empty.content()).thenReturn(Flux.empty());
        OcrController controller = controller(client, accounts, limiter);

        StepVerifier.create(controller.extractTextStream(Flux.just(empty), null))
            .expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot be empty"))
            .verify();
        verify(client, never()).extractTextStreamFromUploadedFiles(anyList(), any());
    }

    private static OcrController controller(
        OcrServiceClient client,
        CurrentAccountService accounts,
        ApiAbuseRateLimiter limiter
    ) {
        return controller(client, accounts, limiter, mock(OcrImageAssociationService.class));
    }

    private static OcrController controller(
        OcrServiceClient client,
        CurrentAccountService accounts,
        ApiAbuseRateLimiter limiter,
        OcrImageAssociationService images
    ) {
        return controller(client, mock(OcrPersistenceService.class), accounts, limiter, images);
    }

    private static OcrController controller(
        OcrServiceClient client,
        OcrPersistenceService persistence,
        CurrentAccountService accounts,
        ApiAbuseRateLimiter limiter,
        OcrImageAssociationService images
    ) {
        return new OcrController(
            client,
            persistence,
            accounts,
            limiter,
            images,
            new ObjectMapper()
        );
    }

    private static JsonNode readTree(ObjectMapper mapper, String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static FilePart file(String filename, long contentLength) {
        FilePart file = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(contentLength);
        when(file.filename()).thenReturn(filename);
        when(file.headers()).thenReturn(headers);
        when(file.content()).thenReturn(contentLength == 0
            ? Flux.empty()
            : Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(VALID_PNG)));
        return file;
    }
}
