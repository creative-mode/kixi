package ao.creativemode.kixi.dto.schoolyears;

import jakarta.validation.constraints.*;

public class StatementRequest {

    @NotBlank(message = "O título é obrigatório")
    @Size(min = 3, max = 255, message = "O título deve ter entre 3 e 255 caracteres")
    private String title;

    @NotBlank(message = "O tipo de exame é obrigatório")
    private String examType;

    @Positive(message = "A duração deve ser maior que zero")
    private Integer durationMinutes;

    @Size(max = 50, message = "A variante deve ter no máximo 50 caracteres")
    private String variant;

    @Size(max = 5000, message = "As instruções devem ter no máximo 5000 caracteres")
    private String instructions;

    @PositiveOrZero(message = "A pontuação máxima não pode ser negativa")
    private Integer totalMaxScore;

    @NotNull(message = "O ano letivo é obrigatório")
    private Long schoolYearId;

    @NotNull(message = "O trimestre é obrigatório")
    private Long termId;

    @NotNull(message = "A disciplina é obrigatória")
    private Long subjectId;

    @NotNull(message = "A turma é obrigatória")
    private Long classId;

    private Long courseId;

    private Boolean visible;

    public StatementRequest() {}

    // Getters e Setters

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getExamType() { return examType; }
    public void setExamType(String examType) { this.examType = examType; }

    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }

    public String getVariant() { return variant; }
    public void setVariant(String variant) { this.variant = variant; }

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
}
