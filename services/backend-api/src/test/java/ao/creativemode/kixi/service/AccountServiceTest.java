package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.accounts.AccountRequest;
import ao.creativemode.kixi.dto.accounts.AccountResponse;
import ao.creativemode.kixi.model.Account;
import ao.creativemode.kixi.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    private AccountService accountService;
    private BCryptPasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository);
        passwordEncoder = new BCryptPasswordEncoder();
    }

    // ============ findAllActive ============

    @Test
    void testFindAllActive() {
        Account account1 = new Account(1L, "user1", "user1@email.com", "hash1", false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);
        Account account2 = new Account(2L, "user2", "user2@email.com", "hash2", false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(accountRepository.findAllByDeletedAtIsNull())
            .thenReturn(Flux.just(account1, account2));

        StepVerifier.create(accountService.findAllActive())
            .expectNextCount(2)
            .verifyComplete();

        verify(accountRepository, times(1)).findAllByDeletedAtIsNull();
    }

    // ============ findAllDeleted ============

    @Test
    void testFindAllDeleted() {
        Account account = new Account(1L, "user1", "user1@email.com", "hash1", false, true, null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());

        when(accountRepository.findAllByDeletedAtIsNotNull())
            .thenReturn(Flux.just(account));

        StepVerifier.create(accountService.findAllDeleted())
            .expectNextCount(1)
            .verifyComplete();

        verify(accountRepository, times(1)).findAllByDeletedAtIsNotNull();
    }

    // ============ findByIdActive ============

    @Test
    void testFindByIdActive_Success() {
        Long accountId = 1L;
        Account account = new Account(accountId, "user1", "user1@email.com", "hash1", false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(accountRepository.findByIdAndDeletedAtIsNull(accountId))
            .thenReturn(Mono.just(account));

        StepVerifier.create(accountService.findByIdActive(accountId))
            .expectNext(new AccountResponse(1L, "user1", "user1@email.com", false, true, null, account.getCreatedAt(), account.getUpdatedAt(), null))
            .verifyComplete();

        verify(accountRepository, times(1)).findByIdAndDeletedAtIsNull(accountId);
    }

    @Test
    void testFindByIdActive_NotFound() {
        Long accountId = 999L;

        when(accountRepository.findByIdAndDeletedAtIsNull(accountId))
            .thenReturn(Mono.empty());

        StepVerifier.create(accountService.findByIdActive(accountId))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.NOT_FOUND)
            .verify();

        verify(accountRepository, times(1)).findByIdAndDeletedAtIsNull(accountId);
    }

    @Test
    void testFindByIdActive_InvalidId() {
        StepVerifier.create(accountService.findByIdActive(0L))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.BAD_REQUEST)
            .verify();

        verify(accountRepository, never()).findByIdAndDeletedAtIsNull(anyLong());
    }

    // ============ findByUsername ============

    @Test
    void testFindByUsername_Success() {
        String username = "user1";
        Account account = new Account(1L, username, "user1@email.com", "hash1", false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(accountRepository.findByUsernameAndDeletedAtIsNull(username))
            .thenReturn(Mono.just(account));

        StepVerifier.create(accountService.findByUsername(username))
            .expectNextCount(1)
            .verifyComplete();

        verify(accountRepository, times(1)).findByUsernameAndDeletedAtIsNull(username);
    }

    @Test
    void testFindByUsername_NotFound() {
        String username = "nonexistent";

        when(accountRepository.findByUsernameAndDeletedAtIsNull(username))
            .thenReturn(Mono.empty());

        StepVerifier.create(accountService.findByUsername(username))
            .expectErrorMatches(error -> error instanceof ApiException)
            .verify();

        verify(accountRepository, times(1)).findByUsernameAndDeletedAtIsNull(username);
    }

    // ============ create ============

    @Test
    void testCreate_Success() {
        AccountRequest request = new AccountRequest("newuser", "newuser@email.com", "Password123");

        when(accountRepository.findByUsernameAndDeletedAtIsNull("newuser"))
            .thenReturn(Mono.empty());

        when(accountRepository.findByEmailAndDeletedAtIsNull("newuser@email.com"))
            .thenReturn(Mono.empty());

        Account savedAccount = new Account(1L, "newuser", "newuser@email.com", "hash", false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);
        when(accountRepository.save(any(Account.class)))
            .thenReturn(Mono.just(savedAccount));

        StepVerifier.create(accountService.create(request))
            .expectNextCount(1)
            .verifyComplete();

        verify(accountRepository, times(1)).findByUsernameAndDeletedAtIsNull("newuser");
        verify(accountRepository, times(1)).findByEmailAndDeletedAtIsNull("newuser@email.com");
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    @Test
    void testCreate_UsernameExists() {
        AccountRequest request = new AccountRequest("existinguser", "new@email.com", "Password123");

        Account existingAccount = new Account(1L, "existinguser", "existing@email.com", "hash", false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);
        when(accountRepository.findByUsernameAndDeletedAtIsNull("existinguser"))
            .thenReturn(Mono.just(existingAccount));

        StepVerifier.create(accountService.create(request))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.CONFLICT)
            .verify();

        verify(accountRepository, times(1)).findByUsernameAndDeletedAtIsNull("existinguser");
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void testCreate_EmailExists() {
        AccountRequest request = new AccountRequest("newuser", "existing@email.com", "Password123");

        when(accountRepository.findByUsernameAndDeletedAtIsNull("newuser"))
            .thenReturn(Mono.empty());

        Account existingAccount = new Account(1L, "otheruser", "existing@email.com", "hash", false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);
        when(accountRepository.findByEmailAndDeletedAtIsNull("existing@email.com"))
            .thenReturn(Mono.just(existingAccount));

        StepVerifier.create(accountService.create(request))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.CONFLICT)
            .verify();

        verify(accountRepository, times(1)).findByUsernameAndDeletedAtIsNull("newuser");
        verify(accountRepository, times(1)).findByEmailAndDeletedAtIsNull("existing@email.com");
        verify(accountRepository, never()).save(any(Account.class));
    }

    // ============ update ============

    @Test
    void testUpdate_Success() {
        Long accountId = 1L;
        AccountRequest request = new AccountRequest("updateduser", "updated@email.com", "NewPassword123");

        Account existingAccount = new Account(accountId, "olduser", "old@email.com", "oldhash", false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(accountRepository.findByIdAndDeletedAtIsNull(accountId))
            .thenReturn(Mono.just(existingAccount));

        when(accountRepository.findByUsernameAndIdNotAndDeletedAtIsNull("updateduser", accountId))
            .thenReturn(Mono.empty());

        when(accountRepository.findByEmailAndIdNotAndDeletedAtIsNull("updated@email.com", accountId))
            .thenReturn(Mono.empty());

        Account updatedAccount = new Account(accountId, "updateduser", "updated@email.com", "newhash", false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);
        when(accountRepository.save(any(Account.class)))
            .thenReturn(Mono.just(updatedAccount));

        StepVerifier.create(accountService.update(accountId, request))
            .expectNextCount(1)
            .verifyComplete();

        verify(accountRepository, times(1)).findByIdAndDeletedAtIsNull(accountId);
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    @Test
    void testUpdate_NotFound() {
        Long accountId = 999L;
        AccountRequest request = new AccountRequest("updateduser", "updated@email.com", "NewPassword123");

        when(accountRepository.findByIdAndDeletedAtIsNull(accountId))
            .thenReturn(Mono.empty());

        StepVerifier.create(accountService.update(accountId, request))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.NOT_FOUND)
            .verify();

        verify(accountRepository, times(1)).findByIdAndDeletedAtIsNull(accountId);
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void testUpdate_InvalidId() {
        AccountRequest request = new AccountRequest("updateduser", "updated@email.com", "NewPassword123");

        StepVerifier.create(accountService.update(0L, request))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.BAD_REQUEST)
            .verify();

        verify(accountRepository, never()).findByIdAndDeletedAtIsNull(anyLong());
    }

    // ============ softDelete ============

    @Test
    void testSoftDelete_Success() {
        Long accountId = 1L;
        Account account = new Account(accountId, "user1", "user1@email.com", "hash1", false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(accountRepository.findByIdAndDeletedAtIsNull(accountId))
            .thenReturn(Mono.just(account));

        Account deletedAccount = new Account(accountId, "user1", "user1@email.com", "hash1", false, true, null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
        when(accountRepository.save(any(Account.class)))
            .thenReturn(Mono.just(deletedAccount));

        StepVerifier.create(accountService.softDelete(accountId))
            .verifyComplete();

        verify(accountRepository, times(1)).findByIdAndDeletedAtIsNull(accountId);
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    @Test
    void testSoftDelete_NotFound() {
        Long accountId = 999L;

        when(accountRepository.findByIdAndDeletedAtIsNull(accountId))
            .thenReturn(Mono.empty());

        StepVerifier.create(accountService.softDelete(accountId))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.NOT_FOUND)
            .verify();

        verify(accountRepository, times(1)).findByIdAndDeletedAtIsNull(accountId);
        verify(accountRepository, never()).save(any(Account.class));
    }

    // ============ restore ============

    @Test
    void testRestore_Success() {
        Long accountId = 1L;
        Account deletedAccount = new Account(accountId, "user1", "user1@email.com", "hash1", false, true, null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());

        when(accountRepository.findById(accountId))
            .thenReturn(Mono.just(deletedAccount));

        Account restoredAccount = new Account(accountId, "user1", "user1@email.com", "hash1", false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);
        when(accountRepository.save(any(Account.class)))
            .thenReturn(Mono.just(restoredAccount));

        StepVerifier.create(accountService.restore(accountId))
            .expectNextCount(1)
            .verifyComplete();

        verify(accountRepository, times(1)).findById(accountId);
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    @Test
    void testRestore_NotDeleted() {
        Long accountId = 1L;
        Account activeAccount = new Account(accountId, "user1", "user1@email.com", "hash1", false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(accountRepository.findById(accountId))
            .thenReturn(Mono.just(activeAccount));

        StepVerifier.create(accountService.restore(accountId))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.BAD_REQUEST)
            .verify();

        verify(accountRepository, times(1)).findById(accountId);
        verify(accountRepository, never()).save(any(Account.class));
    }

    // ============ recordLogin ============

    @Test
    void testRecordLogin_Success() {
        Long accountId = 1L;
        Account account = new Account(accountId, "user1", "user1@email.com", "hash1", false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(accountRepository.findByIdAndDeletedAtIsNull(accountId))
            .thenReturn(Mono.just(account));

        Account updatedAccount = new Account(accountId, "user1", "user1@email.com", "hash1", false, true, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(), null);
        when(accountRepository.save(any(Account.class)))
            .thenReturn(Mono.just(updatedAccount));

        StepVerifier.create(accountService.recordLogin(accountId))
            .expectNextCount(1)
            .verifyComplete();

        verify(accountRepository, times(1)).findByIdAndDeletedAtIsNull(accountId);
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    @Test
    void testRecordLogin_AccountInactive() {
        Long accountId = 1L;
        Account inactiveAccount = new Account(accountId, "user1", "user1@email.com", "hash1", false, false, null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(accountRepository.findByIdAndDeletedAtIsNull(accountId))
            .thenReturn(Mono.just(inactiveAccount));

        StepVerifier.create(accountService.recordLogin(accountId))
            .expectErrorMatches(error -> error instanceof ApiException && ((ApiException) error).getStatus() == HttpStatus.FORBIDDEN)
            .verify();

        verify(accountRepository, times(1)).findByIdAndDeletedAtIsNull(accountId);
        verify(accountRepository, never()).save(any(Account.class));
    }

    // ============ verifyPassword ============

    @Test
    void testVerifyPassword_Success() {
        String username = "user1";
        String password = "Password123";
        String hashedPassword = new BCryptPasswordEncoder().encode(password);

        Account account = new Account(1L, username, "user1@email.com", hashedPassword, false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(accountRepository.findByUsernameAndDeletedAtIsNull(username))
            .thenReturn(Mono.just(account));

        StepVerifier.create(accountService.verifyPassword(username, password))
            .expectNext(true)
            .verifyComplete();

        verify(accountRepository, times(1)).findByUsernameAndDeletedAtIsNull(username);
    }

    @Test
    void testVerifyPassword_InvalidPassword() {
        String username = "user1";
        String password = "WrongPassword";
        String hashedPassword = new BCryptPasswordEncoder().encode("Password123");

        Account account = new Account(1L, username, "user1@email.com", hashedPassword, false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(accountRepository.findByUsernameAndDeletedAtIsNull(username))
            .thenReturn(Mono.just(account));

        StepVerifier.create(accountService.verifyPassword(username, password))
            .expectNext(false)
            .verifyComplete();

        verify(accountRepository, times(1)).findByUsernameAndDeletedAtIsNull(username);
    }

    @Test
    void testVerifyPassword_UserNotFound() {
        String username = "nonexistent";
        String password = "Password123";

        when(accountRepository.findByUsernameAndDeletedAtIsNull(username))
            .thenReturn(Mono.empty());

        StepVerifier.create(accountService.verifyPassword(username, password))
            .expectNext(false)
            .verifyComplete();

        verify(accountRepository, times(1)).findByUsernameAndDeletedAtIsNull(username);
    }

    // ============ findAllByActive ============

    @Test
    void testFindAllByActive_True() {
        Account account1 = new Account(1L, "user1", "user1@email.com", "hash1", false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);
        Account account2 = new Account(2L, "user2", "user2@email.com", "hash2", false, true, null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(accountRepository.findAllByActiveAndDeletedAtIsNull(true))
            .thenReturn(Flux.just(account1, account2));

        StepVerifier.create(accountService.findAllByActive(true))
            .expectNextCount(2)
            .verifyComplete();

        verify(accountRepository, times(1)).findAllByActiveAndDeletedAtIsNull(true);
    }

    @Test
    void testFindAllByActive_False() {
        Account account = new Account(1L, "user1", "user1@email.com", "hash1", false, false, null, LocalDateTime.now(), LocalDateTime.now(), null);

        when(accountRepository.findAllByActiveAndDeletedAtIsNull(false))
            .thenReturn(Flux.just(account));

        StepVerifier.create(accountService.findAllByActive(false))
            .expectNextCount(1)
            .verifyComplete();

        verify(accountRepository, times(1)).findAllByActiveAndDeletedAtIsNull(false);
    }
}
