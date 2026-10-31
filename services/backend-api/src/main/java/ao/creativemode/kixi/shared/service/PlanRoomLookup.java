package ao.creativemode.kixi.shared.service;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Diz se uma sala (ou a sala de uma resposta) está ligada a uma avaliação de um plano do trimestre (kixi#542). Essas provas
 * são corrigidas pela IA no fluxo do motor SchoolOps: ninguém as corrige à mão na sala. Só SQL, para os módulos de salas e de
 * simulações a consultarem sem dependerem do módulo dos planos.
 */
@Component
public class PlanRoomLookup {

    private final DatabaseClient db;

    public PlanRoomLookup(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Boolean> isPlanRoom(Long roomId) {
        return db.sql("SELECT 1 FROM term_assessments WHERE room_id = :room LIMIT 1")
                .bind("room", roomId).map((row, meta) -> 1).first().hasElement();
    }

    public Mono<Boolean> isPlanRoomAnswer(Long answerId) {
        return db.sql("""
                SELECT 1 FROM simulation_answers a JOIN simulations s ON s.id = a.simulation_id
                  JOIN term_assessments t ON t.room_id = s.exam_room_id
                 WHERE a.id = :answer LIMIT 1""")
                .bind("answer", answerId).map((row, meta) -> 1).first().hasElement();
    }
}
