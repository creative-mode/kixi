package ao.creativemode.kixi.academic.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.academic.dto.classe.ClassRequest;
import ao.creativemode.kixi.academic.dto.classe.ClassResponse;
import ao.creativemode.kixi.academic.model.Course;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.academic.service.ClassService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * Regression coverage for a bug found via manual testing: the Location header
 * built on create pointed at "/api/v1/class/{code}" - singular path, and
 * expanded with the class code even though every class endpoint is keyed by
 * the numeric id. Following the header therefore produced a 400 type mismatch
 * instead of resolving to the created resource.
 */
class ClassControllerTest {

    private ClassService service;
    private ClassController controller;

    @BeforeEach
    void setUp() {
        service = mock(ClassService.class);
        controller = new ClassController(service);
    }

    @Test
    void createLocationHeaderUsesThePluralPathAndTheNumericId() {
        ClassResponse created = new ClassResponse(
                42L,
                "12-A",
                12,
                new Course(),
                new SchoolYear(),
                LocalDateTime.now(),
                LocalDateTime.now(),
                null);

        when(service.create(any())).thenReturn(Mono.just(created));

        ResponseEntity<ClassResponse> response = controller
                .create(new ClassRequest("12-A", 12, 1L, 1L),
                        UriComponentsBuilder.newInstance())
                .block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getFirst("Location"))
                .endsWith("/api/v1/classes/42");
    }

    /**
     * ClassService.update() existed and was fully implemented, but no
     * controller ever called it, so PUT /api/v1/classes/{id} was absent and
     * every update attempt returned 405 Method Not Allowed - the resource
     * supported create, read, delete and restore but not update.
     */
    @Test
    void updateIsExposedAndDelegatesToTheService() {
        ClassResponse updated = new ClassResponse(
                42L,
                "12-B",
                12,
                new Course(),
                new SchoolYear(),
                LocalDateTime.now(),
                LocalDateTime.now(),
                null);

        when(service.update(org.mockito.ArgumentMatchers.eq(42L), any()))
                .thenReturn(Mono.just(updated));

        ResponseEntity<ClassResponse> response = controller
                .update(42L, new ClassRequest("12-B", 12, 1L, 1L))
                .block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(updated);
    }

    @Test
    void restoreReturnsNoContent() {
        when(service.restore(42L)).thenReturn(Mono.empty());

        ResponseEntity<Void> response = controller.restore(42L).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
    }
}
