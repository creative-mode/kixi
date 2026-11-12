package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Trilho de auditoria de apagar, restaurar e purgar respostas dos alunos (kixi#596). Cada acto exige um motivo e grava quem o
 * fez; o purge é irreversível, por isso o registo não depende da resposta continuar a existir.
 */
@Service
public class SimulationAnswerAuditService {

    public enum Action { SOFT_DELETE, RESTORE, PURGE }

    static final int MIN_REASON = 5;
    static final int MAX_REASON = 500;

    private final DatabaseClient db;

    public SimulationAnswerAuditService(DatabaseClient db) {
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

    public Mono<Void> record(Long answerId, Long simulationId, Action action, Long actorAccountId, String reason) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO simulation_answer_audit (answer_id, simulation_id, action, actor_account_id, reason)
                VALUES (:answer, :simulation, :action, :actor, :reason)""")
            .bind("answer", answerId)
            .bind("action", action.name())
            .bind("actor", actorAccountId)
            .bind("reason", reason);
        spec = simulationId == null ? spec.bindNull("simulation", Long.class) : spec.bind("simulation", simulationId);
        return spec.then();
    }
}
