package ao.creativemode.kixi.common.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String title;
    private final String code;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
        this.title = null;
        this.code = null;
    }

    public ApiException(HttpStatus status, String title, String message) {
        super(message);
        this.status = status;
        this.title = title;
        this.code = null;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "Not Found", message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Bad Request", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "Conflict", message);
    }
}