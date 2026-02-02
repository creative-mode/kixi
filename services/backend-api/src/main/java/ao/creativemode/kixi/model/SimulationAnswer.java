package ao.creativemode.kixi.model;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

@Getter
@Setter
@Table("simulation_answer")
public class SimulationAnswer {
    @Id
    private Long id;

    @Column("simulation_id")
    private Long simulationId;

    @Column("question_id")
    private Long questionId;

    @Column("selected_option_id")
    private Long selectedOptionId;

    @Column("score_obtained")
    private float scoreObtained;

    @Column("is_correct")
    private Boolean isCorrect;

    @Column("answered_at")
    private LocalDateTime answeredAt;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

}

