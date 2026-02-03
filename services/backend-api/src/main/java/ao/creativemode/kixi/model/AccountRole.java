package ao.creativemode.kixi.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Entidade associativa para o relacionamento N:N entre Account e Role.
 * Permite soft-delete (deletedAt) e auditoria (createdAt).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("account_roles")
public class AccountRole {

    @Id
    private Long id;

    @Column("account_id")
    private Long accountId;

    @Column("role_id")
    private Long roleId;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    public AccountRole(Long accountId, Long roleId) {
        this.accountId = accountId;
        this.roleId = roleId;
    }

    public void markAsDeleted() {
        this.deletedAt = LocalDateTime.now();
    }

    public void restore() {
        this.deletedAt = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
