package ao.creativemode.kixi.service;

import ao.creativemode.kixi.model.User;
import ao.creativemode.kixi.repository.UserRepository;
import ao.creativemode.kixi.dto.users.UserResponse;
import ao.creativemode.kixi.dto.users.UserRequest;
import ao.creativemode.kixi.common.exception.ApiException;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class UserService {

    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);
    private static final String MSG_USER_NOT_FOUND = "Utilizador não encontrado";
    private static final String MSG_ACCOUNT_REQUIRED = "ID da conta é obrigatório";
    private static final int RETRY_ATTEMPTS = 1;

    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    public Flux<UserResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull()
                .map(this::toResponse);
    }

    public Flux<UserResponse> findAllDeleted() {
        return repository.findAllByDeletedAtIsNotNull()
                .map(this::toResponse);
    }

    public Mono<UserResponse> findByIdActive(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound(MSG_USER_NOT_FOUND)))
                .map(this::toResponse);
    }

    public Flux<UserResponse> findByAccountIdActive(Long accountId) {
        if (accountId == null || accountId <= 0) {
            LOG.warn("Tentativa de buscar utilizadores com accountId inválido: {}", accountId);
            return Flux.error(ApiException.badRequest("ID da conta deve ser um número positivo"));
        }

        return repository.findByAccountIdAndDeletedAtIsNull(accountId)
                .map(this::toResponse);
    }

    public Mono<UserResponse> create(UserRequest dto) {
        if (dto.accountId() == null || dto.accountId() <= 0) {
            LOG.warn("Tentativa de criar utilizador com accountId inválido: {}", dto.accountId());
            return Mono.error(ApiException.badRequest(MSG_ACCOUNT_REQUIRED));
        }

        if (!StringUtils.hasText(dto.firstName()) || !StringUtils.hasText(dto.lastName())) {
            LOG.warn("Tentativa de criar utilizador com nome ou sobrenome vazio");
            return Mono.error(ApiException.badRequest("Nome e sobrenome são obrigatórios"));
        }

        String normalizedFirstName = dto.firstName().trim();
        String normalizedLastName = dto.lastName().trim();
        LOG.info("Criando novo utilizador: accountId={}, firstName={}, lastName={}", 
                dto.accountId(), normalizedFirstName, normalizedLastName);

        User entity = new User(dto.accountId(), normalizedFirstName, normalizedLastName);
        if (StringUtils.hasText(dto.photo())) {
            entity.setPhoto(dto.photo().trim());
        }

        return repository.save(entity)
                .retry(RETRY_ATTEMPTS)
                .map(this::toResponse)
                .doOnSuccess(result -> LOG.info("Utilizador criado com sucesso: id={}, accountId={}", 
                        result.id(), result.accountId()))
                .doOnError(error -> LOG.error("Erro ao criar utilizador: {}", error.getMessage()));
    }

    public Mono<UserResponse> update(Long id, UserRequest dto) {
        if (id == null || id <= 0) {
            LOG.warn("Tentativa de atualizar com ID inválido: {}", id);
            return Mono.error(ApiException.badRequest("ID inválido"));
        }

        if (dto.accountId() == null || dto.accountId() <= 0) {
            LOG.warn("Tentativa de atualizar utilizador com accountId inválido: {}", dto.accountId());
            return Mono.error(ApiException.badRequest(MSG_ACCOUNT_REQUIRED));
        }

        String normalizedFirstName = dto.firstName() != null ? dto.firstName().trim() : null;
        String normalizedLastName = dto.lastName() != null ? dto.lastName().trim() : null;
        LOG.info("Atualizando utilizador: id={}, accountId={}", id, dto.accountId());

        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound(MSG_USER_NOT_FOUND)))
                .flatMap(entity -> {
                    entity.setAccountId(dto.accountId());
                    entity.setFirstName(normalizedFirstName);
                    entity.setLastName(normalizedLastName);
                    if (StringUtils.hasText(dto.photo())) {
                        entity.setPhoto(dto.photo().trim());
                    } else {
                        entity.setPhoto(null);
                    }

                    return repository.save(entity)
                            .retry(RETRY_ATTEMPTS)
                            .doOnSuccess(result -> LOG.info("Utilizador atualizado: id={}", result.getId()));
                })
                .map(this::toResponse)
                .doOnError(error -> LOG.error("Erro ao atualizar utilizador id={}: {}", id, error.getMessage()));
    }

    public Mono<Void> softDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound(MSG_USER_NOT_FOUND)))
                .flatMap(entity -> {
                    entity.markAsDeleted();
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> restore(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Utilizador não está deletado")))
                .flatMap(entity -> {
                    entity.restore();
                    return repository.save(entity);
                })
                .then();
    }

    private UserResponse toResponse(User entity) {
        return new UserResponse(
                entity.getId(),
                entity.getAccountId(),
                entity.getFirstName(),
                entity.getLastName(),
                entity.getPhoto(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }
}
