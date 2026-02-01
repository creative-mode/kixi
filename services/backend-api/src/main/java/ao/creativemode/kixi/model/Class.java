package ao.creativemode.kixi.model;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@Getter
@Setter
@Table("classes")
public class Class implements Persistable<String> {
    @Id
    @Column("code")
    private String code;

    @Column("grade")
    private String grade;

    @Column("course_id")
    private Long courseId;

    @Column("school_year_id")
    private Long schoolYearId;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    public void markAsDeleted() {
        this.deletedAt = LocalDateTime.now();
    }

    public void restore() {
        this.deletedAt = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    @Transient
    private boolean isNewRecord = false;

    @Override
    public boolean isNew() {
        return this.isNewRecord || this.code == null;
    }
    @Override
    public String getId() {
        return this.code;
    }

    public void setNewRecord(boolean isNew) {
        this.isNewRecord = isNew;
    }
}
