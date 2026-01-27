package ao.creativemode.kixi.service;

import ao.creativemode.kixi.model.User;
import ao.creativemode.kixi.repository.UserRepository;
import ao.creativemode.kixi.dto.users.UserResponse;
import ao.creativemode.kixi.dto.users.UserRequest;
import ao.creativemode.kixi.common.exception.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;

@Service
public class UserService {

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
                .switchIfEmpty(Mono.error(ApiException.notFound("User not found")))
                .map(this::toResponse);
    }

    public Flux<UserResponse> findByAccountIdActive(Long accountId) {
        return repository.findByAccountIdAndDeletedAtIsNull(accountId)
                .map(this::toResponse);
    }

    public Mono<UserResponse> create(UserRequest dto) {
        String normalizedFirstName = dto.firstName().trim();
        String normalizedLastName = dto.lastName().trim();

        User entity = new User(dto.accountId(), normalizedFirstName, normalizedLastName);
        if (StringUtils.hasText(dto.photo())) {
            entity.setPhoto(dto.photo().trim());
        }

        return repository.save(entity)
                .map(this::toResponse);
    }

    public Mono<UserResponse> update(Long id, UserRequest dto) {
        String normalizedFirstName = dto.firstName() != null ? dto.firstName().trim() : null;
        String normalizedLastName = dto.lastName() != null ? dto.lastName().trim() : null;

        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("User not found")))
                .flatMap(entity -> {
                    entity.setAccountId(dto.accountId());
                    entity.setFirstName(normalizedFirstName);
                    entity.setLastName(normalizedLastName);
                    if (StringUtils.hasText(dto.photo())) {
                        entity.setPhoto(dto.photo().trim());
                    } else {
                        entity.setPhoto(null);
                    }
                    entity.setUpdatedAt(LocalDateTime.now());

                    return repository.save(entity);
                })
                .map(this::toResponse);
    }

    public Mono<Void> softDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("User not found")))
                .flatMap(entity -> {
                    entity.setDeletedAt(LocalDateTime.now());
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> restore(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("User is not deleted")))
                .flatMap(entity -> {
                    entity.setDeletedAt(null);
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
