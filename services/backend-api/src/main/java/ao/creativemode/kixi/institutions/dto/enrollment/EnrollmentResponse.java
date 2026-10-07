package ao.creativemode.kixi.institutions.dto.enrollment;

public record EnrollmentResponse(
        Long id,
        Long accountId,
        Long classId,
        Long schoolYearId,
        String status
) { }
