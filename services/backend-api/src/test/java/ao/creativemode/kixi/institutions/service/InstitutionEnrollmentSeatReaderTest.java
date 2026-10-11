package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.institutions.model.Enrollment;
import ao.creativemode.kixi.institutions.repository.EnrollmentRepository;
import ao.creativemode.kixi.shared.service.EnrollmentSeatReader.Seat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Where the rule for "which seat is the current one" lives now that it is behind a door.
 * The ranking service takes the first seat it is handed, so everything deciding which
 * seat that is has to be checked here.
 */
class InstitutionEnrollmentSeatReaderTest {

    private static final Long ACCOUNT_ID = 12L;
    private static final Long CLASS_ID = 7L;
    private static final Long OTHER_CLASS_ID = 9L;
    private static final Long SCHOOL_ID = 1L;
    private static final Long OTHER_SCHOOL_ID = 2L;
    private static final Long YEAR = 1L;

    private EnrollmentRepository enrollments;
    private ClassRepository classes;
    private InstitutionEnrollmentSeatReader reader;

    @BeforeEach
    void setUp() {
        enrollments = mock(EnrollmentRepository.class);
        classes = mock(ClassRepository.class);
        reader = new InstitutionEnrollmentSeatReader(enrollments, classes);
    }

    /** Classes 7 and 9 sit in schools 1 and 2. */
    private void classesExist() {
        when(classes.findByIdAndDeletedAtIsNull(anyLong()))
                .thenAnswer(call -> Mono.just(klass(
                        call.<Long>getArgument(0),
                        CLASS_ID.equals(call.<Long>getArgument(0)) ? SCHOOL_ID : OTHER_SCHOOL_ID)));
    }

    private static Class klass(Long id, Long institutionId) {
        Class klass = new Class();
        klass.setId(id);
        klass.setInstitutionId(institutionId);
        return klass;
    }

    private void holding(Enrollment... rows) {
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(ACCOUNT_ID))
                .thenReturn(Flux.just(rows));
    }

    @Test
    void aCancelledRowIsNotASeat() {
        classesExist();
        // markAsDeleted is what a withdrawal leaves behind, and it is not a seat.
        holding(cancelled(OTHER_CLASS_ID, OTHER_SCHOOL_ID),
                active(CLASS_ID, SCHOOL_ID, LocalDateTime.of(2026, 9, 1, 0, 0), 1L));

        StepVerifier.create(reader.activeSeatsOf(ACCOUNT_ID))
                .expectNext(new Seat(CLASS_ID, YEAR, SCHOOL_ID))
                .verifyComplete();
    }

    @Test
    void theNewestSeatComesFirst() {
        classesExist();
        holding(active(OTHER_CLASS_ID, OTHER_SCHOOL_ID, LocalDateTime.of(2026, 10, 1, 0, 0), 2L),
                active(CLASS_ID, SCHOOL_ID, LocalDateTime.of(2026, 9, 1, 0, 0), 1L));

        StepVerifier.create(reader.activeSeatsOf(ACCOUNT_ID))
                .expectNext(new Seat(OTHER_CLASS_ID, YEAR, OTHER_SCHOOL_ID))
                .expectNext(new Seat(CLASS_ID, YEAR, SCHOOL_ID))
                .verifyComplete();
    }

    @Test
    void twoSeatsJoinedAtTheSameInstantStillComeBackInOneOrder() {
        // created_at has second precision in some setups, so the id decides.
        classesExist();
        LocalDateTime sameInstant = LocalDateTime.of(2026, 10, 1, 9, 0);
        holding(active(CLASS_ID, SCHOOL_ID, sameInstant, 1L),
                active(OTHER_CLASS_ID, OTHER_SCHOOL_ID, sameInstant, 2L));

        StepVerifier.create(reader.activeSeatsOf(ACCOUNT_ID))
                .expectNext(new Seat(OTHER_CLASS_ID, YEAR, OTHER_SCHOOL_ID))
                .expectNext(new Seat(CLASS_ID, YEAR, SCHOOL_ID))
                .verifyComplete();
    }

    @Test
    void theOrderDoesNotFollowTheOrderTheRowsCameBack() {
        classesExist();
        holding(active(OTHER_CLASS_ID, OTHER_SCHOOL_ID, LocalDateTime.of(2026, 10, 1, 0, 0), 2L),
                active(CLASS_ID, SCHOOL_ID, LocalDateTime.of(2026, 9, 1, 0, 0), 1L));

        StepVerifier.create(reader.activeSeatsOf(ACCOUNT_ID))
                .expectNext(new Seat(OTHER_CLASS_ID, YEAR, OTHER_SCHOOL_ID))
                .expectNext(new Seat(CLASS_ID, YEAR, SCHOOL_ID))
                .verifyComplete();
    }

    @Test
    void aSeatInAClassWithNoSchoolIsStillASeat() {
        when(classes.findByIdAndDeletedAtIsNull(CLASS_ID)).thenReturn(Mono.just(klass(CLASS_ID, null)));
        holding(active(CLASS_ID, SCHOOL_ID, LocalDateTime.of(2026, 9, 1, 0, 0), 1L));

        StepVerifier.create(reader.activeSeatsOf(ACCOUNT_ID))
                .expectNext(new Seat(CLASS_ID, YEAR, null))
                .verifyComplete();
    }

    @Test
    void aClassThatNoLongerExistsIsNotASeat() {
        when(classes.findByIdAndDeletedAtIsNull(CLASS_ID)).thenReturn(Mono.empty());
        holding(active(CLASS_ID, SCHOOL_ID, LocalDateTime.of(2026, 9, 1, 0, 0), 1L));

        StepVerifier.create(reader.activeSeatsOf(ACCOUNT_ID)).verifyComplete();
    }

    @Test
    void anAccountWithNoEnrollmentsHasNoSeats() {
        holding();

        StepVerifier.create(reader.activeSeatsOf(ACCOUNT_ID)).verifyComplete();
    }

    @Test
    void everySeatCarriesTheYearItBelongsTo() {
        classesExist();
        when(classes.findByIdAndDeletedAtIsNull(CLASS_ID)).thenReturn(Mono.just(klass(CLASS_ID, SCHOOL_ID)));
        Enrollment older = active(CLASS_ID, SCHOOL_ID, LocalDateTime.of(2026, 9, 1, 0, 0), 1L);
        older.setSchoolYearId(3L);
        holding(older);

        StepVerifier.create(reader.activeSeatsOf(ACCOUNT_ID))
                .expectNext(new Seat(CLASS_ID, 3L, SCHOOL_ID))
                .verifyComplete();
    }

    private static Enrollment active(
            Long classId, Long schoolId, LocalDateTime createdAt, Long id) {
        Enrollment enrollment = new Enrollment();
        enrollment.setAccountId(ACCOUNT_ID);
        enrollment.setClassId(classId);
        enrollment.setSchoolYearId(YEAR);
        enrollment.setId(id);
        enrollment.setCreatedAt(createdAt);
        return enrollment;
    }

    private static Enrollment cancelled(Long classId, Long schoolId) {
        Enrollment enrollment = active(classId, schoolId, LocalDateTime.of(2026, 10, 1, 0, 0), 9L);
        enrollment.markAsDeleted();
        return enrollment;
    }
}