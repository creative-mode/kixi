package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.model.TeachingAssignment;
import ao.creativemode.kixi.institutions.repository.TeachingAssignmentRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class InstitutionTeachingScopeReaderTest {

    private static final Long ACCOUNT_ID = 2L;
    private static final Long TEACHER_ID = 20L;

    private TeacherRepository teachers;
    private TeachingAssignmentRepository assignments;
    private InstitutionTeachingScopeReader reader;

    @BeforeEach
    void setUp() {
        teachers = mock(TeacherRepository.class);
        assignments = mock(TeachingAssignmentRepository.class);
        reader = new InstitutionTeachingScopeReader(teachers, assignments);
    }

    private void hasAProfile() {
        Teacher teacher = new Teacher();
        teacher.setId(TEACHER_ID);
        when(teachers.findByAccountIdAndDeletedAtIsNull(ACCOUNT_ID)).thenReturn(Mono.just(teacher));
    }

    private void assignedTo(Long... classIds) {
        TeachingAssignment[] rows = new TeachingAssignment[classIds.length];
        for (int i = 0; i < classIds.length; i++) {
            rows[i] = new TeachingAssignment();
            rows[i].setClassId(classIds[i]);
        }
        when(assignments.findAllByTeacherIdAndDeletedAtIsNull(TEACHER_ID)).thenReturn(Flux.just(rows));
    }

    @Test
    void aTeacherSeesTheClassesTheyAreAssignedTo() {
        hasAProfile();
        assignedTo(7L, 9L);

        StepVerifier.create(reader.classIdsTaughtBy(ACCOUNT_ID))
                .expectNext(7L)
                .expectNext(9L)
                .verifyComplete();
    }

    @Test
    void theSameClassTwiceIsOneClass() {
        hasAProfile();
        // Two subjects in the same class is two assignments and one class.
        assignedTo(7L, 7L);

        StepVerifier.create(reader.classIdsTaughtBy(ACCOUNT_ID))
                .expectNext(7L)
                .verifyComplete();
    }

    /** An administrator administers the school; it does not answer for a class. */
    @Test
    void anAccountWithoutATeacherProfileTeachesNothing() {
        when(teachers.findByAccountIdAndDeletedAtIsNull(ACCOUNT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(reader.classIdsTaughtBy(ACCOUNT_ID)).verifyComplete();
    }

    @Test
    void aTeacherWithNoAssignmentTeachesNothing() {
        hasAProfile();
        when(assignments.findAllByTeacherIdAndDeletedAtIsNull(TEACHER_ID)).thenReturn(Flux.empty());

        StepVerifier.create(reader.classIdsTaughtBy(ACCOUNT_ID)).verifyComplete();
    }

    @Test
    void nothingIsTaughtWithoutAProfileOrAnAssignment() {
        when(teachers.findByAccountIdAndDeletedAtIsNull(ACCOUNT_ID)).thenReturn(Mono.empty());
        when(assignments.findAllByTeacherIdAndDeletedAtIsNull(TEACHER_ID)).thenReturn(Flux.empty());

        assertThat(reader.classIdsTaughtBy(ACCOUNT_ID).collectList().block()).isEmpty();
    }
}