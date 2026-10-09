package ao.creativemode.kixi.institutions.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import ao.creativemode.kixi.academic.model.Course;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.CourseRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.model.AccountRole;
import ao.creativemode.kixi.identity.model.Role;
import ao.creativemode.kixi.identity.model.User;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.AccountRoleRepository;
import ao.creativemode.kixi.identity.repository.RoleRepository;
import ao.creativemode.kixi.identity.repository.UserRepository;
import ao.creativemode.kixi.institutions.dto.enrollment.MeUpdateRequest;
import ao.creativemode.kixi.institutions.model.Enrollment;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.model.InstitutionStudent;
import ao.creativemode.kixi.institutions.repository.EnrollmentRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionStudentRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class MeServiceTest {

    private AccountRepository accounts;
    private AccountRoleRepository accountRoles;
    private RoleRepository roles;
    private UserRepository users;
    private EnrollmentRepository enrollments;
    private ClassRepository classes;
    private CourseRepository courses;
    private SchoolYearRepository schoolYears;
    private InstitutionStudentRepository studentLinks;
    private InstitutionRepository institutions;
    private MeService service;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        accountRoles = mock(AccountRoleRepository.class);
        roles = mock(RoleRepository.class);
        users = mock(UserRepository.class);
        enrollments = mock(EnrollmentRepository.class);
        classes = mock(ClassRepository.class);
        courses = mock(CourseRepository.class);
        schoolYears = mock(SchoolYearRepository.class);
        studentLinks = mock(InstitutionStudentRepository.class);
        institutions = mock(InstitutionRepository.class);
        service = new MeService(accounts, accountRoles, roles, users, enrollments,
                classes, courses, schoolYears, studentLinks, institutions);
    }

    @Test
    void getMeAssemblesFullProfile() {
        stubIdentity(42L, "ada", "ada@kixi.ao", "Ada", "Lovelace", "photo.png", "STUDENT");
        stubAcademicContext(42L);

        StepVerifier.create(service.getMe(42L))
            .expectNextMatches(me ->
                me.accountId().equals(42L)
                    && me.username().equals("ada")
                    && me.firstName().equals("Ada")
                    && me.roles().contains("STUDENT")
                    && me.school() != null && me.school().code().equals("ITEL")
                    && me.course() != null && me.course().code().equals("INFO")
                    && me.currentClass() != null && me.currentClass().grade().equals(10)
                    && me.currentClass().schoolYear().equals("2024/2025"))
            .verifyComplete();
    }

    @Test
    void getMeWithoutEnrollmentLeavesAcademicContextNull() {
        stubIdentity(42L, "ada", "ada@kixi.ao", "Ada", "Lovelace", null, "STUDENT");
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(42L)).thenReturn(Flux.empty());
        when(users.findByAccountIdAndDeletedAtIsNull(42L)).thenReturn(Flux.just(user(9L, 42L)));
        when(studentLinks.findAllByUserIdAndDeletedAtIsNull(9L)).thenReturn(Flux.empty());

        StepVerifier.create(service.getMe(42L))
            .expectNextMatches(me ->
                me.accountId().equals(42L) && me.school() == null
                    && me.course() == null && me.currentClass() == null)
            .verifyComplete();
    }

    @Test
    void getMeForUnknownAccountIsNotFound() {
        when(accounts.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.getMe(99L))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.NOT_FOUND);
    }

    @Test
    void updateMeChangesOnlyProvidedFields() {
        User user = user(9L, 42L);
        user.setFirstName("Ada");
        user.setLastName("Lovelace");
        user.setPhoto("old.png");
        when(users.findByAccountIdAndDeletedAtIsNull(42L)).thenReturn(Flux.just(user));
        when(users.save(any(User.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        stubIdentity(42L, "ada", "ada@kixi.ao", "Ada", "Lovelace", "new.png", "STUDENT");
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(42L)).thenReturn(Flux.empty());
        when(studentLinks.findAllByUserIdAndDeletedAtIsNull(9L)).thenReturn(Flux.empty());

        StepVerifier.create(service.updateMe(42L, new MeUpdateRequest(null, null, "new.png")))
            .expectNextMatches(me -> me.photo().equals("new.png") && me.firstName().equals("Ada"))
            .verifyComplete();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assert saved.getValue().getFirstName().equals("Ada");
        assert saved.getValue().getPhoto().equals("new.png");
    }

    @Test
    void updateMeCreatesMissingProfile() {
        stubAccountAndRoles(42L, "ada", "ada@kixi.ao", "STUDENT");
        java.util.concurrent.atomic.AtomicReference<User> store =
            new java.util.concurrent.atomic.AtomicReference<>();
        when(users.findByAccountIdAndDeletedAtIsNull(42L)).thenAnswer(invocation -> {
            User current = store.get();
            return current == null ? Flux.empty() : Flux.just(current);
        });
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(10L);
            }
            store.set(saved);
            return Mono.just(saved);
        });
        when(studentLinks.findAllByUserIdAndDeletedAtIsNull(10L)).thenReturn(Flux.empty());
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(42L)).thenReturn(Flux.empty());

        StepVerifier.create(service.updateMe(42L, new MeUpdateRequest("Ada", "Lovelace", null)))
            .expectNextMatches(me -> me.firstName().equals("Ada") && me.lastName().equals("Lovelace"))
            .verifyComplete();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assert saved.getValue().getAccountId().equals(42L);
    }

    @Test
    void updateMeWithoutNamesCannotCreateProfile() {
        when(users.findByAccountIdAndDeletedAtIsNull(42L)).thenReturn(Flux.empty());

        StepVerifier.create(service.updateMe(42L, new MeUpdateRequest(null, null, "pic.png")))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.BAD_REQUEST);
    }

    private void stubIdentity(Long accountId, String username, String email,
            String firstName, String lastName, String photo, String roleName) {
        stubAccountAndRoles(accountId, username, email, roleName);

        User profile = user(9L, accountId);
        profile.setFirstName(firstName);
        profile.setLastName(lastName);
        profile.setPhoto(photo);
        when(users.findByAccountIdAndDeletedAtIsNull(accountId)).thenReturn(Flux.just(profile));
    }

    private void stubAccountAndRoles(Long accountId, String username, String email, String roleName) {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(accountId);
        when(account.getUsername()).thenReturn(username);
        when(account.getEmail()).thenReturn(email);
        when(account.getDeletedAt()).thenReturn(null);
        when(account.getActive()).thenReturn(Boolean.TRUE);
        when(accounts.findById(accountId)).thenReturn(Mono.just(account));

        AccountRole link = mock(AccountRole.class);
        when(link.getRoleId()).thenReturn(5L);
        when(accountRoles.findByAccountIdAndDeletedAtIsNull(accountId)).thenReturn(Flux.just(link));
        Role role = mock(Role.class);
        when(role.getName()).thenReturn(roleName);
        when(role.getDeletedAt()).thenReturn(null);
        when(roles.findById(5L)).thenReturn(Mono.just(role));
    }

    /**
     * The acceptance criterion of BE-04: a student who enrolled themselves, and whose
     * profile no administrator ever linked to a school, still gets a complete /me. The
     * school comes from the class they are in, through its course.
     */
    @Test
    void getMeFallsBackToTheSchoolOfTheEnrolledClass() {
        stubIdentity(42L, "ada", "ada@kixi.ao", "Ada", "Lovelace", null, "STUDENT");
        when(users.findByAccountIdAndDeletedAtIsNull(42L)).thenReturn(Flux.just(user(9L, 42L)));
        // No institution_students link: nobody from an administration ever set one.
        when(studentLinks.findAllByUserIdAndDeletedAtIsNull(9L)).thenReturn(Flux.empty());

        Enrollment enrollment = new Enrollment(42L, 7L, 2024L);
        enrollment.setId(3L);
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(42L)).thenReturn(Flux.just(enrollment));

        Class clazz = mock(Class.class);
        when(clazz.getId()).thenReturn(7L);
        when(clazz.getCode()).thenReturn("10A");
        when(clazz.getGrade()).thenReturn(10);
        when(clazz.getCourseId()).thenReturn(3L);
        when(clazz.getSchoolYearId()).thenReturn(2024L);
        when(clazz.getInstitutionId()).thenReturn(11L);
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(clazz));

        Course course = mock(Course.class);
        when(course.getId()).thenReturn(3L);
        when(course.getCode()).thenReturn("INFO");
        when(course.getName()).thenReturn("Informática");
        when(courses.findByIdAndDeletedAtIsNull(3L)).thenReturn(Mono.just(course));

        SchoolYear year = mock(SchoolYear.class);
        when(year.getStartYear()).thenReturn(2024);
        when(year.getEndYear()).thenReturn(2025);
        when(schoolYears.findByIdAndDeletedAtIsNull(2024L)).thenReturn(Mono.just(year));

        Institution institution = mock(Institution.class);
        when(institution.getId()).thenReturn(11L);
        when(institution.getCode()).thenReturn("ITEL");
        when(institution.getName()).thenReturn("Instituto de Telecomunicações");
        when(institutions.findByIdAndDeletedAtIsNull(11L)).thenReturn(Mono.just(institution));

        StepVerifier.create(service.getMe(42L))
            .expectNextMatches(me ->
                me.school() != null && me.school().code().equals("ITEL")
                    && me.course() != null && me.course().code().equals("INFO")
                    && me.currentClass() != null && me.currentClass().code().equals("10A"))
            .verifyComplete();
    }

    /**
     * The enrollment decides the school. A profile showing the school of one institution
     * next to the course and class of another reads as a mistake, so when both exist and
     * disagree, the school of the class the student is actually in is the one shown.
     */
    @Test
    void getMePrefersTheEnrolledClassSchoolOverTheAdministratorLink() {
        stubIdentity(42L, "ada", "ada@kixi.ao", "Ada", "Lovelace", null, "STUDENT");
        when(users.findByAccountIdAndDeletedAtIsNull(42L)).thenReturn(Flux.just(user(9L, 42L)));
        when(studentLinks.findAllByUserIdAndDeletedAtIsNull(9L))
            .thenReturn(Flux.just(new InstitutionStudent(77L, 9L)));

        Enrollment enrollment = new Enrollment(42L, 7L, 2024L);
        enrollment.setId(3L);
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(42L)).thenReturn(Flux.just(enrollment));

        Class clazz = mock(Class.class);
        when(clazz.getCourseId()).thenReturn(3L);
        when(clazz.getInstitutionId()).thenReturn(11L);
        when(clazz.getSchoolYearId()).thenReturn(2024L);
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(clazz));

        Course course = mock(Course.class);
        when(courses.findByIdAndDeletedAtIsNull(3L)).thenReturn(Mono.just(course));

        SchoolYear year = mock(SchoolYear.class);
        when(year.getStartYear()).thenReturn(2024);
        when(year.getEndYear()).thenReturn(2025);
        when(schoolYears.findByIdAndDeletedAtIsNull(2024L)).thenReturn(Mono.just(year));

        Institution enrolled = mock(Institution.class);
        when(enrolled.getId()).thenReturn(11L);
        when(enrolled.getCode()).thenReturn("ITEL");
        when(enrolled.getName()).thenReturn("ITEL");
        when(institutions.findByIdAndDeletedAtIsNull(11L)).thenReturn(Mono.just(enrolled));

        Institution linked = mock(Institution.class);
        when(linked.getId()).thenReturn(77L);
        when(institutions.findByIdAndDeletedAtIsNull(77L)).thenReturn(Mono.just(linked));

        StepVerifier.create(service.getMe(42L))
            .expectNextMatches(me -> me.school() != null && me.school().code().equals("ITEL"))
            .verifyComplete();
    }

    private void stubAcademicContext(Long accountId) {
        Enrollment enrollment = new Enrollment(accountId, 7L, 2024L);
        enrollment.setId(3L);
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(accountId))
            .thenReturn(Flux.just(enrollment));

        Class clazz = mock(Class.class);
        when(clazz.getId()).thenReturn(7L);
        when(clazz.getCode()).thenReturn("10A");
        when(clazz.getGrade()).thenReturn(10);
        when(clazz.getCourseId()).thenReturn(3L);
        when(clazz.getSchoolYearId()).thenReturn(2024L);
        // The class carries the school of its course, which is where /me reads the
        // school from now.
        when(clazz.getInstitutionId()).thenReturn(11L);
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(clazz));

        Course course = mock(Course.class);
        when(course.getId()).thenReturn(3L);
        when(course.getCode()).thenReturn("INFO");
        when(course.getName()).thenReturn("Informática");
        when(courses.findByIdAndDeletedAtIsNull(3L)).thenReturn(Mono.just(course));

        SchoolYear year = mock(SchoolYear.class);
        when(year.getStartYear()).thenReturn(2024);
        when(year.getEndYear()).thenReturn(2025);
        when(schoolYears.findByIdAndDeletedAtIsNull(2024L)).thenReturn(Mono.just(year));

        when(studentLinks.findAllByUserIdAndDeletedAtIsNull(9L))
            .thenReturn(Flux.just(new InstitutionStudent(11L, 9L)));
        Institution institution = mock(Institution.class);
        when(institution.getId()).thenReturn(11L);
        when(institution.getCode()).thenReturn("ITEL");
        when(institution.getName()).thenReturn("ITEL");
        when(institutions.findByIdAndDeletedAtIsNull(11L)).thenReturn(Mono.just(institution));
    }

    private static User user(Long id, Long accountId) {
        User user = new User();
        user.setId(id);
        user.setAccountId(accountId);
        return user;
    }
}
