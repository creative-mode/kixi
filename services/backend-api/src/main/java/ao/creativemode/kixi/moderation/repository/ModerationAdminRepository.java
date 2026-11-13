package ao.creativemode.kixi.moderation.repository;

import ao.creativemode.kixi.moderation.dto.AuditEntryResponse;
import ao.creativemode.kixi.moderation.dto.ModerationReportResponse;
import io.r2dbc.spi.Row;
import java.time.LocalDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * As leituras da fila e da auditoria (com os nomes resolvidos) e as escritas da decisão (kixi#591). Fica à parte de
 * {@link ModerationReportRepository} porque junta contas, pessoas e escolas; quem chama é que abre a transacção.
 */
@Repository
public class ModerationAdminRepository {

    /** O nome de uma conta: a pessoa, ou o utilizador se ainda não tiver perfil. */
    private static String accountName(String accountId) {
        return "COALESCE((SELECT NULLIF(TRIM(u.first_name || ' ' || u.last_name), '') FROM users u WHERE u.account_id = "
                + accountId + "), (SELECT a.username FROM accounts a WHERE a.id = " + accountId + "), '')";
    }

    private static final String REPORT_SELECT = """
            SELECT r.id, r.target_type, COALESCE(r.target_account_id, r.target_institution_id) AS target_id,
                   CASE WHEN r.target_type = 'ACCOUNT' THEN %s
                        ELSE COALESCE((SELECT i.name FROM institutions i WHERE i.id = r.target_institution_id), '') END AS target_name,
                   r.reporter_account_id, %s AS reporter_name,
                   r.reason, r.note, r.status, r.created_at, r.decided_at,
                   CASE WHEN r.decided_by_account_id IS NULL THEN NULL ELSE %s END AS decided_by,
                   r.decision_note
            FROM moderation_reports r
            """.formatted(accountName("r.target_account_id"), accountName("r.reporter_account_id"),
            accountName("r.decided_by_account_id"));

    private final DatabaseClient db;

    public ModerationAdminRepository(DatabaseClient db) {
        this.db = db;
    }

    /** A fila: as por decidir primeiro; dentro de cada grupo, as mais recentes à frente. */
    public Flux<ModerationReportResponse> queue(String status) {
        String order = " ORDER BY (r.status = 'OPEN') DESC, COALESCE(r.decided_at, r.created_at) DESC, r.id DESC";
        DatabaseClient.GenericExecuteSpec spec = status == null
                ? db.sql(REPORT_SELECT + order)
                : db.sql(REPORT_SELECT + " WHERE r.status = :status" + order).bind("status", status);
        return spec.map((row, meta) -> report(row)).all();
    }

    public Mono<ModerationReportResponse> report(Long id) {
        return db.sql(REPORT_SELECT + " WHERE r.id = :id").bind("id", id).map((row, meta) -> report(row)).one();
    }

    public Flux<AuditEntryResponse> audit() {
        return db.sql("""
                SELECT m.id, m.created_at, %s AS actor, m.action, m.target_type, m.target_id,
                       CASE WHEN m.target_type = 'ACCOUNT' THEN %s
                            ELSE COALESCE((SELECT i.name FROM institutions i WHERE i.id = m.target_id), '') END AS target_name,
                       m.report_id, m.note
                FROM moderation_audit m
                ORDER BY m.created_at DESC, m.id DESC
                """.formatted(accountName("m.actor_account_id"), accountName("m.target_id")))
                .map((row, meta) -> new AuditEntryResponse(
                        row.get("id", Long.class), row.get("created_at", LocalDateTime.class),
                        row.get("actor", String.class), row.get("action", String.class),
                        row.get("target_type", String.class), row.get("target_id", Long.class),
                        row.get("target_name", String.class), row.get("report_id", Long.class),
                        row.get("note", String.class)))
                .all();
    }

    /** Fecha a denúncia só se ainda estiver por decidir: devolve 0 se alguém decidiu entretanto. */
    public Mono<Long> closeIfOpen(Long reportId, String decision, Long actorId, String note, LocalDateTime now) {
        return db.sql("""
                UPDATE moderation_reports
                SET status = :status, decided_at = :at, decided_by_account_id = :actor, decision_note = :note
                WHERE id = :id AND status = 'OPEN'
                """)
                .bind("status", decision).bind("at", now).bind("actor", actorId).bind("note", note).bind("id", reportId)
                .fetch().rowsUpdated();
    }

    public Mono<Void> recordAudit(Long reportId, String decision, String targetType, Long targetId, Long actorId,
            String note, LocalDateTime now) {
        return db.sql("""
                INSERT INTO moderation_audit (report_id, action, target_type, target_id, actor_account_id, note, created_at)
                VALUES (:report, :action, :type, :target, :actor, :note, :at)
                """)
                .bind("report", reportId).bind("action", decision).bind("type", targetType).bind("target", targetId)
                .bind("actor", actorId).bind("note", note).bind("at", now)
                .then();
    }

    /** Suspender: a conta deixa de entrar (o login e o filtro de sessão já recusam {@code active = false}). */
    public Mono<Long> deactivateAccount(Long accountId) {
        return db.sql("UPDATE accounts SET active = FALSE, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND deleted_at IS NULL")
                .bind("id", accountId).fetch().rowsUpdated();
    }

    /** Ocultar uma escola: sai de todas as listagens e perfis; o administrador repõe-a pela lixeira. */
    public Mono<Long> hideInstitution(Long institutionId) {
        return db.sql("UPDATE institutions SET deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND deleted_at IS NULL")
                .bind("id", institutionId).fetch().rowsUpdated();
    }

    private static ModerationReportResponse report(Row row) {
        return new ModerationReportResponse(
                row.get("id", Long.class), row.get("target_type", String.class), row.get("target_id", Long.class),
                row.get("target_name", String.class), row.get("reporter_account_id", Long.class),
                row.get("reporter_name", String.class), row.get("reason", String.class), row.get("note", String.class),
                row.get("status", String.class), row.get("created_at", LocalDateTime.class),
                row.get("decided_at", LocalDateTime.class), row.get("decided_by", String.class),
                row.get("decision_note", String.class));
    }
}
