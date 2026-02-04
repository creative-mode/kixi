package ao.creativemode.kixi.model;

import java.time.LocalDateTime;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
@Data
@Table("simulation")
public class Simulation {

    @Id
    private Long id;

    @Column("account_id")
    private Long accountId;

    @Column("statement_id")
    private Long statementId;

    @Column("school_year_id")
    private Long schoolYearId;

    @Column("started_at")
    private LocalDateTime startedAt;

    @Column("finished_at")
    private LocalDateTime finishedAt;

    @Column("time_spent_seconds")
    private Integer timeSpentSeconds;

    @Column("final_score")
    private Double finalScore;

    @Column("status")
    private SimulationStatus status;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    public Simulation() {
        this.createdAt = LocalDateTime.now();
    }



    public void markAsDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public void restore() {
        this.deletedAt = null;
    }
}
