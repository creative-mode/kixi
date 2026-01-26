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
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CourseServiceTest {

    @Mock
    private CourseRepository courseRepository;

    private CourseService courseService;

    @BeforeEach
    void setUp() {
        courseService = new CourseService(courseRepository);
    }

    @Test
    void testFindAllActive() {
        Course course1 = new Course(1L, "CS101", "Intro to CS", "Description", LocalDateTime.now(), LocalDateTime.now(), null);
        Course course2 = new Course(2L, "CS102", "Data Structures", null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(courseRepository.findAllByDeletedAtIsNull())
            .thenReturn(Flux.just(course1, course2));

        StepVerifier.create(courseService.findAllActive())
            .expectNextCount(2)
            .verifyComplete();

        verify(courseRepository, times(1)).findAllByDeletedAtIsNull();
    }

    @Test
    void testFindAllDeleted() {
        Course course = new Course(1L, "CS101", "Intro to CS", null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());

        when(courseRepository.findAllByDeletedAtIsNotNull())
            .thenReturn(Flux.just(course));

        StepVerifier.create(courseService.findAllDeleted())
            .expectNextCount(1)
            .verifyComplete();

        verify(courseRepository, times(1)).findAllByDeletedAtIsNotNull();
    }

    @Test
    void testFindByIdActive_Success() {
        Long courseId = 1L;
        Course course = new Course(courseId, "CS101", "Intro to CS", "Description", LocalDateTime.now(), LocalDateTime.now(), null);

        when(courseRepository.findByIdAndDeletedAtIsNull(courseId))
            .thenReturn(Mono.just(course));

        StepVerifier.create(courseService.findByIdActive(courseId))
            .expectNextMatches(response -> response.id().equals(courseId) && response.code().equals("CS101"))
            .verifyComplete();

        verify(courseRepository, times(1)).findByIdAndDeletedAtIsNull(courseId);
    }

    @Test
    void testFindByIdActive_NotFound() {
        Long courseId = 999L;

        when(courseRepository.findByIdAndDeletedAtIsNull(courseId))
            .thenReturn(Mono.empty());

        StepVerifier.create(courseService.findByIdActive(courseId))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.NOT_FOUND)
            .verify();

        verify(courseRepository, times(1)).findByIdAndDeletedAtIsNull(courseId);
    }

    @Test
    void testFindByIdActive_InvalidId() {
        StepVerifier.create(courseService.findByIdActive(0L))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.BAD_REQUEST)
            .verify();

        verify(courseRepository, never()).findByIdAndDeletedAtIsNull(anyLong());
    }

    @Test
    void testCreate_Success() {
        CourseRequest request = new CourseRequest("cs101", "Intro to CS", "Description");

        when(courseRepository.findByCodeAndDeletedAtIsNull("CS101"))
            .thenReturn(Mono.empty());

        Course savedCourse = new Course(1L, "CS101", "Intro to CS", "Description", LocalDateTime.now(), LocalDateTime.now(), null);
        when(courseRepository.save(any(Course.class)))
            .thenReturn(Mono.just(savedCourse));

        StepVerifier.create(courseService.create(request))
            .expectNextMatches(response -> response.code().equals("CS101"))
            .verifyComplete();

        verify(courseRepository, times(1)).findByCodeAndDeletedAtIsNull("CS101");
        verify(courseRepository, times(1)).save(any(Course.class));
    }

    @Test
    void testCreate_CodeExists() {
        CourseRequest request = new CourseRequest("CS101", "Intro to CS", "Description");

        Course existingCourse = new Course(1L, "CS101", "Existing Course", null, LocalDateTime.now(), LocalDateTime.now(), null);
        when(courseRepository.findByCodeAndDeletedAtIsNull("CS101"))
            .thenReturn(Mono.just(existingCourse));

        StepVerifier.create(courseService.create(request))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.CONFLICT)
            .verify();

        verify(courseRepository, times(1)).findByCodeAndDeletedAtIsNull("CS101");
        verify(courseRepository, never()).save(any(Course.class));
    }

    @Test
    void testUpdate_Success() {
        Long courseId = 1L;
        CourseRequest request = new CourseRequest("CS102", "Updated Course", "Updated Description");

        Course existingCourse = new Course(courseId, "CS101", "Old Course", "Old Description", LocalDateTime.now(), LocalDateTime.now(), null);

        when(courseRepository.findByIdAndDeletedAtIsNull(courseId))
            .thenReturn(Mono.just(existingCourse));

        when(courseRepository.findByCodeAndIdNotAndDeletedAtIsNull("CS102", courseId))
            .thenReturn(Mono.empty());

        Course updatedCourse = new Course(courseId, "CS102", "Updated Course", "Updated Description", LocalDateTime.now(), LocalDateTime.now(), null);
        when(courseRepository.save(any(Course.class)))
            .thenReturn(Mono.just(updatedCourse));

        StepVerifier.create(courseService.update(courseId, request))
            .expectNextMatches(response -> response.code().equals("CS102") && response.name().equals("Updated Course"))
            .verifyComplete();

        verify(courseRepository, times(1)).findByIdAndDeletedAtIsNull(courseId);
        verify(courseRepository, times(1)).save(any(Course.class));
    }

    @Test
    void testUpdate_NotFound() {
        Long courseId = 999L;
        CourseRequest request = new CourseRequest("CS102", "Updated Course", null);

        when(courseRepository.findByIdAndDeletedAtIsNull(courseId))
            .thenReturn(Mono.empty());

        StepVerifier.create(courseService.update(courseId, request))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.NOT_FOUND)
            .verify();

        verify(courseRepository, times(1)).findByIdAndDeletedAtIsNull(courseId);
        verify(courseRepository, never()).save(any(Course.class));
    }

    @Test
    void testUpdate_CodeExists() {
        Long courseId = 1L;
        CourseRequest request = new CourseRequest("CS999", "Updated Course", null);

        Course existingCourse = new Course(courseId, "CS101", "Old Course", null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(courseRepository.findByIdAndDeletedAtIsNull(courseId))
            .thenReturn(Mono.just(existingCourse));

        Course duplicateCourse = new Course(2L, "CS999", "Another Course", null, LocalDateTime.now(), LocalDateTime.now(), null);
        when(courseRepository.findByCodeAndIdNotAndDeletedAtIsNull("CS999", courseId))
            .thenReturn(Mono.just(duplicateCourse));

        StepVerifier.create(courseService.update(courseId, request))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.CONFLICT)
            .verify();

        verify(courseRepository, times(1)).findByIdAndDeletedAtIsNull(courseId);
        verify(courseRepository, never()).save(any(Course.class));
    }

    @Test
    void testUpdate_InvalidId() {
        CourseRequest request = new CourseRequest("CS102", "Updated Course", null);

        StepVerifier.create(courseService.update(0L, request))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.BAD_REQUEST)
            .verify();

        verify(courseRepository, never()).findByIdAndDeletedAtIsNull(anyLong());
    }

    @Test
    void testSoftDelete_Success() {
        Long courseId = 1L;
        Course course = new Course(courseId, "CS101", "Course", null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(courseRepository.findByIdAndDeletedAtIsNull(courseId))
            .thenReturn(Mono.just(course));

        Course deletedCourse = new Course(courseId, "CS101", "Course", null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
        when(courseRepository.save(any(Course.class)))
            .thenReturn(Mono.just(deletedCourse));

        StepVerifier.create(courseService.softDelete(courseId))
            .verifyComplete();

        verify(courseRepository, times(1)).findByIdAndDeletedAtIsNull(courseId);
        verify(courseRepository, times(1)).save(any(Course.class));
    }

    @Test
    void testSoftDelete_NotFound() {
        Long courseId = 999L;

        when(courseRepository.findByIdAndDeletedAtIsNull(courseId))
            .thenReturn(Mono.empty());

        StepVerifier.create(courseService.softDelete(courseId))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.NOT_FOUND)
            .verify();

        verify(courseRepository, times(1)).findByIdAndDeletedAtIsNull(courseId);
        verify(courseRepository, never()).save(any(Course.class));
    }

    @Test
    void testRestore_Success() {
        Long courseId = 1L;
        Course deletedCourse = new Course(courseId, "CS101", "Course", null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());

        when(courseRepository.findById(courseId))
            .thenReturn(Mono.just(deletedCourse));

        Course restoredCourse = new Course(courseId, "CS101", "Course", null, LocalDateTime.now(), LocalDateTime.now(), null);
        when(courseRepository.save(any(Course.class)))
            .thenReturn(Mono.just(restoredCourse));

        StepVerifier.create(courseService.restore(courseId))
            .expectNextMatches(response -> response.deletedAt() == null)
            .verifyComplete();

        verify(courseRepository, times(1)).findById(courseId);
        verify(courseRepository, times(1)).save(any(Course.class));
    }

    @Test
    void testRestore_NotDeleted() {
        Long courseId = 1L;
        Course activeCourse = new Course(courseId, "CS101", "Course", null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(courseRepository.findById(courseId))
            .thenReturn(Mono.just(activeCourse));

        StepVerifier.create(courseService.restore(courseId))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.BAD_REQUEST)
            .verify();

        verify(courseRepository, times(1)).findById(courseId);
        verify(courseRepository, never()).save(any(Course.class));
    }
}
