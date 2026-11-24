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
    private ao.creativemode.kixi.institutions.repository.FriendshipRepository friendships;
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
        friendships = mock(ao.creativemode.kixi.institutions.repository.FriendshipRepository.class);
        when(friendships.findBetween(anyLong(), anyLong())).thenReturn(Mono.empty());
        when(blocks.findByAccountLowAndAccountHigh(anyLong(), anyLong())).thenReturn(Mono.empty());
        service = new ModerationService(accounts, institutions, blocks, reports, rateLimiter, friendships,
                mock(ao.creativemode.kixi.shared.service.DisplayNameReader.class));
    }

    private static ao.creativemode.kixi.moderation.model.ModerationBlock blockRow(Long blockedBy) {
        var row = new ao.creativemode.kixi.moderation.model.ModerationBlock();
        row.setAccountLow(12L);
        row.setAccountHigh(34L);
        row.setBlockedBy(blockedBy);
        return row;
    }

    @Test
    void blockStoresAnOrderedPairWithTheBlockerAndIsIdempotent() {
        when(blocks.insertPair(12L, 34L, 34L)).thenReturn(Mono.empty());
        when(blocks.findByAccountLowAndAccountHigh(12L, 34L)).thenReturn(Mono.just(blockRow(34L)));

        var first = service.block(34L, 12L).block();
        var again = service.block(34L, 12L).block();

        assertThat(first.blocked()).isTrue();
        assertThat(first.blockedByMe()).isTrue();
        assertThat(again).isEqualTo(first);
        verify(blocks, times(2)).insertPair(12L, 34L, 34L);
    }

    @Test
    void blockingAFriendEndsTheFriendship() {
        var friendship = ao.creativemode.kixi.institutions.model.Friendship.between(12L, 34L, 12L);
        friendship.accept();
        when(blocks.insertPair(12L, 34L, 34L)).thenReturn(Mono.empty());
        when(blocks.findByAccountLowAndAccountHigh(12L, 34L)).thenReturn(Mono.just(blockRow(34L)));
        when(friendships.findBetween(12L, 34L)).thenReturn(Mono.just(friendship));
        when(friendships.save(any(ao.creativemode.kixi.institutions.model.Friendship.class)))
                .thenAnswer(call -> Mono.just(call.getArgument(0)));

        service.block(34L, 12L).block();

        assertThat(friendship.getDeletedAt()).isNotNull();
    }

    @Test
    void unblockRemovesTheBlockOnlyForWhoPutIt() {
        when(blocks.findByAccountLowAndAccountHigh(12L, 34L)).thenReturn(Mono.just(blockRow(34L)));
        when(blocks.deletePair(12L, 34L)).thenReturn(Mono.just(1));

        var response = service.unblock(34L, 12L).block();

        assertThat(response.blocked()).isFalse();
        verify(blocks).deletePair(12L, 34L);
    }

    @Test
    void theBlockedPersonCannotLiftTheBlock() {
        when(blocks.findByAccountLowAndAccountHigh(12L, 34L)).thenReturn(Mono.just(blockRow(34L)));

        StepVerifier.create(service.unblock(12L, 34L))
                .expectErrorSatisfies(error ->
                        assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verify(blocks, never()).deletePair(anyLong(), anyLong());
    }

    @Test
    void unblockingWhatIsNotBlockedIsAHarmlessNoop() {
        var response = service.unblock(34L, 12L).block();

        assertThat(response.blocked()).isFalse();
        verify(blocks, never()).deletePair(anyLong(), anyLong());
    }

    @Test
    void theLegacyToggleStillBlocksThenUnblocks() {
        var stored = new java.util.concurrent.atomic.AtomicReference<ao.creativemode.kixi.moderation.model.ModerationBlock>();
        when(blocks.insertPair(12L, 34L, 34L)).thenAnswer(call -> {
            stored.set(blockRow(34L));
            return Mono.empty();
        });
        when(blocks.findByAccountLowAndAccountHigh(12L, 34L))
                .thenAnswer(call -> Mono.justOrEmpty(stored.get()));
        when(blocks.deletePair(12L, 34L)).thenAnswer(call -> {
            stored.set(null);
            return Mono.just(1);
        });

        assertThat(service.toggleBlock(34L, 12L).block().blocked()).isTrue();
        assertThat(service.toggleBlock(34L, 12L).block().blocked()).isFalse();
    }

    @Test
    void theLegacyToggleCannotBeUsedByTheBlockedPersonToLiftTheBlock() {
        when(blocks.findByAccountLowAndAccountHigh(12L, 34L)).thenReturn(Mono.just(blockRow(34L)));

        StepVerifier.create(service.toggleBlock(12L, 34L))
                .expectErrorSatisfies(error ->
                        assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
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
        when(blocks.insertPair(12L, 34L, 34L)).thenReturn(Mono.error(new DataIntegrityViolationException("duplicate")));

        StepVerifier.create(service.block(34L, 12L))
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
