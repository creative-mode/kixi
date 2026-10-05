package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.academic.model.Subject;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.model.User;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.identity.repository.UserRepository;
import ao.creativemode.kixi.institutions.dto.institution.InstitutionResponse;
import ao.creativemode.kixi.institutions.dto.membership.StudentLinkResponse;
import ao.creativemode.kixi.institutions.dto.membership.SubjectLinkResponse;
import ao.creativemode.kixi.institutions.dto.membership.TeacherLinkResponse;
import ao.creativemode.kixi.institutions.model.InstitutionStudent;
import ao.creativemode.kixi.institutions.model.InstitutionSubject;
import ao.creativemode.kixi.institutions.model.InstitutionTeacher;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionStudentRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionSubjectRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The N:N links of an institution: the subjects it teaches, its teachers and
 * its students. Teachers and students can belong to several institutions.
 * A removed link is soft-deleted and restored if it is added again.
 */
@Service
public class InstitutionMembershipService {

    private final InstitutionRepository institutionRepository;
    private final InstitutionSubjectRepository subjectLinks;
    private final InstitutionTeacherRepository teacherLinks;
    private final InstitutionStudentRepository studentLinks;
    private final SubjectRepository subjectRepository;
    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;

    public InstitutionMembershipService(
        InstitutionRepository institutionRepository,
        InstitutionSubjectRepository subjectLinks,
        InstitutionTeacherRepository teacherLinks,
        InstitutionStudentRepository studentLinks,
        SubjectRepository subjectRepository,
        TeacherRepository teacherRepository,
        UserRepository userRepository
    ) {
        this.institutionRepository = institutionRepository;
        this.subjectLinks = subjectLinks;
        this.teacherLinks = teacherLinks;
        this.studentLinks = studentLinks;
        this.subjectRepository = subjectRepository;
        this.teacherRepository = teacherRepository;
        this.userRepository = userRepository;
    }

    // ── Subjects ────────────────────────────────────────────────────────────

    public Flux<SubjectLinkResponse> findSubjects(Long institutionId) {
        return requireInstitution(institutionId).thenMany(
            subjectLinks
                .findAllByInstitutionIdAndDeletedAtIsNull(institutionId)
                .map(InstitutionSubject::getSubjectId)
                .collectList()
                .flatMapMany(ids -> subjectRepository.findAllById(ids))
                .filter(subject -> !subject.isDeleted())
                .map(this::toSubjectLink)
        );
    }

    public Mono<Void> addSubject(Long institutionId, Long subjectId) {
        return requireInstitution(institutionId)
            .then(subjectRepository.findById(subjectId)
                .filter(subject -> !subject.isDeleted())
                .switchIfEmpty(Mono.error(ApiException.notFound("Subject not found"))))
            .then(Mono.defer(() ->
                subjectLinks.findFirstByInstitutionIdAndSubjectId(institutionId, subjectId)
                    .flatMap(link -> {
                        if (link.isDeleted()) {
                            link.restore();
                            return subjectLinks.save(link);
                        }
                        return Mono.<InstitutionSubject>error(
                            ApiException.conflict("Subject is already taught by this institution"));
                    })
                    .switchIfEmpty(Mono.defer(() ->
                        subjectLinks.save(new InstitutionSubject(institutionId, subjectId))))
            ))
            .then();
    }

    public Mono<Void> removeSubject(Long institutionId, Long subjectId) {
        return requireInstitution(institutionId)
            .then(Mono.defer(() ->
                subjectLinks.findFirstByInstitutionIdAndSubjectId(institutionId, subjectId)
                    .filter(link -> !link.isDeleted())
                    .switchIfEmpty(Mono.error(ApiException.notFound("Subject is not taught by this institution")))
                    .flatMap(link -> {
                        link.markAsDeleted();
                        return subjectLinks.save(link);
                    })
            ))
            .then();
    }

    // ── Teachers ────────────────────────────────────────────────────────────

    public Flux<TeacherLinkResponse> findTeachers(Long institutionId) {
        return requireInstitution(institutionId).thenMany(
            teacherLinks
                .findAllByInstitutionIdAndDeletedAtIsNull(institutionId)
                .map(InstitutionTeacher::getTeacherId)
                .collectList()
                .flatMapMany(ids -> teacherRepository.findAllById(ids))
                .filter(teacher -> !teacher.isDeleted())
                .map(this::toTeacherLink)
        );
    }

