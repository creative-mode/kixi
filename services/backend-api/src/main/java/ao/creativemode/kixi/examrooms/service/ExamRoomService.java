package ao.creativemode.kixi.examrooms.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.examrooms.dto.ExamRoomParticipantRequest;
import ao.creativemode.kixi.examrooms.dto.ExamRoomParticipantResponse;
import ao.creativemode.kixi.examrooms.dto.ExamRoomRequest;
import ao.creativemode.kixi.examrooms.dto.ExamRoomResponse;
import ao.creativemode.kixi.examrooms.model.ExamRoom;
import ao.creativemode.kixi.examrooms.model.ExamRoomParticipant;
import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import ao.creativemode.kixi.examrooms.repository.ExamRoomParticipantRepository;
import ao.creativemode.kixi.examrooms.repository.ExamRoomRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.institutions.repository.EnrollmentRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ExamRoomService {
    private final ExamRoomRepository rooms;
    private final ExamRoomParticipantRepository participants;
    private final SimulationRepository simulations;
    private final AccountRepository accounts;
    private final StatementRepository statements;
    private final ClassRepository classes;
    private final EnrollmentRepository enrollments;

    public ExamRoomService(ExamRoomRepository rooms, ExamRoomParticipantRepository participants,
            SimulationRepository simulations, AccountRepository accounts, StatementRepository statements,
            ClassRepository classes, EnrollmentRepository enrollments) {
        this.rooms = rooms;
        this.participants = participants;
        this.simulations = simulations;
        this.accounts = accounts;
        this.statements = statements;
        this.classes = classes;
        this.enrollments = enrollments;
    }

    public Mono<ExamRoomResponse> create(ExamRoomRequest data, Long teacherAccountId) {
        if (!data.endsAt().isAfter(data.startsAt())) {
            return Mono.error(ApiException.badRequest("The exam room end must be after its start"));
        }
        return statements.findByIdAndDeletedAtIsNull(data.statementId())
                .switchIfEmpty(Mono.error(ApiException.notFound("Statement not found")))
                .then(accounts.findById(teacherAccountId)
                        .switchIfEmpty(Mono.error(ApiException.notFound("Account not found"))))
                .then(Mono.defer(() -> {
                    ExamRoom room = new ExamRoom();
                    room.setStatementId(data.statementId());
                    room.setTeacherAccountId(teacherAccountId);
                    room.setStartsAt(data.startsAt());
                    room.setEndsAt(data.endsAt());
                    room.setDurationMinutes(data.durationMinutes());
                    return rooms.save(room);
                })).map(ExamRoomService::toResponse);
    }

    public Flux<ExamRoomResponse> findMine(Long teacherAccountId) {
        return rooms.findByTeacherAccountIdOrderByCreatedAtDesc(teacherAccountId).map(ExamRoomService::toResponse);
    }

    public Flux<ExamRoomParticipantResponse> mineAsStudent(Long accountId) {
        return participants.findByAccountIdOrderByCreatedAtDesc(accountId)
                .map(ExamRoomService::toParticipantResponse);
    }

    public Mono<ExamRoomParticipantResponse> addParticipant(Long roomId, ExamRoomParticipantRequest request,
            Long caller, boolean admin) {
        return managedRoom(roomId, caller, admin).flatMap(room -> {
            Mono<List<Long>> targetAccounts = request.accountId() != null
                    ? accounts.findById(request.accountId()).switchIfEmpty(Mono.error(ApiException.notFound("Account not found")))
                            .map(account -> List.of(account.getId()))
                    : classes.findByIdAndDeletedAtIsNull(request.classId())
                            .switchIfEmpty(Mono.error(ApiException.notFound("Class not found")))
                            .then(enrollments.findAllByClassIdAndDeletedAtIsNull(request.classId())
                                    .map(enrollment -> enrollment.getAccountId()).collectList());
            return targetAccounts.flatMapMany(Flux::fromIterable)
                    .flatMap(accountId -> addOne(room, accountId))
                    .last()
                    .switchIfEmpty(Mono.error(ApiException.badRequest("The class has no active students")));
        });
    }

    private Mono<ExamRoomParticipantResponse> addOne(ExamRoom room, Long accountId) {
        return participants.findByExamRoomIdAndAccountId(room.getId(), accountId)
                .map(ExamRoomService::toParticipantResponse)
                .switchIfEmpty(Mono.defer(() -> {
                    ExamRoomParticipant participant = new ExamRoomParticipant();
                    participant.setExamRoomId(room.getId());
                    participant.setAccountId(accountId);
                    return participants.save(participant);
                }).map(ExamRoomService::toParticipantResponse))
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> ApiException.conflict("The account is already invited to this room"));
    }

    public Mono<ExamRoomParticipantResponse> join(Long roomId, Long accountId) {
        return rooms.findById(roomId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Exam room not found")))
                .flatMap(room -> participants.findByExamRoomIdAndAccountId(roomId, accountId)
                        .switchIfEmpty(Mono.error(ApiException.forbidden("The student is not invited to this room")))
                        .flatMap(participant -> {
                            if (room.getStatus() != ExamRoomStatus.OPEN && room.getStatus() != ExamRoomStatus.RUNNING) {
                                return Mono.error(ApiException.conflict("The exam room is not open"));
                            }
                            if (participant.getSimulationId() != null) {
                                return Mono.just(participant);
                            }
                            Simulation simulation = new Simulation();
                            simulation.setAccountId(accountId);
                            simulation.setStatementId(room.getStatementId());
                            simulation.setStartedAt(room.getStartsAt());
                            simulation.setStatus(SimulationStatus.IN_PROGRESS);
                            simulation.setExamRoomId(room.getId());
                            simulation.setExamRoomDurationMinutes(room.getDurationMinutes());
                            return simulations.save(simulation)
                                    .flatMap(saved -> {
                                        participant.setSimulationId(saved.getId());
                                        participant.setJoinedAt(LocalDateTime.now());
                                        return participants.save(participant);
                                    });
                        }).map(ExamRoomService::toParticipantResponse))
                .onErrorResume(DataIntegrityViolationException.class,
                        e -> participants.findByExamRoomIdAndAccountId(roomId, accountId)
                                .map(ExamRoomService::toParticipantResponse));
    }

    public Mono<ExamRoomResponse> transition(Long id, Long caller, boolean admin, ExamRoomStatus next) {
        return managedRoom(id, caller, admin).flatMap(room -> {
            boolean valid = (next == ExamRoomStatus.OPEN && room.getStatus() == ExamRoomStatus.DRAFT)
                    || (next == ExamRoomStatus.RUNNING && room.getStatus() == ExamRoomStatus.OPEN)
                    || (next == ExamRoomStatus.CLOSED && (room.getStatus() == ExamRoomStatus.OPEN
                            || room.getStatus() == ExamRoomStatus.RUNNING));
            if (!valid) return Mono.error(ApiException.conflict("Invalid exam room state transition"));
            return rooms.transition(id, room.getStatus(), next)
                    .filter(count -> count == 1)
                    .switchIfEmpty(Mono.error(ApiException.conflict("Exam room changed concurrently")))
                    .then(rooms.findById(id)).map(ExamRoomService::toResponse);
        });
    }

    private Mono<ExamRoom> managedRoom(Long id, Long caller, boolean admin) {
        Mono<ExamRoom> room = admin ? rooms.findById(id) : rooms.findByIdAndTeacherAccountId(id, caller);
        return room.switchIfEmpty(Mono.error(ApiException.forbidden("Only the room owner or ADMIN can manage it")));
    }

    private static ExamRoomResponse toResponse(ExamRoom room) {
        return new ExamRoomResponse(room.getId(), room.getStatementId(), room.getTeacherAccountId(), room.getStartsAt(),
                room.getEndsAt(), room.getDurationMinutes(), room.getStatus());
    }

    private static ExamRoomParticipantResponse toParticipantResponse(ExamRoomParticipant participant) {
        return new ExamRoomParticipantResponse(participant.getExamRoomId(), participant.getAccountId(),
                participant.getSimulationId(), participant.getJoinedAt());
    }
}
