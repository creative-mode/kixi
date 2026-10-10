package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionSubjectRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.TeachingAssignmentAuthorizer;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Decides whether an account may author statements for an institution, so the
 * exams module can enforce the school rules without knowing how they are stored.
 */
@Service
public class InstitutionAccessService implements TeachingAssignmentAuthorizer {

    private final InstitutionRepository institutionRepository;
    private final InstitutionSubjectRepository subjectLinks;
    private final InstitutionTeacherRepository teacherLinks;
    private final TeacherRepository teacherRepository;
    private final TeachingAssignmentService teachingAssignments;

    public InstitutionAccessService(
        InstitutionRepository institutionRepository,
        InstitutionSubjectRepository subjectLinks,
        InstitutionTeacherRepository teacherLinks,
        TeacherRepository teacherRepository,
        TeachingAssignmentService teachingAssignments
    ) {
        this.institutionRepository = institutionRepository;
        this.subjectLinks = subjectLinks;
        this.teacherLinks = teacherLinks;
        this.teacherRepository = teacherRepository;
        this.teachingAssignments = teachingAssignments;
    }

    /**
     * Completes when the account is assigned to teach {@code subjectId} in
     * {@code classId}, with no institution to weigh against.
     *
     * <p>For the statements that predate the institution model — the OCR flows
     * leave {@code institution_id} empty. There is no school to check them
     * against, but the class is still on them, and skipping it entirely let any
     * teacher edit or delete a statement sitting in a class they do not teach.
     */
    @Override
    public Mono<Void> requireAssignedTo(Long accountId, boolean admin, Long classId, Long subjectId) {
        return Mono.defer(() -> teachingAssignments.requireTeaches(accountId, admin, classId, subjectId));
    }

    /**
     * Completes when the account may author a statement of {@code subjectId} in
     * {@code classId} for {@code institutionId}; fails otherwise.
     *
     * <p>Beyond belonging to the institution and to one of its subjects, a
     * statement bound to a class is only for a teacher assigned to teach that
     * class and subject: affiliation says where a teacher works, the assignment
     * says what they teach.
     */
    public Mono<Void> requireCanAuthor(
        Long accountId,
        boolean admin,
        Long institutionId,
        Long subjectId,
        Long classId
    ) {
        return requireCanAuthor(accountId, admin, institutionId, subjectId)
            .then(Mono.defer(() ->
                teachingAssignments.requireTeaches(accountId, admin, classId, subjectId)));
    }

    /**
     * Completes when the account may author a statement of {@code subjectId} for
     * {@code institutionId}; fails otherwise. The institution must exist and
     * teach the subject; a non-administrator must be a teacher affiliated with it.
     */
    public Mono<Void> requireCanAuthor(Long accountId, boolean admin, Long institutionId, Long subjectId) {
        Mono<Void> institution = institutionRepository
            .findByIdAndDeletedAtIsNull(institutionId)
            .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found")))
            .then();

        // Built lazily: each check only touches its repository once the previous
        // one has passed, so a failed check never reaches the later queries.
        Mono<Void> affiliation = Mono.defer(() -> admin
            ? Mono.<Void>empty()
            : teacherRepository
                .findByAccountIdAndDeletedAtIsNull(accountId)
                .switchIfEmpty(Mono.error(ApiException.forbidden("Only teachers can build statements")))
                .flatMap(teacher ->
                    teacherLinks.existsByInstitutionIdAndTeacherIdAndDeletedAtIsNull(institutionId, teacher.getId()))
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(ApiException.forbidden("Teacher is not affiliated with this institution")))
                .then());

        Mono<Void> subject = Mono.defer(() -> subjectLinks
            .existsByInstitutionIdAndSubjectIdAndDeletedAtIsNull(institutionId, subjectId)
            .filter(Boolean::booleanValue)
            .switchIfEmpty(Mono.error(ApiException.unprocessableEntity("Subject is not taught by this institution")))
            .then());

        return institution.then(affiliation).then(subject);
    }
}
