package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.roles.RoleResponse;
import ao.creativemode.kixi.model.AccountRole;
import ao.creativemode.kixi.model.Account;
import ao.creativemode.kixi.model.Role;
import ao.creativemode.kixi.repository.AccountRepository;
import ao.creativemode.kixi.repository.AccountRoleRepository;
import ao.creativemode.kixi.repository.RoleRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class AccountRoleService {

    private final AccountRoleRepository accountRoleRepository;
    private final AccountRepository accountRepository;
    private final RoleRepository roleRepository;

    public AccountRoleService(AccountRoleRepository accountRoleRepository,
                              AccountRepository accountRepository,
                              RoleRepository roleRepository) {
        this.accountRoleRepository = accountRoleRepository;
        this.accountRepository = accountRepository;
        this.roleRepository = roleRepository;
    }

    /**
     * Lista os roles atualmente atribuídos a um account (associações ativas).
     */
    public Flux<RoleResponse> findRolesByAccountId(Long accountId) {
        return accountRepository.findByIdAndDeletedAtIsNull(accountId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Account not found")))
                .thenMany(accountRoleRepository.findByAccountIdAndDeletedAtIsNull(accountId))
                .flatMap(ar -> roleRepository.findById(ar.getRoleId()))
                .filter(role -> role.getDeletedAt() == null)
                .map(this::toRoleResponse);
    }

    /**
     * Atribui um role a um account (cria associação ativa ou restaura se já existia com soft-delete).
     */
    public Mono<Void> assignRoleToAccount(Long accountId, Long roleId) {
        Mono<Account> accountMono = accountRepository.findByIdAndDeletedAtIsNull(accountId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Account not found")));
        Mono<Role> roleMono = roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Role not found")));

        return Mono.zip(accountMono, roleMono)
                .flatMap(tuple -> accountRoleRepository.findFirstByAccountIdAndRoleId(accountId, roleId)
                        .flatMap(ar -> {
                            if (ar.isDeleted()) {
                                ar.restore();
                                return accountRoleRepository.save(ar);
                            }
                            return Mono.<AccountRole>error(ApiException.conflict("Account already has this role"));
                        })
                        .switchIfEmpty(Mono.defer(() -> {
                            AccountRole ar = new AccountRole(accountId, roleId);
                            return accountRoleRepository.save(ar);
                        })))
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> ApiException.conflict("Account already has this role"))
                .then();
    }

    /**
     * Remove a atribuição de um role a um account (soft delete da associação).
     */
    public Mono<Void> removeRoleFromAccount(Long accountId, Long roleId) {
        return accountRoleRepository.findByAccountIdAndRoleIdAndDeletedAtIsNull(accountId, roleId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Account does not have this role")))
                .flatMap(ar -> {
                    ar.markAsDeleted();
                    return accountRoleRepository.save(ar);
                })
                .then();
    }

    private RoleResponse toRoleResponse(Role entity) {
        return new RoleResponse(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }
}
