package ao.creativemode.kixi.examrooms.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;

import ao.creativemode.kixi.examrooms.dto.ExamRoomLiveResponse;
import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import ao.creativemode.kixi.examrooms.service.ExamRoomMonitoringService;
import ao.creativemode.kixi.examrooms.service.ExamRoomService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class ExamRoomControllerSseTest {
    private WebTestClient client;

    private ExamRoomService service;

    private CurrentAccountService current;

    private ExamRoomMonitoringService monitoring;

    @BeforeEach
    void setUp() {
        service = org.mockito.Mockito.mock(ExamRoomService.class);
        current = org.mockito.Mockito.mock(CurrentAccountService.class);
        monitoring = org.mockito.Mockito.mock(ExamRoomMonitoringService.class);
        client = WebTestClient.bindToController(new ExamRoomController(service, current, monitoring)).build();
        when(current.requiredAccountId()).thenReturn(Mono.just(99L));
        when(current.hasAnyRole("ADMIN")).thenReturn(Mono.just(false));
        when(current.hasAnyRole("TEACHER")).thenReturn(Mono.just(true));
    }

    @Test
    void servesAndCancelsOneHundredTwentyConcurrentLiveStreams() {
        ExamRoomLiveResponse snapshot = snapshotWithParticipants(120);
        AtomicInteger emitted = new AtomicInteger();
        AtomicInteger openStreams = new AtomicInteger();
        AtomicInteger maximumOpenStreams = new AtomicInteger();
        when(monitoring.live(7L, 99L, false)).thenReturn(Mono.just(snapshot));
        when(monitoring.stream(7L, 99L, false)).thenAnswer(ignored -> {
            int open = openStreams.incrementAndGet();
            maximumOpenStreams.updateAndGet(previous -> Math.max(previous, open));
            return Flux.range(0, 120)
                    .map(ignoredEvent -> snapshot)
                    .doOnNext(ignoredEvent -> emitted.incrementAndGet())
                    .doFinally(signal -> openStreams.decrementAndGet());
        });

        List<String> bodies = Flux.range(0, 120)
                .flatMap(ignored -> Mono.fromCallable(() -> readTwoEvents())
                        .subscribeOn(Schedulers.boundedElastic()), 120)
                .collectList()
                .block();

        assertThat(bodies).hasSize(120);
        assertThat(bodies).allSatisfy(body -> {
            assertThat(body).contains("event='progress'").contains("roomId=7");
            assertThat(body).contains("totalParticipants=120");
        });
        assertThat(emitted).hasValue(240);
        assertThat(openStreams).hasValue(0);
        assertThat(maximumOpenStreams.get()).isGreaterThan(0);
    }

    private String readTwoEvents() {
        return client
                .get()
                .uri("/api/v1/exam-rooms/7/live")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(ServerSentEvent.class)
                .getResponseBody()
                .take(2)
                .collectList()
                .map(events -> events.toString())
                .block();
    }

    private ExamRoomLiveResponse snapshotWithParticipants(int count) {
        List<ExamRoomLiveResponse.ParticipantProgress> participants =
                java.util.stream.IntStream.rangeClosed(1, count)
                        .mapToObj(account -> new ExamRoomLiveResponse.ParticipantProgress(
                                (long) account, (long) account, LocalDateTime.now(), "IN_PROGRESS", 1, 2))
                        .toList();
        return new ExamRoomLiveResponse(7L, ExamRoomStatus.RUNNING, LocalDateTime.now(),
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(30),
                1800, count, count, 0, 50d, participants);
    }

}
