package ao.creativemode.kixi.examrooms.service;

import java.time.LocalDateTime;
import java.time.Clock;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.examrooms.dto.ExamRoomParticipantRequest;
import ao.creativemode.kixi.examrooms.dto.ExamRoomParticipantResponse;
import ao.creativemode.kixi.examrooms.dto.ExamRoomRequest;
import ao.creativemode.kixi.examrooms.dto.ExamRoomResponse;
import ao.creativemode.kixi.examrooms.dto.ExamRoomStudentResponse;
import ao.creativemode.kixi.examrooms.dto.ExamRoomQuestionResponse;
import ao.creativemode.kixi.examrooms.model.ExamRoom;
import ao.creativemode.kixi.examrooms.model.ExamRoomParticipant;
import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import ao.creativemode.kixi.examrooms.repository.ExamRoomParticipantRepository;
import ao.creativemode.kixi.examrooms.repository.ExamRoomRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.dto.questionoption.QuestionOptionResponse;
import ao.creativemode.kixi.exams.service.StatementWriteAccessService;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.AccountRoleRepository;
import ao.creativemode.kixi.identity.repository.RoleRepository;
import ao.creativemode.kixi.institutions.repository.EnrollmentRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.ExamRoomAccess;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ExamRoomService implements ExamRoomAccess {
    private final ExamRoomRepository rooms;
    private final ExamRoomParticipantRepository participants;
    private final SimulationRepository simulations;
    private final AccountRepository accounts;
    private final StatementRepository statements;
    private final ClassRepository classes;
    private final EnrollmentRepository enrollments;
    private final StatementWriteAccessService statementWriteAccess;
    private final AccountRoleRepository accountRoles;
    private final RoleRepository roles;
    private final QuestionRepository questions;
    private final QuestionOptionRepository options;
    private final Clock clock;

    public ExamRoomService(ExamRoomRepository rooms, ExamRoomParticipantRepository participants,
            SimulationRepository simulations, AccountRepository accounts, StatementRepository statements,
            ClassRepository classes, EnrollmentRepository enrollments, StatementWriteAccessService statementWriteAccess,
            AccountRoleRepository accountRoles, RoleRepository roles, QuestionRepository questions,
            QuestionOptionRepository options) {
        this(rooms, participants, simulations, accounts, statements, classes, enrollments, statementWriteAccess,
                accountRoles, roles, questions, options, Clock.systemDefaultZone());
    }

    @Autowired
    public ExamRoomService(ExamRoomRepository rooms, ExamRoomParticipantRepository participants,
            SimulationRepository simulations, AccountRepository accounts, StatementRepository statements,
            ClassRepository classes, EnrollmentRepository enrollments, StatementWriteAccessService statementWriteAccess,
            AccountRoleRepository accountRoles, RoleRepository roles, QuestionRepository questions,
            QuestionOptionRepository options, Clock clock) {
        this.rooms = rooms;
        this.participants = participants;
        this.simulations = simulations;
        this.accounts = accounts;
        this.statements = statements;
        this.classes = classes;
        this.enrollments = enrollments;
        this.statementWriteAccess = statementWriteAccess;
        this.accountRoles = accountRoles;
        this.roles = roles;
        this.questions = questions;
        this.options = options;
        this.clock = clock;
    }

    public ExamRoomService(ExamRoomRepository rooms, ExamRoomParticipantRepository participants,
            SimulationRepository simulations, AccountRepository accounts, StatementRepository statements,
            ClassRepository classes, EnrollmentRepository enrollments) {
        this(rooms, participants, simulations, accounts, statements, classes, enrollments, null, null, null, null, null);
    }

    public Mono<ExamRoomResponse> create(ExamRoomRequest data, Long teacherAccountId) {
        return create(data, teacherAccountId, false);
    }

    public Mono<ExamRoomResponse> create(ExamRoomRequest data, Long teacherAccountId, boolean admin) {
        if (!data.endsAt().isAfter(data.startsAt())) {
            return Mono.error(ApiException.badRequest("The exam room end must be after its start"));
        }
        return statements.findByIdAndDeletedAtIsNull(data.statementId())
                 .switchIfEmpty(Mono.error(ApiException.notFound("Statement not found")))
                .flatMap(statement -> {
                    if (!admin && statement.getClassId() == null && data.classId() != null) {
                        return Mono.error(ApiException.forbidden("A teacher may only create a room for an assigned class"));
                    }
                    if (statement.getClassId() != null
                            && (data.classId() == null || !data.classId().equals(statement.getClassId()))) {
                        return Mono.error(ApiException.forbidden("The room class must match the statement class"));
                    }
                    return statementWriteAccess == null
                            ? Mono.just(statement)
                            : statementWriteAccess.requireCanWrite(data.statementId(), teacherAccountId, admin)
                                .thenReturn(statement);
                })
                .then(data.classId() == null ? Mono.empty()
                        : classes.findByIdAndDeletedAtIsNull(data.classId())
                            .switchIfEmpty(Mono.error(ApiException.notFound("Class not found"))))
                .then(accounts.findById(teacherAccountId)
                        .switchIfEmpty(Mono.error(ApiException.notFound("Account not found"))))
                .then(Mono.defer(() -> {
                     ExamRoom room = new ExamRoom();
                     room.setStatementId(data.statementId());
                     room.setClassId(data.classId());
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
            if (request.classId() != null && room.getClassId() != null
                    && !request.classId().equals(room.getClassId())) {
                return Mono.error(ApiException.forbidden("The invitation class must match the room class"));
            }
            Mono<List<Long>> targetAccounts = request.accountId() != null
                    ? accounts.findById(request.accountId()).switchIfEmpty(Mono.error(ApiException.notFound("Account not found")))
                            .map(account -> List.of(account.getId()))
                    : classes.findByIdAndDeletedAtIsNull(request.classId())
                            .switchIfEmpty(Mono.error(ApiException.notFound("Class not found")))
                            .then(enrollments.findAllByClassIdAndDeletedAtIsNull(request.classId())
                                    .map(enrollment -> enrollment.getAccountId()).collectList());
            return targetAccounts.flatMapMany(Flux::fromIterable)
                    .flatMap(accountId -> validateStudent(room, accountId).then(addOne(room, accountId)))
                    .last()
                    .switchIfEmpty(Mono.error(ApiException.badRequest("The class has no active students")));
        });
    }

    private Mono<Void> validateStudent(ExamRoom room, Long accountId) {
        if (accountRoles == null || roles == null) return Mono.empty();
        if (room.getClassId() == null) {
            return Mono.error(ApiException.conflict("This legacy room is not scoped to a class"));
        }
        return accounts.findById(accountId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Account not found")))
                .flatMap(account -> roles.findByNameAndDeletedAtIsNull("STUDENT")
                        .flatMap(role -> accountRoles.existsByAccountIdAndRoleIdAndDeletedAtIsNull(accountId, role.getId()))
                        .filter(Boolean::booleanValue)
                        .switchIfEmpty(Mono.error(ApiException.forbidden("The invited account must be a STUDENT"))))
                .then(enrollments.findAllByClassIdAndDeletedAtIsNull(room.getClassId())
                        .filter(enrollment -> accountId.equals(enrollment.getAccountId()))
                        .hasElements()
                        .filter(Boolean::booleanValue)
                        .switchIfEmpty(Mono.error(ApiException.forbidden("The student is not enrolled in this room class")))
                        .then());
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

    @Transactional
    public Mono<ExamRoomParticipantResponse> join(Long roomId, Long accountId) {
        return rooms.findById(roomId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Exam room not found")))
                .flatMap(room -> participants.findByExamRoomIdAndAccountId(roomId, accountId)
                        .switchIfEmpty(Mono.error(ApiException.forbidden("The student is not invited to this room")))
                        .flatMap(participant -> {
                            LocalDateTime now = LocalDateTime.now(clock);
                            if (room.getStatus() != ExamRoomStatus.OPEN && room.getStatus() != ExamRoomStatus.RUNNING) {
                                return Mono.error(ApiException.conflict("The exam room is not open"));
                            }
                            if (now.isBefore(room.getStartsAt())) {
                                return Mono.error(ApiException.conflict("The exam room has not started"));
                            }
                            if (!now.isBefore(room.getEndsAt())) {
                                return Mono.error(ApiException.conflict("The exam room has ended"));
                            }
                            if (participant.getSimulationId() != null) {
                                Mono<Simulation> existingSimulation = simulations.findById(participant.getSimulationId());
                                if (existingSimulation == null) return Mono.just(participant);
                                return existingSimulation
                                        .filter(simulation -> simulation.getDeletedAt() == null)
                                        .switchIfEmpty(Mono.error(ApiException.conflict(
                                                "The room invitation points to a deleted simulation")))
                                        .thenReturn(participant);
                            }
                            Simulation simulation = new Simulation();
                            simulation.setAccountId(accountId);
                            simulation.setStatementId(room.getStatementId());
                            simulation.setStartedAt(now);
                            simulation.setStatus(SimulationStatus.IN_PROGRESS);
                            simulation.setExamRoomId(room.getId());
                            simulation.setExamRoomDurationMinutes(room.getDurationMinutes());
                             return existingOrInsert(roomId, accountId, simulation)
                                      .flatMap(saved -> {
                                          participant.setSimulationId(saved.getId());
                                          Mono<Integer> claimed = participants.claimSimulation(participant.getId(), saved.getId());
                                          if (claimed == null) return participants.save(participant);
                                          return claimed.flatMap(count -> count == 1
                                                  ? participants.findByExamRoomIdAndAccountId(roomId, accountId)
                                                      .defaultIfEmpty(participant)
                                                  : participants.findByExamRoomIdAndAccountId(roomId, accountId)
                                                      .switchIfEmpty(Mono.error(ApiException.conflict("Could not claim the invitation"))));
                                      });
                         }).map(ExamRoomService::toParticipantResponse));
    }

    private Mono<Simulation> existingOrInsert(Long roomId, Long accountId, Simulation simulation) {
        Mono<Simulation> existing = simulations.findByExamRoomIdAndAccountIdAndDeletedAtIsNull(roomId, accountId);
        if (existing == null) existing = Mono.empty();
        Mono<Simulation> inserted = simulations.insertExamRoomSimulation(accountId, simulation.getStatementId(), roomId,
                simulation.getExamRoomDurationMinutes());
        Mono<Simulation> afterConflict = simulations.findByExamRoomIdAndAccountIdAndDeletedAtIsNull(roomId, accountId);
        return existing.switchIfEmpty(inserted.switchIfEmpty(afterConflict));
    }

    public Mono<ExamRoomStudentResponse> studentView(Long roomId, Long accountId) {
        return rooms.findById(roomId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Exam room not found")))
                .flatMap(room -> participants.findByExamRoomIdAndAccountId(roomId, accountId)
                        .filter(participant -> participant.getSimulationId() != null)
                        .switchIfEmpty(Mono.error(ApiException.notFound("Exam room not found")))
                        .flatMap(participant -> questions.findAllByStatementIdOrderedByOrderIndex(room.getStatementId())
                                .flatMap(question -> options.findAllByQuestionIdAndDeletedAtIsNull(question.getId())
                                        .map(option -> new QuestionOptionResponse(option.getId(), option.getQuestionId(),
                                                option.getOptionLabel(), option.getOptionText(), null,
                                                option.getOrderIndex(), option.getCreatedAt(), option.getUpdatedAt(), null))
                                        .collectList()
                                        .map(questionOptions -> new ExamRoomQuestionResponse(question.getId(),
                                                question.getStatementId(), question.getNumber(), question.getText(),
                                                question.getQuestionType(), question.getMaxScore(), question.getOrderIndex(),
                                                question.getPageIndex(), questionOptions, question.getCreatedAt(),
                                                question.getUpdatedAt())))
                                .collectList()
                                .map(items -> new ExamRoomStudentResponse(roomId, participant.getSimulationId(),
                                        room.getStatus(), room.getStartsAt(), room.getEndsAt(), room.getDurationMinutes(), items))));
    }

    @Override
    public Mono<Boolean> canAccessSimulation(Long roomId, Long simulationId, Long simulationAccountId,
            Long accountId, boolean admin, boolean teacher) {
        if (admin) return Mono.just(true);
        return rooms.findById(roomId).flatMap(room -> {
            if (teacher && accountId.equals(room.getTeacherAccountId())) return Mono.just(true);
            return participants.findByExamRoomIdAndAccountId(roomId, accountId)
                    .map(participant -> accountId.equals(simulationAccountId)
                            && simulationId.equals(participant.getSimulationId()));
        }).defaultIfEmpty(false);
    }

    @Override
    public Mono<LocalDateTime> effectiveRoomDeadline(Long roomId, LocalDateTime durationDeadline) {
        return rooms.findById(roomId)
                .map(room -> room.getStatus() == ExamRoomStatus.CLOSED ? LocalDateTime.MIN
                        : (durationDeadline == null || room.getEndsAt().isBefore(durationDeadline)
                                ? room.getEndsAt() : durationDeadline));
    }

    public Mono<ExamRoomResponse> transition(Long id, Long caller, boolean admin, ExamRoomStatus next) {
        return managedRoom(id, caller, admin).flatMap(room -> {
            boolean valid = (next == ExamRoomStatus.OPEN && room.getStatus() == ExamRoomStatus.DRAFT)
                    || (next == ExamRoomStatus.RUNNING && room.getStatus() == ExamRoomStatus.OPEN)
                    || (next == ExamRoomStatus.CLOSED && (room.getStatus() == ExamRoomStatus.OPEN
                            || room.getStatus() == ExamRoomStatus.RUNNING));
            if (!valid) return Mono.error(ApiException.conflict("Invalid exam room state transition"));
            if (next == ExamRoomStatus.RUNNING && LocalDateTime.now(clock).isBefore(room.getStartsAt())) {
                return Mono.error(ApiException.conflict("The exam room has not reached its start time"));
            }
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
        return new ExamRoomResponse(room.getId(), room.getStatementId(), room.getClassId(), room.getTeacherAccountId(), room.getStartsAt(),
                room.getEndsAt(), room.getDurationMinutes(), room.getStatus());
    }

    private static ExamRoomParticipantResponse toParticipantResponse(ExamRoomParticipant participant) {
        return new ExamRoomParticipantResponse(participant.getExamRoomId(), participant.getAccountId(),
                participant.getSimulationId(), participant.getJoinedAt());
    }
}
