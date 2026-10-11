package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.institutions.model.Enrollment;
import ao.creativemode.kixi.institutions.repository.EnrollmentRepository;
import ao.creativemode.kixi.shared.service.EnrollmentSeatReader;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

/**
 * {@link EnrollmentSeatReader} over the enrollments and classes of this module.
 */
@Service
public class InstitutionEnrollmentSeatReader implements EnrollmentSeatReader {

    /**
     * Newest seat first, then highest id: the id decides between two rows joined in the
     * same instant so the answer never depends on the order the rows came back in.
     */
    private static final java.util.Comparator<Enrollment> SEAT_ORDER = java.util.Comparator
            .comparing(Enrollment::getCreatedAt,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
            .thenComparing(Enrollment::getId,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));

    private final EnrollmentRepository enrollments;
    private final ClassRepository classes;

    public InstitutionEnrollmentSeatReader(
            EnrollmentRepository enrollments, ClassRepository classes) {
        this.enrollments = enrollments;
        this.classes = classes;
    }

    @Override
    public Flux<Seat> activeSeatsOf(Long accountId) {
        return enrollments.findAllByAccountIdAndDeletedAtIsNull(accountId)
                .filter(Enrollment::isActive)
                .filter(enrollment -> enrollment.getClassId() != null)
                .sort(SEAT_ORDER)
                // concatMap, not filter: reading the class behind a seat is a query, and
                // blocking here would park a thread on the database. A class that is gone
                // is not a seat at all; a class with no school still is one, for a class
                // ranking, and carries a null school for a school one to skip.
                .concatMap(enrollment -> classes.findByIdAndDeletedAtIsNull(enrollment.getClassId())
                        .map(klass -> new Seat(
                                enrollment.getClassId(),
                                enrollment.getSchoolYearId(),
                                klass.getInstitutionId())));
    }
}