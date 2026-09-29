package ao.creativemode.kixi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.model.Account;
import ao.creativemode.kixi.model.AccountRole;
import ao.creativemode.kixi.model.Role;
import ao.creativemode.kixi.repository.AccountRepository;
import ao.creativemode.kixi.repository.AccountRoleRepository;
import ao.creativemode.kixi.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AccountRoleServiceTest {

    private AccountRoleRepository accountRoleRepository;
    private AccountRepository accountRepository;
    private RoleRepository roleRepository;
    private AccountRoleService service;

    @BeforeEach
    void setUp() {
        accountRoleRepository = mock(AccountRoleRepository.class);
        accountRepository = mock(AccountRepository.class);
        roleRepository = mock(RoleRepository.class);
        service = new AccountRoleService(accountRoleRepository, accountRepository, roleRepository);
    }

    @Test
    void findRolesByAccountIdRejectsMissingAccount() {
        when(accountRepository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findRolesByAccountId(99L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();
    }

    @Test
    void findRolesByAccountIdFiltersOutDeletedRoles() {
        when(accountRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Account()));
        when(accountRoleRepository.findByAccountIdAndDeletedAtIsNull(1L))
                .thenReturn(Flux.just(new AccountRole(1L, 2L), new AccountRole(1L, 3L)));
        when(roleRepository.findById(2L)).thenReturn(Mono.just(role(2L, "TEACHER", null)));
        Role deletedRole = role(3L, "GHOST", java.time.LocalDateTime.now());
        when(roleRepository.findById(3L)).thenReturn(Mono.just(deletedRole));

        StepVerifier.create(service.findRolesByAccountId(1L))
                .assertNext(response -> assertThat(response.name()).isEqualTo("TEACHER"))
                .verifyComplete();
    }

    @Test
    void assignRoleToAccountRejectsMissingAccount() {
        when(accountRepository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());
        when(roleRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Mono.just(role(2L, "TEACHER", null)));

        StepVerifier.create(service.assignRoleToAccount(99L, 2L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(accountRoleRepository, never()).save(any());
    }

    @Test
    void assignRoleToAccountRejectsMissingRole() {
        when(accountRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Account()));
        when(roleRepository.findByIdAndDeletedAtIsNull(9999L)).thenReturn(Mono.empty());

        StepVerifier.create(service.assignRoleToAccount(1L, 9999L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(accountRoleRepository, never()).save(any());
    }

    @Test
    void assignRoleToAccountRejectsAlreadyAssignedActiveRole() {
        when(accountRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Account()));
        when(roleRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Mono.just(role(2L, "TEACHER", null)));
        when(accountRoleRepository.findFirstByAccountIdAndRoleId(1L, 2L))
                .thenReturn(Mono.just(new AccountRole(1L, 2L)));

        StepVerifier.create(service.assignRoleToAccount(1L, 2L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                })
                .verify();

        verify(accountRoleRepository, never()).save(any());
    }

    @Test
    void assignRoleToAccountRestoresPreviouslyRemovedAssociation() {
        when(accountRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Account()));
        when(roleRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Mono.just(role(2L, "TEACHER", null)));
        AccountRole removed = new AccountRole(1L, 2L);
        removed.markAsDeleted();
        when(accountRoleRepository.findFirstByAccountIdAndRoleId(1L, 2L)).thenReturn(Mono.just(removed));
        when(accountRoleRepository.save(removed)).thenReturn(Mono.just(removed));

        StepVerifier.create(service.assignRoleToAccount(1L, 2L)).verifyComplete();

        assertThat(removed.isDeleted()).isFalse();
        verify(accountRoleRepository).save(removed);
    }

    @Test
    void assignRoleToAccountCreatesNewAssociationWhenNoneExists() {
        when(accountRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Account()));
        when(roleRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Mono.just(role(2L, "TEACHER", null)));
        when(accountRoleRepository.findFirstByAccountIdAndRoleId(1L, 2L)).thenReturn(Mono.empty());
        when(accountRoleRepository.save(any(AccountRole.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.assignRoleToAccount(1L, 2L)).verifyComplete();

        verify(accountRoleRepository).save(any(AccountRole.class));
    }

    @Test
    void removeRoleFromAccountRejectsWhenAssociationDoesNotExist() {
        when(accountRoleRepository.findByAccountIdAndRoleIdAndDeletedAtIsNull(1L, 2L)).thenReturn(Mono.empty());

        StepVerifier.create(service.removeRoleFromAccount(1L, 2L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(accountRoleRepository, never()).save(any());
    }

    @Test
    void removeRoleFromAccountMarksAssociationAsDeleted() {
        AccountRole existing = new AccountRole(1L, 2L);
        when(accountRoleRepository.findByAccountIdAndRoleIdAndDeletedAtIsNull(1L, 2L)).thenReturn(Mono.just(existing));
        when(accountRoleRepository.save(existing)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.removeRoleFromAccount(1L, 2L)).verifyComplete();

        assertThat(existing.isDeleted()).isTrue();
    }

    private Role role(Long id, String name, java.time.LocalDateTime deletedAt) {
        Role role = new Role(name, null);
        role.setId(id);
        role.setDeletedAt(deletedAt);
        return role;
    }
}
