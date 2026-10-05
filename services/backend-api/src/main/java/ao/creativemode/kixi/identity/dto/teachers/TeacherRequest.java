package ao.creativemode.kixi.identity.dto.teachers;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TeacherRequest(
    @NotBlank(message = "First name is required")
    @Size(max = 100, message = "First name must not exceed 100 characters")
    String firstName,

    @NotBlank(message = "Last name is required")
    @Size(max = 100, message = "Last name must not exceed 100 characters")
    String lastName,

    @Email(message = "Email must be valid")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    String email,

    @Size(max = 500, message = "Photo must not exceed 500 characters")
    String photo,

    @Size(max = 255, message = "Specialty must not exceed 255 characters")
    String specialty,

    @Size(max = 50, message = "Employee number must not exceed 50 characters")
    String employeeNumber
) {}
