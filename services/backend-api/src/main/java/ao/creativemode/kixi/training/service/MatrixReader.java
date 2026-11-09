package ao.creativemode.kixi.training.service;

import ao.creativemode.kixi.training.dto.MatrixResponse;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Lê a matriz de uma sala (kixi#729); vazio se a sala não tem matriz. */
@Component
public class MatrixReader {

    private final DatabaseClient db;

    public MatrixReader(DatabaseClient db) {
        this.db = db;
    }

    public Mono<MatrixResponse> read(Long roomId) {
        return db.sql("SELECT id, title, total_points FROM exam_matrices WHERE exam_room_id = :room")
                .bind("room", roomId)
                .map((row, meta) -> new Object[] {row.get("id", Long.class), row.get("title", String.class),
                        row.get("total_points", BigDecimal.class)})
                .one()
                .flatMap(head -> db.sql("""
                                SELECT i.id, i.topic_id, t.name, i.question_count, i.points, i.cognitive_level, i.question_types
                                  FROM exam_matrix_items i JOIN subject_topics t ON t.id = i.topic_id
                                 WHERE i.matrix_id = :matrix ORDER BY i.position, i.id
                                """)
                        .bind("matrix", head[0])
                        .map((row, meta) -> new MatrixResponse.Item(row.get("id", Long.class), row.get("topic_id", Long.class),
                                row.get("name", String.class), row.get("question_count", Integer.class),
                                toDouble(row.get("points", BigDecimal.class)), row.get("cognitive_level", String.class),
                                types(row.get("question_types", String.class))))
                        .all().collectList()
                        .map(items -> new MatrixResponse(roomId, (String) head[1], toDouble((BigDecimal) head[2]), items)));
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    static List<String> types(String stored) {
        return stored == null || stored.isBlank() ? List.of()
                : Arrays.stream(stored.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
