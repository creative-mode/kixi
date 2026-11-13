package ao.creativemode.kixi.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.moderation.dto.ModerationReportResponse;
import ao.creativemode.kixi.moderation.model.ModerationReport;
import ao.creativemode.kixi.moderation.repository.ModerationAdminRepository;
import ao.creativemode.kixi.moderation.repository.ModerationReportRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ModerationDecisionServiceTest {
    private static final long ADMIN = 1L;
    private static final String NOTE = "Fez-se passar por um professor.";

    private ModerationReportRepository reports;
    private ModerationAdminRepository admin;
    private ModerationDecisionService service;
    private ModerationReportResponse view;

    @BeforeEach
    void setUp() {
        reports = mock(ModerationReportRepository.class);
        admin = mock(ModerationAdminRepository.class);
        service = new ModerationDecisionService(reports, admin);
        view = new ModerationReportResponse(7L, "ACCOUNT", 42L, "Aluno", 9L, "Outro", "OTHER", "x", "SUSPENDED",
                LocalDateTime.now(), LocalDateTime.now(), "Admin", NOTE);
        when(admin.closeIfOpen(anyLong(), anyString(), anyLong(), anyString(), any())).thenReturn(Mono.just(1L));
        when(admin.deactivateAccount(anyLong())).thenReturn(Mono.just(1L));
        when(admin.hideInstitution(anyLong())).thenReturn(Mono.just(1L));
        when(admin.recordAudit(anyLong(), anyString(), anyString(), anyLong(), anyLong(), anyString(), any()))
                .thenReturn(Mono.empty());
        when(admin.report(anyLong())).thenReturn(Mono.just(view));
    }

    private ModerationReport account() {
        ModerationReport r = new ModerationReport();
        r.setId(7L);
        r.setTargetType("ACCOUNT");
        r.setTargetAccountId(42L);
        when(reports.findById(7L)).thenReturn(Mono.just(r));
        return r;
    }

    private ModerationReport institution() {
        ModerationReport r = new ModerationReport();
        r.setId(8L);
        r.setTargetType("INSTITUTION");
        r.setTargetInstitutionId(3L);
        when(reports.findById(8L)).thenReturn(Mono.just(r));
        return r;
    }

    private static void failsWith(Mono<?> result, HttpStatus status, String part) {
        StepVerifier.create(result).expectErrorSatisfies(e -> {
            assertThat(e).isInstanceOf(ApiException.class);
            assertThat(((ApiException) e).getStatus()).isEqualTo(status);
            assertThat(e.getMessage()).contains(part);
        }).verify();
    }

    @Test
    void suspendingAnAccountDeactivatesItAndWritesTheAuditAfterClosingTheReport() {
        account();

        StepVerifier.create(service.decide(ADMIN, 7L, "SUSPENDED", "  " + NOTE + "  "))
                .expectNext(view).verifyComplete();

        InOrder order = inOrder(admin);
        order.verify(admin).closeIfOpen(eq(7L), eq("SUSPENDED"), eq(ADMIN), eq(NOTE), any());
        order.verify(admin).deactivateAccount(42L);
        order.verify(admin).recordAudit(eq(7L), eq("SUSPENDED"), eq("ACCOUNT"), eq(42L), eq(ADMIN), eq(NOTE), any());
    }

    @Test
    void hidingASchoolSendsItOutOfSightAndAudits() {
        institution();

        StepVerifier.create(service.decide(ADMIN, 8L, "HIDDEN", NOTE)).expectNext(view).verifyComplete();

        verify(admin).hideInstitution(3L);
        verify(admin).recordAudit(eq(8L), eq("HIDDEN"), eq("INSTITUTION"), eq(3L), eq(ADMIN), eq(NOTE), any());
    }

    @Test
    void ignoringChangesNothingInTheTargetButIsStillAuditedAndNeedsNoNote() {
        account();

        StepVerifier.create(service.decide(ADMIN, 7L, "IGNORED", null)).expectNext(view).verifyComplete();

        verify(admin, never()).deactivateAccount(anyLong());
        verify(admin, never()).hideInstitution(anyLong());
        verify(admin).recordAudit(eq(7L), eq("IGNORED"), eq("ACCOUNT"), eq(42L), eq(ADMIN), eq(""), any());
    }

    @Test
    void aSchoolCannotBeSuspendedNorAPersonHidden() {
        institution();
        account();

        failsWith(service.decide(ADMIN, 8L, "SUSPENDED", NOTE), HttpStatus.BAD_REQUEST, "ocultada, não suspensa");
        failsWith(service.decide(ADMIN, 7L, "HIDDEN", NOTE), HttpStatus.BAD_REQUEST, "suspensa, não ocultada");
        verify(admin, never()).closeIfOpen(anyLong(), anyString(), anyLong(), anyString(), any());
        verify(admin, never()).recordAudit(anyLong(), anyString(), anyString(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void hidingAndSuspendingNeedARealNoteButIgnoringDoesNot() {
        failsWith(service.decide(ADMIN, 7L, "SUSPENDED", "curta"), HttpStatus.BAD_REQUEST, "pelo menos 10");
        failsWith(service.decide(ADMIN, 8L, "HIDDEN", "   "), HttpStatus.BAD_REQUEST, "pelo menos 10");
        failsWith(service.decide(ADMIN, 7L, "IGNORED", "x".repeat(501)), HttpStatus.BAD_REQUEST, "no máximo 500");
        verifyNoInteractions(reports);
        verifyNoInteractions(admin);
    }

    @Test
    void aReportThatDoesNotExistIsNotFound() {
        when(reports.findById(99L)).thenReturn(Mono.empty());

        failsWith(service.decide(ADMIN, 99L, "IGNORED", ""), HttpStatus.NOT_FOUND, "Denúncia");
    }

    @Test
    void aReportAlreadyDecidedIsAConflictAndWritesNothing() {
        account().setStatus("IGNORED");

        failsWith(service.decide(ADMIN, 7L, "SUSPENDED", NOTE), HttpStatus.CONFLICT, "já foi decidida");
        verify(admin, never()).closeIfOpen(anyLong(), anyString(), anyLong(), anyString(), any());
        verify(admin, never()).recordAudit(anyLong(), anyString(), anyString(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void whenAnotherAdministratorDecidesFirstNeitherTheEffectNorTheAuditIsWritten() {
        account();
        when(admin.closeIfOpen(anyLong(), anyString(), anyLong(), anyString(), any())).thenReturn(Mono.just(0L));

        failsWith(service.decide(ADMIN, 7L, "SUSPENDED", NOTE), HttpStatus.CONFLICT, "já foi decidida");
        verify(admin, never()).deactivateAccount(anyLong());
        verify(admin, never()).recordAudit(anyLong(), anyString(), anyString(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void anAdministratorCannotSuspendTheirOwnAccount() {
        account().setTargetAccountId(ADMIN);

        failsWith(service.decide(ADMIN, 7L, "SUSPENDED", NOTE), HttpStatus.BAD_REQUEST, "própria conta");
        verify(admin, never()).deactivateAccount(anyLong());
    }

    @Test
    void aFailingAuditFailsTheWholeDecision() {
        // A transacção desfaz o fecho e o efeito: o erro do trilho tem de chegar ao chamador, nunca ser engolido.
        account();
        when(admin.recordAudit(anyLong(), anyString(), anyString(), anyLong(), anyLong(), anyString(), any()))
                .thenReturn(Mono.error(new IllegalStateException("audit down")));

        StepVerifier.create(service.decide(ADMIN, 7L, "SUSPENDED", NOTE))
                .expectErrorMessage("audit down").verify();
    }

    @Test
    void theQueueRejectsAnUnknownStatusAndPassesAKnownOne() {
        when(admin.queue("OPEN")).thenReturn(Flux.just(view));
        when(admin.queue(null)).thenReturn(Flux.just(view));

        StepVerifier.create(service.queue("PENDING")).expectError(ApiException.class).verify();
        StepVerifier.create(service.queue(" OPEN ")).expectNext(view).verifyComplete();
        StepVerifier.create(service.queue(null)).expectNext(view).verifyComplete();
    }
}
