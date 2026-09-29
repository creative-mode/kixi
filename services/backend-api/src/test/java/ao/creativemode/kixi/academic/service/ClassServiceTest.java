package ao.creativemode.kixi.academic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.academic.dto.classe.ClassRequest;
import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.model.Course;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.CourseRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Regression coverage for a bug found via manual testing: creating or
 * updating a class with a nonexistent courseId/schoolYearId used to hit the
 * database's foreign key constraint and get reported back as
 * "class is already exist" (409) - a duplicate-resource message for what was
 * actually a missing-reference error. ClassService now validates both
 * references before ever touching the repository.
 */
class ClassServiceTest {

    private ClassRepository repository;
    private CourseRepository courseRepository;
    private SchoolYearRepository schoolYearRepository;
    private ClassService service;

    @BeforeEach
    void setUp() {
        repository = mock(ClassRepository.class);
        courseRepository = mock(CourseRepository.class);
        schoolYearRepository = mock(SchoolYearRepository.class);
        service = new ClassService(repository, courseRepository, schoolYearRepository);
    }

    @Test
    void createRejectsNonexistentCourseWithoutTouchingRepository() {
        when(courseRepository.findById(9999L)).thenReturn(Mono.empty());
        when(schoolYearRepository.findById(1L)).thenReturn(Mono.just(new SchoolYear()));

        StepVerifier.create(service.create(new ClassRequest("12-X", 12, 9999L, 1L)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage())
                            .isEqualTo("Course not found: 9999");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsNonexistentSchoolYearWithoutTouchingRepository() {
        when(courseRepository.findById(1L)).thenReturn(Mono.just(new Course()));
        when(schoolYearRepository.findById(9999L)).thenReturn(Mono.empty());

        StepVerifier.create(service.create(new ClassRequest("12-X", 12, 1L, 9999L)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage())
                            .isEqualTo("School year not found: 9999");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void createSavesClassWhenCourseAndSchoolYearExist() {
        Course course = new Course();
        course.setId(1L);
        course.setCode("TOD");
        course.setName("TODOS");
        SchoolYear schoolYear = new SchoolYear();
        schoolYear.setId(1L);

        when(courseRepository.findById(1L)).thenReturn(Mono.just(course));
        when(schoolYearRepository.findById(1L)).thenReturn(Mono.just(schoolYear));
        when(repository.save(any(Class.class))).thenAnswer(invocation -> {
            Class entity = invocation.getArgument(0);
            entity.setId(42L);
            return Mono.just(entity);
        });

        StepVerifier.create(service.create(new ClassRequest("12-TOD", 12, 1L, 1L)))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(42L);
                    assertThat(response.code()).isEqualTo("12-TOD");
                })
                .verifyComplete();
    }

    @Test
    void updateRejectsNonexistentCourseWithoutSaving() {
        Class existing = new Class();
        existing.setId(5L);
        when(repository.findByIdAndDeletedAtIsNull(5L)).thenReturn(Mono.just(existing));
        when(courseRepository.findById(9999L)).thenReturn(Mono.empty());
        // requireCourseAndSchoolYear() builds its .then(schoolYearRepository.findById(...))
        // argument eagerly, before the course check's emptiness is even known, so the
        // school year mock still needs a stub even though the course error wins.
        when(schoolYearRepository.findById(1L)).thenReturn(Mono.just(new SchoolYear()));

        StepVerifier.create(service.update(5L, new ClassRequest("12-X", 12, 9999L, 1L)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage())
                            .isEqualTo("Course not found: 9999");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void findAllActiveResolvesCourseAndSchoolYearForEveryEntity() {
        Class entity = new Class();
        entity.setId(1L);
        entity.setCourseId(1L);
        entity.setSchoolYearId(1L);
        when(repository.findAllByDeletedAtIsNull()).thenReturn(Flux.just(entity));
        when(courseRepository.findById(1L)).thenReturn(Mono.just(new Course()));
        when(schoolYearRepository.findById(1L)).thenReturn(Mono.just(new SchoolYear()));

        StepVerifier.create(service.findAllActive())
                .assertNext(response -> assertThat(response.id()).isEqualTo(1L))
                .verifyComplete();
    }

    @Test
    void findAllDetetedReturnsOnlyTrashedEntities() {
        Class entity = new Class();
        entity.setId(2L);
        entity.setCourseId(1L);
        entity.setSchoolYearId(1L);
        when(repository.findAllByDeletedAtIsNotNull()).thenReturn(Flux.just(entity));
        when(courseRepository.findById(1L)).thenReturn(Mono.just(new Course()));
        when(schoolYearRepository.findById(1L)).thenReturn(Mono.just(new SchoolYear()));

        StepVerifier.create(service.findAllDeteted())
                .assertNext(response -> assertThat(response.id()).isEqualTo(2L))
                .verifyComplete();
    }

    @Test
    void findByIdActiveReturnsNotFoundForMissingClass() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findByIdActive(99L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(404);
                })
                .verify();
    }

    @Test
    void softDeleteRejectsMissingClass() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.softDelete(99L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void softDeleteMarksEntityAsDeleted() {
        Class existing = new Class();
        existing.setId(1L);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(existing)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.softDelete(1L)).verifyComplete();

        assertThat(existing.isDeleted()).isTrue();
    }

    @Test
    void restoreRejectsClassThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.restore(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void restoreClearsDeletedAt() {
        Class deleted = new Class();
        deleted.setId(1L);
        deleted.markAsDeleted();
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.save(deleted)).thenReturn(Mono.just(deleted));

        StepVerifier.create(service.restore(1L)).verifyComplete();

        assertThat(deleted.isDeleted()).isFalse();
    }

    @Test
    void hardDeleteRejectsClassThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).delete(any(Class.class));
    }

    @Test
    void hardDeleteRemovesTrashedClass() {
        Class deleted = new Class();
        deleted.setId(1L);
        deleted.markAsDeleted();
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.delete(deleted)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L)).verifyComplete();

        verify(repository).delete(deleted);
    }
}
