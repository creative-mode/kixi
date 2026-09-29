package ao.creativemode.kixi.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.shared.dto.ProblemDetail;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import org.springframework.http.HttpMethod;
import reactor.test.StepVerifier;

import java.util.List;

/**
 * Regression coverage for a bug found via manual testing: a request WebFlux
 * itself rejects before any controller runs - e.g. a multipart POST with no
 * body at all, or an unsupported HTTP method - was falling through to the
 * catch-all Exception handler and coming back as a 500 Internal Server Error
 * instead of the status the framework exception already carries.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void missingMultipartPartIsReportedAsBadRequestNotServerError() {
        ServerWebExchange exchange = exchange();
        ServerWebInputException ex = new ServerWebInputException("Required request part 'data' is not present.");

        ResponseEntity<ProblemDetail> response = handler
                .handleErrorResponseException(ex, exchange)
                .block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().status()).isEqualTo(400);
    }

    @Test
    void unsupportedMediaTypeKeepsItsOwn415StatusInsteadOfBecoming500() {
        ServerWebExchange exchange = exchange();
        UnsupportedMediaTypeStatusException ex =
                new UnsupportedMediaTypeStatusException("No content-type");

        ResponseEntity<ProblemDetail> response = handler
                .handleErrorResponseException(ex, exchange)
                .block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody().status()).isEqualTo(415);
    }

    @Test
    void methodNotAllowedKeepsItsOwn405Status() {
        ServerWebExchange exchange = exchange();
        MethodNotAllowedException ex =
                new MethodNotAllowedException(HttpMethod.DELETE, List.of(HttpMethod.GET, HttpMethod.POST));

        ResponseEntity<ProblemDetail> response = handler
                .handleErrorResponseException(ex, exchange)
                .block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void genericExceptionStillFallsBackTo500() {
        ServerWebExchange exchange = exchange();

        StepVerifier.create(handler.handleGenericException(new RuntimeException("boom"), exchange))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(response.getBody().status()).isEqualTo(500);
                })
                .verifyComplete();
    }

    private ServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/question-images"));
    }
}
