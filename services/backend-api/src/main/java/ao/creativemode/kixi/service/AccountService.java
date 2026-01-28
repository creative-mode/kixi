package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.accounts.AccountRequest;
import ao.creativemode.kixi.dto.accounts.AccountResponse;
import ao.creativemode.kixi.model.Account;
import ao.creativemode.kixi.repository.AccountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class AccountService {

    private final AccountRepository repository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AccountService(AccountRepository repository) {
        this.repository = repository;
        this.passwordEncoder = new BCryptPasswordEncoder();
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
            entity.getLastLogin(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            entity.getDeletedAt()
        );
    }
}
