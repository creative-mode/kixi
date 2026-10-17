package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ProfileServiceTest {

    private InstitutionRepository institutions;
    private TeacherRepository teachers;
    private ProfileService service;

    @BeforeEach
    void setUp() {
        institutions = mock(InstitutionRepository.class);
        teachers = mock(TeacherRepository.class);
        service = new ProfileService(institutions, teachers);
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
}
