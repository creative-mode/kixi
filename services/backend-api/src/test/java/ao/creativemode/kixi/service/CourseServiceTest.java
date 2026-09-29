package ao.creativemode.kixi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.dto.courses.CourseRequest;
import ao.creativemode.kixi.model.Course;
import ao.creativemode.kixi.repository.CourseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CourseServiceTest {

    private CourseRepository repository;
    private CourseService service;

    @BeforeEach
    void setUp() {
        repository = mock(CourseRepository.class);
        service = new CourseService(repository);
    }

    @Test
    void findAllActiveMapsEveryEntityToResponse() {
        when(repository.findAllByDeletedAtIsNull()).thenReturn(Flux.just(course(1L, "TOD", "TODOS")));

        StepVerifier.create(service.findAllActive())
                .assertNext(response -> assertThat(response.name()).isEqualTo("TODOS"))
                .verifyComplete();
    }

    @Test
    void findAllDeletedReturnsOnlyTrashedEntities() {
        when(repository.findAllByDeletedAtIsNotNull()).thenReturn(Flux.just(course(2L, "CT", "Ciências")));

        StepVerifier.create(service.findAllDeleted())
                .assertNext(response -> assertThat(response.id()).isEqualTo(2L))
                .verifyComplete();
    }

    @Test
    void findByIdActiveReturnsNotFoundForMissingCourse() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findByIdActive(99L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(404);
                })
                .verify();
    }

    @Test
    void createNormalizesCodeToUppercaseAndTrimsFields() {
        when(repository.save(any(Course.class))).thenAnswer(invocation -> {
            Course entity = invocation.getArgument(0);
            entity.setId(5L);
            return Mono.just(entity);
        });

        StepVerifier.create(service.create(new CourseRequest("  tod  ", "  TODOS  ", "  desc  ")))
                .assertNext(response -> {
                    assertThat(response.code()).isEqualTo("TOD");
                    assertThat(response.name()).isEqualTo("TODOS");
                    assertThat(response.description()).isEqualTo("desc");
                })
                .verifyComplete();
    }

    @Test
    void createMapsDuplicateCodeConflictToApiException() {
        when(repository.save(any(Course.class)))
                .thenReturn(Mono.error(new DataIntegrityViolationException("duplicate")));

        StepVerifier.create(service.create(new CourseRequest("TOD", "TODOS", null)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                })
                .verify();
    }

    @Test
    void updateRejectsMissingCourseWithoutSaving() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(99L, new CourseRequest("X", "Nome", null)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void updateAppliesNewFieldsToExistingCourse() {
        Course existing = course(1L, "TOD", "TODOS");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(Course.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.update(1L, new CourseRequest("tod2", "TODOS 2", "desc2")))
                .assertNext(response -> {
                    assertThat(response.code()).isEqualTo("TOD2");
                    assertThat(response.name()).isEqualTo("TODOS 2");
                })
                .verifyComplete();
    }

    @Test
    void softDeleteRejectsMissingCourse() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.softDelete(99L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void softDeleteMarksEntityAsDeleted() {
        Course existing = course(1L, "TOD", "TODOS");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(Course.class))).thenReturn(Mono.just(existing));

        StepVerifier.create(service.softDelete(1L)).verifyComplete();

        assertThat(existing.isDeleted()).isTrue();
    }

    @Test
    void restoreRejectsCourseThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.restore(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void restoreClearsDeletedAt() {
        Course deleted = course(1L, "TOD", "TODOS");
        deleted.markAsDeleted();
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.save(any(Course.class))).thenReturn(Mono.just(deleted));

        StepVerifier.create(service.restore(1L)).verifyComplete();

        assertThat(deleted.isDeleted()).isFalse();
    }

    @Test
    void hardDeleteRejectsCourseThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).delete(any(Course.class));
    }

    @Test
    void hardDeleteRemovesTrashedCourse() {
        Course deleted = course(1L, "TOD", "TODOS");
        deleted.markAsDeleted();
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.delete(deleted)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L)).verifyComplete();

        verify(repository).delete(deleted);
    }

    private Course course(Long id, String code, String name) {
        Course course = new Course();
        course.setId(id);
        course.setCode(code);
        course.setName(name);
        return course;
    }
}
