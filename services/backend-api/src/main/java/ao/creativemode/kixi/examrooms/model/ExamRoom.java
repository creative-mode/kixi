package ao.creativemode.kixi.examrooms.model;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import ao.creativemode.kixi.shared.config.ServerClockConfig;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("exam_rooms")
public class ExamRoom {
    @Id private Long id;
    @Column("statement_id") private Long statementId;
    @Column("class_id") private Long classId;
    @Column("teacher_account_id") private Long teacherAccountId;
    @Column("starts_at") private OffsetDateTime startsAtInstant;
    @Column("ends_at") private OffsetDateTime endsAtInstant;
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
    public LocalDateTime getStartsAt() { return local(startsAtInstant); }
    public void setStartsAt(LocalDateTime value) { startsAtInstant = offset(value); }
    public LocalDateTime getEndsAt() { return local(endsAtInstant); }
    public void setEndsAt(LocalDateTime value) { endsAtInstant = offset(value); }
    private static LocalDateTime local(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(ServerClockConfig.ZONE).toLocalDateTime();
    }
    private static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(ServerClockConfig.ZONE).toOffsetDateTime();
    }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer value) { durationMinutes = value; }
    public ExamRoomStatus getStatus() { return status; }
    public void setStatus(ExamRoomStatus value) { status = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
