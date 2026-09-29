package ao.creativemode.kixi.shared.dto;

import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.server.ServerWebExchange;

public record ProblemDetail(
        URI type,
        String title,
        Integer status,
        String detail,
        Map<String, Object> properties
) {
    public static ProblemDetail forStatus(int status) {
        return new ProblemDetail(null, null, status, null, null);
    }

    public static ProblemDetail forStatusAndDetail(int status, String detail) {
        return new ProblemDetail(null, null, status, detail, null);
    }

    public static ProblemDetail validationError(String detail, Map<String, Object> fieldErrors) {
        return new ProblemDetail(
                URI.create("https://api.kixi.ao/errors/validation-error"),
                "Validation Error",
                400,
                detail,
                fieldErrors
        );
    }

    // Useful helper method (optional)
    public ProblemDetail withType(URI type) {
        return new ProblemDetail(type, title, status, detail, properties);
    }

    public ProblemDetail withTitle(String title) {
        return new ProblemDetail(type, title, status, detail, properties);
    }

    /**
     * Adds the 'instance' and 'requestId' fields describing the current
     * request (RFC 9457 recommends 'instance'; 'requestId' is this API's
     * own addition for correlating with logs).
     */
    public ProblemDetail withInstance(ServerWebExchange exchange) {
        Map<String, Object> updatedProps = new HashMap<>(
                properties != null ? properties : Map.of()
        );
        updatedProps.put("instance", exchange.getRequest().getPath().value());
        updatedProps.put("requestId", RequestIdWebFilter.requestId(exchange));
        return new ProblemDetail(type, title, status, detail, updatedProps);
    }
}