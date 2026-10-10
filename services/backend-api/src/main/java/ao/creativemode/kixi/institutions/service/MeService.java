package ao.creativemode.kixi.institutions.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import ao.creativemode.kixi.academic.model.Course;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.CourseRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.identity.model.Role;
import ao.creativemode.kixi.identity.model.User;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.AccountRoleRepository;
import ao.creativemode.kixi.identity.repository.RoleRepository;
import ao.creativemode.kixi.identity.repository.UserRepository;
import ao.creativemode.kixi.institutions.dto.enrollment.MeResponse;
import ao.creativemode.kixi.institutions.dto.enrollment.MeUpdateRequest;
import ao.creativemode.kixi.institutions.model.Enrollment;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.repository.EnrollmentRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionStudentRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The signed-in account's profile: identity, roles and current academic
 * context (school, course, class).
 *
 * <p>The school comes from the most recent active enrollment — through the
 * class, which carries the school of its course — and falls back to the
 * account's own institution link when there is no enrollment to read it from.
 * Course and class come from that same enrollment.</p>
 */
@Service
public class MeService {

    private final AccountRepository accountRepository;
    private final AccountRoleRepository accountRoleRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;
    private final CourseRepository courseRepository;
    private final SchoolYearRepository schoolYearRepository;
    private final InstitutionStudentRepository studentLinks;
    private final InstitutionRepository institutionRepository;

    public MeService(
        AccountRepository accountRepository,
        AccountRoleRepository accountRoleRepository,
        RoleRepository roleRepository,
        UserRepository userRepository,
        EnrollmentRepository enrollmentRepository,
        ClassRepository classRepository,
        CourseRepository courseRepository,
        SchoolYearRepository schoolYearRepository,
        InstitutionStudentRepository studentLinks,
        InstitutionRepository institutionRepository
    ) {
        this.accountRepository = accountRepository;
        this.accountRoleRepository = accountRoleRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.courseRepository = courseRepository;
        this.schoolYearRepository = schoolYearRepository;
        this.studentLinks = studentLinks;
        this.institutionRepository = institutionRepository;
    }

    public Mono<MeResponse> getMe(Long accountId) {
        return Mono.defer(() -> accountRepository.findById(accountId)
                .filter(account -> account.getDeletedAt() == null && Boolean.TRUE.equals(account.getActive()))
                .switchIfEmpty(Mono.error(ApiException.notFound("Account not found"))))
            .flatMap(account -> Mono.zip(
                userRepository.findByAccountIdAndDeletedAtIsNull(accountId).singleOrEmpty()
                    .defaultIfEmpty(new User()),
                loadRoleNames(accountId).collectList(),
                currentEnrollment(accountId).map(Optional::<EnrollmentContext>of)
                    .defaultIfEmpty(Optional.empty()),
                resolveSchool(accountId).map(Optional::<MeResponse.SchoolInfo>of)
                    .defaultIfEmpty(Optional.empty())
            ).map(tuple -> {
                User user = tuple.getT1();
                List<String> roles = tuple.getT2();
                Optional<EnrollmentContext> enrollment = tuple.getT3();
                Optional<MeResponse.SchoolInfo> school = tuple.getT4();
                return new MeResponse(
                    account.getId(), account.getUsername(), account.getEmail(),
                    user.getFirstName(), user.getLastName(), user.getPhoto(),
                    roles, school.orElse(null),
                    enrollment.map(context -> new MeResponse.CourseInfo(
                            context.course().getId(), context.course().getCode(), context.course().getName()))
                        .orElse(null),
                    enrollment.map(context -> new MeResponse.ClassInfo(
                            context.clazz().getId(), context.clazz().getCode(), context.clazz().getGrade(),
                            context.clazz().getSchoolYearId(), context.schoolYearLabel()))
                        .orElse(null),
                    account.getAccessibilityExtraTime()
            );
            }));
    }

    public Mono<MeResponse> updateMe(Long accountId, MeUpdateRequest request) {
        return userRepository.findByAccountIdAndDeletedAtIsNull(accountId)
            .singleOrEmpty()
            .switchIfEmpty(Mono.defer(() -> {
                User created = new User();
                created.setAccountId(accountId);
                return Mono.just(created);
            }))
            .flatMap(user -> {
                if (request.firstName() != null) {
                    user.setFirstName(request.firstName());
                }
                if (request.lastName() != null) {
                    user.setLastName(request.lastName());
                }
                if (request.photo() != null) {
                    user.setPhoto(request.photo());
                }
                if (user.getId() == null
                        && (user.getFirstName() == null || user.getLastName() == null)) {
                    return Mono.error(ApiException.badRequest(
                        "First and last name are required to create the profile"));
                }
                return userRepository.save(user);
            })
            .then(getMe(accountId));
    }

