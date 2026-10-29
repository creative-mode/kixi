package ao.creativemode.kixi.moderation.service;

import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.moderation.dto.ReportRequest;
import ao.creativemode.kixi.moderation.repository.ModerationBlockRepository;
import ao.creativemode.kixi.moderation.repository.ModerationReportRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.ApiAbuseRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ModerationServiceTest {
    private AccountRepository accounts;
    private InstitutionRepository institutions;
    private ModerationBlockRepository blocks;
    private ModerationReportRepository reports;
    private ApiAbuseRateLimiter rateLimiter;
    private ModerationService service;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        institutions = mock(InstitutionRepository.class);
        blocks = mock(ModerationBlockRepository.class);
        reports = mock(ModerationReportRepository.class);
        rateLimiter = mock(ApiAbuseRateLimiter.class);
        when(rateLimiter.checkReport(anyLong())).thenReturn(Mono.empty());
        when(accounts.findByIdAndDeletedAtIsNull(anyLong())).thenReturn(Mono.just(new Account()));
        service = new ModerationService(accounts, institutions, blocks, reports, rateLimiter);
    }

    @Test
    void toggleStoresAnOrderedPairAndReturnsBlocked() {
        when(blocks.togglePair(12L, 34L)).thenReturn(Mono.just(true));

        var response = service.toggleBlock(34L, 12L).block();

        assertThat(response.blocked()).isTrue();
        verify(blocks).togglePair(12L, 34L);
    }

    @Test
    void cannotBlockSelf() {
        StepVerifier.create(service.toggleBlock(12L, 12L))
                .expectErrorMessage("You cannot block yourself")
                .verify();
        verifyNoInteractions(blocks);
    }

    @Test
    void aConcurrentDoubleInsertComesBackAsAConflictNotA500() {
        // Two requests racing to block the same pair for the first time can both
        // reach the INSERT despite the advisory lock (see ModerationBlockRepository);
        // the unique constraint then rejects the loser with this exception.
        when(blocks.togglePair(12L, 34L)).thenReturn(Mono.error(new DataIntegrityViolationException("duplicate")));

        StepVerifier.create(service.toggleBlock(34L, 12L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                })
                .verify();
    }

    @Test
    void otherReasonRequiresANote() {
        StepVerifier.create(service.reportAccount(12L, 34L, new ReportRequest("OTHER", "  ")))
                .expectErrorMessage("Write the reason")
                .verify();
        verifyNoInteractions(reports);
    }

    @Test
    void cannotReportSelf() {
        StepVerifier.create(service.reportAccount(12L, 12L, new ReportRequest("OTHER", "spam")))
                .expectErrorMessage("You cannot report yourself")
                .verify();
        verifyNoInteractions(reports);
    }
}
