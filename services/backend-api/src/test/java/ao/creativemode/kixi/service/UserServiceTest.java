package ao.creativemode.kixi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.dto.users.UserRequest;
import ao.creativemode.kixi.model.Account;
import ao.creativemode.kixi.model.User;
import ao.creativemode.kixi.repository.AccountRepository;
import ao.creativemode.kixi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class UserServiceTest {

    private UserRepository repository;
    private AccountRepository accountRepository;
    private UserService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        accountRepository = mock(AccountRepository.class);
        service = new UserService(repository, accountRepository);
        when(accountRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(account(1L)));
    }

    @Test
    void findAllActiveLoadsAccountRelationshipForEveryEntity() {
        when(repository.findAllByDeletedAtIsNull()).thenReturn(Flux.just(user(1L, "Gap")));

        StepVerifier.create(service.findAllActive())
                .assertNext(response -> assertThat(response.firstName()).isEqualTo("Gap"))
                .verifyComplete();
    }

    @Test
    void findAllDeletedReturnsOnlyTrashedEntities() {
        when(repository.findAllByDeletedAtIsNotNull()).thenReturn(Flux.just(user(2L, "Trashed")));

        StepVerifier.create(service.findAllDeleted())
                .assertNext(response -> assertThat(response.id()).isEqualTo(2L))
                .verifyComplete();
    }

    @Test
    void findByIdActiveReturnsNotFoundForMissingUser() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findByIdActive(99L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(404);
                })
                .verify();
    }

    @Test
    void findByIdActiveWithAccountReturnsNotFoundWhenAccountRelationshipMissing() {
        User orphan = user(1L, "Orphan");
        orphan.setAccountId(null);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(orphan));

        StepVerifier.create(service.findByIdActiveWithAccount(1L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage()).isEqualTo("Account not found");
                })
                .verify();
    }

    @Test
    void findByIdActiveWithAccountReturnsAccountDetails() {
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(user(1L, "Gap")));

        StepVerifier.create(service.findByIdActiveWithAccount(1L))
                .assertNext(response -> assertThat(response.account().username()).isEqualTo("student_test"))
                .verifyComplete();
    }

    @Test
    void findByAccountIdActiveDelegatesToRepository() {
        when(repository.findByAccountIdAndDeletedAtIsNull(1L)).thenReturn(Flux.just(user(1L, "Gap")));

        StepVerifier.create(service.findByAccountIdActive(1L))
                .assertNext(response -> assertThat(response.accountId()).isEqualTo(1L))
                .verifyComplete();
    }

    @Test
    void createSavesUserBuiltFromRequest() {
        when(repository.save(any(User.class))).thenAnswer(invocation -> {
            User entity = invocation.getArgument(0);
            entity.setId(7L);
            return Mono.just(entity);
        });

        StepVerifier.create(service.create(new UserRequest(1L, "  Gap  ", "  Test  ", null)))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(7L);
                    assertThat(response.firstName()).isEqualTo("Gap");
                    assertThat(response.lastName()).isEqualTo("Test");
                })
                .verifyComplete();
    }

    @Test
    void updateRejectsMissingUserWithoutSaving() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(99L, new UserRequest(1L, "X", "Y", null)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void updateAppliesNewFieldsToExistingUser() {
        User existing = user(1L, "Gap");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(User.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.update(1L, new UserRequest(1L, "Edited", "User", null)))
                .assertNext(response -> assertThat(response.firstName()).isEqualTo("Edited"))
                .verifyComplete();
    }

    @Test
    void softDeleteRejectsMissingUser() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.softDelete(99L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void softDeleteMarksEntityAsDeleted() {
        User existing = user(1L, "Gap");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(User.class))).thenReturn(Mono.just(existing));

        StepVerifier.create(service.softDelete(1L)).verifyComplete();

        assertThat(existing.isDeleted()).isTrue();
    }

    @Test
    void restoreRejectsUserThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.restore(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void restoreClearsDeletedAt() {
        User deleted = user(1L, "Gap");
        deleted.markAsDeleted();
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.save(any(User.class))).thenReturn(Mono.just(deleted));

        StepVerifier.create(service.restore(1L)).verifyComplete();

        assertThat(deleted.isDeleted()).isFalse();
    }

    @Test
    void hardDeleteRejectsUserThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).delete(any(User.class));
    }

    @Test
    void hardDeleteRemovesTrashedUser() {
        User deleted = user(1L, "Gap");
        deleted.markAsDeleted();
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.delete(deleted)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L)).verifyComplete();

        verify(repository).delete(deleted);
    }

    private User user(Long id, String firstName) {
        User user = new User(1L, firstName, "Test");
        user.setId(id);
        return user;
    }

    private Account account(Long id) {
        Account account = new Account();
        account.setId(id);
        account.setUsername("student_test");
        account.setEmail("student_test@kixi.local");
        return account;
    }
}
