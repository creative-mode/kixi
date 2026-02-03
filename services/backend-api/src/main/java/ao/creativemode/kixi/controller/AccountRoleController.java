package ao.creativemode.kixi.controller;

import java.util.List;

import static org.springframework.http.HttpStatus.NO_CONTENT;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ao.creativemode.kixi.dto.roles.RoleResponse;
import ao.creativemode.kixi.service.AccountRoleService;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/roles")
public class AccountRoleController {

    private final AccountRoleService accountRoleService;

    public AccountRoleController(AccountRoleService accountRoleService) {
        this.accountRoleService = accountRoleService;
    }

    /**
     * Lists the roles assigned to the account.
     */
    @GetMapping
    public Mono<ResponseEntity<List<RoleResponse>>> listRolesByAccount(@PathVariable Long accountId) {
        return accountRoleService.findRolesByAccountId(accountId)
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Assigns a role to the account.
     */
    @PostMapping("/{roleId}")
    public Mono<ResponseEntity<Void>> assignRole(
            @PathVariable Long accountId,
            @PathVariable Long roleId) {
        return accountRoleService.assignRoleToAccount(accountId, roleId)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    /**
     * Removes the role assignment from the account (soft delete of the association).
     */
    @DeleteMapping("/{roleId}")
    public Mono<ResponseEntity<Void>> removeRole(
            @PathVariable Long accountId,
            @PathVariable Long roleId) {
        return accountRoleService.removeRoleFromAccount(accountId, roleId)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }
}
