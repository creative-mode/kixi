package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.dto.users.UserRequest;
import ao.creativemode.kixi.dto.users.UserResponse;
import ao.creativemode.kixi.service.UserService;
import ao.creativemode.kixi.common.exception.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.util.List;
import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private static final Logger LOG = LoggerFactory.getLogger(UserController.class);
    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    public Mono<ResponseEntity<List<UserResponse>>> listAllActive() {
        return service.findAllActive()
                .collectList()
                .map(ResponseEntity::ok);
    }

    @GetMapping("/trash")
    public Mono<ResponseEntity<List<UserResponse>>> listTrashed() {
        return service.findAllDeleted()
                .collectList()
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<UserResponse>> getById(@PathVariable Long id) {
        if (id == null || id <= 0) {
            LOG.warn("Requisição GET com ID inválido: {}", id);
            return Mono.error(ApiException.badRequest("ID deve ser um número positivo"));
        }

        LOG.debug("Buscando utilizador por ID: {}", id);
        return service.findByIdActive(id)
                .map(ResponseEntity::ok)
                .doOnSuccess(response -> LOG.debug("Utilizador encontrado: {}", id))
                .doOnError(error -> LOG.warn("Erro ao buscar utilizador id={}: {}", id, error.getMessage()));
    }

    @GetMapping("/account/{accountId}")
    public Mono<ResponseEntity<List<UserResponse>>> getByAccountId(@PathVariable Long accountId) {
        if (accountId == null || accountId <= 0) {
            LOG.warn("Requisição com accountId inválido: {}", accountId);
            return Mono.error(ApiException.badRequest("ID da conta deve ser um número positivo"));
        }

        LOG.debug("Buscando utilizadores por accountId: {}", accountId);
        return service.findByAccountIdActive(accountId)
                .collectList()
                .map(ResponseEntity::ok);
    }

    @PostMapping
    public Mono<ResponseEntity<UserResponse>> create(
            @Valid @RequestBody UserRequest request,
            UriComponentsBuilder uriBuilder) {

        LOG.info("Criando novo utilizador: accountId={}, firstName={}", request.accountId(), request.firstName());

        return service.create(request)
                .map(created -> {
                    URI location = uriBuilder
                            .path("/api/v1/users/{id}")
                            .buildAndExpand(created.id())
                            .toUri();

                    LOG.info("Utilizador criado com sucesso: id={}, accountId={}", created.id(), created.accountId());
                    return ResponseEntity.created(location).body(created);
                })
                .doOnError(error -> LOG.error("Erro ao criar utilizador: {}", error.getMessage()));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<UserResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody UserRequest request) {

        if (id == null || id <= 0) {
            LOG.warn("Requisição PUT com ID inválido: {}", id);
            return Mono.error(ApiException.badRequest("ID deve ser um número positivo"));
        }

        LOG.info("Atualizando utilizador: id={}, accountId={}", id, request.accountId());

        return service.update(id, request)
                .map(ResponseEntity::ok)
                .doOnSuccess(response -> LOG.info("Utilizador atualizado: id={}", id))
                .doOnError(error -> LOG.error("Erro ao atualizar utilizador id={}: {}", id, error.getMessage()));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        if (id == null || id <= 0) {
            LOG.warn("Requisição DELETE com ID inválido: {}", id);
            return Mono.error(ApiException.badRequest("ID deve ser um número positivo"));
        }

        LOG.info("Soft-deletando utilizador: id={}", id);
        return service.softDelete(id)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build())
                .doOnSuccess(response -> LOG.info("Utilizador deletado: id={}", id))
                .doOnError(error -> LOG.error("Erro ao deletar utilizador id={}: {}", id, error.getMessage()));
    }

    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        if (id == null || id <= 0) {
            LOG.warn("Requisição RESTORE com ID inválido: {}", id);
            return Mono.error(ApiException.badRequest("ID deve ser um número positivo"));
        }

        LOG.info("Restaurando utilizador: id={}", id);
        return service.restore(id)
                .thenReturn(ResponseEntity.ok().build())
                .doOnSuccess(response -> LOG.info("Utilizador restaurado: id={}", id))
                .doOnError(error -> LOG.error("Erro ao restaurar utilizador id={}: {}", id, error.getMessage()));
    }
}
