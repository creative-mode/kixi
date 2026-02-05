package ao.creativemode.kixi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("questions")
public class Question {

    @Id
    private Long id;

    @Column("statement_id")
    private Long statementId;

    @Column("number")
    private Integer number;

    @Column("text")
    private String text;

    @Column("question_type")
    private String questionType;

    @Column("max_score")
    private Double maxScore;

    @Column("order_index")
    private Integer orderIndex;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    /**
     * Logic for soft delete
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * Logic for restoring a soft-deleted entity
     */
    public void restore() {
        this.deletedAt = null;
    }

    /**
     * Check if the entity is currently soft-deleted
     */
    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
