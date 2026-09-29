package ao.creativemode.kixi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.schoolyears.SchoolYearRequest;
import ao.creativemode.kixi.model.SchoolYear;
import ao.creativemode.kixi.repository.SchoolYearRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SchoolYearServiceTest {

    private SchoolYearRepository repository;
    private SchoolYearService service;

    @BeforeEach
    void setUp() {
        repository = mock(SchoolYearRepository.class);
        service = new SchoolYearService(repository);
    }

    @Test
    void findAllActiveMapsEveryEntityToResponse() {
        when(repository.findAllByDeletedAtIsNull()).thenReturn(Flux.just(schoolYear(1L, 2024, 2025)));

        StepVerifier.create(service.findAllActive())
                .assertNext(response -> assertThat(response.startYear()).isEqualTo(2024))
                .verifyComplete();
    }

    @Test
    void findAllDeletedReturnsOnlyTrashedEntities() {
        when(repository.findAllByDeletedAtIsNotNull()).thenReturn(Flux.just(schoolYear(2L, 2025, 2026)));

        StepVerifier.create(service.findAllDeleted())
                .assertNext(response -> assertThat(response.id()).isEqualTo(2L))
                .verifyComplete();
    }

    @Test
    void findByIdActiveReturnsNotFoundForMissingSchoolYear() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findByIdActive(99L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(404);
                })
                .verify();
    }

    @Test
    void createRejectsStartYearNotBeforeEndYearWithoutTouchingRepository() {
        StepVerifier.create(service.create(new SchoolYearRequest(2025, 2024)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage())
                            .isEqualTo("Start year must be less than end year");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void createSavesValidSchoolYear() {
        when(repository.save(any(SchoolYear.class))).thenAnswer(invocation -> {
            SchoolYear entity = invocation.getArgument(0);
            entity.setId(3L);
            return Mono.just(entity);
        });

        StepVerifier.create(service.create(new SchoolYearRequest(2024, 2025)))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(3L);
                    assertThat(response.startYear()).isEqualTo(2024);
                    assertThat(response.endYear()).isEqualTo(2025);
                })
                .verifyComplete();
    }

    @Test
    void createMapsDuplicateRangeConflictToApiException() {
        when(repository.save(any(SchoolYear.class)))
                .thenReturn(Mono.error(new DataIntegrityViolationException("duplicate")));

        StepVerifier.create(service.create(new SchoolYearRequest(2024, 2025)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                })
                .verify();
    }

    @Test
    void updateRejectsMissingSchoolYearWithoutSaving() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(99L, new SchoolYearRequest(2024, 2025)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void updateRejectsStartYearNotBeforeEndYear() {
        SchoolYear existing = schoolYear(1L, 2024, 2025);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.update(1L, new SchoolYearRequest(2026, 2025)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage())
                            .isEqualTo("Start year must be less than end year");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void updateAppliesNewYearsToExistingSchoolYear() {
        SchoolYear existing = schoolYear(1L, 2024, 2025);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(SchoolYear.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.update(1L, new SchoolYearRequest(2025, 2026)))
                .assertNext(response -> {
                    assertThat(response.startYear()).isEqualTo(2025);
                    assertThat(response.endYear()).isEqualTo(2026);
                })
                .verifyComplete();
    }

    @Test
    void softDeleteRejectsMissingSchoolYear() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.softDelete(99L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void softDeleteMarksEntityAsDeleted() {
        SchoolYear existing = schoolYear(1L, 2024, 2025);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(SchoolYear.class))).thenReturn(Mono.just(existing));

        StepVerifier.create(service.softDelete(1L)).verifyComplete();

        assertThat(existing.isDeleted()).isTrue();
    }

    @Test
    void restoreRejectsSchoolYearThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.restore(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void restoreClearsDeletedAt() {
        SchoolYear deleted = schoolYear(1L, 2024, 2025);
        deleted.markAsDeleted();
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.save(any(SchoolYear.class))).thenReturn(Mono.just(deleted));

        StepVerifier.create(service.restore(1L)).verifyComplete();

        assertThat(deleted.isDeleted()).isFalse();
    }

    @Test
    void hardDeleteRejectsSchoolYearThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).delete(any(SchoolYear.class));
    }

    @Test
    void hardDeleteRemovesTrashedSchoolYear() {
        SchoolYear deleted = schoolYear(1L, 2024, 2025);
        deleted.markAsDeleted();
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.delete(deleted)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L)).verifyComplete();

        verify(repository).delete(deleted);
    }

    private SchoolYear schoolYear(Long id, Integer start, Integer end) {
        SchoolYear schoolYear = new SchoolYear();
        schoolYear.setId(id);
        schoolYear.setStartYear(start);
        schoolYear.setEndYear(end);
        return schoolYear;
    }
}
