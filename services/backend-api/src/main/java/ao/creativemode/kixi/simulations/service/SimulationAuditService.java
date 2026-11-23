package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Trilho de auditoria das simulações e das respostas (kixi#596): quem criou, editou, apagou, restaurou ou purgou. Quem
 * chama grava o registo na MESMA transação da mutação, para nunca haver uma mutação sem trilho. Apagar, restaurar e
 * purgar exigem motivo; criar e editar não.
 */
@Service
public class SimulationAuditService {

    public enum Target { ANSWER, SIMULATION }

    public enum Action { CREATE, UPDATE, SOFT_DELETE, RESTORE, PURGE }

    static final int MIN_REASON = 5;
    static final int MAX_REASON = 500;

    private final DatabaseClient db;

    public SimulationAuditService(DatabaseClient db) {
        this.db = db;
    }

    /** O motivo, aparado; 400 se faltar ou for curto/longo demais. */
    public static String requireReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() < MIN_REASON) {
            throw ApiException.badRequest("Indique o motivo (pelo menos " + MIN_REASON + " caracteres)");
        }
        if (trimmed.length() > MAX_REASON) {
            throw ApiException.badRequest("O motivo não pode passar de " + MAX_REASON + " caracteres");
        }
        return trimmed;
    }

    /** O purge é irreversível: além de já estar na lixeira, o pedido repete o id do que se apaga (kixi#596). */
    public static void requirePurgeConfirmation(Long id, String confirm) {
        if (confirm == null || !String.valueOf(id).equals(confirm.trim())) {
            throw ApiException.badRequest("Confirme o purge com confirm=" + id);
        }
    }

    public Mono<Void> record(Target target, Long targetId, Long simulationId, Action action, Long actorAccountId,
            String reason) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO simulation_audit (target_type, target_id, simulation_id, action, actor_account_id, reason)
                VALUES (:target, :targetId, :simulation, :action, :actor, :reason)""")
            .bind("target", target.name())
            .bind("targetId", targetId)
            .bind("action", action.name())
            .bind("actor", actorAccountId);
        spec = simulationId == null ? spec.bindNull("simulation", Long.class) : spec.bind("simulation", simulationId);
        spec = reason == null ? spec.bindNull("reason", String.class) : spec.bind("reason", reason);
        return spec.then();
    }
}
