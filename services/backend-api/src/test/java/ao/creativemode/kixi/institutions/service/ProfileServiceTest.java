package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.model.InstitutionTeacher;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ProfileServiceTest {

    private InstitutionRepository institutions;
    private TeacherRepository teachers;
    private InstitutionTeacherRepository teacherLinks;
    private ProfileService service;

    @BeforeEach
    void setUp() {
        institutions = mock(InstitutionRepository.class);
        teachers = mock(TeacherRepository.class);
        teacherLinks = mock(InstitutionTeacherRepository.class);
        service = new ProfileService(institutions, teachers, teacherLinks);
    }

    private Institution school(boolean verified) {
        Institution institution = new Institution();
        institution.setId(7L);
        institution.setCode("ITEL");
        institution.setName("Instituto de Telecomunicações");
        institution.setVerified(verified);
        return institution;
    }

    @Test
    void profileCarriesIdentityBadgeAndCounts() {
        when(institutions.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(school(true)));
        when(institutions.countActiveTeachers(7L)).thenReturn(Mono.just(12L));
        when(institutions.countActiveStudents(7L)).thenReturn(Mono.just(340L));
        when(institutions.countVisibleStatements(7L)).thenReturn(Mono.just(25L));
        when(institutions.countPublishedRooms(7L)).thenReturn(Mono.just(4L));

        StepVerifier.create(service.institution(7L))
            .assertNext(profile -> {
                assertThat(profile.name()).isEqualTo("Instituto de Telecomunicações");
                assertThat(profile.verified()).isTrue();
                assertThat(profile.teacherCount()).isEqualTo(12L);
                assertThat(profile.studentCount()).isEqualTo(340L);
                assertThat(profile.statementCount()).isEqualTo(25L);
                assertThat(profile.roomCount()).isEqualTo(4L);
            })
            .verifyComplete();
    }

    @Test
    void missingOrDeletedSchoolIsNotFound() {
        when(institutions.findByIdAndDeletedAtIsNull(9L)).thenReturn(Mono.empty());

        StepVerifier.create(service.institution(9L))
            .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
            .verify();
    }

    @Test
    void teacherOfAVerifiedSchoolIsVerifiedAndShowsOnlyAPublicCard() {
        Teacher teacher = new Teacher();
        teacher.setId(5L);
        teacher.setFirstName("Ana");
        teacher.setLastName(" Silva ");
        teacher.setEmail("ana@itel.ao");
        teacher.setSpecialty("Redes");
        when(institutions.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(school(true)));
        when(teachers.findAllActiveByInstitutionId(7L)).thenReturn(Flux.just(teacher));

        StepVerifier.create(service.teachersOf(7L))
            .assertNext(card -> {
                assertThat(card.displayName()).isEqualTo("Ana Silva");
                assertThat(card.specialty()).isEqualTo("Redes");
                assertThat(card.verified()).isTrue();
            })
            .verifyComplete();
    }

    @Test
    void teacherOfAnUnverifiedSchoolIsNotVerified() {
        Teacher teacher = new Teacher();
        teacher.setId(5L);
        when(institutions.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(school(false)));
        when(teachers.findAllActiveByInstitutionId(7L)).thenReturn(Flux.just(teacher));

        StepVerifier.create(service.teachersOf(7L))
            .assertNext(card -> {
                assertThat(card.displayName()).isEqualTo("Professor");
                assertThat(card.verified()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void aTooShortQueryAnswersNothingWithoutTouchingTheDatabase() {
        StepVerifier.create(service.searchInstitutions(" a ")).verifyComplete();
        verifyNoInteractions(institutions);
    }

    @Test
    void searchEscapesLikeWildcardsAndLowerCases() {
        when(institutions.search(anyString(), anyInt())).thenReturn(Flux.just(school(true)));

        StepVerifier.create(service.searchInstitutions("IT_50%"))
            .assertNext(card -> assertThat(card.verified()).isTrue())
            .verifyComplete();

        verify(institutions).search("%it\\_50\\%%", ProfileService.MAX_RESULTS);
    }

    @Test
    void likePatternWrapsAndEscapes() {
        assertThat(ProfileService.likePattern("Mutu")).isEqualTo("%mutu%");
        assertThat(ProfileService.likePattern("a\\b")).isEqualTo("%a\\\\b%");
    }

    private Institution school(String name, Long id, boolean verified) {
        Institution institution = new Institution();
        institution.setId(id);
        institution.setCode(name);
        institution.setName(name);
        institution.setVerified(verified);
        return institution;
    }

    // ── Teacher search (issue #406) ──────────────────────────────────────

    @Test
    void aTooShortTeacherQueryAnswersNothingWithoutTouchingTheDatabase() {
        StepVerifier.create(service.searchTeachers(" a ")).verifyComplete();
        verifyNoInteractions(teachers);
        verifyNoInteractions(teacherLinks);
    }

    @Test
    void teacherSearchEscapesLikeWildcardsAndLowerCases() {
        when(teachers.searchActiveByText(anyString(), anyInt())).thenReturn(Flux.empty());

        StepVerifier.create(service.searchTeachers("Jo_50%")).verifyComplete();

        verify(teachers).searchActiveByText("%jo\\_50\\%%", ProfileService.MAX_RESULTS);
    }

    @Test
    void aTeacherAtTwoSchoolsIsOneCardCarryingBoth() {
        Teacher teacher = new Teacher();
        teacher.setId(5L);
        teacher.setAccountId(50L);
        teacher.setFirstName("Joaquim");
        teacher.setLastName("Alberto");
        teacher.setSpecialty("Matemática");
        when(teachers.searchActiveByText(anyString(), anyInt())).thenReturn(Flux.just(teacher));
        when(teacherLinks.findAllByTeacherIdInAndDeletedAtIsNull(any()))
            .thenReturn(Flux.just(new InstitutionTeacher(7L, 5L), new InstitutionTeacher(9L, 5L)));
        when(institutions.findAllById(anyIterable()))
            .thenReturn(Flux.just(school("ITEL", 7L, true), school("Liceu", 9L, false)));

        StepVerifier.create(service.searchTeachers("joaquim"))
            .assertNext(card -> {
                assertThat(card.displayName()).isEqualTo("Joaquim Alberto");
                assertThat(card.specialty()).isEqualTo("Matemática");
                assertThat(card.verified()).isTrue();
                assertThat(card.schools()).extracting("name").containsExactly("ITEL", "Liceu");
            })
            .verifyComplete();
    }

    @Test
    void aMatchedTeacherWithoutALiveSchoolCarriesNoSchools() {
        Teacher teacher = new Teacher();
        teacher.setId(5L);
        when(teachers.searchActiveByText(anyString(), anyInt())).thenReturn(Flux.just(teacher));
        when(teacherLinks.findAllByTeacherIdInAndDeletedAtIsNull(any())).thenReturn(Flux.empty());

        StepVerifier.create(service.searchTeachers("joaquim"))
            .assertNext(card -> {
                assertThat(card.schools()).isEmpty();
                assertThat(card.verified()).isFalse();
            })
            .verifyComplete();
    }
}
