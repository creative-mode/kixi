package ao.creativemode.kixi.examrooms.model;

import java.time.LocalDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("exam_room_participants")
public class ExamRoomParticipant {
    @Id private Long id;
    @Column("exam_room_id") private Long examRoomId;
    @Column("account_id") private Long accountId;
    @Column("simulation_id") private Long simulationId;
    @Column("joined_at") private LocalDateTime joinedAt;
    @Column("created_at") private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getExamRoomId() { return examRoomId; }
    public void setExamRoomId(Long value) { examRoomId = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { accountId = value; }
    public Long getSimulationId() { return simulationId; }
    public void setSimulationId(Long value) { simulationId = value; }
    public LocalDateTime getJoinedAt() { return joinedAt; }
    public void setJoinedAt(LocalDateTime value) { joinedAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
