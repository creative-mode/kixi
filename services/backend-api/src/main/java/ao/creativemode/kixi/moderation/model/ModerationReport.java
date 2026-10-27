package ao.creativemode.kixi.moderation.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Table("moderation_reports")
public class ModerationReport {
    @Id
    private Long id;
    @Column("target_type")
    private String targetType;
    @Column("target_account_id")
    private Long targetAccountId;
    @Column("target_institution_id")
    private Long targetInstitutionId;
    @Column("reporter_account_id")
    private Long reporterAccountId;
    private String reason;
    private String note;
    @Column("created_at")
    private LocalDateTime createdAt;
}
