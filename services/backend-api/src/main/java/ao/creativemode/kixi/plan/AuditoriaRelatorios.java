package ao.creativemode.kixi.plan;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Registo das exportações da pauta em PDF (kixi#622): quem pediu, de que plano, que documento e quando.
 *
 * <p>O conteúdo do PDF e a ligação assinada não ficam guardados — o motor é que os serve, a pedido, com um prazo
 * curto. O registo é só a prova de que houve acesso (ADR 0001 do motor, «registo de acessos por cliente»).
 */
@Component
public class AuditoriaRelatorios {

    private final DatabaseClient db;

    public AuditoriaRelatorios(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> registar(Long accountId, Long planId, String kind, Long assessmentId) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                        INSERT INTO report_exports (account_id, plan_id, kind, assessment_id)
                        VALUES (:account, :plan, :kind, :assessment)
                        """)
                .bind("account", accountId)
                .bind("plan", planId)
                .bind("kind", kind);
        spec = assessmentId == null ? spec.bindNull("assessment", Long.class) : spec.bind("assessment", assessmentId);
        return spec.then();
    }
}
