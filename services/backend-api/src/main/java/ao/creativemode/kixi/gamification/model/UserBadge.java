package ao.creativemode.kixi.gamification.model;

import java.time.LocalDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("user_badges")
public class UserBadge {
    @Id
    private Long id;
    @Column("account_id")
    private Long accountId;
    @Column("badge_id")
    private Long badgeId;
    @Column("subject_id")
    private Long subjectId;
    @Column("unlocked_at")
    private LocalDateTime unlockedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { accountId = value; }
    public Long getBadgeId() { return badgeId; }
    public void setBadgeId(Long value) { badgeId = value; }
    public Long getSubjectId() { return subjectId; }
    public void setSubjectId(Long value) { subjectId = value; }
    public LocalDateTime getUnlockedAt() { return unlockedAt; }
    public void setUnlockedAt(LocalDateTime value) { unlockedAt = value; }
}
