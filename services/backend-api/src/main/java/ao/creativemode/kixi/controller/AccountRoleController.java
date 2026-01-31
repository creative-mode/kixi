package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.dto.roles.RoleResponse;
import ao.creativemode.kixi.service.AccountRoleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.springframework.http.HttpStatus.NO_CONTENT;

/**
 * Controller para atribuição de roles a accounts (relacionamento N:N).
 * Base path: /api/v1/accounts/{accountId}/roles
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/roles")
public class AccountRoleController {

    private final AccountRoleService accountRoleService;

    public AccountRoleController(AccountRoleService accountRoleService) {
        this.accountRoleService = accountRoleService;
    }

    /**
     * Lista os roles atribuídos ao account.
     */
    @GetMapping
    public Mono<ResponseEntity<List<RoleResponse>>> listRolesByAccount(@PathVariable Long accountId) {
        return accountRoleService.findRolesByAccountId(accountId)
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Atribui um role ao account.
     */
    @PostMapping("/{roleId}")
    public Mono<ResponseEntity<Void>> assignRole(
            @PathVariable Long accountId,
            @PathVariable Long roleId) {
        return accountRoleService.assignRoleToAccount(accountId, roleId)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    /**
     * Remove a atribuição do role ao account (soft delete da associação).
     */
    @DeleteMapping("/{roleId}")
    public Mono<ResponseEntity<Void>> removeRole(
            @PathVariable Long accountId,
            @PathVariable Long roleId) {
        return accountRoleService.removeRoleFromAccount(accountId, roleId)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }
}
