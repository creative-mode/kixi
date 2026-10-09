package ao.creativemode.kixi.chat.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * A tutor conversation bound to one account and one statement.
 *
 * The binding is the whole point of the tutor (ADR-0010 / issue #114): the
 * model only ever sees the material of this session's statement, so a session
 * that belongs to another account must not be reachable — the service looks
 * sessions up by (id, account_id) and reports a plain 404 otherwise.
 */
@Table("chat_sessions")
public class ChatSession {

    @Id
    private Long id;

    @Column("account_id")
    private Long accountId;

    @Column("statement_id")
    private Long statementId;

    @Column("created_at")
    private LocalDateTime createdAt;

    public ChatSession() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getStatementId() {
        return statementId;
    }

    public void setStatementId(Long statementId) {
        this.statementId = statementId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
