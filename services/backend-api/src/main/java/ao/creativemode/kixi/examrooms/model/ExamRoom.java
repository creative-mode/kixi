package ao.creativemode.kixi.examrooms.model;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("exam_rooms")
public class ExamRoom {
    @Id private Long id;
    @Column("statement_id") private Long statementId;
    @Column("class_id") private Long classId;
    @Column("teacher_account_id") private Long teacherAccountId;
    @Column("starts_at") private LocalDateTime startsAt;
    @Column("ends_at") private LocalDateTime endsAt;
    @Column("duration_minutes") private Integer durationMinutes;
    private ExamRoomStatus status = ExamRoomStatus.DRAFT;
    @Column("created_at") private LocalDateTime createdAt = LocalDateTime.now();
    @Column("updated_at") private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStatementId() { return statementId; }
    public void setStatementId(Long value) { statementId = value; }
    public Long getClassId() { return classId; }
    public void setClassId(Long value) { classId = value; }
    public Long getTeacherAccountId() { return teacherAccountId; }
    public void setTeacherAccountId(Long value) { teacherAccountId = value; }
    public LocalDateTime getStartsAt() { return startsAt; }
    public void setStartsAt(LocalDateTime value) { startsAt = value; }
    public LocalDateTime getEndsAt() { return endsAt; }
    public void setEndsAt(LocalDateTime value) { endsAt = value; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer value) { durationMinutes = value; }
    public ExamRoomStatus getStatus() { return status; }
    public void setStatus(ExamRoomStatus value) { status = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
