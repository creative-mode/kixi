package ao.creativemode.kixi.training.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.training.dto.MatrixItemRequest;
import ao.creativemode.kixi.training.dto.MatrixRequest;
import ao.creativemode.kixi.training.dto.MatrixResponse;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A matriz de especificação de uma prova (kixi#729): o professor dono da sala (ou o administrador) escreve-a, apaga-a ou
 * importa-a de um CSV; ela lê-se também pelo aluno convidado à sala. Escrever substitui a matriz que existia.
 */
@Service
public class MatrixService {

    private final DatabaseClient db;
    private final TrainingRoomReader rooms;
    private final MatrixReader reader;
    private final TopicService topics;

    public MatrixService(DatabaseClient db, TrainingRoomReader rooms, MatrixReader reader, TopicService topics) {
        this.db = db;
        this.rooms = rooms;
        this.reader = reader;
        this.topics = topics;
    }

    @Transactional
    public Mono<MatrixResponse> put(Long roomId, MatrixRequest request, Long accountId, boolean admin) {
        // A forma do pedido valida-se antes de tocar na base de dados.
        MatrixValidator.Validated matrix;
        try {
            matrix = MatrixValidator.validate(request);
        } catch (ApiException e) {
            return Mono.error(e);
        }
        MatrixValidator.Validated validated = matrix;
        return ownedRoom(roomId, accountId, admin)
                .flatMap(room -> resolveTopics(room, validated.items(), accountId)
                        .flatMap(resolved -> save(room, validated, resolved, accountId)))
                .then(Mono.defer(() -> reader.read(roomId)));
    }

    /** Importa de um CSV {@code tema;questoes;cotacao;nivel}: os temas indicam-se pelo nome e criam-se se não existirem. */
    @Transactional
    public Mono<MatrixResponse> importCsv(Long roomId, String csv, String title, Double totalPoints, Long accountId, boolean admin) {
        List<MatrixItemRequest> items;
        try {
            items = MatrixCsvParser.parseOrThrow(csv);
        } catch (ApiException e) {
            return Mono.error(e);
        }
        return put(roomId, new MatrixRequest(title, totalPoints, items), accountId, admin);
    }

    /** A matriz vista por quem pode: o dono da sala, o administrador ou um aluno convidado. */
    public Mono<MatrixResponse> get(Long roomId, Long accountId, boolean admin) {
        return rooms.find(roomId, accountId)
                .filter(room -> admin || accountId.equals(room.teacherAccountId()) || room.participant())
                .switchIfEmpty(Mono.error(() -> ApiException.notFound("Sala não encontrada: " + roomId)))
                .flatMap(room -> reader.read(roomId))
                .switchIfEmpty(Mono.error(() -> ApiException.notFound("Esta prova ainda não tem matriz.")));
    }

    @Transactional
    public Mono<Void> delete(Long roomId, Long accountId, boolean admin) {
        return ownedRoom(roomId, accountId, admin)
                .then(Mono.defer(() -> db.sql("DELETE FROM exam_matrices WHERE exam_room_id = :room").bind("room", roomId)
                        .fetch().rowsUpdated()))
                .flatMap(deleted -> deleted == 0
                        ? Mono.<Void>error(ApiException.notFound("Esta prova ainda não tem matriz."))
                        : Mono.<Void>empty());
    }

    private Mono<TrainingRoomReader.RoomInfo> ownedRoom(Long roomId, Long accountId, boolean admin) {
        return rooms.find(roomId, accountId)
                .switchIfEmpty(Mono.error(() -> ApiException.notFound("Sala não encontrada: " + roomId)))
                .flatMap(room -> admin || accountId.equals(room.teacherAccountId())
                        ? Mono.just(room)
                        : Mono.error(ApiException.forbidden("Só o professor dono da sala pode alterar a sua matriz.")));
    }

    private record Resolved(Long topicId, MatrixValidator.Item item) {}

    /** Cada tema passa a um id da disciplina do enunciado da sala; os que vêm por nome criam-se se faltarem. */
    private Mono<List<Resolved>> resolveTopics(TrainingRoomReader.RoomInfo room, List<MatrixValidator.Item> items,
            Long accountId) {
        return Flux.fromIterable(items).concatMap(item -> {
            if (item.topicId() != null) {
                return checkTopic(room, item).thenReturn(new Resolved(item.topicId(), item));
            }
            if (room.subjectId() == null) {
                return Mono.<Resolved>error(ApiException.badRequest(
                        "O enunciado da sala não tem disciplina: indique o tema por topicId."));
            }
            return topics.findOrCreate(room.subjectId(), item.topicName(), accountId).map(id -> new Resolved(id, item));
        }).collectList().flatMap(resolved -> {
            Set<Long> seen = new HashSet<>();
            for (Resolved r : resolved) {
                if (!seen.add(r.topicId())) {
                    return Mono.<List<Resolved>>error(ApiException.badRequest(
                            "O tema «" + r.item().label() + "» aparece repetido na matriz."));
                }
            }
            return Mono.just(resolved);
        });
    }

    private Mono<Void> checkTopic(TrainingRoomReader.RoomInfo room, MatrixValidator.Item item) {
        return db.sql("SELECT subject_id FROM subject_topics WHERE id = :id AND deleted_at IS NULL").bind("id", item.topicId())
                .map((row, meta) -> row.get("subject_id", Long.class)).one()
                .switchIfEmpty(Mono.error(() -> ApiException.badRequest("O tema " + item.topicId() + " não existe.")))
                .flatMap(subject -> room.subjectId() == null
                        ? Mono.<Void>error(ApiException.badRequest(
                                "O enunciado da sala não tem disciplina, por isso não se pode validar o tema " + item.topicId() + "."))
                        : !room.subjectId().equals(subject)
                        ? Mono.<Void>error(ApiException.badRequest(
                                "O tema " + item.topicId() + " não é da disciplina do enunciado da sala."))
                        : Mono.<Void>empty());
    }

    private Mono<Void> save(TrainingRoomReader.RoomInfo room, MatrixValidator.Validated matrix, List<Resolved> items,
            Long accountId) {
        DatabaseClient.GenericExecuteSpec upsert = db.sql("""
                        INSERT INTO exam_matrices (exam_room_id, title, total_points, created_by)
                        VALUES (:room, :title, :total, :by)
                        ON CONFLICT (exam_room_id) DO UPDATE
                           SET title = EXCLUDED.title, total_points = EXCLUDED.total_points, updated_at = CURRENT_TIMESTAMP
                        RETURNING id
                        """)
                .bind("room", room.roomId()).bind("total", matrix.totalPoints()).bind("by", accountId);
        upsert = matrix.title() == null ? upsert.bindNull("title", String.class) : upsert.bind("title", matrix.title());
        return upsert.map((row, meta) -> row.get("id", Long.class)).one()
                .flatMap(matrixId -> db.sql("DELETE FROM exam_matrix_items WHERE matrix_id = :m").bind("m", matrixId)
                        .fetch().rowsUpdated()
                        .thenMany(Flux.range(0, items.size()).concatMap(position -> insertItem(matrixId, items.get(position), position)))
                        .then());
    }

    private Mono<Long> insertItem(Long matrixId, Resolved resolved, int position) {
        MatrixValidator.Item item = resolved.item();
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                        INSERT INTO exam_matrix_items (matrix_id, topic_id, question_count, points, cognitive_level, question_types, position)
                        VALUES (:matrix, :topic, :count, :points, :level, :types, :position)
                        """)
                .bind("matrix", matrixId).bind("topic", resolved.topicId()).bind("count", item.questionCount())
                .bind("position", position);
        spec = item.points() == null ? spec.bindNull("points", BigDecimal.class) : spec.bind("points", item.points());
        spec = item.level() == null ? spec.bindNull("level", String.class) : spec.bind("level", item.level().name());
        spec = item.questionTypes().isEmpty() ? spec.bindNull("types", String.class)
                : spec.bind("types", String.join(",", item.questionTypes()));
        return spec.fetch().rowsUpdated();
    }
}
