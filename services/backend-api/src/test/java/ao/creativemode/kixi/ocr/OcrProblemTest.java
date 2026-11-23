package ao.creativemode.kixi.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.ocr.client.OcrServiceClient.OcrClientException;
import ao.creativemode.kixi.ocr.client.OcrServiceClient.OcrServerException;
import ao.creativemode.kixi.ocr.exception.OcrExceptionHandler;
import ao.creativemode.kixi.ocr.service.OcrUploadBuffer;
import ao.creativemode.kixi.shared.dto.ProblemDetail;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

/**
 * V2-4: os erros conhecidos do OCR ganham um {@code code} estável (em {@code properties.code} do erro padrão RFC 9457 e em
 * {@code errorCode} do estado do trabalho) para o frontend mapear. A mensagem existente em inglês NÃO muda: o frontend já a mapeia para
 * português (kixi-frontend#841) e não pode partir. A imagem em branco, a ilegível e o ficheiro corrompido distinguem-se pelo que o
 * serviço OCR devolve em {@code errorMessage}.
 */
class OcrProblemTest {

    private static ServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/ocr/jobs").build());
    }

    @Test
    void anInvalidUploadKeepsItsEnglishMessageAndGainsAStableCode() {
        byte[] notAnImage = "isto e texto".getBytes();

        ApiException error = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class, () -> OcrUploadBuffer.validateContent(notAnImage));

        assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error.getCode()).isEqualTo("OCR_FILE_INVALID");
        assertThat(error.getMessage()).isEqualTo("Unsupported or invalid OCR file");
    }

    @Test
    void theApiExceptionCodeReachesTheProblemDetail() {
        ProblemDetail problem = new GlobalExceptionHandler().handleApiException(OcrProblem.FILE_INVALID.exception(), exchange())
                .block().getBody();

        assertThat(problem.status()).isEqualTo(400);
        assertThat(problem.detail()).isEqualTo("Unsupported or invalid OCR file");
        assertThat(problem.properties()).containsEntry("code", "OCR_FILE_INVALID");
    }

    @Test
    void theServiceResultMessagesTellBlankFromUnreadableFromCorrupt() {
        assertThat(OcrProblem.fromServiceMessage("No text could be extracted from the image")).isEqualTo(OcrProblem.NO_TEXT);
        assertThat(OcrProblem.fromServiceMessage("Imagem ilegível (muito desfocada)")).isEqualTo(OcrProblem.UNREADABLE);
        assertThat(OcrProblem.fromServiceMessage("Failed to load image")).isEqualTo(OcrProblem.FILE_CORRUPT);
        assertThat(OcrProblem.fromServiceMessage("Image processing failed")).isEqualTo(OcrProblem.PROCESSING_FAILED);
        assertThat(OcrProblem.fromServiceMessage(null)).isEqualTo(OcrProblem.PROCESSING_FAILED);
        assertThat(OcrProblem.values()).extracting(OcrProblem::code).doesNotHaveDuplicates();
        assertThat(OcrProblem.JOB_FAILED_MESSAGE).isEqualTo("OCR processing failed");
    }

    @Test
    void aFailedJobMapsItsCauseToAStableCode() {
        assertThat(OcrProblem.forJobFailure(new OcrFailure.Unreadable(OcrProblem.NO_TEXT))).isEqualTo(OcrProblem.NO_TEXT);
        assertThat(OcrProblem.forJobFailure(new OcrClientException("OCR request failed: x", 400))).isEqualTo(OcrProblem.FILE_REJECTED);
        assertThat(OcrProblem.forJobFailure(new OcrServerException("OCR service error: {\"status\": \"error\"}", 500, true)))
                .isEqualTo(OcrProblem.PROCESSING_FAILED);
        assertThat(OcrProblem.forJobFailure(new IllegalStateException("boom"))).isEqualTo(OcrProblem.PROCESSING_FAILED);
    }

    @Test
    void theOcrExceptionHandlerKeepsItsMessagesAndAddsTheCode() {
        OcrExceptionHandler handler = new OcrExceptionHandler();

        var rejected = handler.handleOcrClientException(new OcrClientException("OCR request failed: bad", 400), exchange()).block();
        assertThat(rejected.getStatusCode().value()).isEqualTo(400);
        assertThat(rejected.getBody().detail())
                .isEqualTo("The OCR request was rejected. Please verify the uploaded file and try again.");
        assertThat(rejected.getBody().properties()).containsEntry("code", "OCR_FILE_REJECTED").containsEntry("errorType", "client_error");

        var down = handler.handleOcrServerException(new OcrServerException("OCR service error: x", 503), exchange()).block();
        assertThat(down.getStatusCode().value()).isEqualTo(503);
        assertThat(down.getBody().detail()).isEqualTo("The OCR service is temporarily unavailable. Please try again later.");
        assertThat(down.getBody().properties()).containsEntry("code", "OCR_UNAVAILABLE").containsEntry("errorType", "server_error");
    }
}
