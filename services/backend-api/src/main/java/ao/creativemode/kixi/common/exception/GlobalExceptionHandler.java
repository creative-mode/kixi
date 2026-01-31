package ao.creativemode.kixi.common.exception;

import ao.creativemode.kixi.common.dto.ProblemDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import org.springframework.dao.DuplicateKeyException;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler for the API.
 * Returns RFC 9457 Problem Details in case of errors.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final URI DEFAULT_TYPE = URI.create("https://api.kixi.com/errors");

    @ExceptionHandler(DuplicateKeyException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleDuplicateKeyException(
            DuplicateKeyException ex,
            ServerWebExchange exchange) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                        409,
                        "There is already an active simulation for this account and statement.")
                .withTitle("Duplicate Resource");

        problem = addInstance(exchange, problem);

        return Mono.just(ResponseEntity.status(409).body(problem));
    }


    @ExceptionHandler(ApiException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleApiException(
            ApiException ex,
            ServerWebExchange exchange) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                500,
                ex.getMessage() != null ? ex.getMessage() : "API Error occurred").withTitle("API Error");
        problem = addInstance(exchange, problem);
        return Mono.just(ResponseEntity.status(500).body(problem));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleValidationErrors(
            WebExchangeBindException ex,
            ServerWebExchange exchange) {

        Map<String, Object> fieldErrors = ex.getFieldErrors().stream()
                .collect(Collectors.toMap(
                        fieldError -> fieldError.getField(),
                        fieldError -> {
                            String msg = fieldError.getDefaultMessage() != null
                                    ? fieldError.getDefaultMessage()
                                    : "Invalid value";
                            if (fieldError.getRejectedValue() != null) {
                                return Map.of(
                                        "message", msg,
                                        "rejectedValue", fieldError.getRejectedValue());
                            }
                            return msg;
                        }));

        ProblemDetail problem = ProblemDetail.validationError(
                "Validation failed for one or more fields",
                fieldErrors);

        problem = addInstance(exchange, problem);

        return Mono.just(ResponseEntity.badRequest().body(problem));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ProblemDetail>> handleGenericException(
            Exception ex,
            ServerWebExchange exchange) {

        log.error("Unhandled exception occurred", ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                500,
                "An unexpected error occurred on the server. Please try again later.")
                .withTitle("Internal Server Error");
        problem = addInstance(exchange, problem);
        return Mono.just(ResponseEntity.internalServerError().body(problem));
    }

    /**
     * 
     * Adds the 'instance' field with the URI of the current request (RFC 9457
     * recommended)
     */
    private ProblemDetail addInstance(ServerWebExchange exchange, ProblemDetail problem) {
        String requestUri = exchange.getRequest().getURI().toString();
        Map<String, Object> currentProps = problem.properties() != null ? problem.properties() : Map.of();
        Map<String, Object> updatedProps = new java.util.HashMap<>(currentProps);
        updatedProps.put("instance", requestUri);
        return new ProblemDetail(
                problem.type(),
                problem.title(),
                problem.status(),
                problem.detail(),
                updatedProps);
    }
}