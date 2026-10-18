package ao.creativemode.kixi.institutions.dto.enrollment;

public record EnrollRequest(
        /**
         * Target account. Null (or equal to the caller) means "myself".
         * Only ADMIN/TEACHER may enroll another account.
         */
        Long accountId,
        /**
         * Used only when ADMIN/TEACHER enrolls another account; a self-enroll ignores
         * this and derives its class from {@code code} instead (#255), so a student
         * cannot pick an arbitrary class by guessing its id.
         */
        Long classId,
        /** Same as {@code classId}: staff-enroll only. */
        Long schoolYearId,
        /**
         * The code a school issued for a class (#255). Required for a self-enroll;
         * ignored for a staff-enroll of another account.
         */
        String code
) { }
