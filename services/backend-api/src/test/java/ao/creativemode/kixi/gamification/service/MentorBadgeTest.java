package ao.creativemode.kixi.gamification.service;

import ao.creativemode.kixi.gamification.repository.BadgeRepository;
import ao.creativemode.kixi.gamification.repository.GamificationRepository;
import ao.creativemode.kixi.gamification.repository.UserBadgeRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** «Mentor da turma» (kixi#729): só à terceira resposta aceite. */
class MentorBadgeTest {

    private final GamificationRepository stats = mock(GamificationRepository.class);
    private final GamificationService service = new GamificationService(
            stats, mock(BadgeRepository.class), mock(UserBadgeRepository.class), Clock.systemDefaultZone());

    @Test
    void thirdAcceptedAnswerUnlocksTheBadge() {
        when(stats.unlockBadge(eq(5L), eq("MENTOR"), isNull(), any(LocalDateTime.class))).thenReturn(Mono.just(1));
        StepVerifier.create(service.answerAccepted(5L, 3)).verifyComplete();
        verify(stats).unlockBadge(eq(5L), eq("MENTOR"), isNull(), any(LocalDateTime.class));
    }

    @Test
    void fewerThanThreeDoesNothing() {
        StepVerifier.create(service.answerAccepted(5L, 2)).verifyComplete();
        verify(stats, never()).unlockBadge(any(), any(), any(), any());
    }
}
