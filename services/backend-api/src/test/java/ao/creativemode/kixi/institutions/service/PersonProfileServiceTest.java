package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.model.User;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.identity.repository.UserRepository;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.model.InstitutionStudent;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionStudentRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.institutions.repository.ProfileSettingRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.PublicStatsReader;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PersonProfileServiceTest {

    private static final long TARGET_USER = 10L;
    private static final long TARGET_ACCOUNT = 100L;
    private static final long VIEWER_ACCOUNT = 200L;
    private static final long VIEWER_USER = 20L;
    private static final long SCHOOL = 7L;

    private UserRepository users;
    private TeacherRepository teachers;
    private InstitutionStudentRepository studentLinks;
    private InstitutionTeacherRepository teacherLinks;
    private InstitutionRepository institutions;
    private ProfileSettingRepository settings;
    private PublicStatsReader stats;
    private PersonProfileService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        teachers = mock(TeacherRepository.class);
        studentLinks = mock(InstitutionStudentRepository.class);
        teacherLinks = mock(InstitutionTeacherRepository.class);
        institutions = mock(InstitutionRepository.class);
        settings = mock(ProfileSettingRepository.class);
        stats = mock(PublicStatsReader.class);
        service = new PersonProfileService(users, teachers, studentLinks, teacherLinks, institutions, settings, stats);

        User target = user(TARGET_USER, TARGET_ACCOUNT, "Maria", "Santos");
        User viewer = user(VIEWER_USER, VIEWER_ACCOUNT, "João", "Pedro");
        when(users.findByIdAndDeletedAtIsNull(TARGET_USER)).thenReturn(Mono.just(target));
        when(users.findByAccountIdAndDeletedAtIsNull(TARGET_ACCOUNT)).thenReturn(Flux.just(target));
        when(users.findByAccountIdAndDeletedAtIsNull(VIEWER_ACCOUNT)).thenReturn(Flux.just(viewer));
        when(teachers.findByAccountIdAndDeletedAtIsNull(any())).thenReturn(Mono.empty());
        when(studentLinks.findAllByUserIdAndDeletedAtIsNull(TARGET_USER)).thenReturn(Flux.just(link(SCHOOL)));
        when(studentLinks.findAllByUserIdAndDeletedAtIsNull(VIEWER_USER)).thenReturn(Flux.just(link(SCHOOL)));

        Institution school = new Institution();
        school.setId(SCHOOL);
        school.setName("ITEL");
        school.setVerified(true);
        when(institutions.findAllById(ArgumentMatchers.<Iterable<Long>>any())).thenReturn(Flux.just(school));
        when(stats.read(TARGET_ACCOUNT)).thenReturn(Mono.just(
            new PublicStatsReader.PublicStats(4, 450L, List.of("Primeira prova"))));
    }

    private static User user(long id, long accountId, String first, String last) {
        User user = new User();
        user.setId(id);
        user.setAccountId(accountId);
        user.setFirstName(first);
        user.setLastName(last);
        return user;
    }

    private static InstitutionStudent link(long institutionId) {
        InstitutionStudent link = new InstitutionStudent();
        link.setInstitutionId(institutionId);
        return link;
    }

    private static Teacher teacher(String specialty) {
        Teacher teacher = new Teacher();
        teacher.setAccountId(TARGET_ACCOUNT);
        teacher.setSpecialty(specialty);
        return teacher;
    }

    @Test
    void publicProfileShowsEverythingToAnyone() {
        when(settings.findVisibility(TARGET_USER)).thenReturn(Mono.just("public"));
        when(studentLinks.findAllByUserIdAndDeletedAtIsNull(VIEWER_USER)).thenReturn(Flux.empty());

        StepVerifier.create(service.person(VIEWER_ACCOUNT, false, TARGET_USER))
            .assertNext(profile -> {
                assertThat(profile.restricted()).isFalse();
                assertThat(profile.displayName()).isEqualTo("Maria Santos");
                assertThat(profile.schools()).hasSize(1);
                assertThat(profile.verified()).isTrue();
                assertThat(profile.level()).isEqualTo(4);
                assertThat(profile.badges()).containsExactly("Primeira prova");
            })
            .verifyComplete();
    }

    @Test
    void teacherProfileShowsSpecialtyInsteadOfGamificationAndNeverReadsStats() {
        when(settings.findVisibility(TARGET_USER)).thenReturn(Mono.just("public"));
        when(teachers.findByAccountIdAndDeletedAtIsNull(TARGET_ACCOUNT)).thenReturn(Mono.just(teacher("Matemática")));

        StepVerifier.create(service.person(VIEWER_ACCOUNT, false, TARGET_USER))
            .assertNext(profile -> {
                assertThat(profile.kind()).isEqualTo("teacher");
                assertThat(profile.specialty()).isEqualTo("Matemática");
                assertThat(profile.level()).isNull();
                assertThat(profile.xp()).isNull();
                assertThat(profile.badges()).isEmpty();
            })
            .verifyComplete();
        verify(stats, never()).read(any());
    }

    @Test
    void restrictedTeacherProfileHasNoSpecialtyEither() {
        when(settings.findVisibility(TARGET_USER)).thenReturn(Mono.just("private"));
        when(teachers.findByAccountIdAndDeletedAtIsNull(TARGET_ACCOUNT)).thenReturn(Mono.just(teacher("Matemática")));

        StepVerifier.create(service.person(VIEWER_ACCOUNT, false, TARGET_USER))
            .assertNext(profile -> {
                assertThat(profile.restricted()).isTrue();
                assertThat(profile.kind()).isEqualTo("teacher");
                assertThat(profile.specialty()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void schoolProfileIsFullForASchoolmateAndRestrictedForAStranger() {
        when(settings.findVisibility(TARGET_USER)).thenReturn(Mono.empty());

        StepVerifier.create(service.person(VIEWER_ACCOUNT, false, TARGET_USER))
            .assertNext(profile -> {
                assertThat(profile.visibility()).isEqualTo("school");
                assertThat(profile.restricted()).isFalse();
            })
            .verifyComplete();

        when(studentLinks.findAllByUserIdAndDeletedAtIsNull(VIEWER_USER)).thenReturn(Flux.just(link(99L)));

        StepVerifier.create(service.person(VIEWER_ACCOUNT, false, TARGET_USER))
            .assertNext(profile -> {
                assertThat(profile.restricted()).isTrue();
                assertThat(profile.displayName()).isEqualTo("Maria Santos");
                assertThat(profile.schools()).isEmpty();
                assertThat(profile.level()).isNull();
                assertThat(profile.badges()).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void privateProfileHidesEvenFromASchoolmateButNotFromTheOwnerOrAnAdmin() {
        when(settings.findVisibility(TARGET_USER)).thenReturn(Mono.just("private"));

        StepVerifier.create(service.person(VIEWER_ACCOUNT, false, TARGET_USER))
            .assertNext(profile -> assertThat(profile.restricted()).isTrue())
            .verifyComplete();
        StepVerifier.create(service.person(TARGET_ACCOUNT, false, TARGET_USER))
            .assertNext(profile -> assertThat(profile.restricted()).isFalse())
            .verifyComplete();
        StepVerifier.create(service.person(VIEWER_ACCOUNT, true, TARGET_USER))
            .assertNext(profile -> assertThat(profile.restricted()).isFalse())
            .verifyComplete();
    }

    @Test
    void deletedOrMissingUserIsNotFound() {
        when(users.findByIdAndDeletedAtIsNull(404L)).thenReturn(Mono.empty());

        StepVerifier.create(service.person(VIEWER_ACCOUNT, false, 404L))
            .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
            .verify();
    }

    @Test
    void myVisibilityDefaultsToSchoolAndCanBeChanged() {
        when(settings.findVisibility(VIEWER_USER)).thenReturn(Mono.empty());
        when(settings.upsertVisibility(VIEWER_USER, "private")).thenReturn(Mono.just(1));

        StepVerifier.create(service.myVisibility(VIEWER_ACCOUNT)).expectNext("school").verifyComplete();
        StepVerifier.create(service.setMyVisibility(VIEWER_ACCOUNT, "private")).expectNext("private").verifyComplete();
        verify(settings).upsertVisibility(VIEWER_USER, "private");
    }

    @Test
    void anAccountWithoutAUserCannotSetVisibility() {
        when(users.findByAccountIdAndDeletedAtIsNull(555L)).thenReturn(Flux.empty());

        StepVerifier.create(service.setMyVisibility(555L, "public"))
            .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
            .verify();
        verify(settings, never()).upsertVisibility(any(), any());
    }

    @Test
    void profileByAccountResolvesTheUserOfThatAccount() {
        when(settings.findVisibility(TARGET_USER)).thenReturn(Mono.just("public"));

        StepVerifier.create(service.personByAccount(VIEWER_ACCOUNT, false, TARGET_ACCOUNT))
            .assertNext(profile -> assertThat(profile.userId()).isEqualTo(TARGET_USER))
            .verifyComplete();
    }

    @Test
    void anAccountWithoutAUserHasNoProfile() {
        when(users.findByAccountIdAndDeletedAtIsNull(777L)).thenReturn(Flux.empty());

        StepVerifier.create(service.personByAccount(VIEWER_ACCOUNT, false, 777L))
            .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
            .verify();
    }
}
