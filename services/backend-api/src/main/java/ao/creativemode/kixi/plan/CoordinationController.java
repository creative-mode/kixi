package ao.creativemode.kixi.plan;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * O que o coordenador acompanha das salas de prova da sua instituição (kixi#529), só em leitura: as salas, em que ponto estão e
 * as notas por aluno. Não fecha salas nem corrige: isso continua a ser do professor dono da sala. O administrador vê as de todas
 * as instituições; quem não coordena nenhuma recebe 403.
 */
@RestController
@RequestMapping("/api/v1/coordination/exam-rooms")
public class CoordinationController {

    public record RoomRow(Long id, String status, OffsetDateTime startsAt, OffsetDateTime endsAt, String className, String statementTitle,
                          Long teacherAccountId, String teacherName, long participants, long finished, long pendingAnswers) { }

    public record GradeRow(Long accountId, String name, String status, Double finalScore, long pendingAnswers) { }

    private final DatabaseClient db;
    private final CurrentAccountService current;

    public CoordinationController(DatabaseClient db, CurrentAccountService current) {
        this.db = db;
        this.current = current;
    }

    @GetMapping
    public Mono<List<RoomRow>> rooms() {
        return scope().flatMap(institutions -> db.sql("""
                SELECT r.id, r.status, r.starts_at, r.ends_at, c.code AS class_code, st.title AS statement_title, r.teacher_account_id,
                       TRIM(COALESCE(u.first_name, '') || ' ' || COALESCE(u.last_name, '')) AS teacher_name,
                       (SELECT count(*) FROM exam_room_participants p WHERE p.exam_room_id = r.id) AS participants,
                       (SELECT count(*) FROM simulations x WHERE x.exam_room_id = r.id AND x.deleted_at IS NULL AND x.status = 'FINISHED') AS finished,
                       (SELECT count(*) FROM simulation_answers a JOIN simulations x ON x.id = a.simulation_id WHERE x.exam_room_id = r.id
                          AND x.deleted_at IS NULL AND a.deleted_at IS NULL AND a.review_status = 'PENDING_REVIEW') AS pending
                FROM exam_rooms r JOIN classes c ON c.id = r.class_id JOIN statements st ON st.id = r.statement_id
                LEFT JOIN users u ON u.account_id = r.teacher_account_id AND u.deleted_at IS NULL
                WHERE (:all OR c.institution_id = ANY(:ids)) ORDER BY r.created_at DESC, r.id DESC
                """).bind("all", institutions.isEmpty()).bind("ids", institutions.isEmpty() ? new Long[] {0L} : institutions.toArray(Long[]::new))
                .map((row, meta) -> new RoomRow(row.get("id", Long.class), row.get("status", String.class), row.get("starts_at", OffsetDateTime.class),
                        row.get("ends_at", OffsetDateTime.class), row.get("class_code", String.class), row.get("statement_title", String.class),
                        row.get("teacher_account_id", Long.class), row.get("teacher_name", String.class), row.get("participants", Long.class),
                        row.get("finished", Long.class), row.get("pending", Long.class))).all().collectList());
    }

    @GetMapping("/{roomId}/grades")
    public Mono<List<GradeRow>> grades(@PathVariable Long roomId) {
        return scope().flatMap(institutions -> db.sql("SELECT c.institution_id FROM exam_rooms r JOIN classes c ON c.id = r.class_id WHERE r.id = :r")
                .bind("r", roomId).map((row, meta) -> row.get(0, Long.class)).one()
                // uma sala que não existe e uma de outra instituição dão a mesma resposta (403), para não revelar o que há
                .filter(institution -> institutions.isEmpty() || institutions.contains(institution))
                .switchIfEmpty(Mono.error(ApiException.forbidden("This room is not in your institution")))
                .then(db.sql("""
                        SELECT p.account_id, TRIM(COALESCE(u.first_name, '') || ' ' || COALESCE(u.last_name, '')) AS name, x.status, x.final_score,
                               (SELECT count(*) FROM simulation_answers a WHERE a.simulation_id = x.id AND a.deleted_at IS NULL
                                  AND a.review_status = 'PENDING_REVIEW') AS pending
                        FROM exam_room_participants p LEFT JOIN simulations x ON x.id = p.simulation_id AND x.deleted_at IS NULL
                          LEFT JOIN users u ON u.account_id = p.account_id AND u.deleted_at IS NULL
                        WHERE p.exam_room_id = :r ORDER BY name, p.account_id
                        """).bind("r", roomId).map((row, meta) -> {
                    Number score = row.get("final_score", Number.class);
                    return new GradeRow(row.get("account_id", Long.class), row.get("name", String.class), row.get("status", String.class),
                            score == null ? null : score.doubleValue(), row.get("pending", Long.class));
                }).all().collectList()));
    }

    /** O administrador vê tudo (conjunto vazio); o coordenador as instituições que coordena; mais ninguém. */
    private Mono<Set<Long>> scope() {
        return current.hasAnyRole("ADMIN").flatMap(admin -> {
            if (admin) return Mono.just(Set.<Long>of());
            return current.requiredAccountId().flatMap(account -> db.sql("SELECT institution_id FROM institution_coordinators WHERE account_id = :a")
                    .bind("a", account).map((row, meta) -> row.get(0, Long.class)).all().collect(java.util.stream.Collectors.toSet()))
                    .flatMap(set -> set.isEmpty() ? Mono.<Set<Long>>error(ApiException.forbidden("Only a coordinator or ADMIN follows the rooms of an institution")) : Mono.just(set));
        });
    }
}
