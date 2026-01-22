package ao.creativemode.kixi.common.dto;

import java.net.URI;
import java.util.Map;

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
}