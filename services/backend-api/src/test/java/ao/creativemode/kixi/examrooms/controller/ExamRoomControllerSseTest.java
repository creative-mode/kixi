package ao.creativemode.kixi.examrooms.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.web.reactive.config.EnableWebFlux;

import ao.creativemode.kixi.examrooms.dto.ExamRoomLiveResponse;
import ao.creativemode.kixi.examrooms.model.ExamRoom;
import ao.creativemode.kixi.examrooms.model.ExamRoomParticipant;
import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import ao.creativemode.kixi.examrooms.repository.ExamRoomParticipantRepository;
import ao.creativemode.kixi.examrooms.repository.ExamRoomRepository;
import ao.creativemode.kixi.examrooms.service.ExamRoomMonitoringService;
import ao.creativemode.kixi.examrooms.service.ExamRoomService;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@SpringJUnitConfig(ExamRoomControllerSseTest.TestConfiguration.class)
class ExamRoomControllerSseTest {
    private static final int STREAMS = 120;

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private StreamBarrier barrier;
    @Autowired
    private ExamRoomRepository rooms;
    @Autowired
    private QuestionRepository questions;
    @Autowired
    private SimulationRepository simulations;
    @Autowired
    private ExamRoomMonitoringService monitoring;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        ExamRoom room = new ExamRoom();
        room.setId(7L);
        room.setStatementId(5L);
        room.setTeacherAccountId(99L);
        room.setStatus(ExamRoomStatus.RUNNING);
        room.setStartsAt(LocalDateTime.now().minusMinutes(10));
        room.setEndsAt(LocalDateTime.now().plusMinutes(30));
        when(rooms.findByIdAndTeacherAccountId(7L, 99L)).thenReturn(Mono.just(room));
        when(questions.findAllByStatementIdAndDeletedAtIsNull(5L))
                .thenReturn(Flux.just(new ao.creativemode.kixi.exams.model.Question()));
        when(simulations.findByExamRoomIdAndDeletedAtIsNull(7L)).thenReturn(Flux.empty());
        client = WebTestClient.bindToApplicationContext(applicationContext)
                .configureClient().responseTimeout(Duration.ofSeconds(30)).build();
    }

    @Test
    void servesOneHundredTwentyConcurrentRealSseStreamsWithPollingAndCancellation() throws Exception {
        barrier.openStreamPhase();
        List<FluxExchangeResult<ServerSentEvent<ExamRoomLiveResponse>>> responses = Flux.range(0, STREAMS)
                .flatMap(ignored -> Mono.fromCallable(() -> client.get()
                        .uri("/api/v1/exam-rooms/7/live")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange()
                        .expectStatus().isOk()
                        .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                        .returnResult(new ParameterizedTypeReference<ServerSentEvent<ExamRoomLiveResponse>>() { }))
                        .subscribeOn(Schedulers.boundedElastic()), STREAMS)
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(responses).hasSize(STREAMS);

        List<List<ServerSentEvent<ExamRoomLiveResponse>>> events = Flux.fromIterable(responses)
                .flatMap(response -> response.getResponseBody()
                        .take(2)
                        .collectList(), STREAMS)
                .collectList()
                .block(Duration.ofSeconds(45));

        assertThat(events).hasSize(STREAMS);
        assertThat(events).allSatisfy(stream -> {
            assertThat(stream).hasSize(2);
            assertThat(stream).allSatisfy(event -> {
                assertThat(event.event()).isEqualTo("progress");
                assertThat(event.data()).isInstanceOf(ExamRoomLiveResponse.class);
                assertThat(((ExamRoomLiveResponse) event.data()).totalParticipants()).isEqualTo(STREAMS);
            });
        });
        assertThat(barrier.maximumActive()).isGreaterThan(100);
        assertThat(barrier.snapshotCalls()).isGreaterThanOrEqualTo(STREAMS * 2);
        assertThat(monitoring.activeStreams()).isZero();
    }

    @Configuration
    @EnableWebFlux
    static class TestConfiguration {
        @Bean
        StreamBarrier streamBarrier() {
            return new StreamBarrier();
        }

        @Bean
        ExamRoomRepository rooms() {
            return mock(ExamRoomRepository.class);
        }

        @Bean
        ExamRoomParticipantRepository participants(StreamBarrier barrier) {
            return barrier.participants;
        }

        @Bean
        SimulationRepository simulations() {
            return mock(SimulationRepository.class);
        }

        @Bean
        SimulationAnswerRepository answers() {
            return mock(SimulationAnswerRepository.class);
        }

        @Bean
        QuestionRepository questions() {
            return mock(QuestionRepository.class);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-11T13:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        ExamRoomMonitoringService monitoring(ExamRoomRepository rooms,
                ExamRoomParticipantRepository participants, SimulationRepository simulations,
                SimulationAnswerRepository answers, QuestionRepository questions, Clock clock) {
            return new ExamRoomMonitoringService(rooms, participants, simulations, answers, questions, clock);
        }

        @Bean
        ExamRoomService service() {
            return mock(ExamRoomService.class);
        }

        @Bean
        CurrentAccountService current() {
            CurrentAccountService current = mock(CurrentAccountService.class);
            when(current.requiredAccountId()).thenReturn(Mono.just(99L));
            when(current.hasAnyRole("ADMIN")).thenReturn(Mono.just(false));
            when(current.hasAnyRole("TEACHER")).thenReturn(Mono.just(true));
            return current;
        }

        @Bean
        ExamRoomController controller(ExamRoomService service, CurrentAccountService current,
                ExamRoomMonitoringService monitoring) {
            return new ExamRoomController(service, current, monitoring);
        }
    }

    static class StreamBarrier {
        private final ExamRoomParticipantRepository participants = mock(ExamRoomParticipantRepository.class);
        private final AtomicBoolean streamPhase = new AtomicBoolean();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximum = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(STREAMS);
        private final CompletableFuture<Void> release = new CompletableFuture<>();
        private final List<ExamRoomParticipant> rows = participantRows();

        StreamBarrier() {
            when(participants.findByExamRoomId(7L)).thenAnswer(ignored -> {
                int call = calls.incrementAndGet();
                if (!streamPhase.get() || call <= STREAMS * 2) {
                    return Flux.fromIterable(rows);
                }
                int current = active.incrementAndGet();
                maximum.updateAndGet(previous -> Math.max(previous, current));
                entered.countDown();
                if (entered.getCount() == 0) {
                    release.complete(null);
                }
                return Mono.fromFuture(release)
                        .thenMany(Flux.fromIterable(rows))
                        .doFinally(signal -> active.decrementAndGet());
            });
        }

        void openStreamPhase() {
            streamPhase.set(true);
        }

        int maximumActive() {
            return maximum.get();
        }

        int snapshotCalls() {
            return calls.get();
        }

        private static List<ExamRoomParticipant> participantRows() {
            LocalDateTime now = LocalDateTime.of(2026, 10, 11, 14, 0);
            return java.util.stream.IntStream.rangeClosed(1, STREAMS).mapToObj(account -> {
                ExamRoomParticipant participant = new ExamRoomParticipant();
                participant.setExamRoomId(7L);
                participant.setAccountId((long) account);
                participant.setSimulationId((long) account);
                participant.setJoinedAt(now.minusMinutes(10));
                return participant;
            }).toList();
        }
    }
}
