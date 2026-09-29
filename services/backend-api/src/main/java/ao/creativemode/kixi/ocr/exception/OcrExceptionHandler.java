package ao.creativemode.kixi.ocr.exception;

import ao.creativemode.kixi.ocr.client.OcrServiceClient.OcrClientException;
import ao.creativemode.kixi.ocr.client.OcrServiceClient.OcrServerException;
import ao.creativemode.kixi.shared.dto.ProblemDetail;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Maps failures talking to the OCR microservice to RFC 9457 Problem Details.
 * Kept in the ocr module, not shared.GlobalExceptionHandler, so that shared
 * never depends on the ocr client.
 */
@ControllerAdvice
public class OcrExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OcrExceptionHandler.class);

    private static final URI OCR_ERROR_TYPE = URI.create("https://api.kixi.ao/errors/ocr-error");

    /**
     * Handle OCR client exceptions (4xx errors from OCR service).
     */
    @ExceptionHandler(OcrClientException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleOcrClientException(
        OcrClientException ex,
        ServerWebExchange exchange
    ) {
        log.warn(
            "OCR client error: status={}, type={}, requestId={}",
            ex.getStatusCode(),
            ex.getClass().getSimpleName(),
            RequestIdWebFilter.requestId(exchange)
        );

        ProblemDetail problem = new ProblemDetail(
            OCR_ERROR_TYPE,
            "OCR Processing Error",
            ex.getStatusCode(),
            "The OCR request was rejected. Please verify the uploaded file and try again.",
            Map.of("service", "ocr-service", "errorType", "client_error")
        ).withInstance(exchange);

        return Mono.just(
            ResponseEntity.status(ex.getStatusCode()).body(problem)
        );
    }

    /**
     * Handle OCR server exceptions (5xx errors from OCR service).
     */
    @ExceptionHandler(OcrServerException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleOcrServerException(
        OcrServerException ex,
        ServerWebExchange exchange
    ) {
        log.error(
            "OCR server error: status={}, type={}, requestId={}",
            ex.getStatusCode(),
            ex.getClass().getSimpleName(),
            RequestIdWebFilter.requestId(exchange)
        );

        ProblemDetail problem = new ProblemDetail(
            OCR_ERROR_TYPE,
            "OCR Service Unavailable",
            HttpStatus.SERVICE_UNAVAILABLE.value(),
            "The OCR service is temporarily unavailable. Please try again later.",
            Map.of("service", "ocr-service", "errorType", "server_error")
        ).withInstance(exchange);

        return Mono.just(
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem)
        );
    }
}
