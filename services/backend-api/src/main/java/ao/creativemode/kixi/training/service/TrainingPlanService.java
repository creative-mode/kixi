package ao.creativemode.kixi.training.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.training.dto.MatrixResponse;
import ao.creativemode.kixi.training.dto.TrainingPlanResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** O plano de treino de uma prova agendada (kixi#729): os temas da matriz e o que há para treinar em cada um. */
@Service
public class TrainingPlanService {

    private final TrainingRoomReader rooms;
    private final MatrixReader matrices;
    private final TrainingPoolReader pool;

    public TrainingPlanService(TrainingRoomReader rooms, MatrixReader matrices, TrainingPoolReader pool) {
        this.rooms = rooms;
        this.matrices = matrices;
        this.pool = pool;
    }

    /** A sala, a matriz e o conjunto de treino para um aluno convidado; uma sala alheia é «não encontrada». */
    record Context(TrainingRoomReader.RoomInfo room, MatrixResponse matrix, List<TrainingPoolReader.PoolRow> pool) {}

    Mono<Context> context(Long roomId, Long accountId, Set<Long> onlyTopics) {
        return rooms.find(roomId, accountId)
                .filter(TrainingRoomReader.RoomInfo::participant)
                .switchIfEmpty(Mono.error(() -> ApiException.notFound("Sala não encontrada: " + roomId)))
                .flatMap(room -> matrices.read(roomId)
                        .switchIfEmpty(Mono.error(() -> ApiException.notFound("Esta prova ainda não tem matriz.")))
                        .flatMap(matrix -> {
                            if (room.subjectId() == null) {
                                return Mono.<Context>error(ApiException.conflict(
                                        "O enunciado da prova não tem disciplina, por isso não há treino por temas."));
                            }
                            List<Long> topicIds = matrix.items().stream().map(MatrixResponse.Item::topicId)
                                    .filter(id -> onlyTopics == null || onlyTopics.contains(id)).toList();
                            return pool.read(accountId, room.subjectId(), room.statementId(), topicIds)
                                    .map(rows -> new Context(room, matrix, rows));
                        }));
    }

    public Mono<TrainingPlanResponse> plan(Long roomId, Long accountId) {
        return context(roomId, accountId, null).map(ctx -> {
            Map<Long, List<TrainingPoolReader.PoolRow>> byTopic = ctx.pool().stream()
                    .collect(Collectors.groupingBy(TrainingPoolReader.PoolRow::topicId, LinkedHashMap::new, Collectors.toList()));
            List<TrainingPlanResponse.Item> items = new ArrayList<>();
            for (MatrixResponse.Item item : ctx.matrix().items()) {
                List<TrainingPoolReader.PoolRow> rows = byTopic.getOrDefault(item.topicId(), List.of());
                Map<Long, List<TrainingPoolReader.PoolRow>> byStatement = rows.stream().collect(
                        Collectors.groupingBy(TrainingPoolReader.PoolRow::statementId, LinkedHashMap::new, Collectors.toList()));
                List<TrainingPlanResponse.Source> sources = byStatement.values().stream()
                        .map(list -> new TrainingPlanResponse.Source(list.get(0).statementId(), list.get(0).statementTitle(),
                                list.get(0).year(), list.size()))
                        .sorted(java.util.Comparator.comparing(TrainingPlanResponse.Source::year).reversed()
                                .thenComparing(TrainingPlanResponse.Source::statementId))
                        .toList();
                items.add(new TrainingPlanResponse.Item(item.topicId(), item.topicName(), item.questionCount(), item.points(),
                        item.cognitiveLevel(), rows.size(), sources,
                        (int) rows.stream().filter(TrainingPoolReader.PoolRow::attempted).count(),
                        (int) rows.stream().filter(TrainingPoolReader.PoolRow::correct).count()));
            }
            return new TrainingPlanResponse(ctx.room().roomId(), ctx.room().subjectId(), ctx.room().subjectName(),
                    ctx.room().statementTitle(), ctx.room().startsAt(), ctx.room().durationMinutes(),
                    ctx.matrix().totalPoints(), items);
        });
    }
}
