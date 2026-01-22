package ao.creativemode.kixi.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("schoolYears")
public class SchoolYear {

    @Id
    private Long id;

    @Column("startYear")
    private Integer startYear;

    @Column("endYear")
    private Integer endYear;

    private boolean deleted = false;

    @Column("deletedAt")
    private LocalDateTime deletedAt;

    public SchoolYear() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getStartYear() { return startYear; }
    public void setStartYear(Integer startYear) { this.startYear = startYear; }

    public Integer getEndYear() { return endYear; }
    public void setEndYear(Integer endYear) { this.endYear = endYear; }

    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

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