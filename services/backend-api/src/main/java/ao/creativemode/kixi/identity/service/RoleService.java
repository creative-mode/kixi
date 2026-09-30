package ao.creativemode.kixi.identity.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.identity.dto.roles.RoleRequest;
import ao.creativemode.kixi.identity.dto.roles.RoleResponse;
import ao.creativemode.kixi.identity.model.Role;
import ao.creativemode.kixi.identity.repository.RoleRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class RoleService {

    private final RoleRepository repository;
    private final AccountRoleService accountRoleService;

    public RoleService(RoleRepository repository, AccountRoleService accountRoleService) {
        this.repository = repository;
        this.accountRoleService = accountRoleService;
    }

    public Flux<RoleResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull()
                .map(this::toResponse);
    }

    public Flux<RoleResponse> findAllDeleted() {
        return repository.findAllByDeletedAtIsNotNull()
                .map(this::toResponse);
    }

    public Mono<RoleResponse> findByIdActive(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Role not found")))
                .map(this::toResponse);
    }

    public Mono<RoleResponse> create(RoleRequest request) {
        String name = request.name().trim().toUpperCase();
        String description = request.description() != null ? request.description().trim() : null;

        Role entity = new Role();
        entity.setName(name);
        entity.setDescription(description);
        entity.setDeletedAt(null);

        return repository.save(entity)
                .map(this::toResponse)
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> ApiException.conflict("A role with name " + name + " already exists"));
    }

    public Mono<RoleResponse> update(Long id, RoleRequest request) {
        String name = request.name().trim().toUpperCase();
        String description = request.description() != null ? request.description().trim() : null;

        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Role not found")))
                .flatMap(entity -> {
                    entity.setName(name);
                    entity.setDescription(description);
                    entity.setUpdatedAt(LocalDateTime.now());

                    return repository.save(entity)
                            .onErrorMap(DataIntegrityViolationException.class,
                                    e -> ApiException.conflict("Another role with name " + name + " already exists"));
                })
                .map(this::toResponse);
    }

    public Mono<Void> softDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Role not found")))
                .flatMap(entity -> {
                    entity.setDeletedAt(LocalDateTime.now());
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> restore(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Role is not deleted")))
                .flatMap(entity -> {
                    entity.setDeletedAt(null);
                    return repository.save(entity);
                })
                .then();
    }

    private RoleResponse toResponse(Role entity) {
        return new RoleResponse(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }

    @Transactional
    public Mono<Void> hardDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(
                        Mono.error(ApiException.badRequest("Only deleted roles can be permanently removed")))
                .flatMap(role ->
                        // Role assignments keep the roles foreign key alive, so
                        // they must be hard deleted first. Soft deleting them
                        // would not unblock the purge.
                        accountRoleService.purgeAssociationsForRole(id)
                                .then(repository.delete(role)))
                .then();
    }
}
