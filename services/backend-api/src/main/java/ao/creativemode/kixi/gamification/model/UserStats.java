package ao.creativemode.kixi.gamification.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("user_stats")
public class UserStats {
    @Id
    @Column("account_id")
    private Long accountId;
    private Long xp;
    private Integer level;
    private Integer streak;
    @Column("longest_streak")
    private Integer longestStreak;
    @Column("free_pauses")
    private Integer freePauses;
    @Column("last_activity_date")
    private LocalDate lastActivityDate;
    @Column("created_at")
    private LocalDateTime createdAt;
    @Column("updated_at")
    private LocalDateTime updatedAt;

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { accountId = value; }
    public Long getXp() { return xp; }
    public void setXp(Long value) { xp = value; }
    public Integer getLevel() { return level; }
    public void setLevel(Integer value) { level = value; }
    public Integer getStreak() { return streak; }
    public void setStreak(Integer value) { streak = value; }
    public Integer getLongestStreak() { return longestStreak; }
    public void setLongestStreak(Integer value) { longestStreak = value; }
    public Integer getFreePauses() { return freePauses; }
    public void setFreePauses(Integer value) { freePauses = value; }
    public LocalDate getLastActivityDate() { return lastActivityDate; }
    public void setLastActivityDate(LocalDate value) { lastActivityDate = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