    private Flux<String> loadRoleNames(Long accountId) {
        return accountRoleRepository.findByAccountIdAndDeletedAtIsNull(accountId)
            .flatMap(link -> roleRepository.findById(link.getRoleId()))
            .filter(role -> role.getDeletedAt() == null)
            .map(Role::getName);
    }

    /** Most recent active enrollment, fully resolved (empty when none). */
    private Mono<EnrollmentContext> currentEnrollment(Long accountId) {
        return enrollmentRepository.findAllByAccountIdAndDeletedAtIsNull(accountId)
            .sort(Comparator.comparing(Enrollment::getId).reversed())
            .next()
            .flatMap(enrollment -> Mono.zip(
                classRepository.findByIdAndDeletedAtIsNull(enrollment.getClassId()),
                schoolYearRepository.findByIdAndDeletedAtIsNull(enrollment.getSchoolYearId())
            ).flatMap(tuple -> courseRepository
                .findByIdAndDeletedAtIsNull(tuple.getT1().getCourseId())
                .map(course -> new EnrollmentContext(tuple.getT1(), course, schoolYearLabel(tuple.getT2())))));
    }

    /**
     * The school of the class the account is enrolled in. This wins over an explicit
     * administrator link: the enrollment already names a class, the class carries the
     * school of its course, and showing one school with the course and class of another
     * reads as a mistake on the screen. The link is what fills the gap when there is no
     * enrollment to read the school from.
     */
    private Mono<MeResponse.SchoolInfo> resolveSchool(Long accountId) {
        return enrolledSchool(accountId).switchIfEmpty(linkedSchool(accountId));
    }

    /**
     * The school a class belongs to, read through the class.
     *
     * <p>Public because a cohort membership cannot be decided from a profile alone:
     * issue #117 has to know which school an enrollment actually sits in before it
     * ranks the student with it.</p>
     */
    public Mono<Long> classInstitutionId(Long classId) {
        if (classId == null) {
            return Mono.empty();
        }
        return classRepository.findByIdAndDeletedAtIsNull(classId)
                .flatMap(clazz -> clazz.getInstitutionId() == null
                        ? Mono.empty()
                        : Mono.just(clazz.getInstitutionId()));
    }

    /**
     * The school of the most recent active enrollment, resolved through the class.
     */
    private Mono<MeResponse.SchoolInfo> enrolledSchool(Long accountId) {
        return enrollmentRepository.findAllByAccountIdAndDeletedAtIsNull(accountId)
            .sort(Comparator.comparing(Enrollment::getId).reversed())
            .next()
            .flatMap(enrollment -> classRepository.findByIdAndDeletedAtIsNull(enrollment.getClassId()))
            .flatMap(clazz -> clazz.getInstitutionId() == null
                ? Mono.empty()
                : institutionRepository.findByIdAndDeletedAtIsNull(clazz.getInstitutionId()))
            .map(MeService::toSchoolInfo);
    }

    /**
     * The explicit link an administrator set, used only when the account has no
     * enrollment to read a school from.
     */
    private Mono<MeResponse.SchoolInfo> linkedSchool(Long accountId) {
        return userRepository.findByAccountIdAndDeletedAtIsNull(accountId)
            .singleOrEmpty()
            .flatMapMany(user -> studentLinks.findAllByUserIdAndDeletedAtIsNull(user.getId()))
            .next()
            .flatMap(link -> institutionRepository.findByIdAndDeletedAtIsNull(link.getInstitutionId()))
            .map(MeService::toSchoolInfo);
    }

    private static MeResponse.SchoolInfo toSchoolInfo(Institution institution) {
        return new MeResponse.SchoolInfo(
            institution.getId(), institution.getCode(), institution.getName());
    }

    private static String schoolYearLabel(SchoolYear schoolYear) {
        return schoolYear.getStartYear() + "/" + schoolYear.getEndYear();
    }

    private record EnrollmentContext(
        ao.creativemode.kixi.academic.model.Class clazz,
        Course course,
        String schoolYearLabel
    ) { }
}
