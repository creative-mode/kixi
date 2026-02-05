package ao.creativemode.kixi.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Custom API exception with HTTP status support.
 *
 * Provides factory methods for common HTTP error statuses
 * and allows the status to be retrieved for proper response handling.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;
    private final String code;

    /**
     * Create an API exception with status and message.
     *
     * @param status HTTP status code
     * @param message Error message
     */
    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
        this.title = status.getReasonPhrase();
        this.code = null;
    }

    /**
     * Create an API exception with status, title, and message.
     *
     * @param status HTTP status code
     * @param title Error title
     * @param message Error message
     */
    public ApiException(HttpStatus status, String title, String message) {
        super(message);
        this.status = status;
        this.title = title;
        this.code = null;
    }

    /**
     * Create an API exception with status, title, message, and code.
     *
     * @param status HTTP status code
     * @param title Error title
     * @param message Error message
     * @param code Error code for programmatic handling
     */
    public ApiException(
        HttpStatus status,
        String title,
        String message,
        String code
    ) {
        super(message);
        this.status = status;
        this.title = title;
        this.code = code;
    }

    /**
     * Get the HTTP status code.
     *
     * @return HTTP status
     */
    public HttpStatus getStatus() {
        return status;
    }

    /**
     * Get the HTTP status code as integer.
     *
     * @return HTTP status code value
     */
    public int getStatusCode() {
        return status.value();
    }

    /**
     * Get the error title.
     *
     * @return Error title
     */
    public String getTitle() {
        return title;
    }

    /**
     * Get the error code.
     *
     * @return Error code or null if not set
     */
    public String getCode() {
        return code;
    }

    // =========================================================================
    // Factory methods for common HTTP errors
    // =========================================================================

    /**
     * Create a 400 Bad Request exception.
     *
     * @param message Error message
     * @return ApiException with BAD_REQUEST status
     */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Bad Request", message);
    }

    /**
     * Create a 400 Bad Request exception with custom title.
     *
     * @param title Error title
     * @param message Error message
     * @return ApiException with BAD_REQUEST status
     */
    public static ApiException badRequest(String title, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, title, message);
    }

    /**
     * Create a 401 Unauthorized exception.
     *
     * @param message Error message
     * @return ApiException with UNAUTHORIZED status
     */
    public static ApiException unauthorized(String message) {
        return new ApiException(
            HttpStatus.UNAUTHORIZED,
            "Unauthorized",
            message
        );
    }

    /**
     * Create a 403 Forbidden exception.
     *
     * @param message Error message
     * @return ApiException with FORBIDDEN status
     */
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "Forbidden", message);
    }

    /**
     * Create a 404 Not Found exception.
     *
     * @param message Error message
     * @return ApiException with NOT_FOUND status
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "Not Found", message);
    }

    /**
     * Create a 404 Not Found exception for a specific resource.
     *
     * @param resourceName Name of the resource (e.g., "Statement", "Question")
     * @param resourceId ID of the resource
     * @return ApiException with NOT_FOUND status
     */
    public static ApiException notFound(
        String resourceName,
        Object resourceId
    ) {
        return new ApiException(
            HttpStatus.NOT_FOUND,
            "Not Found",
            String.format("%s with ID %s not found", resourceName, resourceId)
        );
    }

    /**
     * Create a 409 Conflict exception.
     *
     * @param message Error message
     * @return ApiException with CONFLICT status
     */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "Conflict", message);
    }

    /**
     * Create a 409 Conflict exception for duplicate resource.
     *
     * @param resourceName Name of the resource
     * @param field Field that caused the conflict
     * @param value Value that already exists
     * @return ApiException with CONFLICT status
     */
    public static ApiException duplicate(
        String resourceName,
        String field,
        Object value
    ) {
        return new ApiException(
            HttpStatus.CONFLICT,
            "Duplicate Resource",
            String.format(
                "%s with %s '%s' already exists",
                resourceName,
                field,
                value
            )
        );
    }

    /**
     * Create a 422 Unprocessable Entity exception.
     *
     * @param message Error message
     * @return ApiException with UNPROCESSABLE_ENTITY status
     */
    public static ApiException unprocessableEntity(String message) {
        return new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Unprocessable Entity",
            message
        );
    }

    /**
     * Create a 500 Internal Server Error exception.
     *
     * @param message Error message
     * @return ApiException with INTERNAL_SERVER_ERROR status
     */
    public static ApiException internalError(String message) {
        return new ApiException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal Server Error",
            message
        );
    }

    /**
     * Create a 500 Internal Server Error exception with cause.
     *
     * @param message Error message
     * @param cause Original exception
     * @return ApiException with INTERNAL_SERVER_ERROR status
     */
    public static ApiException internalError(String message, Throwable cause) {
        ApiException exception = new ApiException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal Server Error",
            message
        );
        exception.initCause(cause);
        return exception;
    }

    /**
     * Create a 502 Bad Gateway exception.
     *
     * @param message Error message
     * @return ApiException with BAD_GATEWAY status
     */
    public static ApiException badGateway(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "Bad Gateway", message);
    }

    /**
     * Create a 503 Service Unavailable exception.
     *
     * @param message Error message
     * @return ApiException with SERVICE_UNAVAILABLE status
     */
    public static ApiException serviceUnavailable(String message) {
        return new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Service Unavailable",
            message
        );
    }

    /**
     * Create a 504 Gateway Timeout exception.
     *
     * @param message Error message
     * @return ApiException with GATEWAY_TIMEOUT status
     */
    public static ApiException gatewayTimeout(String message) {
        return new ApiException(
            HttpStatus.GATEWAY_TIMEOUT,
            "Gateway Timeout",
            message
        );
    }

    @Override
    public String toString() {
        return String.format(
            "ApiException{status=%d %s, title='%s', message='%s', code='%s'}",
            status.value(),
            status.getReasonPhrase(),
            title,
            getMessage(),
            code
        );
    }
}
