package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ExperienceServiceTest {

    @Test
    void theSchoolYearStartsInSeptember() {
        assertThat(ExperienceService.currentSchoolYearStart(LocalDate.of(2026, 8, 31))).isEqualTo(2025);
        assertThat(ExperienceService.currentSchoolYearStart(LocalDate.of(2026, 9, 1))).isEqualTo(2026);
        assertThat(ExperienceService.currentSchoolYearStart(LocalDate.of(2027, 1, 15))).isEqualTo(2026);
    }
}
