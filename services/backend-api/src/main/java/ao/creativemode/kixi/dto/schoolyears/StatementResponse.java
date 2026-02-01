package ao.creativemode.kixi.dto.schoolyears;

import java.time.LocalDateTime;

public class StatementResponse {

    private Long id;
    private String examType;
    private Integer durationMinutes;
    private String variant;
    private String title;
    private String instructions;
    private Integer totalMaxScore;
    private Long schoolYearId;
    private Long termId;
    private Long subjectId;
    private Long classId;
    private Long courseId;
    private Boolean visible;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;


    public StatementResponse() {

    }


    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getExamType() { return examType; }
    public void setExamType(String examType) { this.examType = examType; }

    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }

    public String getVariant() { return variant; }
    public void setVariant(String variant) { this.variant = variant; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }

    public Integer getTotalMaxScore() { return totalMaxScore; }
    public void setTotalMaxScore(Integer totalMaxScore) { this.totalMaxScore = totalMaxScore; }

    public Long getSchoolYearId() { return schoolYearId; }
    public void setSchoolYearId(Long schoolYearId) { this.schoolYearId = schoolYearId; }

    public Long getTermId() { return termId; }
    public void setTermId(Long termId) { this.termId = termId; }

    public Long getSubjectId() { return subjectId; }
    public void setSubjectId(Long subjectId) { this.subjectId = subjectId; }

    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }

    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }

    public Boolean getVisible() { return visible; }
    public void setVisible(Boolean visible) { this.visible = visible; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
