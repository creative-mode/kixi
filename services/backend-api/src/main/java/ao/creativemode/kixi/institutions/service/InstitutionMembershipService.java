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
import ao.creativemode.kixi.institutions.repository.AcademicDependencyQueries;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionStudentRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionSubjectRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
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
    private final AcademicDependencyQueries dependencies;

    public InstitutionMembershipService(
        InstitutionRepository institutionRepository,
        InstitutionSubjectRepository subjectLinks,
        InstitutionTeacherRepository teacherLinks,
        InstitutionStudentRepository studentLinks,
        SubjectRepository subjectRepository,
        TeacherRepository teacherRepository,
        UserRepository userRepository,
        AcademicDependencyQueries dependencies
    ) {
        this.institutionRepository = institutionRepository;
        this.subjectLinks = subjectLinks;
        this.teacherLinks = teacherLinks;
        this.studentLinks = studentLinks;
        this.subjectRepository = subjectRepository;
        this.teacherRepository = teacherRepository;
        this.userRepository = userRepository;
        this.dependencies = dependencies;
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
            .then(Mono.defer(() -> subjectRepository.findById(subjectId)
                .filter(subject -> !subject.isDeleted())
                .switchIfEmpty(Mono.error(ApiException.notFound("Subject not found")))))
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
                    .flatMap(link -> dependencies.curriculumEntriesOfSchoolSubject(institutionId, subjectId)
                        .flatMap(entries -> entries > 0
                            ? Mono.<InstitutionSubject>error(ApiException.conflict(
                                "This subject is in the curriculum of " + entries
                                    + " course entr" + (entries == 1 ? "y" : "ies")
                                    + " of this institution; remove it from the curriculum first"))
                            : Mono.just(link)))
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
            .then(Mono.defer(() -> teacherRepository.findByIdAndDeletedAtIsNull(teacherId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Teacher not found")))))
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
                .collectList()
                .flatMapMany(links -> {
                    Map<Long, String> numbers = new HashMap<>();
                    links.forEach(link -> numbers.put(link.getUserId(), link.getProcessNumber()));
                    return userRepository.findAllById(links.stream().map(InstitutionStudent::getUserId).toList())
                        .filter(user -> !user.isDeleted())
                        .map(user -> toStudentLink(user, numbers.get(user.getId())));
                })
        );
    }

    public Mono<Void> addStudent(Long institutionId, Long userId) {
        return requireInstitution(institutionId)
            .then(Mono.defer(() -> userRepository.findByIdAndDeletedAtIsNull(userId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Student not found")))))
            .then(Mono.defer(() ->
                studentLinks.findFirstByInstitutionIdAndUserId(institutionId, userId)
                    .flatMap(link -> {
                        if (link.isDeleted()) {
                            link.restore();
                            // O número ficou livre quando a ligação foi apagada; se entretanto é de outro aluno, perde-se
                            // (a ligação volta sem número) em vez de rebentar no índice único (kixi#512).
                            String number = link.getProcessNumber();
                            if (number == null) return studentLinks.save(link);
                            return studentLinks.existsByInstitutionIdAndProcessNumberAndUserIdNotAndDeletedAtIsNull(
                                    institutionId, number, userId)
                                .flatMap(taken -> {
                                    if (taken) link.setProcessNumber(null);
                                    return studentLinks.save(link);
                                });
                        }
                        return Mono.<InstitutionStudent>error(
                            ApiException.conflict("Student is already enrolled in this institution"));
                    })
                    .switchIfEmpty(Mono.defer(() ->
                        studentLinks.save(new InstitutionStudent(institutionId, userId))))
            ))
            .then();
    }

    /** O limite do contrato v1 do motor de pautas (alunos[].processo). */
    public static final int PROCESS_NUMBER_MAX = 30;
    private static final Pattern PROCESS_NUMBER = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} ./_-]*$");

    /**
     * Normaliza o número de processo: aparado; vazio é "sem número" (nulo). Letras, dígitos, espaço e {@code . / _ -},
     * até {@value #PROCESS_NUMBER_MAX} caracteres: o que se imprime numa pauta, sem caracteres de controlo.
     */
    public static String normalizeProcessNumber(String raw) {
        if (raw == null) return null;
        String value = raw.strip();
        if (value.isEmpty()) return null;
        if (value.length() > PROCESS_NUMBER_MAX) {
            throw ApiException.badRequest("O número de processo tem no máximo " + PROCESS_NUMBER_MAX + " caracteres.");
        }
        if (!PROCESS_NUMBER.matcher(value).matches()) {
            throw ApiException.badRequest("O número de processo só pode ter letras, algarismos, espaço e . / _ -");
        }
        return value;
    }

    /**
     * Define (ou apaga) o número de processo do aluno nesta escola. Só o administrador (SecurityConfig); o aluno nunca
     * o edita. Se o aluno ainda não tem ligação à escola (entrou por código de turma), a ligação cria-se aqui: o número
     * é dele nessa escola. Único por escola entre os alunos ativos (kixi#512).
     */
    public Mono<StudentLinkResponse> setProcessNumber(Long institutionId, Long userId, String rawNumber) {
        String number;
        try {
            number = normalizeProcessNumber(rawNumber);
        } catch (ApiException error) {
            return Mono.error(error);
        }
        return requireInstitution(institutionId)
            .then(Mono.defer(() -> userRepository.findByIdAndDeletedAtIsNull(userId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Student not found")))))
            .flatMap(user -> {
                Mono<Void> unique = number == null ? Mono.empty()
                    : studentLinks.existsByInstitutionIdAndProcessNumberAndUserIdNotAndDeletedAtIsNull(institutionId, number, userId)
                        .flatMap(taken -> taken
                            ? Mono.<Void>error(ApiException.conflict("O número de processo " + number + " já pertence a outro aluno desta escola."))
                            : Mono.<Void>empty());
                return unique.then(Mono.defer(() -> studentLinks.findFirstByInstitutionIdAndUserId(institutionId, userId)
                    .switchIfEmpty(Mono.defer(() -> Mono.just(new InstitutionStudent(institutionId, userId))))
                    .flatMap(link -> {
                        if (link.isDeleted()) link.restore();
                        link.setProcessNumber(number);
                        return studentLinks.save(link);
                    })
                    // A corrida entre dois pedidos apanha-se no índice único.
                    .onErrorMap(org.springframework.dao.DataIntegrityViolationException.class,
                        e -> ApiException.conflict("O número de processo já pertence a outro aluno desta escola."))
                    .map(link -> toStudentLink(user, link.getProcessNumber()))));
            });
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

    private StudentLinkResponse toStudentLink(User user, String processNumber) {
        return new StudentLinkResponse(user.getId(), user.getAccountId(), user.getFirstName(), user.getLastName(), processNumber);
    }
}
