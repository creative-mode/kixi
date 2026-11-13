package ao.creativemode.kixi.examrooms.service;

import static org.mockito.Mockito.*;

import ao.creativemode.kixi.examrooms.repository.ExamRoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

class ExamRoomExpirationJobTest {
    @Test
    void aLongRoomDoesNotCancelTheRoomOrTheSweep() {
        VirtualTimeScheduler timer = VirtualTimeScheduler.getOrSet();
        try {
            ExamRoomRepository rooms = mock(ExamRoomRepository.class);
            ExamRoomService service = mock(ExamRoomService.class);
            Clock clock = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneId.of("Africa/Luanda"));
            LocalDateTime now = LocalDateTime.now(clock);
            var instant = now.atZone(clock.getZone()).toOffsetDateTime();
            when(rooms.findExpiredIds(instant, 0)).thenReturn(Flux.just(1L, 2L));
            when(rooms.findExpiredIds(instant, 2)).thenReturn(Flux.empty());
            when(service.closeIfExpired(1L, now)).thenReturn(Mono.delay(Duration.ofMinutes(11)).then(Mono.empty()));
            when(service.closeIfExpired(2L, now)).thenReturn(Mono.empty());
            ExamRoomExpirationJob job = new ExamRoomExpirationJob(rooms, service, clock);

            job.closeExpiredRooms();
            timer.advanceTimeBy(Duration.ofMinutes(11));

            verify(service).closeIfExpired(2L, now);
            verify(rooms).findExpiredIds(instant, 2);
        } finally {
            VirtualTimeScheduler.reset();
        }
    }

    @Test
    void schedulerDoesNotStartAnotherSweepWhileOneIsRunning() {
        ExamRoomRepository rooms = mock(ExamRoomRepository.class);
        ExamRoomService service = mock(ExamRoomService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneId.of("Africa/Luanda"));
        var instant = clock.instant().atZone(clock.getZone()).toOffsetDateTime();
        when(rooms.findExpiredIds(instant, 0)).thenReturn(Flux.never());
        ExamRoomExpirationJob job = new ExamRoomExpirationJob(rooms, service, clock);

        job.closeExpiredRooms();
        job.closeExpiredRooms();

        verify(rooms, times(1)).findExpiredIds(instant, 0);
        verifyNoInteractions(service);
    }

    @Test
    void advancesPastFailedRoomsAndRetriesThemOnTheNextSweep() {
        ExamRoomRepository rooms = mock(ExamRoomRepository.class);
        ExamRoomService service = mock(ExamRoomService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneId.of("Africa/Luanda"));
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 11, 0);
        var instant = now.atZone(clock.getZone()).toOffsetDateTime();
        when(rooms.findExpiredIds(instant, 0)).thenReturn(Flux.just(1L, 2L));
        when(rooms.findExpiredIds(instant, 2)).thenReturn(Flux.empty());
        when(service.closeIfExpired(1L, now)).thenReturn(Mono.error(new IllegalStateException()));
        when(service.closeIfExpired(2L, now)).thenReturn(Mono.empty());

        ExamRoomExpirationJob job = new ExamRoomExpirationJob(rooms, service, clock);
        job.sweep().block();
        job.sweep().block();

        verify(service, times(2)).closeIfExpired(1L, now);
        verify(service, times(2)).closeIfExpired(2L, now);
        verify(rooms, times(2)).findExpiredIds(instant, 2);
    }
}
