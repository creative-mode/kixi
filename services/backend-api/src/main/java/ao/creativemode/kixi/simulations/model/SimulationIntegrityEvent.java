package ao.creativemode.kixi.simulations.model;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import ao.creativemode.kixi.shared.config.ServerClockConfig;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("simulation_integrity_events")
public class SimulationIntegrityEvent {
    @Id
    private Long id;

    @Column("simulation_id")
    private Long simulationId;

    @Column("kind")
    private SimulationIntegrityEventKind kind;

    @Column("question_id")
    private Long questionId;

    @Column("occurred_at")
    private OffsetDateTime atInstant;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getSimulationId() { return simulationId; }
    public void setSimulationId(Long value) { simulationId = value; }
    public SimulationIntegrityEventKind getKind() { return kind; }
    public void setKind(SimulationIntegrityEventKind value) { kind = value; }
    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long value) { questionId = value; }
    public LocalDateTime getAt() {
        return atInstant == null ? null : atInstant.atZoneSameInstant(ServerClockConfig.ZONE).toLocalDateTime();
    }
    public void setAt(LocalDateTime value) {
        atInstant = value == null ? null : value.atZone(ServerClockConfig.ZONE).toOffsetDateTime();
    }
    public void setAtInstant(OffsetDateTime value) { atInstant = value; }
}
