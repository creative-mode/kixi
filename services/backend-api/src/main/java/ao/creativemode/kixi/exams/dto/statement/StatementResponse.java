package ao.creativemode.kixi.exams.dto.statement;

import java.time.LocalDateTime;

import ao.creativemode.kixi.identity.dto.accounts.AccountBasicResponse;
import ao.creativemode.kixi.academic.dto.classe.ClassResponse;
import ao.creativemode.kixi.academic.dto.courses.CourseResponse;
import ao.creativemode.kixi.academic.dto.schoolyears.SchoolYearResponse;
import ao.creativemode.kixi.academic.dto.subject.SubjectResponse;
import ao.creativemode.kixi.academic.dto.term.TermResponse;

public record StatementResponse(
        Long id,
        String examType,
        Integer durationMinutes,
        String variant,
        String title,
        String instructions,
        Integer totalMaxScore,
        SchoolYearResponse schoolYear,
        TermResponse term,
        SubjectResponse subject,
        ClassResponse classInfo,
        CourseResponse course,
        AccountBasicResponse createdBy,
        Boolean visible,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
