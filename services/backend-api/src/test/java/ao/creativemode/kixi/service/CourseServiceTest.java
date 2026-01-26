package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.courses.CourseRequest;
import ao.creativemode.kixi.dto.courses.CourseResponse;
import ao.creativemode.kixi.model.Course;
import ao.creativemode.kixi.repository.CourseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CourseServiceTest {

    @Mock
    private CourseRepository repository;

    private CourseService courseService;

    @BeforeEach
    void setUp() {
        courseService = new CourseService(repository);
    }

    @Test
    void testFindAllActive() {
        Course course1 = new Course(1L, "CS101", "Intro to CS", "Description", LocalDateTime.now(), LocalDateTime.now(), null);
        Course course2 = new Course(2L, "CS102", "Data Structures", null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(repository.findAllByDeletedAtIsNull())
            .thenReturn(Flux.just(course1, course2));

        StepVerifier.create(courseService.findAllActive())
            .expectNextCount(2)
            .verifyComplete();

        verify(repository, times(1)).findAllByDeletedAtIsNull();
    }

    @Test
    void testFindAllDeleted() {
        Course course = new Course(1L, "CS101", "Intro to CS", null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());

        when(repository.findAllByDeletedAtIsNotNull())
            .thenReturn(Flux.just(course));

        StepVerifier.create(courseService.findAllDeleted())
            .expectNextCount(1)
            .verifyComplete();

        verify(repository, times(1)).findAllByDeletedAtIsNotNull();
    }

    @Test
    void testFindByIdActive_Success() {
        Long courseId = 1L;
        Course course = new Course(courseId, "CS101", "Intro to CS", "Description", LocalDateTime.now(), LocalDateTime.now(), null);

        when(repository.findByIdAndDeletedAtIsNull(courseId))
            .thenReturn(Mono.just(course));

        StepVerifier.create(courseService.findByIdActive(courseId))
            .expectNextMatches(response -> response.id().equals(courseId) && response.code().equals("CS101"))
            .verifyComplete();

        verify(repository, times(1)).findByIdAndDeletedAtIsNull(courseId);
    }

    @Test
    void testFindByIdActive_NotFound() {
        Long courseId = 999L;

        when(repository.findByIdAndDeletedAtIsNull(courseId))
            .thenReturn(Mono.empty());

        StepVerifier.create(courseService.findByIdActive(courseId))
            .expectErrorMatches(error -> error instanceof ApiException)
            .verify();

        verify(repository, times(1)).findByIdAndDeletedAtIsNull(courseId);
    }

    @Test
    void testCreate_Success() {
        CourseRequest request = new CourseRequest("cs101", "Intro to CS", "Description");

        Course savedCourse = new Course(1L, "CS101", "Intro to CS", "Description", LocalDateTime.now(), LocalDateTime.now(), null);
        when(repository.save(any(Course.class)))
            .thenReturn(Mono.just(savedCourse));

        StepVerifier.create(courseService.create(request))
            .expectNextMatches(response -> response.code().equals("CS101"))
            .verifyComplete();

        verify(repository, times(1)).save(any(Course.class));
    }

    @Test
    void testCreate_CodeExists() {
        CourseRequest request = new CourseRequest("CS101", "Intro to CS", "Description");

        when(repository.save(any(Course.class)))
            .thenReturn(Mono.error(new DataIntegrityViolationException("Duplicate code")));

        StepVerifier.create(courseService.create(request))
            .expectError(ApiException.class)
            .verify();

        verify(repository, times(1)).save(any(Course.class));
    }

    @Test
    void testUpdate_Success() {
        Long courseId = 1L;
        CourseRequest request = new CourseRequest("CS102", "Updated Course", "Updated Description");

        Course existingCourse = new Course(courseId, "CS101", "Old Course", "Old Description", LocalDateTime.now(), LocalDateTime.now(), null);

        when(repository.findByIdAndDeletedAtIsNull(courseId))
            .thenReturn(Mono.just(existingCourse));

        Course updatedCourse = new Course(courseId, "CS102", "Updated Course", "Updated Description", LocalDateTime.now(), LocalDateTime.now(), null);
        when(repository.save(any(Course.class)))
            .thenReturn(Mono.just(updatedCourse));

        StepVerifier.create(courseService.update(courseId, request))
            .expectNextMatches(response -> response.code().equals("CS102") && response.name().equals("Updated Course"))
            .verifyComplete();

        verify(repository, times(1)).findByIdAndDeletedAtIsNull(courseId);
        verify(repository, times(1)).save(any(Course.class));
    }

    @Test
    void testUpdate_NotFound() {
        Long courseId = 999L;
        CourseRequest request = new CourseRequest("CS102", "Updated Course", null);

        when(repository.findByIdAndDeletedAtIsNull(courseId))
            .thenReturn(Mono.empty());

        StepVerifier.create(courseService.update(courseId, request))
            .expectError(ApiException.class)
            .verify();

        verify(repository, times(1)).findByIdAndDeletedAtIsNull(courseId);
        verify(repository, never()).save(any(Course.class));
    }

    @Test
    void testUpdate_CodeExists() {
        Long courseId = 1L;
        CourseRequest request = new CourseRequest("CS999", "Updated Course", null);

        Course existingCourse = new Course(courseId, "CS101", "Old Course", null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(repository.findByIdAndDeletedAtIsNull(courseId))
            .thenReturn(Mono.just(existingCourse));

        when(repository.save(any(Course.class)))
            .thenReturn(Mono.error(new DataIntegrityViolationException("Duplicate code")));

        StepVerifier.create(courseService.update(courseId, request))
            .expectError(ApiException.class)
            .verify();

        verify(repository, times(1)).findByIdAndDeletedAtIsNull(courseId);
        verify(repository, times(1)).save(any(Course.class));
    }

    @Test
    void testSoftDelete_Success() {
        Long courseId = 1L;
        Course course = new Course(courseId, "CS101", "Course", null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(repository.findByIdAndDeletedAtIsNull(courseId))
            .thenReturn(Mono.just(course));

        Course deletedCourse = new Course(courseId, "CS101", "Course", null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
        when(repository.save(any(Course.class)))
            .thenReturn(Mono.just(deletedCourse));

        StepVerifier.create(courseService.softDelete(courseId))
            .verifyComplete();

        verify(repository, times(1)).findByIdAndDeletedAtIsNull(courseId);
        verify(repository, times(1)).save(any(Course.class));
    }

    @Test
    void testSoftDelete_NotFound() {
        Long courseId = 999L;

        when(repository.findByIdAndDeletedAtIsNull(courseId))
            .thenReturn(Mono.empty());

        StepVerifier.create(courseService.softDelete(courseId))
            .expectError(ApiException.class)
            .verify();

        verify(repository, times(1)).findByIdAndDeletedAtIsNull(courseId);
        verify(repository, never()).save(any(Course.class));
    }

    @Test
    void testRestore_Success() {
        Long courseId = 1L;
        Course deletedCourse = new Course(courseId, "CS101", "Course", null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());

        when(repository.findByIdAndDeletedAtIsNotNull(courseId))
            .thenReturn(Mono.just(deletedCourse));

        Course restoredCourse = new Course(courseId, "CS101", "Course", null, LocalDateTime.now(), LocalDateTime.now(), null);
        when(repository.save(any(Course.class)))
            .thenReturn(Mono.just(restoredCourse));

        StepVerifier.create(courseService.restore(courseId))
            .verifyComplete();

        verify(repository, times(1)).findByIdAndDeletedAtIsNotNull(courseId);
        verify(repository, times(1)).save(any(Course.class));
    }

    @Test
    void testRestore_NotDeleted() {
        Long courseId = 1L;
        Course activeCourse = new Course(courseId, "CS101", "Course", null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(repository.findByIdAndDeletedAtIsNotNull(courseId))
            .thenReturn(Mono.empty());

        StepVerifier.create(courseService.restore(courseId))
            .expectError(ApiException.class)
            .verify();

        verify(repository, times(1)).findByIdAndDeletedAtIsNotNull(courseId);
        verify(repository, never()).save(any(Course.class));
    }
}
