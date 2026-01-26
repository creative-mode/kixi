package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.accounts.AccountRequest;
import ao.creativemode.kixi.dto.accounts.AccountResponse;
import ao.creativemode.kixi.model.Account;
import ao.creativemode.kixi.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class AccountService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountService.class);
    private static final int RETRY_ATTEMPTS = 3;

    private static final String MSG_ACCOUNT_NOT_FOUND = "Conta não encontrada";
    private static final String MSG_USERNAME_EXISTS = "Username já existe";
    private static final String MSG_EMAIL_EXISTS = "Email já existe";
    private static final String MSG_INVALID_CREDENTIALS = "Credenciais inválidas";
    private static final String MSG_ACCOUNT_INACTIVE = "Conta inativa";

    private final AccountRepository accountRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    public Flux<AccountResponse> findAllActive() {
        LOG.info("Buscando todas as contas ativas");
        return accountRepository.findAllByDeletedAtIsNull()
            .map(this::convertToResponse)
            .doOnNext(account -> LOG.debug("Conta encontrada: {}", account.id()))
            .doOnError(error -> LOG.error("Erro ao buscar contas ativas", error))
            .retry(RETRY_ATTEMPTS);
    }

    public Flux<AccountResponse> findAllDeleted() {
        LOG.info("Buscando todas as contas deletadas");
        return accountRepository.findAllByDeletedAtIsNotNull()
            .map(this::convertToResponse)
            .doOnNext(account -> LOG.debug("Conta deletada encontrada: {}", account.id()))
            .doOnError(error -> LOG.error("Erro ao buscar contas deletadas", error))
            .retry(RETRY_ATTEMPTS);
    }

    public Flux<AccountResponse> findAllByActive(Boolean active) {
        LOG.info("Buscando contas com active={}", active);
        return accountRepository.findAllByActiveAndDeletedAtIsNull(active)
            .map(this::convertToResponse)
            .doOnError(error -> LOG.error("Erro ao buscar contas por status ativo", error))
            .retry(RETRY_ATTEMPTS);
    }

    public Mono<AccountResponse> findByIdActive(Long id) {
        if (id == null || id <= 0) {
            LOG.warn("ID inválido fornecido: {}", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("Buscando conta com ID: {}", id);
        return accountRepository.findByIdAndDeletedAtIsNull(id)
            .map(this::convertToResponse)
            .switchIfEmpty(Mono.error(new ApiException(MSG_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND)))
            .doOnError(error -> LOG.warn("Conta {} não encontrada", id))
            .retry(RETRY_ATTEMPTS);
    }

    public Mono<AccountResponse> findByUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            LOG.warn("Username inválido fornecido");
            return Mono.error(new ApiException("Username inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("Buscando conta com username: {}", username);
        return accountRepository.findByUsernameAndDeletedAtIsNull(username.trim())
            .map(this::convertToResponse)
            .switchIfEmpty(Mono.error(new ApiException(MSG_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND)))
            .doOnError(error -> LOG.warn("Conta com username {} não encontrada", username))
            .retry(RETRY_ATTEMPTS);
    }

    public Mono<AccountResponse> create(AccountRequest request) {
        LOG.info("Criando nova conta com username: {}", request.username());

        String username = request.username().trim();
        String email = request.email().trim().toLowerCase();

        return validateUsernameUniqueness(username)
            .flatMap(unused -> validateEmailUniqueness(email))
            .flatMap(unused -> {
                String passwordHash = passwordEncoder.encode(request.password());
                Account account = new Account(
                    null,
                    username,
                    email,
                    passwordHash,
                    false,
                    true,
                    null,
                    null,
                    null,
                    null
                );
                return accountRepository.save(account);
            })
            .map(this::convertToResponse)
            .doOnSuccess(saved -> LOG.info("Conta criada com sucesso: {}", saved.id()))
            .doOnError(error -> LOG.error("Erro ao criar conta", error))
            .retry(RETRY_ATTEMPTS);
    }

    public Mono<AccountResponse> update(Long id, AccountRequest request) {
        if (id == null || id <= 0) {
            LOG.warn("ID inválido para atualização: {}", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("Atualizando conta: {}", id);

        String username = request.username().trim();
        String email = request.email().trim().toLowerCase();

        return accountRepository.findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(Mono.error(new ApiException(MSG_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND)))
            .flatMap(existingAccount -> {
                if (!existingAccount.getUsername().equals(username)) {
                    return validateUsernameUniquenesForUpdate(username, id)
                        .thenReturn(existingAccount);
                }
                return Mono.just(existingAccount);
            })
            .flatMap(existingAccount -> {
                if (!existingAccount.getEmail().equals(email)) {
                    return validateEmailUniquenesForUpdate(email, id)
                        .thenReturn(existingAccount);
                }
                return Mono.just(existingAccount);
            })
            .flatMap(existingAccount -> {
                String passwordHash = passwordEncoder.encode(request.password());
                existingAccount.setUsername(username);
                existingAccount.setEmail(email);
                existingAccount.setPasswordHash(passwordHash);
                return accountRepository.save(existingAccount);
            })
            .map(this::convertToResponse)
            .doOnSuccess(updated -> LOG.info("Conta {} atualizada com sucesso", id))
            .doOnError(error -> LOG.error("Erro ao atualizar conta {}", id, error))
            .retry(RETRY_ATTEMPTS);
    }

    public Mono<Void> softDelete(Long id) {
        if (id == null || id <= 0) {
            LOG.warn("ID inválido para deleção: {}", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("Deletando conta: {}", id);

        return accountRepository.findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(Mono.error(new ApiException(MSG_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND)))
            .flatMap(account -> {
                account.markAsDeleted();
                return accountRepository.save(account);
            })
            .doOnSuccess(deleted -> LOG.info("Conta {} deletada com sucesso", id))
            .doOnError(error -> LOG.error("Erro ao deletar conta {}", id, error))
            .then()
            .retry(RETRY_ATTEMPTS);
    }

    public Mono<AccountResponse> restore(Long id) {
        if (id == null || id <= 0) {
            LOG.warn("ID inválido para restauração: {}", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("Restaurando conta: {}", id);

        return accountRepository.findById(id)
            .filterWhen(account -> Mono.fromCallable(account::isDeleted))
            .switchIfEmpty(Mono.error(new ApiException("Conta não foi deletada", HttpStatus.BAD_REQUEST)))
            .flatMap(account -> {
                account.restore();
                return accountRepository.save(account);
            })
            .map(this::convertToResponse)
            .doOnSuccess(restored -> LOG.info("Conta {} restaurada com sucesso", id))
            .doOnError(error -> LOG.error("Erro ao restaurar conta {}", id, error))
            .retry(RETRY_ATTEMPTS);
    }

    public Mono<AccountResponse> recordLogin(Long id) {
        if (id == null || id <= 0) {
            LOG.warn("ID inválido para registrar login: {}", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("Registrando login para conta: {}", id);

        return accountRepository.findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(Mono.error(new ApiException(MSG_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND)))
            .filter(Account::getActive)
            .switchIfEmpty(Mono.error(new ApiException(MSG_ACCOUNT_INACTIVE, HttpStatus.FORBIDDEN)))
            .flatMap(account -> {
                account.recordLogin();
                return accountRepository.save(account);
            })
            .map(this::convertToResponse)
            .doOnSuccess(account -> LOG.info("Login registrado para conta: {}", id))
            .doOnError(error -> LOG.error("Erro ao registrar login para conta {}", id, error))
            .retry(RETRY_ATTEMPTS);
    }

    public Mono<Boolean> verifyPassword(String username, String password) {
        LOG.info("Verificando senha para username: {}", username);

        return accountRepository.findByUsernameAndDeletedAtIsNull(username.trim())
            .map(account -> {
                boolean matches = passwordEncoder.matches(password, account.getPasswordHash());
                if (matches) {
                    LOG.info("Senha verificada com sucesso para username: {}", username);
                } else {
                    LOG.warn("Falha na verificação de senha para username: {}", username);
                }
                return matches;
            })
            .switchIfEmpty(Mono.fromCallable(() -> {
                LOG.warn("Username {} não encontrado para verificação de senha", username);
                return false;
            }))
            .doOnError(error -> LOG.error("Erro ao verificar senha para username: {}", username, error))
            .retry(RETRY_ATTEMPTS);
    }

    private Mono<Void> validateUsernameUniqueness(String username) {
        LOG.debug("Validando unicidade do username: {}", username);
        return accountRepository.findByUsernameAndDeletedAtIsNull(username)
            .flatMap(existing -> Mono.error(new ApiException(MSG_USERNAME_EXISTS, HttpStatus.CONFLICT)))
            .then()
            .onErrorResume(error -> {
                if (error instanceof ApiException) {
                    return Mono.error(error);
                }
                LOG.debug("Username {} é único", username);
                return Mono.empty();
            });
    }

    private Mono<Void> validateEmailUniqueness(String email) {
        LOG.debug("Validando unicidade do email: {}", email);
        return accountRepository.findByEmailAndDeletedAtIsNull(email)
            .flatMap(existing -> Mono.error(new ApiException(MSG_EMAIL_EXISTS, HttpStatus.CONFLICT)))
            .then()
            .onErrorResume(error -> {
                if (error instanceof ApiException) {
                    return Mono.error(error);
                }
                LOG.debug("Email {} é único", email);
                return Mono.empty();
            });
    }

    private Mono<Void> validateUsernameUniquenesForUpdate(String username, Long excludeId) {
        LOG.debug("Validando unicidade do username (excluindo ID {}): {}", excludeId, username);
        return accountRepository.findByUsernameAndIdNotAndDeletedAtIsNull(username, excludeId)
            .flatMap(existing -> Mono.error(new ApiException(MSG_USERNAME_EXISTS, HttpStatus.CONFLICT)))
            .then()
            .onErrorResume(error -> {
                if (error instanceof ApiException) {
                    return Mono.error(error);
                }
                LOG.debug("Username {} é único (excluindo ID {})", username, excludeId);
                return Mono.empty();
            });
    }

    private Mono<Void> validateEmailUniquenesForUpdate(String email, Long excludeId) {
        LOG.debug("Validando unicidade do email (excluindo ID {}): {}", excludeId, email);
        return accountRepository.findByEmailAndIdNotAndDeletedAtIsNull(email, excludeId)
            .flatMap(existing -> Mono.error(new ApiException(MSG_EMAIL_EXISTS, HttpStatus.CONFLICT)))
            .then()
            .onErrorResume(error -> {
                if (error instanceof ApiException) {
                    return Mono.error(error);
                }
                LOG.debug("Email {} é único (excluindo ID {})", email, excludeId);
                return Mono.empty();
            });
    }

    private AccountResponse convertToResponse(Account account) {
        return new AccountResponse(
            account.getId(),
            account.getUsername(),
            account.getEmail(),
            account.getEmailVerified(),
            account.getActive(),
            account.getLastLogin(),
            account.getCreatedAt(),
            account.getUpdatedAt(),
            account.getDeletedAt()
        );
    }
}
