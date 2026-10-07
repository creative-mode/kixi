package ao.creativemode.kixi.institutions.dto.enrollment;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MeResponse(
        Long accountId,
        String username,
        String email,
        @JsonProperty("first_name")
        String firstName,
        @JsonProperty("last_name")
        String lastName,
        String photo,
        List<String> roles,
        SchoolInfo school,
        CourseInfo course,
        ClassInfo currentClass
) {
    public record SchoolInfo(Long id, String code, String name) { }

    public record CourseInfo(Long id, String code, String name) { }

    public record ClassInfo(
            Long id,
            String code,
            Integer grade,
            @JsonProperty("school_year_id")
            Long schoolYearId,
            @JsonProperty("school_year")
            String schoolYear
    ) { }
}