    public Mono<Void> addTeacher(Long institutionId, Long teacherId) {
        return requireInstitution(institutionId)
            .then(teacherRepository.findByIdAndDeletedAtIsNull(teacherId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Teacher not found"))))
            .then(Mono.defer(() ->
                teacherLinks.findFirstByInstitutionIdAndTeacherId(institutionId, teacherId)
                    .flatMap(link -> {
                        if (link.isDeleted()) {
                            link.restore();
                            return teacherLinks.save(link);
                        }
                        return Mono.<InstitutionTeacher>error(
                            ApiException.conflict("Teacher is already affiliated with this institution"));
                    })
                    .switchIfEmpty(Mono.defer(() ->
                        teacherLinks.save(new InstitutionTeacher(institutionId, teacherId))))
            ))
            .then();
    }

    public Mono<Void> removeTeacher(Long institutionId, Long teacherId) {
        return requireInstitution(institutionId)
            .then(Mono.defer(() ->
                teacherLinks.findFirstByInstitutionIdAndTeacherId(institutionId, teacherId)
                    .filter(link -> !link.isDeleted())
                    .switchIfEmpty(Mono.error(ApiException.notFound("Teacher is not affiliated with this institution")))
                    .flatMap(link -> {
                        link.markAsDeleted();
                        return teacherLinks.save(link);
                    })
            ))
            .then();
    }

    // ── Students ────────────────────────────────────────────────────────────

    public Flux<StudentLinkResponse> findStudents(Long institutionId) {
        return requireInstitution(institutionId).thenMany(
            studentLinks
                .findAllByInstitutionIdAndDeletedAtIsNull(institutionId)
                .map(InstitutionStudent::getUserId)
                .collectList()
                .flatMapMany(ids -> userRepository.findAllById(ids))
                .filter(user -> !user.isDeleted())
                .map(this::toStudentLink)
        );
    }

    public Mono<Void> addStudent(Long institutionId, Long userId) {
        return requireInstitution(institutionId)
            .then(userRepository.findByIdAndDeletedAtIsNull(userId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Student not found"))))
            .then(Mono.defer(() ->
                studentLinks.findFirstByInstitutionIdAndUserId(institutionId, userId)
                    .flatMap(link -> {
                        if (link.isDeleted()) {
                            link.restore();
                            return studentLinks.save(link);
                        }
                        return Mono.<InstitutionStudent>error(
                            ApiException.conflict("Student is already enrolled in this institution"));
                    })
                    .switchIfEmpty(Mono.defer(() ->
                        studentLinks.save(new InstitutionStudent(institutionId, userId))))
            ))
            .then();
    }

    public Mono<Void> removeStudent(Long institutionId, Long userId) {
        return requireInstitution(institutionId)
            .then(Mono.defer(() ->
                studentLinks.findFirstByInstitutionIdAndUserId(institutionId, userId)
                    .filter(link -> !link.isDeleted())
                    .switchIfEmpty(Mono.error(ApiException.notFound("Student is not enrolled in this institution")))
                    .flatMap(link -> {
                        link.markAsDeleted();
                        return studentLinks.save(link);
                    })
            ))
            .then();
    }

    // ── What the signed-in account belongs to ───────────────────────────────

    /**
     * The institutions an account belongs to: every active one for an
     * administrator, otherwise those of its teacher profile and of its student
     * profiles (a person can be both, and can be in several institutions).
     */
    public Flux<InstitutionResponse> findForAccount(Long accountId, boolean admin) {
        if (admin) {
            return institutionRepository.findAllByDeletedAtIsNull().map(InstitutionService::toResponse);
        }

        Flux<Long> asTeacher = teacherRepository
            .findByAccountIdAndDeletedAtIsNull(accountId)
            .flatMapMany(teacher -> teacherLinks.findAllByTeacherIdAndDeletedAtIsNull(teacher.getId()))
            .map(InstitutionTeacher::getInstitutionId);

        Flux<Long> asStudent = userRepository
            .findByAccountIdAndDeletedAtIsNull(accountId)
            .flatMap(user -> studentLinks.findAllByUserIdAndDeletedAtIsNull(user.getId()))
            .map(InstitutionStudent::getInstitutionId);

        return Flux.concat(asTeacher, asStudent)
            .distinct()
            .flatMap(institutionRepository::findByIdAndDeletedAtIsNull)
            .map(InstitutionService::toResponse);
    }

    private Mono<Void> requireInstitution(Long institutionId) {
        return institutionRepository
            .findByIdAndDeletedAtIsNull(institutionId)
            .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found")))
            .then();
    }

    private SubjectLinkResponse toSubjectLink(Subject subject) {
        return new SubjectLinkResponse(subject.getId(), subject.getCode(), subject.getName(), subject.getShortName());
    }

    private TeacherLinkResponse toTeacherLink(Teacher teacher) {
        return new TeacherLinkResponse(
            teacher.getId(), teacher.getFirstName(), teacher.getLastName(), teacher.getEmail(), teacher.hasAccess());
    }

    private StudentLinkResponse toStudentLink(User user) {
        return new StudentLinkResponse(user.getId(), user.getAccountId(), user.getFirstName(), user.getLastName());
    }
}
