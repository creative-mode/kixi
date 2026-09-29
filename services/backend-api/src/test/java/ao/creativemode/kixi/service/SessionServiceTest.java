package ao.creativemode.kixi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.sessions.SessionRequest;
import ao.creativemode.kixi.model.Account;
import ao.creativemode.kixi.model.Session;
import ao.creativemode.kixi.repository.AccountRepository;
import ao.creativemode.kixi.repository.SessionRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SessionServiceTest {

    private SessionRepository repository;
    private AccountRepository accountRepository;
    private SessionService service;

    @BeforeEach
    void setUp() {
        repository = mock(SessionRepository.class);
        accountRepository = mock(AccountRepository.class);
        service = new SessionService(repository, accountRepository);
        when(accountRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(account(1L)));
    }

    @Test
    void findAllActiveLoadsAccountRelationshipForEveryEntity() {
        when(repository.findAllByDeletedAtIsNull()).thenReturn(Flux.just(session(1L, "tok-1")));

        StepVerifier.create(service.findAllActive())
                .assertNext(response -> assertThat(response.token()).isEqualTo("tok-1"))
                .verifyComplete();
    }

    @Test
    void findAllDeletedReturnsOnlyTrashedEntities() {
        when(repository.findAllByDeletedAtIsNotNull()).thenReturn(Flux.just(session(2L, "tok-2")));

        StepVerifier.create(service.findAllDeleted())
                .assertNext(response -> assertThat(response.id()).isEqualTo(2L))
                .verifyComplete();
    }

    @Test
    void findByIdActiveReturnsNotFoundForMissingSession() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findByIdActive(99L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(404);
                })
                .verify();
    }

    @Test
    void createSavesSessionWithDefaultExpiryWhenNoneProvided() {
        when(repository.save(any(Session.class))).thenAnswer(invocation -> {
            Session entity = invocation.getArgument(0);
            entity.setId(5L);
            return Mono.just(entity);
        });

        StepVerifier.create(service.create(new SessionRequest(1L, "tok-abc", "127.0.0.1", null)))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(5L);
                    assertThat(response.token()).isEqualTo("tok-abc");
                    assertThat(response.expiresAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void updateRejectsMissingSessionWithoutSaving() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(99L, new SessionRequest(1L, "tok", "127.0.0.1", null)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void updateAppliesNewFieldsToExistingSession() {
        Session existing = session(1L, "tok-old");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(Session.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.update(1L, new SessionRequest(1L, "tok-new", "10.0.0.1", null)))
                .assertNext(response -> {
                    assertThat(response.token()).isEqualTo("tok-new");
                    assertThat(response.ipAddress()).isEqualTo("10.0.0.1");
                })
                .verifyComplete();
    }

    @Test
    void softDeleteRejectsMissingSession() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.softDelete(99L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void softDeleteMarksEntityAsDeleted() {
        Session existing = session(1L, "tok-1");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(Session.class))).thenReturn(Mono.just(existing));

        StepVerifier.create(service.softDelete(1L)).verifyComplete();

        assertThat(existing.isDeleted()).isTrue();
    }

    @Test
    void restoreRejectsSessionThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.restore(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void restoreClearsDeletedAt() {
        Session deleted = session(1L, "tok-1");
        deleted.markAsDeleted();
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.save(any(Session.class))).thenReturn(Mono.just(deleted));

        StepVerifier.create(service.restore(1L)).verifyComplete();

        assertThat(deleted.isDeleted()).isFalse();
    }

    @Test
    void hardDeleteRejectsSessionThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).delete(any(Session.class));
    }

    @Test
    void hardDeleteRemovesTrashedSession() {
        Session deleted = session(1L, "tok-1");
        deleted.markAsDeleted();
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.delete(deleted)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L)).verifyComplete();

        verify(repository).delete(deleted);
    }

    private Session session(Long id, String token) {
        Session session = Session.builder()
                .id(id)
                .accountId(1L)
                .token(token)
                .ipAddress("127.0.0.1")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .lastUsed(LocalDateTime.now())
                .build();
        return session;
    }

    private Account account(Long id) {
        Account account = new Account();
        account.setId(id);
        return account;
    }
}
