package ao.creativemode.kixi.plan;

import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * O coordenador de uma instituição (kixi#513): só o administrador o atribui. A conta ganha o papel {@code COORDINATOR}; o
 * vigilante não tem papel: é um professor. Retirar o último coordenador de uma instituição tira o papel à conta se já não
 * coordenar nenhuma outra.
 */
@RestController
@RequestMapping("/api/v1/institutions/{institutionId}/coordinators")
public class InstitutionCoordinatorController {

    private final DatabaseClient db;

    public InstitutionCoordinatorController(DatabaseClient db) {
        this.db = db;
    }

    @GetMapping
    public Mono<List<Map<String, Object>>> list(@PathVariable Long institutionId) {
        return db.sql("""
                SELECT a.id, a.username, a.email FROM institution_coordinators c JOIN accounts a ON a.id = c.account_id
                WHERE c.institution_id = :i ORDER BY a.username
                """).bind("i", institutionId).map((row, meta) -> Map.<String, Object>of("accountId", row.get("id", Long.class),
                "username", row.get("username", String.class), "email", String.valueOf(row.get("email", String.class)))).all().collectList();
    }

    @PutMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public Mono<Void> assign(@PathVariable Long institutionId, @PathVariable Long accountId) {
        return db.sql("SELECT 1 FROM institutions WHERE id = :i AND deleted_at IS NULL").bind("i", institutionId).fetch().first()
                .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found")))
                .then(db.sql("SELECT 1 FROM accounts WHERE id = :a AND deleted_at IS NULL AND active").bind("a", accountId).fetch().first()
                        .switchIfEmpty(Mono.error(ApiException.notFound("Account not found"))))
                .then(db.sql("INSERT INTO institution_coordinators (institution_id, account_id) VALUES (:i, :a) ON CONFLICT DO NOTHING")
                        .bind("i", institutionId).bind("a", accountId).fetch().rowsUpdated())
                .then(db.sql("""
                        INSERT INTO account_roles (account_id, role_id) SELECT :a, r.id FROM roles r WHERE r.name = 'COORDINATOR'
                        ON CONFLICT DO NOTHING
                        """).bind("a", accountId).fetch().rowsUpdated()).then();
    }

    @DeleteMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public Mono<Void> remove(@PathVariable Long institutionId, @PathVariable Long accountId) {
        return db.sql("DELETE FROM institution_coordinators WHERE institution_id = :i AND account_id = :a")
                .bind("i", institutionId).bind("a", accountId).fetch().rowsUpdated()
                .then(db.sql("""
                        DELETE FROM account_roles WHERE account_id = :a AND role_id = (SELECT id FROM roles WHERE name = 'COORDINATOR')
                          AND NOT EXISTS (SELECT 1 FROM institution_coordinators WHERE account_id = :a)
                        """).bind("a", accountId).fetch().rowsUpdated()).then();
    }
}
