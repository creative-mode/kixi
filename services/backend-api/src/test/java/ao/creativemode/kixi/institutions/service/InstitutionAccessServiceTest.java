package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionSubjectRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class InstitutionAccessServiceTest {

    private InstitutionRepository institutions;
    private InstitutionSubjectRepository subjectLinks;
    private InstitutionTeacherRepository teacherLinks;
    private TeacherRepository teachers;
    private InstitutionAccessService service;

    @BeforeEach
    void setUp() {
        institutions = mock(InstitutionRepository.class);
        subjectLinks = mock(InstitutionSubjectRepository.class);
        teacherLinks = mock(InstitutionTeacherRepository.class);
        teachers = mock(TeacherRepository.class);
        service = new InstitutionAccessService(institutions, subjectLinks, teacherLinks, teachers);
    }

    @Test
    void adminMayAuthorWhenInstitutionTeachesTheSubject() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Institution()));
        when(subjectLinks.existsByInstitutionIdAndSubjectIdAndDeletedAtIsNull(1L, 2L)).thenReturn(Mono.just(true));

        StepVerifier.create(service.requireCanAuthor(9L, true, 1L, 2L)).verifyComplete();
    }

    @Test
    void failsWhenInstitutionDoesNotExist() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.requireCanAuthor(9L, true, 1L, 2L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void teacherAffiliatedWithInstitutionMayAuthor() {
        Teacher teacher = new Teacher();
        teacher.setId(5L);
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Institution()));
        when(teachers.findByAccountIdAndDeletedAtIsNull(9L)).thenReturn(Mono.just(teacher));
        when(teacherLinks.existsByInstitutionIdAndTeacherIdAndDeletedAtIsNull(1L, 5L)).thenReturn(Mono.just(true));
        when(subjectLinks.existsByInstitutionIdAndSubjectIdAndDeletedAtIsNull(1L, 2L)).thenReturn(Mono.just(true));

        StepVerifier.create(service.requireCanAuthor(9L, false, 1L, 2L)).verifyComplete();
    }

    @Test
    void teacherNotAffiliatedIsForbidden() {
        Teacher teacher = new Teacher();
        teacher.setId(5L);
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Institution()));
        when(teachers.findByAccountIdAndDeletedAtIsNull(9L)).thenReturn(Mono.just(teacher));
        when(teacherLinks.existsByInstitutionIdAndTeacherIdAndDeletedAtIsNull(1L, 5L)).thenReturn(Mono.just(false));

        StepVerifier.create(service.requireCanAuthor(9L, false, 1L, 2L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
    }

    @Test
    void accountWithoutTeacherProfileIsForbidden() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Institution()));
        when(teachers.findByAccountIdAndDeletedAtIsNull(9L)).thenReturn(Mono.empty());

        StepVerifier.create(service.requireCanAuthor(9L, false, 1L, 2L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
    }

    @Test
    void subjectNotTaughtByInstitutionIsUnprocessable() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Institution()));
        when(subjectLinks.existsByInstitutionIdAndSubjectIdAndDeletedAtIsNull(1L, 2L)).thenReturn(Mono.just(false));

        StepVerifier.create(service.requireCanAuthor(9L, true, 1L, 2L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .verify();
    }
}
