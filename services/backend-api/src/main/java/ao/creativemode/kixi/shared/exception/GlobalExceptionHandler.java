package ao.creativemode.kixi.shared.exception;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;

import ao.creativemode.kixi.shared.dto.ProblemDetail;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
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
        "https://api.kixi.com/errors"
    );

    /**
     * Handle custom API exceptions with proper status codes.
     */
    @ExceptionHandler(ApiException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleApiException(
        ApiException ex,
        ServerWebExchange exchange
    ) {
        HttpStatus status =
            ex.getStatus() != null
                ? ex.getStatus()
                : HttpStatus.INTERNAL_SERVER_ERROR;
        int statusCode = status.value();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            statusCode,
            ex.getMessage() != null ? ex.getMessage() : "API Error occurred"
        ).withTitle(status.getReasonPhrase());
        problem = problem.withInstance(exchange);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(statusCode);
        if (ex instanceof RegistrationRateLimitException rateLimitException) {
            response.header("Retry-After", String.valueOf(rateLimitException.getRetryAfterSeconds()));
        }
        return Mono.just(response.body(problem));
    }

/**
 * Handle validation errors from request body binding.
 */
@ExceptionHandler(WebExchangeBindException.class)
public Mono<ResponseEntity<ProblemDetail>> handleValidationErrors(
    WebExchangeBindException ex,
    ServerWebExchange exchange
) {
        Map<String, Object> fieldErrors = ex
            .getFieldErrors()
            .stream()
            .collect(
                // A field can break more than one constraint at once — an empty string
                // fails both @NotBlank and @Size — and toMap throws on a duplicate key,
                // turning a validation error into a 500. Keep every message instead of
                // picking one: which constraint the validator reports first is not
                // guaranteed, and silently dropping one loses information the caller
                // would otherwise act on.
                Collectors.groupingBy(
                    fieldError -> fieldError.getField(),
                    LinkedHashMap::new,
                    Collectors.mapping(this::messageFor, Collectors.toList())
                )
            )
            .entrySet()
            .stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> {
                    List<Object> messages = entry.getValue();
                    if (messages.size() == 1) {
                        return messages.get(0);
                    }
                    return Map.of("message", messages.get(0), "messages", messages);
                },
                (first, ignored) -> first,
                LinkedHashMap::new
            ));

        ProblemDetail problem = ProblemDetail.validationError(
            "Validation failed for one or more fields",
            fieldErrors
        );

        problem = problem.withInstance(exchange);

        return Mono.just(ResponseEntity.badRequest().body(problem));
    }

/** The message to report for one field error, wrapped when the value was rejected. */
    private Object messageFor(org.springframework.validation.FieldError fieldError) {
        String msg =
            fieldError.getDefaultMessage() != null
                ? fieldError.getDefaultMessage()
                : "Invalid value";
        if (fieldError.getRejectedValue() != null) {
            return Map.of("message", msg);
        }
        return msg;
    }

    /**
     * Handle timeout exceptions from OCR service calls.
     */
    @ExceptionHandler(TimeoutException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleTimeoutException(
        TimeoutException ex,
        ServerWebExchange exchange
    ) {
        log.error("Request timeout: type={}, requestId={}",
            ex.getClass().getSimpleName(), RequestIdWebFilter.requestId(exchange));

        ProblemDetail problem = new ProblemDetail(
            URI.create("https://api.kixi.ao/errors/timeout"),
            "Request Timeout",
            HttpStatus.GATEWAY_TIMEOUT.value(),
            "The request took too long to process. Please try again with a smaller file or fewer images.",
            Map.of("errorType", "timeout")
        );

        problem = problem.withInstance(exchange);

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
        log.warn("Illegal argument: type={}, requestId={}",
            ex.getClass().getSimpleName(), RequestIdWebFilter.requestId(exchange));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST.value(),
            ex.getMessage() != null ? ex.getMessage() : "Invalid request"
        ).withTitle("Bad Request");

        problem = problem.withInstance(exchange);

        return Mono.just(ResponseEntity.badRequest().body(problem));
    }

    /**
     * Handle framework-level HTTP errors that WebFlux raises before a
     * controller method runs, e.g. a multipart request missing a required
     * part (ServerWebInputException), no body at all (UnsupportedMediaTypeStatusException),
     * or an unsupported HTTP method (MethodNotAllowedException). These all
     * carry their own correct status code via getStatusCode(); without this
     * handler they fall through to the generic 500 handler below and hide a
     * client error as a server fault.
     */
    @ExceptionHandler(ErrorResponseException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleErrorResponseException(
        ErrorResponseException ex,
        ServerWebExchange exchange
    ) {
        int statusCode = ex.getStatusCode().value();
        log.warn("Framework HTTP error: type={}, status={}, requestId={}",
            ex.getClass().getSimpleName(), statusCode, RequestIdWebFilter.requestId(exchange));

        String detail = ex.getBody().getDetail();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            statusCode,
            detail != null ? detail : "Invalid request"
        ).withTitle(HttpStatus.valueOf(statusCode).getReasonPhrase());

        problem = problem.withInstance(exchange);

        return Mono.just(ResponseEntity.status(statusCode).body(problem));
    }

    /**
     * Handle duplicate key violations that no service mapped explicitly.
     * DuplicateKeyException is translated from the R2DBC unique-constraint
     * error and is a client error: the request cannot succeed as sent.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleDuplicateKey(
        DuplicateKeyException ex,
        ServerWebExchange exchange
    ) {
        log.warn("Duplicate key: type={}, requestId={}",
            ex.getClass().getSimpleName(), RequestIdWebFilter.requestId(exchange));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT.value(),
            "A record with the same unique value already exists."
        ).withTitle(HttpStatus.CONFLICT.getReasonPhrase());
        problem = problem.withInstance(exchange);

        return Mono.just(
            ResponseEntity.status(HttpStatus.CONFLICT).body(problem)
        );
    }

    /**
     * Handle referential integrity and check-constraint violations that no
     * service mapped explicitly, typically a purge attempt on a record that
     * is still referenced by other tables. This is a client error (409),
     * not a server fault, so it must not fall through to the generic 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleDataIntegrityViolation(
        DataIntegrityViolationException ex,
        ServerWebExchange exchange
    ) {
        log.warn("Data integrity violation: type={}, requestId={}",
            ex.getClass().getSimpleName(), RequestIdWebFilter.requestId(exchange));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT.value(),
            "The operation conflicts with existing related records. "
                + "Remove or reassign the dependent records first."
        ).withTitle(HttpStatus.CONFLICT.getReasonPhrase());
        problem = problem.withInstance(exchange);

        return Mono.just(
            ResponseEntity.status(HttpStatus.CONFLICT).body(problem)
        );
    }

    /**
     * Handle all other uncaught exceptions.
     */
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ProblemDetail>> handleGenericException(
        Exception ex,
        ServerWebExchange exchange
    ) {
        log.error("Unhandled exception: type={}, requestId={}",
            ex.getClass().getSimpleName(), RequestIdWebFilter.requestId(exchange));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            500,
            "An unexpected error occurred on the server. Please try again later."
        ).withTitle("Internal Server Error");
        problem = problem.withInstance(exchange);

        return Mono.just(ResponseEntity.internalServerError().body(problem));
    }
}
