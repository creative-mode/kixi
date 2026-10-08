package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.institutions.dto.institution.InstitutionRequest;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionStudentRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionSubjectRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class InstitutionServiceTest {

    private InstitutionRepository repository;
    private InstitutionSubjectRepository subjectLinks;
    private InstitutionTeacherRepository teacherLinks;
    private InstitutionStudentRepository studentLinks;
    private InstitutionService service;

    @BeforeEach
    void setUp() {
        repository = mock(InstitutionRepository.class);
        subjectLinks = mock(InstitutionSubjectRepository.class);
        teacherLinks = mock(InstitutionTeacherRepository.class);
        studentLinks = mock(InstitutionStudentRepository.class);
        service = new InstitutionService(repository, subjectLinks, teacherLinks, studentLinks);
    }

    @Test
    void createTrimsFieldsAndDropsBlankOptionals() {
        when(repository.save(any(Institution.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        InstitutionRequest request = new InstitutionRequest(" ITEL ", " Instituto de Telecomunicações ", "  ", null);

        StepVerifier.create(service.create(request))
                .assertNext(response -> assertThat(response.code()).isEqualTo("ITEL"))
                .verifyComplete();

        ArgumentCaptor<Institution> saved = ArgumentCaptor.forClass(Institution.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Instituto de Telecomunicações");
        assertThat(saved.getValue().getShortName()).isNull();
        assertThat(saved.getValue().getLogo()).isNull();
    }

    @Test
    void createMapsDuplicateCodeToConflict() {
        when(repository.save(any(Institution.class)))
                .thenReturn(Mono.error(new DataIntegrityViolationException("duplicate")));

        StepVerifier.create(service.create(new InstitutionRequest("ITEL", "Instituto", null, null)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();
    }

    @Test
    void softDeleteMarksTheInstitutionAsDeleted() {
        Institution institution = institution(1L, null);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(institution));
        when(repository.save(institution)).thenReturn(Mono.just(institution));

        StepVerifier.create(service.softDelete(1L)).verifyComplete();

        assertThat(institution.isDeleted()).isTrue();
    }

    @Test
    void softDeleteOfUnknownInstitutionIsNotFound() {
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.softDelete(1L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void hardDeleteRemovesTheLinksAndThenTheInstitution() {
        Institution institution = institution(1L, LocalDateTime.now());
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(institution));
        when(subjectLinks.deleteAllByInstitutionId(1L)).thenReturn(Mono.empty());
        when(teacherLinks.deleteAllByInstitutionId(1L)).thenReturn(Mono.empty());
        when(studentLinks.deleteAllByInstitutionId(1L)).thenReturn(Mono.empty());
        when(repository.delete(institution)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L)).verifyComplete();

        verify(subjectLinks).deleteAllByInstitutionId(1L);
        verify(teacherLinks).deleteAllByInstitutionId(1L);
        verify(studentLinks).deleteAllByInstitutionId(1L);
        verify(repository).delete(institution);
    }

    @Test
    void hardDeleteRequiresATrashedInstitution() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verifyNoInteractions(subjectLinks, teacherLinks, studentLinks);
    }

    @Test
    void hardDeleteIsBlockedWhileStatementsReferenceTheInstitution() {
        Institution institution = institution(1L, LocalDateTime.now());
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(institution));
        when(subjectLinks.deleteAllByInstitutionId(1L)).thenReturn(Mono.empty());
        when(teacherLinks.deleteAllByInstitutionId(1L)).thenReturn(Mono.empty());
        when(studentLinks.deleteAllByInstitutionId(1L)).thenReturn(Mono.empty());
        when(repository.delete(institution))
                .thenReturn(Mono.error(new DataIntegrityViolationException("fk_statements_institution")));

        StepVerifier.create(service.hardDelete(1L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();
    }

    private Institution institution(Long id, LocalDateTime deletedAt) {
        Institution institution = new Institution();
        institution.setId(id);
        institution.setCode("ITEL");
        institution.setName("Instituto de Telecomunicações");
        institution.setDeletedAt(deletedAt);
        return institution;
    }
}
