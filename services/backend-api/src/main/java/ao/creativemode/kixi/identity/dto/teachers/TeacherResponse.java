package ao.creativemode.kixi.identity.dto.teachers;

import java.time.LocalDateTime;

public record TeacherResponse(
    Long id,
    Long accountId,
    Boolean hasAccess,
    String firstName,
    String lastName,
    String email,
    String photo,
    String specialty,
    String employeeNumber,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime deletedAt
) {}
