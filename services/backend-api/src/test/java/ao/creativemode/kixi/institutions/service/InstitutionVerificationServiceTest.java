package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.dto.verification.VerificationRequestCreate;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.model.InstitutionVerificationRequest;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionVerificationRequestRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class InstitutionVerificationServiceTest {

    private static final long INSTITUTION = 7L;
    private static final long ADMIN_ACCOUNT = 1L;
    private static final long TEACHER_ACCOUNT = 20L;

    private InstitutionRepository institutions;
    private InstitutionVerificationRequestRepository requests;
    private InstitutionTeacherRepository teacherLinks;
    private TeacherRepository teachers;
    private InstitutionVerificationService service;

    private final VerificationRequestCreate body = new VerificationRequestCreate(
        " Instituto de Telecomunicações ", " MED-123 ", "geral@itel.ao", "  ", null);

    @BeforeEach
    void setUp() {
        institutions = mock(InstitutionRepository.class);
        requests = mock(InstitutionVerificationRequestRepository.class);
        teacherLinks = mock(InstitutionTeacherRepository.class);
        teachers = mock(TeacherRepository.class);
        service = new InstitutionVerificationService(institutions, requests, teacherLinks, teachers);

        when(institutions.save(any(Institution.class))).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(requests.save(any(InstitutionVerificationRequest.class))).thenAnswer(i -> {
            InstitutionVerificationRequest saved = i.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(99L);
            }
            return Mono.just(saved);
        });
    }

    private Institution school(boolean verified) {
        Institution institution = new Institution();
        institution.setId(INSTITUTION);
        institution.setName("ITEL");
        institution.setVerified(verified);
        return institution;
    }

    private InstitutionVerificationRequest pending() {
        return InstitutionVerificationRequest.builder()
            .id(99L)
            .institutionId(INSTITUTION)
            .status(InstitutionVerificationRequest.PENDING)
            .build();
    }

    @Test
    void adminSubmitsTrimmedRequestAsPending() {
        when(institutions.findByIdAndDeletedAtIsNull(INSTITUTION)).thenReturn(Mono.just(school(false)));
        when(requests.existsByInstitutionIdAndStatus(INSTITUTION, "pending")).thenReturn(Mono.just(false));

        StepVerifier.create(service.submit(ADMIN_ACCOUNT, true, INSTITUTION, body))
            .assertNext(response -> {
                assertThat(response.status()).isEqualTo("pending");
                assertThat(response.officialName()).isEqualTo("Instituto de Telecomunicações");
                assertThat(response.registrationNumber()).isEqualTo("MED-123");
                assertThat(response.documentUrl()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void schoolCannotAskTwiceWhileOneIsWaiting() {
        when(institutions.findByIdAndDeletedAtIsNull(INSTITUTION)).thenReturn(Mono.just(school(false)));
        when(requests.existsByInstitutionIdAndStatus(INSTITUTION, "pending")).thenReturn(Mono.just(true));

        StepVerifier.create(service.submit(ADMIN_ACCOUNT, true, INSTITUTION, body))
            .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.CONFLICT))
            .verify();
        verify(requests, never()).save(any());
    }

    @Test
    void verifiedSchoolCannotAskAgain() {
        when(institutions.findByIdAndDeletedAtIsNull(INSTITUTION)).thenReturn(Mono.just(school(true)));

        StepVerifier.create(service.submit(ADMIN_ACCOUNT, true, INSTITUTION, body))
            .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.CONFLICT))
            .verify();
    }

    @Test
    void teacherOfAnotherSchoolIsRefused() {
        Teacher teacher = new Teacher();
        teacher.setId(5L);
        when(institutions.findByIdAndDeletedAtIsNull(INSTITUTION)).thenReturn(Mono.just(school(false)));
        when(teachers.findByAccountIdAndDeletedAtIsNull(TEACHER_ACCOUNT)).thenReturn(Mono.just(teacher));
        when(teacherLinks.existsByInstitutionIdAndTeacherIdAndDeletedAtIsNull(INSTITUTION, 5L))
            .thenReturn(Mono.just(false));

        StepVerifier.create(service.submit(TEACHER_ACCOUNT, false, INSTITUTION, body))
            .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN))
            .verify();
        verify(requests, never()).save(any());
    }

    @Test
    void approvingSetsTheBadgeAndRecordsWhoDecided() {
        Institution institution = school(false);
        when(requests.findById(99L)).thenReturn(Mono.just(pending()));
        when(institutions.findByIdAndDeletedAtIsNull(INSTITUTION)).thenReturn(Mono.just(institution));

        StepVerifier.create(service.approve(99L, ADMIN_ACCOUNT, "Documento conferido"))
            .assertNext(response -> {
                assertThat(response.status()).isEqualTo("approved");
                assertThat(response.reviewNote()).isEqualTo("Documento conferido");
            })
            .verifyComplete();

        ArgumentCaptor<Institution> saved = ArgumentCaptor.forClass(Institution.class);
        verify(institutions).save(saved.capture());
        assertThat(saved.getValue().isVerified()).isTrue();
        assertThat(saved.getValue().getVerifiedAt()).isNotNull();
        assertThat(saved.getValue().getVerifiedByAccountId()).isEqualTo(ADMIN_ACCOUNT);
    }

    @Test
    void aDecidedRequestCannotBeDecidedAgain() {
        InstitutionVerificationRequest done = pending();
        done.setStatus(InstitutionVerificationRequest.REJECTED);
        when(requests.findById(99L)).thenReturn(Mono.just(done));

        StepVerifier.create(service.approve(99L, ADMIN_ACCOUNT, null))
            .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.CONFLICT))
            .verify();
        verify(institutions, never()).save(any());
    }

    @Test
    void rejectingNeedsAReasonAndLeavesTheSchoolUnverified() {
        StepVerifier.create(service.reject(99L, ADMIN_ACCOUNT, "  "))
            .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
            .verify();

        when(requests.findById(99L)).thenReturn(Mono.just(pending()));
        StepVerifier.create(service.reject(99L, ADMIN_ACCOUNT, "Número de registo não confere"))
            .assertNext(response -> assertThat(response.status()).isEqualTo("rejected"))
            .verifyComplete();
        verify(institutions, never()).save(any());
    }

    @Test
    void revokingRemovesTheBadgeAtOnce() {
        Institution institution = school(true);
        institution.setVerifiedByAccountId(ADMIN_ACCOUNT);
        when(institutions.findByIdAndDeletedAtIsNull(INSTITUTION)).thenReturn(Mono.just(institution));

        StepVerifier.create(service.revoke(INSTITUTION)).verifyComplete();

        ArgumentCaptor<Institution> saved = ArgumentCaptor.forClass(Institution.class);
        verify(institutions).save(saved.capture());
        assertThat(saved.getValue().isVerified()).isFalse();
        assertThat(saved.getValue().getVerifiedAt()).isNull();
    }

    @Test
    void revokingAnUnverifiedSchoolIsAConflict() {
        when(institutions.findByIdAndDeletedAtIsNull(INSTITUTION)).thenReturn(Mono.just(school(false)));

        StepVerifier.create(service.revoke(INSTITUTION))
            .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.CONFLICT))
            .verify();
    }
}
