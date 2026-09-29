package ao.creativemode.kixi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.dto.subject.SubjectRequest;
import ao.creativemode.kixi.model.Subject;
import ao.creativemode.kixi.repository.SubjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SubjectServiceTest {

    private SubjectRepository repository;
    private SubjectService service;

    @BeforeEach
    void setUp() {
        repository = mock(SubjectRepository.class);
        service = new SubjectService(repository);
    }

    @Test
    void findAllActiveMapsEveryEntityToResponse() {
        when(repository.findAllByDeletedAtIsNull()).thenReturn(Flux.just(subject(1L, "MAT", "Matemática")));

        StepVerifier.create(service.findAllActive())
                .assertNext(response -> assertThat(response.name()).isEqualTo("Matemática"))
                .verifyComplete();
    }

    @Test
    void findAllDeletedReturnsOnlyTrashedEntities() {
        when(repository.findAllByDeletedAtIsNotNull()).thenReturn(Flux.just(subject(2L, "FIS", "Física")));

        StepVerifier.create(service.findAllDeleted())
                .assertNext(response -> assertThat(response.id()).isEqualTo(2L))
                .verifyComplete();
    }

    @Test
    void findByCodeActiveReturnsNotFoundForMissingSubject() {
        when(repository.findByCodeAndDeletedAtIsNull("XXX")).thenReturn(Mono.empty());

        StepVerifier.create(service.findByCodeActive("XXX"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(404);
                })
                .verify();
    }

    @Test
    void createSavesSubjectBuiltFromRequest() {
        when(repository.save(any(Subject.class))).thenAnswer(invocation -> {
            Subject entity = invocation.getArgument(0);
            entity.setId(5L);
            return Mono.just(entity);
        });

        StepVerifier.create(service.create(new SubjectRequest("MAT", "Matemática", "Mat")))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(5L);
                    assertThat(response.code()).isEqualTo("MAT");
                })
                .verifyComplete();
    }

    @Test
    void createMapsDuplicateCodeConflictToApiException() {
        when(repository.save(any(Subject.class)))
                .thenReturn(Mono.error(new DataIntegrityViolationException("duplicate")));

        StepVerifier.create(service.create(new SubjectRequest("MAT", "Matemática", "Mat")))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                })
                .verify();
    }

    @Test
    void updateRejectsMissingSubjectWithoutSaving() {
        when(repository.findByCodeAndDeletedAtIsNull("XXX")).thenReturn(Mono.empty());

        StepVerifier.create(service.update("XXX", new SubjectRequest("XXX", "Nome", null)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void updateAppliesNewFieldsToExistingSubject() {
        Subject existing = subject(1L, "MAT", "Matemática");
        when(repository.findByCodeAndDeletedAtIsNull("MAT")).thenReturn(Mono.just(existing));
        when(repository.save(any(Subject.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.update("MAT", new SubjectRequest("MAT", "Matemática Editada", "MatE")))
                .assertNext(response -> assertThat(response.name()).isEqualTo("Matemática Editada"))
                .verifyComplete();
    }

    @Test
    void softDeleteRejectsMissingSubject() {
        when(repository.findByCodeAndDeletedAtIsNull("XXX")).thenReturn(Mono.empty());

        StepVerifier.create(service.softDelete("XXX"))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void softDeleteMarksEntityAsDeleted() {
        Subject existing = subject(1L, "MAT", "Matemática");
        when(repository.findByCodeAndDeletedAtIsNull("MAT")).thenReturn(Mono.just(existing));
        when(repository.save(any(Subject.class))).thenReturn(Mono.just(existing));

        StepVerifier.create(service.softDelete("MAT")).verifyComplete();

        assertThat(existing.isDeleted()).isTrue();
    }

    @Test
    void restoreRejectsSubjectThatIsNotInTrash() {
        when(repository.findByCodeAndDeletedAtIsNotNull("MAT")).thenReturn(Mono.empty());

        StepVerifier.create(service.restore("MAT"))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void restoreClearsDeletedAt() {
        Subject deleted = subject(1L, "MAT", "Matemática");
        deleted.markAsDeleted();
        when(repository.findByCodeAndDeletedAtIsNotNull("MAT")).thenReturn(Mono.just(deleted));
        when(repository.save(any(Subject.class))).thenReturn(Mono.just(deleted));

        StepVerifier.create(service.restore("MAT")).verifyComplete();

        assertThat(deleted.isDeleted()).isFalse();
    }

    @Test
    void hardDeleteRejectsSubjectThatIsNotInTrash() {
        when(repository.findByCodeAndDeletedAtIsNotNull("MAT")).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete("MAT"))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).delete(any(Subject.class));
    }

    @Test
    void hardDeleteRemovesTrashedSubject() {
        Subject deleted = subject(1L, "MAT", "Matemática");
        deleted.markAsDeleted();
        when(repository.findByCodeAndDeletedAtIsNotNull("MAT")).thenReturn(Mono.just(deleted));
        when(repository.delete(deleted)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete("MAT")).verifyComplete();

        verify(repository).delete(deleted);
    }

    private Subject subject(Long id, String code, String name) {
        Subject subject = new Subject();
        subject.setId(id);
        subject.setCode(code);
        subject.setName(name);
        return subject;
    }
}
