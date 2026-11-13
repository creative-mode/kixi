package ao.creativemode.kixi.simulations.model;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import ao.creativemode.kixi.shared.config.ServerClockConfig;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("simulations")
public class Simulation {

    @Id
    private Long id;

    @Column("account_id")
    private Long accountId;

    @Column("statement_id")
    private Long statementId;

    @Column("school_year_id")
    private Long schoolYearId;

    @Column("exam_room_id")
    private Long examRoomId;

    @Column("exam_room_duration_minutes")
    private Integer examRoomDurationMinutes;

    @Column("started_at")
    private OffsetDateTime startedAtInstant;

    @Column("finished_at")
    private OffsetDateTime finishedAtInstant;

    @Column("time_spent_seconds")
    private Integer timeSpentSeconds;

    @Column("final_score")
    private Double finalScore;

    @Column("status")
    private SimulationStatus status;

    /** Ids das questões de um treino montado, em CSV; null numa simulação normal (todas as questões). */
    @Column("selected_question_ids")
    private String selectedQuestionIds;

    /** Título de um treino montado («Treino · ...»); nulo numa simulação normal. */
    @Column("title")
    private String title;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSelectedQuestionIds() {
        return selectedQuestionIds;
    }

    public void setSelectedQuestionIds(String selectedQuestionIds) {
        this.selectedQuestionIds = selectedQuestionIds;
    }

    /** As questões escolhidas num treino montado, ou null quando a simulação usa o enunciado todo. */
    public java.util.Set<Long> selectedQuestionIdSet() {
        if (selectedQuestionIds == null || selectedQuestionIds.isBlank()) {
            return null;
        }
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        for (String part : selectedQuestionIds.split(",")) {
            if (!part.isBlank()) ids.add(Long.valueOf(part.trim()));
        }
        return ids;
    }

    public Simulation() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getStatementId() {
        return statementId;
    }

    public void setStatementId(Long statementId) {
        this.statementId = statementId;
    }

    public Long getSchoolYearId() {
        return schoolYearId;
    }

    public void setSchoolYearId(Long schoolYearId) {
        this.schoolYearId = schoolYearId;
    }

    public Long getExamRoomId() { return examRoomId; }
    public void setExamRoomId(Long examRoomId) { this.examRoomId = examRoomId; }
    public Integer getExamRoomDurationMinutes() { return examRoomDurationMinutes; }
    public void setExamRoomDurationMinutes(Integer value) { examRoomDurationMinutes = value; }

    public LocalDateTime getStartedAt() {
        return startedAtInstant == null ? null : startedAtInstant.atZoneSameInstant(
                ServerClockConfig.ZONE).toLocalDateTime();
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAtInstant = startedAt == null ? null : startedAt.atZone(
                ServerClockConfig.ZONE).toOffsetDateTime();
    }

    public LocalDateTime getFinishedAt() {
        return finishedAtInstant == null ? null : finishedAtInstant.atZoneSameInstant(
                ServerClockConfig.ZONE).toLocalDateTime();
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAtInstant = finishedAt == null ? null : finishedAt.atZone(
                ServerClockConfig.ZONE).toOffsetDateTime();
    }

    public Integer getTimeSpentSeconds() {
        return timeSpentSeconds;
    }

    public void setTimeSpentSeconds(Integer timeSpentSeconds) {
        this.timeSpentSeconds = timeSpentSeconds;
    }

    public Double getFinalScore() {
        return finalScore;
    }

    public void setFinalScore(Double finalScore) {
        this.finalScore = finalScore;
    }

    public SimulationStatus getStatus() {
        return status;
    }

    public void setStatus(SimulationStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public void markAsDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public void restore() {
        this.deletedAt = null;
    }
}
