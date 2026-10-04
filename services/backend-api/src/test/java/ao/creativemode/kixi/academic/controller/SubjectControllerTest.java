package ao.creativemode.kixi.academic.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.academic.dto.subject.SubjectRequest;
import ao.creativemode.kixi.academic.dto.subject.SubjectResponse;
import ao.creativemode.kixi.academic.service.SubjectService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * Regression coverage for a bug found via manual testing: the lifecycle
 * endpoints mapped their service result with {@code map()}, but the service
 * returns {@code Mono<Void>} completed with {@code then()}, which never emits
 * a value. Mapping an empty Mono emits nothing, so WebFlux answered 200 with an
 * empty body instead of the 204 No Content the endpoint declares, and the
 * whole delete/restore/purge contract silently differed from every other
 * resource.
 */
class SubjectControllerTest {

    private SubjectService service;
    private SubjectController controller;

    @BeforeEach
    void setUp() {
        service = mock(SubjectService.class);
        controller = new SubjectController(service);
    }

    @Test
    void softDeleteAnswersNoContentEvenThoughTheServiceEmitsNothing() {
        when(service.softDelete("MAT")).thenReturn(Mono.empty());

        ResponseEntity<Void> response = controller.softDelete("MAT").block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void restoreAnswersNoContentEvenThoughTheServiceEmitsNothing() {
        when(service.restore("MAT")).thenReturn(Mono.empty());

        ResponseEntity<Void> response = controller.restore("MAT").block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void hardDeleteAnswersNoContentEvenThoughTheServiceEmitsNothing() {
        when(service.hardDelete("MAT")).thenReturn(Mono.empty());

        ResponseEntity<Void> response = controller.hardDelete("MAT").block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void createStillAnswersCreatedWithLocationHeader() {
        SubjectResponse created = new SubjectResponse(
                11L, "TST", "Disciplina", "Tst", LocalDateTime.now(), LocalDateTime.now(), null);
        when(service.create(any())).thenReturn(Mono.just(created));

        ResponseEntity<SubjectResponse> response = controller
                .create(new SubjectRequest("TST", "Disciplina", "Tst"),
                        UriComponentsBuilder.newInstance())
                .block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getFirst("Location")).endsWith("/api/v1/subjects/TST");
    }
}
