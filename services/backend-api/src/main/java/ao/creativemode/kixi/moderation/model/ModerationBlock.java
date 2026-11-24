package ao.creativemode.kixi.moderation.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Table("moderation_blocks")
public class ModerationBlock {
    @Id
    private Long id;
    @Column("account_low")
    private Long accountLow;
    @Column("account_high")
    private Long accountHigh;
    /** Who put the block; null on rows that predate the column (either side may lift those). */
    @Column("blocked_by")
    private Long blockedBy;
    @Column("created_at")
    private LocalDateTime createdAt;
}
