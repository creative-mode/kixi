package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.model.Subject;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.dto.assignment.TeachingAssignmentRequest;
import ao.creativemode.kixi.institutions.dto.assignment.TeachingAssignmentResponse;
import ao.creativemode.kixi.institutions.model.TeachingAssignment;
import ao.creativemode.kixi.institutions.repository.TeachingAssignmentRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Who teaches what. An assignment ties a teacher to a class, a subject and a
 * school year, and it is what decides the statements that teacher may author.
 * Assignments are managed by administrators; teachers only read their own.
 */
@Service
public class TeachingAssignmentService {

    private static final String ALREADY_ASSIGNED =
        "This teacher is already assigned to this class and subject";

    private final TeachingAssignmentRepository assignments;
    private final TeacherRepository teacherRepository;
    private final ClassRepository classRepository;
    private final SubjectRepository subjectRepository;

    public TeachingAssignmentService(
        TeachingAssignmentRepository assignments,
        TeacherRepository teacherRepository,
        ClassRepository classRepository,
        SubjectRepository subjectRepository
    ) {
        this.assignments = assignments;
        this.teacherRepository = teacherRepository;
        this.classRepository = classRepository;
        this.subjectRepository = subjectRepository;
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    public Flux<TeachingAssignmentResponse> findAllActive() {
        return assignments.findAllByDeletedAtIsNull().map(TeachingAssignmentService::toResponse);
    }

    public Flux<TeachingAssignmentResponse> findAllDeleted() {
        return assignments.findAllByDeletedAtIsNotNull().map(TeachingAssignmentService::toResponse);
    }

    public Mono<TeachingAssignmentResponse> findByIdActive(Long id) {
        return assignments
            .findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(Mono.error(ApiException.notFound("Teaching assignment not found")))
            .map(TeachingAssignmentService::toResponse);
    }

    /**
     * What the signed-in account is responsible for teaching. An account without
     * a teacher profile has no assignments, including an administrator who never
     * taught: there is nothing here for them to teach.
     */
    public Flux<TeachingAssignmentResponse> findMineForAccount(Long accountId) {
        return teacherRepository
            .findByAccountIdAndDeletedAtIsNull(accountId)
            .flatMapMany(teacher -> assignments.findAllByTeacherIdAndDeletedAtIsNull(teacher.getId()))
            .map(TeachingAssignmentService::toResponse);
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    public Mono<TeachingAssignmentResponse> create(TeachingAssignmentRequest data) {
        return validate(data)
            .then(Mono.defer(() -> assignments
                .findFirstByTeacherIdAndClassIdAndSubjectIdAndSchoolYearId(
                    data.teacherId(), data.classId(), data.subjectId(), data.schoolYearId())
                .flatMap(existing -> {
                    if (existing.isDeleted()) {
                        existing.restore();
                        apply(existing, data);
                        return assignments.save(existing);
                    }
                    return Mono.<TeachingAssignment>error(ApiException.conflict(ALREADY_ASSIGNED));
                })
                .switchIfEmpty(Mono.defer(() -> assignments.save(newAssignment(data))))))
            .map(TeachingAssignmentService::toResponse)
            .onErrorMap(DataIntegrityViolationException.class, e -> ApiException.conflict(ALREADY_ASSIGNED));
    }

    public Mono<TeachingAssignmentResponse> update(Long id, TeachingAssignmentRequest data) {
        return validate(data)
            .then(Mono.defer(() -> assignments.findByIdAndDeletedAtIsNull(id)))
            .switchIfEmpty(Mono.error(ApiException.notFound("Teaching assignment not found")))
            .flatMap(existing -> {
                apply(existing, data);
                return assignments.save(existing);
            })
            .map(TeachingAssignmentService::toResponse)
            .onErrorMap(DataIntegrityViolationException.class, e -> ApiException.conflict(ALREADY_ASSIGNED));
    }

    public Mono<Void> softDelete(Long id) {
        return assignments
            .findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(Mono.error(ApiException.notFound("Teaching assignment not found")))
            .flatMap(existing -> {
                existing.markAsDeleted();
                return assignments.save(existing);
            })
            .then();
    }

    public Mono<Void> restore(Long id) {
        return assignments
            .findByIdAndDeletedAtIsNotNull(id)
            .switchIfEmpty(Mono.error(ApiException.notFound("Only a deleted assignment can be restored")))
            .flatMap(existing -> {
                existing.restore();
                return assignments.save(existing);
            })
            .then();
    }

    public Mono<Void> hardDelete(Long id) {
        return assignments
            .findByIdAndDeletedAtIsNotNull(id)
            .switchIfEmpty(
                Mono.error(ApiException.notFound("Only a deleted assignment can be permanently removed"))
            )
            .flatMap(assignments::delete)
            .then();
    }

    // ── Authorization ───────────────────────────────────────────────────────

    /**
     * Completes when the account is assigned to teach {@code subjectId} in
     * {@code classId}; fails with 403 otherwise.
     *
     * <p>A statement without a class is not scoped to any class, so there is no
     * assignment to verify and the caller's institution-level access is enough:
     * this method then imposes nothing.
     *
     * <p>The school year is read from the class rather than taken from the
     * caller, so there is a single source of truth for which year the teacher
     * was assigned to. {@code classes.school_year_id} is NOT NULL, and every
     * statement carrying a class goes through {@link #validate}, which keeps the
     * two in step.
     */
    public Mono<Void> requireTeaches(Long accountId, Long classId, Long subjectId) {
        if (classId == null || subjectId == null) {
            return Mono.empty();
        }

        return Mono.defer(() -> teacherRepository.findByAccountIdAndDeletedAtIsNull(accountId))
            .switchIfEmpty(Mono.error(ApiException.forbidden("Only teachers can build statements")))
            .flatMap(teacher ->
                Mono.defer(() -> classRepository.findByIdAndDeletedAtIsNull(classId))
                    .map(klass -> klass.getSchoolYearId())
                    .flatMap(schoolYearId -> assignments
                        .existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                            teacher.getId(), classId, subjectId, schoolYearId))
            )
            .filter(Boolean::booleanValue)
            .switchIfEmpty(Mono.error(
                ApiException.forbidden("Teacher is not assigned to this class and subject")))
            .then();
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /**
     * The teacher, the class and the subject must all exist and be active, and
     * the school year has to be the one the class belongs to: an assignment for
     * the wrong year would silently authorize the wrong statement.
     */
    private Mono<Void> validate(TeachingAssignmentRequest data) {
        return Mono.zip(
                teacherRepository.findByIdAndDeletedAtIsNull(data.teacherId())
                    .switchIfEmpty(Mono.error(ApiException.notFound("Teacher not found"))),
                classRepository.findByIdAndDeletedAtIsNull(data.classId())
                    .switchIfEmpty(Mono.error(ApiException.notFound("Class not found"))),
                subjectRepository.findByIdAndDeletedAtIsNull(data.subjectId())
                    .switchIfEmpty(Mono.error(ApiException.notFound("Subject not found")))
            )
            .flatMap(found -> klassSchoolYearMatches(found.getT2(), data.schoolYearId()));
    }

    private Mono<Void> klassSchoolYearMatches(Class klass, Long schoolYearId) {
        if (klass.getSchoolYearId().equals(schoolYearId)) {
            return Mono.empty();
        }
        return Mono.error(ApiException.unprocessableEntity(
            "The school year must be the one of the class ("
                + klass.getSchoolYearId() + ")"));
    }

    private TeachingAssignment newAssignment(TeachingAssignmentRequest data) {
        TeachingAssignment assignment = new TeachingAssignment();
        apply(assignment, data);
        return assignment;
    }

    private void apply(TeachingAssignment assignment, TeachingAssignmentRequest data) {
        assignment.setTeacherId(data.teacherId());
        assignment.setClassId(data.classId());
        assignment.setSubjectId(data.subjectId());
        assignment.setSchoolYearId(data.schoolYearId());
        assignment.setTutorStyle(
            data.tutorStyle() == null || data.tutorStyle().isBlank() ? null : data.tutorStyle().trim());
    }

    private static TeachingAssignmentResponse toResponse(TeachingAssignment assignment) {
        return new TeachingAssignmentResponse(
            assignment.getId(),
            assignment.getTeacherId(),
            assignment.getClassId(),
            assignment.getSubjectId(),
            assignment.getSchoolYearId(),
            assignment.getTutorStyle(),
            assignment.getCreatedAt(),
            assignment.getDeletedAt()
        );
    }
}
