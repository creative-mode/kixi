package ao.creativemode.kixi.dto.statement;

import java.time.LocalDateTime;

import ao.creativemode.kixi.dto.accounts.AccountBasicResponse;
import ao.creativemode.kixi.dto.classe.ClassResponse;
import ao.creativemode.kixi.dto.courses.CourseResponse;
import ao.creativemode.kixi.dto.schoolyears.SchoolYearResponse;
import ao.creativemode.kixi.dto.subject.SubjectResponse;
import ao.creativemode.kixi.dto.term.TermResponse;

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
