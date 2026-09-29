package ao.creativemode.kixi.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.identity.dto.accounts.AccountRequest;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AccountServiceTest {

    private AccountRepository repository;
    private PasswordEncoder passwordEncoder;
    private AccountService service;

    @BeforeEach
    void setUp() {
        repository = mock(AccountRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new AccountService(repository, passwordEncoder);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
    }

    @Test
    void findAllActiveMapsEveryEntityToResponse() {
        when(repository.findAllByDeletedAtIsNull()).thenReturn(Flux.just(account(1L, "admin")));

        StepVerifier.create(service.findAllActive())
                .assertNext(response -> assertThat(response.username()).isEqualTo("admin"))
                .verifyComplete();
    }

    @Test
    void findAllDeletedReturnsOnlyTrashedEntities() {
        when(repository.findAllByDeletedAtIsNotNull()).thenReturn(Flux.just(account(2L, "student")));

        StepVerifier.create(service.findAllDeleted())
                .assertNext(response -> assertThat(response.id()).isEqualTo(2L))
                .verifyComplete();
    }

    @Test
    void findAllByActiveDelegatesFlagToRepository() {
        when(repository.findAllByActiveAndDeletedAtIsNull(false)).thenReturn(Flux.just(account(3L, "inactive")));

        StepVerifier.create(service.findAllByActive(false))
                .assertNext(response -> assertThat(response.username()).isEqualTo("inactive"))
                .verifyComplete();
    }

    @Test
    void findByIdActiveReturnsNotFoundForMissingAccount() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findByIdActive(99L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(404);
                })
                .verify();
    }

    @Test
    void findByUsernameReturnsNotFoundWhenAbsent() {
        when(repository.findByUsernameAndDeletedAtIsNull("ghost")).thenReturn(Mono.empty());

        StepVerifier.create(service.findByUsername("ghost"))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();
    }

    @Test
    void createHashesPasswordAndNormalizesEmail() {
        when(repository.save(any(Account.class))).thenAnswer(invocation -> {
            Account entity = invocation.getArgument(0);
            entity.setId(5L);
            return Mono.just(entity);
        });

        StepVerifier.create(service.create(new AccountRequest("newuser", "New.User@Kixi.Ao", "SuperSecret1")))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(5L);
                    assertThat(response.username()).isEqualTo("newuser");
                    assertThat(response.email()).isEqualTo("new.user@kixi.ao");
                })
                .verifyComplete();

        verify(passwordEncoder).encode("SuperSecret1");
    }

    @Test
    void createMapsDuplicateUsernameOrEmailConflictToApiException() {
        when(repository.save(any(Account.class)))
                .thenReturn(Mono.error(new DataIntegrityViolationException("duplicate")));

        StepVerifier.create(service.create(new AccountRequest("dup", "dup@kixi.ao", "SuperSecret1")))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                })
                .verify();
    }

    @Test
    void updateRejectsMissingAccountWithoutSaving() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(99L, new AccountRequest("x", "x@kixi.ao", "SuperSecret1")))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void updateAppliesNewFieldsToExistingAccount() {
        Account existing = account(1L, "admin");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(Account.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.update(1L, new AccountRequest("admin2", "admin2@kixi.ao", "NewSecret1")))
                .assertNext(response -> {
                    assertThat(response.username()).isEqualTo("admin2");
                    assertThat(response.email()).isEqualTo("admin2@kixi.ao");
                })
                .verifyComplete();
    }

    @Test
    void softDeleteRejectsMissingAccount() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.softDelete(99L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void softDeleteMarksEntityAsDeleted() {
        Account existing = account(1L, "admin");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(Account.class))).thenReturn(Mono.just(existing));

        StepVerifier.create(service.softDelete(1L)).verifyComplete();

        assertThat(existing.getDeletedAt()).isNotNull();
    }

    @Test
    void restoreRejectsAccountThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.restore(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void restoreClearsDeletedAt() {
        Account deleted = account(1L, "admin");
        deleted.setDeletedAt(java.time.LocalDateTime.now());
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.save(any(Account.class))).thenReturn(Mono.just(deleted));

        StepVerifier.create(service.restore(1L)).verifyComplete();

        assertThat(deleted.getDeletedAt()).isNull();
    }

    @Test
    void recordLoginRejectsMissingAccount() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.recordLogin(99L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();
    }

    @Test
    void recordLoginRejectsInactiveAccount() {
        Account inactive = account(1L, "admin");
        inactive.setActive(false);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(inactive));

        StepVerifier.create(service.recordLogin(1L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(400);
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void recordLoginUpdatesLastLoginForActiveAccount() {
        Account existing = account(1L, "admin");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(Account.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.recordLogin(1L))
                .assertNext(response -> assertThat(response.lastLogin()).isNotNull())
                .verifyComplete();
    }

    @Test
    void verifyPasswordReturnsFalseWhenAccountDoesNotExist() {
        when(repository.findByUsernameAndDeletedAtIsNull("ghost")).thenReturn(Mono.empty());

        StepVerifier.create(service.verifyPassword("ghost", "whatever"))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void verifyPasswordDelegatesMatchingToPasswordEncoder() {
        Account existing = account(1L, "admin");
        existing.setPasswordHash("hashed");
        when(repository.findByUsernameAndDeletedAtIsNull("admin")).thenReturn(Mono.just(existing));
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);

        StepVerifier.create(service.verifyPassword("admin", "correct"))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void hardDeleteRejectsAccountThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).delete(any(Account.class));
    }

    @Test
    void hardDeleteRemovesTrashedAccount() {
        Account deleted = account(1L, "admin");
        deleted.setDeletedAt(java.time.LocalDateTime.now());
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.delete(deleted)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L)).verifyComplete();

        verify(repository).delete(deleted);
    }

    private Account account(Long id, String username) {
        Account account = new Account();
        account.setId(id);
        account.setUsername(username);
        account.setEmail(username + "@kixi.ao");
        account.setActive(true);
        account.setEmailVerified(false);
        return account;
    }
}
