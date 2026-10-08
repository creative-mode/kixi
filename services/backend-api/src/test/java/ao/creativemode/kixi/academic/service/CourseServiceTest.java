package ao.creativemode.kixi.academic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.academic.dto.courses.CourseRequest;
import ao.creativemode.kixi.academic.model.Course;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.CourseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CourseServiceTest {

    private static final Long ITEL = 7L;

    private CourseRepository repository;
    private ClassRepository classRepository;
    private CourseService service;

    @BeforeEach
    void setUp() {
        repository = mock(CourseRepository.class);
        classRepository = mock(ClassRepository.class);
        service = new CourseService(repository, classRepository);
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

        StepVerifier.create(service.create(new CourseRequest("  tod  ", "  TODOS  ", "  desc  ", ITEL)))
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

        StepVerifier.create(service.create(new CourseRequest("TOD", "TODOS", null, ITEL)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                })
                .verify();
    }

    @Test
    void updateRejectsMissingCourseWithoutSaving() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(99L, new CourseRequest("X", "Nome", null, ITEL)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    /**
     * A class carries the school of its course, enforced by a composite foreign key.
     * Without this check, moving a course that has classes fails on that constraint and
     * gets reported as "a course with this code already exists", which is the same
     * misleading-error class ClassServiceTest already guards against.
     */
    @Test
    void updateRefusesToMoveACourseThatAlreadyHasClasses() {
        Course existing = course(1L, "TOD", "TODOS");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(classRepository.findByCourseId(1L))
                .thenReturn(Flux.just(new ao.creativemode.kixi.academic.model.Class()));

        StepVerifier.create(service.update(1L, new CourseRequest("TOD", "TODOS", null, 99L)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                    assertThat(((ApiException) error).getMessage())
                            .contains("already has classes");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void updateAllowsMovingACourseWithNoClasses() {
        Course existing = course(1L, "TOD", "TODOS");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(classRepository.findByCourseId(1L)).thenReturn(Flux.empty());
        when(repository.save(any(Course.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.update(1L, new CourseRequest("TOD", "TODOS", null, 99L)))
                .assertNext(response -> assertThat(response.institutionId()).isEqualTo(99L))
                .verifyComplete();
    }

    @Test
    void updateIgnoresTheClassesCheckWhenTheSchoolIsUnchanged() {
        Course existing = course(1L, "TOD", "TODOS");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(Course.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.update(1L, new CourseRequest("TOD2", "TODOS 2", null, ITEL)))
                .assertNext(response -> assertThat(response.code()).isEqualTo("TOD2"))
                .verifyComplete();

        verify(classRepository, never()).findByCourseId(any());
    }

    @Test
    void updateAppliesNewFieldsToExistingCourse() {
        Course existing = course(1L, "TOD", "TODOS");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(Course.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.update(1L, new CourseRequest("tod2", "TODOS 2", "desc2", ITEL)))
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

    @Test
    void findAllActiveCarriesTheSchoolOfEachCourse() {
        when(repository.findAllByDeletedAtIsNull()).thenReturn(Flux.just(course(1L, "TISM", "Tecnico")));

        StepVerifier.create(service.findAllActive())
                .assertNext(response -> assertThat(response.institutionId()).isEqualTo(ITEL))
                .verifyComplete();
    }

    @Test
    void findAllActiveFiltersBySchoolWhenOneIsGiven() {
        when(repository.findAllByInstitutionIdAndDeletedAtIsNull(ITEL))
                .thenReturn(Flux.just(course(1L, "TISM", "Tecnico")));

        StepVerifier.create(service.findAllActive(ITEL))
                .assertNext(response -> assertThat(response.id()).isEqualTo(1L))
                .verifyComplete();

        verify(repository, never()).findAllByDeletedAtIsNull();
    }

    @Test
    void createRequiresASchool() {
        StepVerifier.create(service.create(new CourseRequest("X", "Nome", null, null)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(400);
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void createStoresTheSchoolOnTheCourse() {
        when(repository.save(any(Course.class))).thenAnswer(invocation -> {
            Course entity = invocation.getArgument(0);
            entity.setId(9L);
            return Mono.just(entity);
        });

        StepVerifier.create(service.create(new CourseRequest("TISM", "Tecnico", null, ITEL)))
                .assertNext(response -> assertThat(response.institutionId()).isEqualTo(ITEL))
                .verifyComplete();
    }

    // ── Telling a duplicated code from a school that does not exist ─────────

    @Test
    void createSaysTheSchoolIsUnknownWhenTheInstitutionKeyIsTheOneThatFailed() {
        // `academic` cannot check the school exists without depending on the
        // `institutions` module, so the constraint the database reported is the
        // only thing that separates the two cases.
        when(repository.save(any(Course.class))).thenReturn(Mono.error(
                new DataIntegrityViolationException(
                        "could not execute statement [ERROR: insert into courses violates foreign key constraint fk_courses_institution]")));

        StepVerifier.create(service.create(new CourseRequest("TISM", "Tecnico", null, 404L)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(422);
                    assertThat(((ApiException) error).getMessage())
                            .contains("No institution exists with that id");
                })
                .verify();
    }

    @Test
    void createStillReportsADuplicatedCodeAsAConflict() {
        when(repository.save(any(Course.class))).thenReturn(Mono.error(
                new DataIntegrityViolationException(
                        "could not execute statement [ERROR: insert into courses violates unique constraint uc_courses_code]")));

        StepVerifier.create(service.create(new CourseRequest("TISM", "Tecnico", null, ITEL)))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                    assertThat(((ApiException) error).getMessage())
                            .contains("A course with code TISM already exists");
                })
                .verify();
    }

    @Test
    void updateSaysTheSchoolIsUnknownWhenTheInstitutionKeyIsTheOneThatFailed() {
        Course existing = course(1L, "TOD", "TODOS");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(classRepository.findByCourseId(1L)).thenReturn(Flux.empty());
        when(repository.save(any(Course.class))).thenReturn(Mono.error(
                new DataIntegrityViolationException(
                        "could not execute statement [ERROR: update courses violates foreign key constraint fk_courses_institution]")));

        StepVerifier.create(service.update(1L, new CourseRequest("TOD", "TODOS", null, 404L)))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(422);
                    assertThat(((ApiException) error).getMessage())
                            .contains("No institution exists with that id");
                })
                .verify();
    }

    @Test
    void updateReportsTheClassesHoldingTheSchoolWhenTheCompositeKeyIsTheOneThatFailed() {
        // The trashed-class check catches this first in normal operation; this is
        // the backstop for a class that appeared between the check and the save.
        Course existing = course(1L, "TOD", "TODOS");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(classRepository.findByCourseId(1L)).thenReturn(Flux.empty());
        when(repository.save(any(Course.class))).thenReturn(Mono.error(
                new DataIntegrityViolationException(
                        "could not execute statement [ERROR: update courses violates foreign key constraint fk_classes_course_institution]")));

        StepVerifier.create(service.update(1L, new CourseRequest("TOD", "TODOS", null, 99L)))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                    assertThat(((ApiException) error).getMessage())
                            .contains("already has classes");
                })
                .verify();
    }

    // ── Trashed classes still hold the course to its school ────────────────

    @Test
    void updateRefusesToMoveACourseWhoseOnlyClassesAreTrashed() {
        // Soft delete only stamps deleted_at, so the row is still in `classes` and
        // still held by the composite key. Checking only the active ones let this
        // pass the verification and then fail on the save with a duplicate-code
        // message that was plainly false.
        Course existing = course(1L, "TOD", "TODOS");
        ao.creativemode.kixi.academic.model.Class trashed =
                new ao.creativemode.kixi.academic.model.Class();
        trashed.markAsDeleted();
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(classRepository.findByCourseId(1L)).thenReturn(Flux.just(trashed));

        StepVerifier.create(service.update(1L, new CourseRequest("TOD", "TODOS", null, 99L)))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                    assertThat(((ApiException) error).getMessage())
                            .contains("including deleted ones");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    private Course course(Long id, String code, String name) {
        Course course = new Course();
        course.setId(id);
        course.setCode(code);
        course.setName(name);
        course.setInstitutionId(ITEL);
        return course;
    }
}
