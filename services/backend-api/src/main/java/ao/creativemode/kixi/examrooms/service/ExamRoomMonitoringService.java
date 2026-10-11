package ao.creativemode.kixi.examrooms.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import ao.creativemode.kixi.examrooms.dto.ExamRoomGradeResponse;
import ao.creativemode.kixi.examrooms.dto.ExamRoomLiveResponse;
import ao.creativemode.kixi.examrooms.model.ExamRoom;
import ao.creativemode.kixi.examrooms.model.ExamRoomParticipant;
import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import ao.creativemode.kixi.examrooms.repository.ExamRoomParticipantRepository;
import ao.creativemode.kixi.examrooms.repository.ExamRoomRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.model.User;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.UserRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ExamRoomMonitoringService {
    private final ExamRoomRepository rooms;
    private final ExamRoomParticipantRepository participants;
    private final SimulationRepository simulations;
    private final SimulationAnswerRepository answers;
    private final QuestionRepository questions;
    private final AccountRepository accounts;
    private final UserRepository users;
    private final Clock clock;
    private final AtomicInteger activeStreams = new AtomicInteger();

    public ExamRoomMonitoringService(ExamRoomRepository rooms, ExamRoomParticipantRepository participants,
            SimulationRepository simulations, SimulationAnswerRepository answers, QuestionRepository questions,
            Clock clock) {
        this(rooms, participants, simulations, answers, questions, clock, null, null);
    }

    @Autowired
    public ExamRoomMonitoringService(ExamRoomRepository rooms, ExamRoomParticipantRepository participants,
            SimulationRepository simulations, SimulationAnswerRepository answers, QuestionRepository questions,
            Clock clock, AccountRepository accounts, UserRepository users) {
        this.rooms = rooms;
        this.participants = participants;
        this.simulations = simulations;
        this.answers = answers;
        this.questions = questions;
        this.clock = clock;
        this.accounts = accounts;
        this.users = users;
    }

    public Mono<ExamRoomLiveResponse> live(Long roomId, Long caller, boolean admin) {
        return managedRoom(roomId, caller, admin).flatMap(this::snapshot);
    }

    /**
     * The grades of every participant (#188). The simulations, their answers and
     * the question count are loaded in batch for the whole room and the grades
     * worked out in memory: before, each participant cost a simulation lookup, an
     * answer query and a question count of their own.
     */
    public Mono<List<ExamRoomGradeResponse>> grades(Long roomId, Long caller, boolean admin) {
        return managedRoom(roomId, caller, admin)
                .then(participants.findByExamRoomId(roomId).collectList())
                .flatMap(rows -> Mono.zip(displayNames(rows), gradingData(rows))
                        .map(tuple -> rows.stream()
                                .map(participant -> grade(participant, tuple.getT1(), tuple.getT2()))
                                .toList()));
    }

    /**
     * Checks that the caller may watch the room, without building a snapshot:
     * the live endpoint authorizes with this before it starts the stream, and the
     * stream builds the first snapshot itself (#188).
     */
    public Mono<ExamRoom> authorize(Long roomId, Long caller, boolean admin) {
        return managedRoom(roomId, caller, admin);
    }

    public Flux<ExamRoomLiveResponse> stream(Long roomId, Long caller, boolean admin) {
        return authorize(roomId, caller, admin).flatMapMany(room -> stream(room, caller, admin));
    }

    /**
     * The live stream of a room already authorized by {@link #authorize}. The
     * first tick snapshots that room; the following ones reload it, so a change
     * of owner or status still reaches the stream.
     */
    public Flux<ExamRoomLiveResponse> stream(ExamRoom authorized, Long caller, boolean admin) {
        return Flux.interval(Duration.ZERO, Duration.ofSeconds(2))
                .onBackpressureLatest()
                .concatMap(tick -> tick == 0 ? snapshot(authorized) : live(authorized.getId(), caller, admin))
                .takeUntil(value -> value.status() == ExamRoomStatus.CLOSED)
                .doOnSubscribe(ignored -> activeStreams.incrementAndGet())
                .doFinally(ignored -> activeStreams.decrementAndGet());
    }

    public int activeStreams() {
        return activeStreams.get();
    }

    private Mono<ExamRoomLiveResponse> snapshot(ExamRoom room) {
        LocalDateTime now = LocalDateTime.now(clock);
        return Mono.zip(participants.findByExamRoomId(room.getId()).collectList(),
                questions.findAllByStatementIdAndDeletedAtIsNull(room.getStatementId()).count(),
                simulations.findByExamRoomIdAndDeletedAtIsNull(room.getId()).collectList())
                .flatMap(tuple -> displayNames(tuple.getT1()).flatMap(names -> {
                    List<ExamRoomParticipant> roomParticipants = tuple.getT1();
                    List<Simulation> roomSimulations = tuple.getT3();
                    if (roomSimulations.isEmpty()) {
                        return Mono.just(buildSnapshot(room, now, roomParticipants, Map.of(), tuple.getT2().intValue(), List.of(), names));
                    }
                    List<Long> ids = roomSimulations.stream().map(Simulation::getId).toList();
                    return answers.findAllBySimulationIdInAndDeletedAtIsNull(ids).collectList()
                            .map(allAnswers -> buildSnapshot(room, now, roomParticipants,
                                    roomSimulations.stream().collect(Collectors.toMap(Simulation::getId, Function.identity())),
                                    tuple.getT2().intValue(), allAnswers, names));
                }));
    }

    private ExamRoomLiveResponse buildSnapshot(ExamRoom room, LocalDateTime now,
            List<ExamRoomParticipant> roomParticipants, Map<Long, Simulation> byId, int totalQuestions) {
        return buildSnapshot(room, now, roomParticipants, byId, totalQuestions, List.of(), Map.of());
    }

    private ExamRoomLiveResponse buildSnapshot(ExamRoom room, LocalDateTime now,
            List<ExamRoomParticipant> roomParticipants, Map<Long, Simulation> byId, int totalQuestions,
            List<SimulationAnswer> allAnswers, Map<Long, String> names) {
        Map<Long, Long> answerCounts = allAnswers.stream().collect(Collectors.groupingBy(
                SimulationAnswer::getSimulationId, Collectors.filtering(answer -> answer.getSelectedOptionId() != null
                        || (answer.getAnswerText() != null && !answer.getAnswerText().isBlank()),
                        Collectors.counting())));
        List<ExamRoomLiveResponse.ParticipantProgress> progress = roomParticipants.stream().map(participant -> {
            Simulation simulation = byId.get(participant.getSimulationId());
            String status = simulation == null ? "NOT_STARTED" : simulation.getStatus().name();
            int answered = answerCounts.getOrDefault(participant.getSimulationId(), 0L).intValue();
            return new ExamRoomLiveResponse.ParticipantProgress(participant.getAccountId(), names.get(participant.getAccountId()),
                    participant.getSimulationId(),
                    participant.getJoinedAt(), status, answered, totalQuestions);
        }).toList();
        long joined = progress.stream().filter(item -> item.joinedAt() != null).count();
        long submitted = progress.stream().filter(item -> "FINISHED".equals(item.status())).count();
        int answered = progress.stream().mapToInt(ExamRoomLiveResponse.ParticipantProgress::answeredQuestions).sum();
        long answerSlots = (long) joined * totalQuestions;
        double percentage = answerSlots == 0 ? 0d : (answered * 100d) / answerSlots;
        long remaining = room.getEndsAt() == null ? 0 : Math.max(0, Duration.between(now, room.getEndsAt()).getSeconds());
        return new ExamRoomLiveResponse(room.getId(), room.getStatus(), now, room.getStartsAt(), room.getEndsAt(),
                remaining, progress.size(), (int) joined, (int) submitted, percentage, progress);
    }

    /** What {@link #grades} needs for every participant at once. */
    private record GradingData(Map<Long, Simulation> simulations, Map<Long, List<SimulationAnswer>> answers,
            Map<Long, Long> questionCounts) {
        static final GradingData EMPTY = new GradingData(Map.of(), Map.of(), Map.of());
    }

    private Mono<GradingData> gradingData(List<ExamRoomParticipant> rows) {
        List<Long> ids = rows.stream().map(ExamRoomParticipant::getSimulationId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Mono.just(GradingData.EMPTY);
        }
        return simulations.findAllById(ids)
                .filter(simulation -> simulation.getDeletedAt() == null)
                .collectMap(Simulation::getId, Function.identity())
                .flatMap(byId -> {
                    if (byId.size() < ids.size()) {
                        // As before: a participant pointing at a missing simulation fails the call.
                        return Mono.error(ApiException.notFound("Simulation not found"));
                    }
                    List<Long> statementIds = byId.values().stream().map(Simulation::getStatementId)
                            .filter(java.util.Objects::nonNull).distinct().toList();
                    return Mono.zip(
                            answers.findAllBySimulationIdInAndDeletedAtIsNull(ids)
                                    .collect(Collectors.groupingBy(SimulationAnswer::getSimulationId)),
                            Flux.fromIterable(statementIds)
                                    .concatMap(statementId -> questions.findAllByStatementIdAndDeletedAtIsNull(statementId)
                                            .count()
                                            .map(count -> Map.entry(statementId, count)))
                                    .collectMap(Map.Entry::getKey, Map.Entry::getValue))
                            .map(tuple -> new GradingData(byId, tuple.getT1(), tuple.getT2()));
                });
    }

    private ExamRoomGradeResponse grade(ExamRoomParticipant participant, Map<Long, String> names, GradingData data) {
        if (participant.getSimulationId() == null) {
            return new ExamRoomGradeResponse(participant.getAccountId(), names.get(participant.getAccountId()), null,
                    "NOT_STARTED",
                    null, 0, 0, 0, null);
        }
        Simulation simulation = data.simulations().get(participant.getSimulationId());
        List<SimulationAnswer> rows = data.answers().getOrDefault(simulation.getId(), List.of());
        int correct = (int) rows.stream().filter(answer -> Boolean.TRUE.equals(answer.getIsCorrect())).count();
        int pending = (int) rows.stream().filter(answer -> answer.getReviewStatus() != null
                && answer.getReviewStatus().name().equals("PENDING_REVIEW")).count();
        int totalQuestions = data.questionCounts().getOrDefault(simulation.getStatementId(), 0L).intValue();
        return new ExamRoomGradeResponse(participant.getAccountId(), names.get(participant.getAccountId()),
                simulation.getId(),
                simulation.getStatus().name(), simulation.getFinalScore(), correct,
                totalQuestions, pending, simulation.getFinishedAt());
    }

    private Mono<Map<Long, String>> displayNames(List<ExamRoomParticipant> rows) {
        List<Long> ids = rows.stream().map(ExamRoomParticipant::getAccountId).distinct().toList();
        if (ids.isEmpty() || accounts == null || users == null) {
            return Mono.just(rows.stream().collect(Collectors.toMap(ExamRoomParticipant::getAccountId,
                    participant -> String.valueOf(participant.getAccountId()), (left, right) -> left)));
        }
        return Mono.zip(accounts.findAllByIdInAndDeletedAtIsNull(ids).collectList(),
                users.findByAccountIdInAndDeletedAtIsNull(ids).collectList())
                .map(tuple -> {
                    Map<Long, String> usernames = tuple.getT1().stream().collect(Collectors.toMap(Account::getId,
                            Account::getUsername));
                    Map<Long, User> profiles = tuple.getT2().stream().collect(Collectors.toMap(User::getAccountId,
                            Function.identity(), (left, right) -> left));
                    return ids.stream().collect(Collectors.toMap(id -> id, id -> {
                        User profile = profiles.get(id);
                        if (profile != null) {
                            String name = java.util.stream.Stream.of(profile.getFirstName(), profile.getLastName())
                                    .filter(value -> value != null && !value.isBlank())
                                    .collect(Collectors.joining(" "));
                            if (!name.isBlank()) return name;
                        }
                        return usernames.getOrDefault(id, String.valueOf(id));
                    }));
                });
    }

    private Mono<ExamRoom> managedRoom(Long roomId, Long caller, boolean admin) {
        Mono<ExamRoom> room = admin ? rooms.findById(roomId) : rooms.findByIdAndTeacherAccountId(roomId, caller);
        return room.switchIfEmpty(Mono.error(ApiException.forbidden("Only the room owner or ADMIN can view it")));
    }
}
