package ao.creativemode.kixi.identity.controller;

import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Até quando o aluno já viu as notificações (kixi#729). O cliente deriva as notificações do que já existe e
 * compara a data de cada uma com este instante; «Marcar tudo como lido» põe-no em «agora».
 */
@RestController
@RequestMapping("/api/v1/me/notifications")
public class NotificationStateController {

    private final DatabaseClient db;
    private final CurrentAccountService currentAccount;

    public NotificationStateController(DatabaseClient db, CurrentAccountService currentAccount) {
        this.db = db;
        this.currentAccount = currentAccount;
    }

    /** {@code readAt} é null enquanto o aluno nunca marcou nada como lido. */
    @GetMapping
    public Mono<Map<String, Object>> state() {
        return currentAccount.requiredAccountId().flatMap(accountId ->
                db.sql("SELECT read_at FROM notification_reads WHERE account_id = :id")
                        .bind("id", accountId)
                        .map((row, meta) -> row.get("read_at", OffsetDateTime.class))
                        .one()
                        .map(at -> Map.<String, Object>of("readAt", at.toString()))
                        .defaultIfEmpty(Map.of()));
    }

    @PostMapping("/read")
    public Mono<Map<String, Object>> markAllRead() {
        return currentAccount.requiredAccountId().flatMap(accountId ->
                db.sql("INSERT INTO notification_reads (account_id, read_at) VALUES (:id, CURRENT_TIMESTAMP) "
                                + "ON CONFLICT (account_id) DO UPDATE SET read_at = CURRENT_TIMESTAMP "
                                + "RETURNING read_at")
                        .bind("id", accountId)
                        .map((row, meta) -> row.get("read_at", OffsetDateTime.class))
                        .one()
                        .map(at -> Map.<String, Object>of("readAt", at.toString())));
    }
}
