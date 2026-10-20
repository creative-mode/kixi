package ao.creativemode.kixi.examrooms.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import ao.creativemode.kixi.examrooms.dto.ExamRoomIntegrityResponse;
import ao.creativemode.kixi.examrooms.model.ExamRoom;
import ao.creativemode.kixi.examrooms.model.ExamRoomParticipant;
import ao.creativemode.kixi.examrooms.repository.ExamRoomParticipantRepository;
import ao.creativemode.kixi.examrooms.repository.ExamRoomRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.dto.integrity.SimulationIntegrityEventResponse;
import ao.creativemode.kixi.simulations.model.SimulationIntegrityEvent;
import ao.creativemode.kixi.simulations.repository.SimulationIntegrityEventRepository;
import ao.creativemode.kixi.simulations.service.SimulationIntegrityEventService;
import reactor.core.publisher.Mono;

@Service
public class ExamRoomIntegrityService {
    private final ExamRoomRepository rooms;
    private final ExamRoomParticipantRepository participants;
    private final SimulationIntegrityEventRepository events;
    private final Clock clock;

    public ExamRoomIntegrityService(ExamRoomRepository rooms, ExamRoomParticipantRepository participants,
            SimulationIntegrityEventRepository events, Clock clock) {
        this.rooms = rooms;
        this.participants = participants;
        this.events = events;
        this.clock = clock;
    }

    public Mono<ExamRoomIntegrityResponse> list(Long roomId, Long caller, boolean admin) {
        return managedRoom(roomId, caller, admin)
                .then(Mono.defer(() -> participants.findByExamRoomId(roomId).collectList()))
                .flatMap(rows -> loadEvents(rows).map(bySimulation -> response(roomId, rows, bySimulation)));
    }

    private Mono<ExamRoom> managedRoom(Long roomId, Long caller, boolean admin) {
        Mono<ExamRoom> room = admin ? rooms.findById(roomId) : rooms.findByIdAndTeacherAccountId(roomId, caller);
        return room.switchIfEmpty(Mono.error(ApiException.forbidden("Only the room owner or ADMIN can view it")));
    }

    private Mono<Map<Long, List<SimulationIntegrityEvent>>> loadEvents(List<ExamRoomParticipant> rows) {
        Set<Long> simulationIds = rows.stream().map(ExamRoomParticipant::getSimulationId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (simulationIds.isEmpty()) return Mono.just(Map.of());
        return events.findBySimulationIdInOrderByAtAscIdAsc(simulationIds)
                .collectMultimap(SimulationIntegrityEvent::getSimulationId)
                .map(grouped -> grouped.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue()))));
    }

    private ExamRoomIntegrityResponse response(Long roomId, List<ExamRoomParticipant> rows,
            Map<Long, List<SimulationIntegrityEvent>> bySimulation) {
        List<ExamRoomIntegrityResponse.ParticipantIntegrity> result = rows.stream().map(row -> {
            Long simulationId = row.getSimulationId();
            List<SimulationIntegrityEventResponse> participantEvents =
                    simulationId == null ? List.of() : bySimulation.getOrDefault(simulationId, List.of()).stream()
                            .map(SimulationIntegrityEventService::response)
                            .toList();
            return new ExamRoomIntegrityResponse.ParticipantIntegrity(row.getAccountId(), simulationId, participantEvents);
        }).toList();
        return new ExamRoomIntegrityResponse(roomId, LocalDateTime.now(clock), result);
    }
}
