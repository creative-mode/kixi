package ao.creativemode.kixi.identity.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.identity.dto.accounts.AccountAccessibilityRequest;
import ao.creativemode.kixi.identity.dto.accounts.AccountRequest;
import ao.creativemode.kixi.identity.dto.accounts.AccountResponse;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class AccountService {

    private final AccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AccountRoleService accountRoleService;
    private final UserService userService;
    private final SessionService sessionService;

    public AccountService(
            AccountRepository repository,
            PasswordEncoder passwordEncoder,
            AccountRoleService accountRoleService,
            UserService userService,
            SessionService sessionService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.accountRoleService = accountRoleService;
        this.userService = userService;
        this.sessionService = sessionService;
    }

    public Flux<AccountResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull()
                .map(this::toResponse);
    }

    public Flux<AccountResponse> findAllDeleted() {
        return repository.findAllByDeletedAtIsNotNull()
                .map(this::toResponse);
    }

    public Flux<AccountResponse> findAllByActive(Boolean active) {
        return repository.findAllByActiveAndDeletedAtIsNull(active)
                .map(this::toResponse);
    }

    public Mono<AccountResponse> findByIdActive(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Account not found")))
                .map(this::toResponse);
    }

    public Mono<AccountResponse> findByUsername(String username) {
        return repository.findByUsernameAndDeletedAtIsNull(username.trim())
                .switchIfEmpty(Mono.error(ApiException.notFound("Account not found")))
                .map(this::toResponse);
    }

    public Mono<AccountResponse> create(AccountRequest dto) {
        String username = dto.username().trim();
        String email = dto.email().trim().toLowerCase();
        String passwordHash = passwordEncoder.encode(dto.password());

        Account entity = new Account();
        entity.setUsername(username);
        entity.setEmail(email);
        entity.setPasswordHash(passwordHash);
        entity.setEmailVerified(false);
        entity.setActive(true);
        entity.setDeletedAt(null);

        return repository.save(entity)
                .map(this::toResponse)
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> ApiException.conflict("Username or email already exists"));
    }

    public Mono<AccountResponse> update(Long id, AccountRequest dto) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Account not found")))
                .flatMap(entity -> {
                    String username = dto.username().trim();
                    String email = dto.email().trim().toLowerCase();
                    String passwordHash = passwordEncoder.encode(dto.password());

                    entity.setUsername(username);
                    entity.setEmail(email);
                    entity.setPasswordHash(passwordHash);
                    entity.setUpdatedAt(LocalDateTime.now());

                    return repository.save(entity)
                            .onErrorMap(DataIntegrityViolationException.class,
                                    e -> ApiException.conflict("Username or email already exists"));
                })
                .map(this::toResponse);
    }

    /**
     * Issue #107: turns the accessibility extra time of an account on or off
     * without touching anything else about it. The flag only changes how long
     * the server lets a simulation run, so it is deliberately not part of the
     * full account update, which would re-encode the password.
     */
    public Mono<AccountResponse> updateAccessibility(Long id, AccountAccessibilityRequest dto) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Account not found")))
                .flatMap(entity -> {
                    entity.setAccessibilityExtraTime(dto.accessibilityExtraTime());
                    entity.setUpdatedAt(LocalDateTime.now());
                    return repository.save(entity);
                })
                .map(this::toResponse);
    }

    public Mono<Void> softDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Account not found")))
                .flatMap(entity -> {
                    entity.setDeletedAt(LocalDateTime.now());
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> restore(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Account is not deleted")))
                .flatMap(entity -> {
                    entity.setDeletedAt(null);
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<AccountResponse> recordLogin(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Account not found")))
                .filter(Account::getActive)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Account is inactive")))
                .flatMap(entity -> {
                    entity.setLastLogin(LocalDateTime.now());
                    return repository.save(entity);
                })
                .map(this::toResponse);
    }

    public Mono<Boolean> verifyPassword(String username, String password) {
        return repository.findByUsernameAndDeletedAtIsNull(username.trim())
                .map(account -> passwordEncoder.matches(password, account.getPasswordHash()))
                .switchIfEmpty(Mono.fromCallable(() -> false));
    }

    private AccountResponse toResponse(Account entity) {
        return new AccountResponse(
            entity.getId(),
            entity.getUsername(),
            entity.getEmail(),
            entity.getEmailVerified(),
            entity.getActive(),
            entity.getAccessibilityExtraTime(),
            entity.getLastLogin(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            entity.getDeletedAt()
        );
    }
    @Transactional
    public Mono<Void> hardDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(
                        Mono.error(ApiException.badRequest("Only deleted accounts can be permanently removed")))
                .flatMap(account ->
                        // Role links, the bound profile and the sessions are
                        // account-scoped rows that keep the accounts foreign
                        // key alive, so they have to go first. Historical rows
                        // (simulations, authored statements) are independent
                        // records and deliberately block the purge instead.
                        accountRoleService.purgeAssociationsForAccount(id)
                                .then(userService.deleteAllForAccount(id))
                                .then(sessionService.deleteAllForAccount(id))
                                .then(repository.delete(account)))
                .then();
    }
}
