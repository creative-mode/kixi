package ao.creativemode.kixi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.term.TermRequest;
import ao.creativemode.kixi.model.Term;
import ao.creativemode.kixi.repository.TermRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TermServiceTest {

    private TermRepository repository;
    private TermService service;

    @BeforeEach
    void setUp() {
        repository = mock(TermRepository.class);
        service = new TermService(repository);
    }

    @Test
    void findAllActiveMapsEveryEntityToResponse() {
        when(repository.findAllByDeletedAtIsNull()).thenReturn(Flux.just(term(1L, 1, "1º Trimestre")));

        StepVerifier.create(service.findAllActive())
                .assertNext(response -> assertThat(response.name()).isEqualTo("1º Trimestre"))
                .verifyComplete();
    }

    @Test
    void findAllDeletedReturnsOnlyTrashedEntities() {
        when(repository.findAllByDeletedAtIsNotNull()).thenReturn(Flux.just(term(2L, 2, "2º Trimestre")));

        StepVerifier.create(service.findAllDeleted())
                .assertNext(response -> assertThat(response.id()).isEqualTo(2L))
                .verifyComplete();
    }

    @Test
    void findByIdActiveReturnsNotFoundForMissingTerm() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findByIdActive(99L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(404);
                })
                .verify();
    }

    @Test
    void findByIdActiveReturnsExistingTerm() {
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(term(1L, 1, "1º Trimestre")));

        StepVerifier.create(service.findByIdActive(1L))
                .assertNext(response -> assertThat(response.name()).isEqualTo("1º Trimestre"))
                .verifyComplete();
    }

    @Test
    void createSavesEntityBuiltFromRequest() {
        when(repository.save(any(Term.class))).thenAnswer(invocation -> {
            Term entity = invocation.getArgument(0);
            entity.setId(5L);
            return Mono.just(entity);
        });

        StepVerifier.create(service.create(new TermRequest("Termo Teste", 4)))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(5L);
                    assertThat(response.name()).isEqualTo("Termo Teste");
                    assertThat(response.number()).isEqualTo(4);
                })
                .verifyComplete();
    }

    @Test
    void updateRejectsMissingTermWithoutSaving() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(99L, new TermRequest("X", 1)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void updateAppliesNewFieldsToExistingTerm() {
        Term existing = term(1L, 1, "1º Trimestre");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(Term.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.update(1L, new TermRequest("Editado", 9)))
                .assertNext(response -> {
                    assertThat(response.name()).isEqualTo("Editado");
                    assertThat(response.number()).isEqualTo(9);
                })
                .verifyComplete();
    }

    @Test
    void softDeleteRejectsMissingTerm() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.softDelete(99L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void softDeleteMarksEntityAsDeleted() {
        Term existing = term(1L, 1, "1º Trimestre");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(Term.class))).thenReturn(Mono.just(existing));

        StepVerifier.create(service.softDelete(1L)).verifyComplete();

        assertThat(existing.isDeleted()).isTrue();
    }

    @Test
    void restoreRejectsTermThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.restore(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void restoreClearsDeletedAt() {
        Term deleted = term(1L, 1, "1º Trimestre");
        deleted.markAsDeleted();
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.save(any(Term.class))).thenReturn(Mono.just(deleted));

        StepVerifier.create(service.restore(1L)).verifyComplete();

        assertThat(deleted.isDeleted()).isFalse();
    }

    @Test
    void hardDeleteRejectsTermThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).delete(any(Term.class));
    }

    @Test
    void hardDeleteRemovesTrashedTerm() {
        Term deleted = term(1L, 1, "1º Trimestre");
        deleted.markAsDeleted();
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.delete(deleted)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L)).verifyComplete();

        verify(repository).delete(deleted);
    }

    private Term term(Long id, int number, String name) {
        Term term = new Term();
        term.setId(id);
        term.setNumber(number);
        term.setName(name);
        return term;
    }
}
