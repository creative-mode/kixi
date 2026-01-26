package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.accounts.AccountRequest;
import ao.creativemode.kixi.dto.accounts.AccountResponse;
import ao.creativemode.kixi.service.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private static final Logger LOG = LoggerFactory.getLogger(AccountController.class);

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public Flux<AccountResponse> listAllActive() {
        LOG.info("GET /api/v1/accounts - Listar todas as contas ativas");
        return accountService.findAllActive();
    }

    @GetMapping("/trash")
    public Flux<AccountResponse> listAllDeleted() {
        LOG.info("GET /api/v1/accounts/trash - Listar todas as contas deletadas");
        return accountService.findAllDeleted();
    }

    @GetMapping("/active")
    public Flux<AccountResponse> listByActive(@RequestParam(defaultValue = "true") Boolean active) {
        LOG.info("GET /api/v1/accounts/active?active={} - Listar contas por status ativo", active);
        return accountService.findAllByActive(active);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<AccountResponse>> getAccountById(@PathVariable Long id) {
        if (id == null || id <= 0) {
            LOG.warn("GET /api/v1/accounts/{} - ID inválido fornecido", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("GET /api/v1/accounts/{} - Obter conta por ID", id);
        return accountService.findByIdActive(id)
            .map(ResponseEntity::ok)
            .doOnError(error -> LOG.error("Erro ao buscar conta com ID {}: {}", id, error.getMessage()));
    }

    @GetMapping("/username/{username}")
    public Mono<ResponseEntity<AccountResponse>> getAccountByUsername(@PathVariable String username) {
        if (username == null || username.trim().isEmpty()) {
            LOG.warn("GET /api/v1/accounts/username/{} - Username inválido fornecido", username);
            return Mono.error(new ApiException("Username inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("GET /api/v1/accounts/username/{} - Obter conta por username", username);
        return accountService.findByUsername(username)
            .map(ResponseEntity::ok)
            .doOnError(error -> LOG.error("Erro ao buscar conta com username {}: {}", username, error.getMessage()));
    }

    @PostMapping
    public Mono<ResponseEntity<AccountResponse>> createAccount(@Valid @RequestBody AccountRequest request) {
        LOG.info("POST /api/v1/accounts - Criar nova conta com username: {}", request.username());

        return accountService.create(request)
            .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
            .doOnSuccess(response -> LOG.info("Conta criada com sucesso: {}", response.getBody().id()))
            .doOnError(error -> LOG.error("Erro ao criar conta: {}", error.getMessage()));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<AccountResponse>> updateAccount(
            @PathVariable Long id,
            @Valid @RequestBody AccountRequest request) {

        if (id == null || id <= 0) {
            LOG.warn("PUT /api/v1/accounts/{} - ID inválido fornecido", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("PUT /api/v1/accounts/{} - Atualizar conta", id);

        return accountService.update(id, request)
            .map(ResponseEntity::ok)
            .doOnSuccess(response -> LOG.info("Conta {} atualizada com sucesso", id))
            .doOnError(error -> LOG.error("Erro ao atualizar conta {}: {}", id, error.getMessage()));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteAccount(@PathVariable Long id) {
        if (id == null || id <= 0) {
            LOG.warn("DELETE /api/v1/accounts/{} - ID inválido fornecido", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("DELETE /api/v1/accounts/{} - Deletar conta", id);

        return accountService.softDelete(id)
            .then(Mono.fromCallable(() -> ResponseEntity.noContent().<Void>build()))
            .doOnSuccess(response -> LOG.info("Conta {} deletada com sucesso", id))
            .doOnError(error -> LOG.error("Erro ao deletar conta {}: {}", id, error.getMessage()));
    }

    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<AccountResponse>> restoreAccount(@PathVariable Long id) {
        if (id == null || id <= 0) {
            LOG.warn("POST /api/v1/accounts/{}/restore - ID inválido fornecido", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("POST /api/v1/accounts/{}/restore - Restaurar conta", id);

        return accountService.restore(id)
            .map(ResponseEntity::ok)
            .doOnSuccess(response -> LOG.info("Conta {} restaurada com sucesso", id))
            .doOnError(error -> LOG.error("Erro ao restaurar conta {}: {}", id, error.getMessage()));
    }

    @PostMapping("/{id}/login")
    public Mono<ResponseEntity<AccountResponse>> recordLogin(@PathVariable Long id) {
        if (id == null || id <= 0) {
            LOG.warn("POST /api/v1/accounts/{}/login - ID inválido fornecido", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("POST /api/v1/accounts/{}/login - Registrar login", id);

        return accountService.recordLogin(id)
            .map(ResponseEntity::ok)
            .doOnSuccess(response -> LOG.info("Login registrado para conta {}", id))
            .doOnError(error -> LOG.error("Erro ao registrar login para conta {}: {}", id, error.getMessage()));
    }
}
