package ao.creativemode.kixi.academic.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.academic.dto.term.TermRequest;
import ao.creativemode.kixi.academic.dto.term.TermResponse;
import ao.creativemode.kixi.academic.service.TermService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * Regression coverage for two inconsistencies found while auditing every
 * controller for response semantics: POST /terms answered 200 without a
 * Location header while all other create endpoints answered 201 + Location,
 * and the restore endpoint answered 200 with an empty body while the rest of
 * the API answered 204.
 */
class TermControllerTest {

    private TermService service;
    private TermController controller;

    @BeforeEach
    void setUp() {
        service = mock(TermService.class);
        controller = new TermController(service);
    }

    @Test
    void createReturnsCreatedWithALocationHeader() {
        TermResponse created =
                new TermResponse(7L, 1, "Termo", java.time.LocalDateTime.now(), java.time.LocalDateTime.now(), null);

        when(service.create(any())).thenReturn(Mono.just(created));

        ResponseEntity<TermResponse> response = controller
                .create(new TermRequest("Termo", 1), UriComponentsBuilder.newInstance())
                .block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getFirst("Location")).endsWith("/api/v1/terms/7");
        assertThat(response.getBody()).isEqualTo(created);
    }

    @Test
    void restoreReturnsNoContent() {
        when(service.restore(7L)).thenReturn(Mono.empty());

        ResponseEntity<Void> response = controller.restore(7L).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void softDeleteAndHardDeleteReturnNoContent() {
        when(service.softDelete(7L)).thenReturn(Mono.empty());
        when(service.hardDelete(7L)).thenReturn(Mono.empty());

        assertThat(controller.softDelete(7L).block().getStatusCode().value()).isEqualTo(204);
        assertThat(controller.hardDelete(7L).block().getStatusCode().value()).isEqualTo(204);
    }
}
