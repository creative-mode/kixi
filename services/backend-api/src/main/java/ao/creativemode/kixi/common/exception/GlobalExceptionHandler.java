package ao.creativemode.kixi.common.exception;

import ao.creativemode.kixi.client.OcrServiceClient.OcrClientException;
import ao.creativemode.kixi.client.OcrServiceClient.OcrServerException;
import ao.creativemode.kixi.common.dto.ProblemDetail;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global exception handler for the API.
 * Returns RFC 9457 Problem Details in case of errors.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(
        GlobalExceptionHandler.class
    );

    private static final URI DEFAULT_TYPE = URI.create(
        "https://api.kixi.ao/errors"
    );
    private static final URI VALIDATION_ERROR_TYPE = URI.create(
        "https://api.kixi.ao/errors/validation-error"
    );
    private static final URI OCR_ERROR_TYPE = URI.create(
        "https://api.kixi.ao/errors/ocr-error"
    );

    /**
     * Handle custom API exceptions with proper status codes.
     */
    @ExceptionHandler(ApiException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleApiException(
        ApiException ex,
        ServerWebExchange exchange
    ) {
        HttpStatus status = ex.getStatus();

        log.warn(
            "API exception: status={}, message={}",
            status.value(),
            ex.getMessage()
        );

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            status.value(),
            ex.getMessage() != null ? ex.getMessage() : "An error occurred"
        ).withTitle(
            ex.getTitle() != null ? ex.getTitle() : status.getReasonPhrase()
        );

        problem = addInstance(exchange, problem);

        return Mono.just(ResponseEntity.status(status).body(problem));
    }

    /**
     * Handle validation errors from request body binding.
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleValidationErrors(
        WebExchangeBindException ex,
        ServerWebExchange exchange
    ) {
        log.warn("Validation error: {}", ex.getMessage());

        Map<String, Object> fieldErrors = ex
            .getFieldErrors()
            .stream()
            .collect(
                Collectors.toMap(
                    fieldError -> fieldError.getField(),
                    fieldError -> {
                        String msg =
                            fieldError.getDefaultMessage() != null
                                ? fieldError.getDefaultMessage()
                                : "Invalid value";
                        if (fieldError.getRejectedValue() != null) {
                            return Map.of(
                                "message",
                                msg,
                                "rejectedValue",
                                fieldError.getRejectedValue()
                            );
                        }
                        return msg;
                    },
                    (existing, replacement) -> existing // Handle duplicate keys
                )
            );

        ProblemDetail problem = ProblemDetail.validationError(
            "Validation failed for one or more fields",
            fieldErrors
        );

        problem = addInstance(exchange, problem);

        return Mono.just(ResponseEntity.badRequest().body(problem));
    }

    /**
     * Handle OCR client exceptions (4xx errors from OCR service).
     */
    @ExceptionHandler(OcrClientException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleOcrClientException(
        OcrClientException ex,
        ServerWebExchange exchange
    ) {
        log.warn(
            "OCR client error: status={}, message={}",
            ex.getStatusCode(),
            ex.getMessage()
        );

        ProblemDetail problem = new ProblemDetail(
            OCR_ERROR_TYPE,
            "OCR Processing Error",
            ex.getStatusCode(),
            ex.getMessage(),
            Map.of("service", "ocr-service", "errorType", "client_error")
        );

        problem = addInstance(exchange, problem);

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
            "OCR server error: status={}, message={}",
            ex.getStatusCode(),
            ex.getMessage()
        );

        ProblemDetail problem = new ProblemDetail(
            OCR_ERROR_TYPE,
            "OCR Service Unavailable",
            HttpStatus.SERVICE_UNAVAILABLE.value(),
            "The OCR service is temporarily unavailable. Please try again later.",
            Map.of("service", "ocr-service", "errorType", "server_error")
        );

        problem = addInstance(exchange, problem);

        return Mono.just(
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem)
        );
    }

    /**
     * Handle timeout exceptions from OCR service calls.
     */
    @ExceptionHandler(TimeoutException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleTimeoutException(
        TimeoutException ex,
        ServerWebExchange exchange
    ) {
        log.error("Request timeout: {}", ex.getMessage());

        ProblemDetail problem = new ProblemDetail(
            URI.create("https://api.kixi.ao/errors/timeout"),
            "Request Timeout",
            HttpStatus.GATEWAY_TIMEOUT.value(),
            "The request took too long to process. Please try again with a smaller file or fewer images.",
            Map.of("errorType", "timeout")
        );

        problem = addInstance(exchange, problem);

        return Mono.just(
            ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(problem)
        );
    }

    /**
     * Handle illegal argument exceptions (bad requests).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleIllegalArgumentException(
        IllegalArgumentException ex,
        ServerWebExchange exchange
    ) {
        log.warn("Illegal argument: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST.value(),
            ex.getMessage() != null ? ex.getMessage() : "Invalid request"
        ).withTitle("Bad Request");

        problem = addInstance(exchange, problem);

        return Mono.just(ResponseEntity.badRequest().body(problem));
    }

    /**
     * Handle all other uncaught exceptions.
     */
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ProblemDetail>> handleGenericException(
        Exception ex,
        ServerWebExchange exchange
    ) {
        log.error("Unhandled exception occurred", ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "An unexpected error occurred on the server. Please try again later."
        ).withTitle("Internal Server Error");

        problem = addInstance(exchange, problem);

        return Mono.just(ResponseEntity.internalServerError().body(problem));
    }

    /**
     * Adds the 'instance' field with the URI of the current request (RFC 9457 recommended).
     */
    private ProblemDetail addInstance(
        ServerWebExchange exchange,
        ProblemDetail problem
    ) {
        String requestUri = exchange.getRequest().getURI().toString();

        Map<String, Object> currentProps =
            problem.properties() != null
                ? new java.util.HashMap<>(problem.properties())
                : new java.util.HashMap<>();

        currentProps.put("instance", requestUri);
        currentProps.put("timestamp", java.time.Instant.now().toString());

        return new ProblemDetail(
            problem.type() != null ? problem.type() : DEFAULT_TYPE,
            problem.title(),
            problem.status(),
            problem.detail(),
            currentProps
        );
    }
}
