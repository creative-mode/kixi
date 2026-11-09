package ao.creativemode.kixi.training.service;

import java.time.LocalDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Lê, em SQL, o que o treino precisa de uma sala de prova (kixi#729): o dono, o enunciado e a sua disciplina, o horário e
 * se uma conta é aluno convidado. O módulo de treino depende de {@code exams} e {@code examrooms} só por aqui.
 */
@Component
public class TrainingRoomReader {

    public record RoomInfo(Long roomId, Long teacherAccountId, Long statementId, Long subjectId, String subjectName,
            String statementTitle, LocalDateTime startsAt, Integer durationMinutes, boolean participant) {}

    private final DatabaseClient db;

    public TrainingRoomReader(DatabaseClient db) {
        this.db = db;
    }

    /** A sala, vista por {@code accountId}; vazio se a sala não existe. */
    public Mono<RoomInfo> find(Long roomId, Long accountId) {
        return db.sql("""
                SELECT r.id, r.teacher_account_id, r.statement_id, r.starts_at, r.duration_minutes,
                       st.subject_id, st.title, sub.name AS subject_name,
                       EXISTS (SELECT 1 FROM exam_room_participants p
                                WHERE p.exam_room_id = r.id AND p.account_id = :account) AS participant
                  FROM exam_rooms r
                  JOIN statements st ON st.id = r.statement_id
                  LEFT JOIN subjects sub ON sub.id = st.subject_id
                 WHERE r.id = :room
                """)
                .bind("room", roomId).bind("account", accountId)
                .map((row, meta) -> new RoomInfo(row.get("id", Long.class), row.get("teacher_account_id", Long.class),
                        row.get("statement_id", Long.class), row.get("subject_id", Long.class),
                        row.get("subject_name", String.class), row.get("title", String.class),
                        row.get("starts_at", LocalDateTime.class), row.get("duration_minutes", Integer.class),
                        Boolean.TRUE.equals(row.get("participant", Boolean.class))))
                .one();
    }
}
