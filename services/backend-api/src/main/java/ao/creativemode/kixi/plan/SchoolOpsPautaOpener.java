package ao.creativemode.kixi.plan;

import ao.creativemode.kixi.plan.TermPlanService.Assessment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Abre a pauta no motor SchoolOps quando o professor conclui o agendamento do plano (kixi#536): cada componente fica
 * à espera da sua data e a correção corre no fluxo, com o controlo do professor. Depois, cada prova P1/P2 é encerrada no
 * motor só quando a sua sala fecha (digital) ou a data passa (papel). Idempotente: o {@code pautaRef} é o plano, o motor
 * devolve a mesma pauta se o pedido se repetir, e o plano só se marca como aberto depois de o motor aceitar.
 */
@Component
public class SchoolOpsPautaOpener {

    private static final Logger log = LoggerFactory.getLogger(SchoolOpsPautaOpener.class);

    private final SchoolOpsProperties properties;
    private final TermPlanService plans;
    private final DatabaseClient db;
    private final WebClient.Builder webClients;

    public SchoolOpsPautaOpener(SchoolOpsProperties properties, TermPlanService plans, DatabaseClient db, WebClient.Builder webClients) {
        this.properties = properties;
        this.plans = plans;
        this.db = db;
        this.webClients = webClients;
    }

    @Scheduled(fixedDelayString = "${schoolops.poll-ms:60000}", initialDelayString = "${schoolops.poll-ms:60000}")
    public void openReadyPlans() {
        if (!properties.isEnabled()) return;
        try {
            List<Long> ready = plans.readyAndNotOpened().collectList().block(Duration.ofSeconds(30));
            if (ready == null) return;
            for (Long planId : ready) {
                try {
                    open(planId).block(Duration.ofSeconds(60));
                } catch (RuntimeException e) {
                    log.warn("[schoolops] plano {}: pauta por abrir ({})", planId, e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.warn("[schoolops] verificação dos planos falhou ({})", e.getMessage());
        }
    }

    public Mono<Void> open(Long planId) {
        return request(planId).flatMap(built -> {
            if (built == null) return Mono.<Void>empty();
            WebClient http = webClients.baseUrl(properties.getBaseUrl()).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getToken()).build();
            return http.post().uri("/v1/pautas").contentType(MediaType.APPLICATION_JSON).bodyValue(built.body()).retrieve()
                    .bodyToMono(Map.class)
                    .flatMap(response -> {
                        String pautaId = String.valueOf(response.get("pautaId"));
                        return db.sql("UPDATE term_plans SET pauta_opened_at = CURRENT_TIMESTAMP, pauta_ref = :r, updated_at = CURRENT_TIMESTAMP WHERE id = :p")
                                .bind("r", pautaId).bind("p", planId).fetch().rowsUpdated().then();
                    });
        });
    }

    /** Encerra no motor as provas cuja sala fechou ou cuja data passou, para o motor passar à recolha e correção. */
    @Scheduled(fixedDelayString = "${schoolops.poll-ms:60000}", initialDelayString = "${schoolops.poll-ms:60000}")
    public void closeFinishedProvas() {
        if (!properties.isEnabled()) return;
        try {
            db.sql("""
                    SELECT a.id, a.statement_id, p.pauta_ref
                    FROM term_assessments a JOIN term_plans p ON p.id = a.plan_id LEFT JOIN exam_rooms r ON r.id = a.room_id
                    WHERE a.kind IN ('P1', 'P2') AND a.closed_notified_at IS NULL AND a.dispensed_at IS NULL AND a.statement_id IS NOT NULL
                      AND p.pauta_opened_at IS NOT NULL AND p.pauta_ref IS NOT NULL
                      AND ((a.room_id IS NOT NULL AND r.status = 'CLOSED') OR (a.room_id IS NULL AND a.planned_date < CURRENT_DATE))
                    ORDER BY a.id
                    """).fetch().all().collectList().block(Duration.ofSeconds(30)).forEach(row -> {
                try {
                    WebClient http = webClients.baseUrl(properties.getBaseUrl()).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getToken()).build();
                    close(http, String.valueOf(row.get("pauta_ref")), String.valueOf(row.get("statement_id")))
                            .then(db.sql("UPDATE term_assessments SET closed_notified_at = CURRENT_TIMESTAMP WHERE id = :a")
                                    .bind("a", row.get("id")).fetch().rowsUpdated().then()).block(Duration.ofSeconds(60));
                } catch (RuntimeException e) {
                    log.warn("[schoolops] prova {} da pauta {}: encerramento por repetir ({})", row.get("statement_id"), row.get("pauta_ref"), e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("[schoolops] verificação das provas por encerrar falhou ({})", e.getMessage());
        }
    }

    /** O motor pode demorar um instante a tornar a pauta consultável: repete-se o 404; um 409 é encerramento já feito. */
    private Mono<Void> close(WebClient http, String pautaId, String prova) {
        return http.post().uri("/v1/pautas/{pauta}/provas/{prova}/encerramento", pautaId, prova).retrieve().toBodilessEntity()
                .retryWhen(reactor.util.retry.Retry.backoff(3, Duration.ofSeconds(1))
                        .filter(e -> e instanceof WebClientResponseException.NotFound))
                .onErrorResume(WebClientResponseException.Conflict.class, e -> Mono.empty())
                .then();
    }

    private record Built(Map<String, Object> body, List<String> provaIds) { }

    Mono<Built> request(Long planId) {
        return plans.assessmentsOf(planId).collectList().flatMap(assessments -> {
            Assessment p1 = assessments.stream().filter(a -> a.kind().equals("P1")).findFirst().orElse(null);
            Assessment p2 = assessments.stream().filter(a -> a.kind().equals("P2")).findFirst().orElse(null);
            if (p1 == null || p2 == null || p1.statementId() == null || p2.statementId() == null) {
                log.warn("[schoolops] plano {}: sem P1 e P2 com enunciado (dispensada?), a pauta não abre sozinha", planId);
                return Mono.empty();
            }
            return db.sql("""
                    SELECT p.id, p.class_id, p.subject_id, c.code AS class_code, c.grade, i.id AS inst_id, i.name AS inst_name, i.code AS inst_code,
                           sy.start_year, sy.end_year, t.id AS term_id, t.number AS term_number, co.id AS course_id, co.code AS course_code,
                           co.name AS course_name, s.code AS subject_code, s.name AS subject_name
                    FROM term_plans p JOIN classes c ON c.id = p.class_id JOIN institutions i ON i.id = c.institution_id
                      JOIN school_years sy ON sy.id = c.school_year_id JOIN terms t ON t.id = p.term_id JOIN courses co ON co.id = c.course_id
                      JOIN subjects s ON s.id = p.subject_id WHERE p.id = :p
                    """).bind("p", planId).fetch().one().flatMap(h -> teacher(h).zipWith(students(h)).map(ts -> {
                Map<String, Object> pedido = new LinkedHashMap<>();
                pedido.put("contratoVersao", "1");
                pedido.put("pautaRef", "kixi-plano-" + planId);
                pedido.put("org", Map.of("sourcedId", String.valueOf(h.get("inst_id")), "nome", h.get("inst_name"), "sigla", String.valueOf(h.get("inst_code"))));
                pedido.put("academicSession", Map.of("sourcedId", String.valueOf(h.get("term_id")),
                        "anoLectivo", h.get("start_year") + "/" + h.get("end_year"), "trimestre", h.get("term_number")));
                String subjectCode = String.valueOf(h.get("subject_code"));
                pedido.put("class", Map.of("sourcedId", h.get("class_id") + "-" + h.get("subject_id") + "-" + planId, "nome", String.valueOf(h.get("class_code")),
                        "classe", h.get("grade"),
                        "curso", Map.of("sourcedId", String.valueOf(h.get("course_id")), "sigla", String.valueOf(h.get("course_code")), "nome", String.valueOf(h.get("course_name"))),
                        "disciplina", Map.of("sourcedId", String.valueOf(h.get("subject_id")), "sigla", subjectCode, "nome", String.valueOf(h.get("subject_name")))));
                pedido.put("professor", ts.getT1());
                pedido.put("alunos", ts.getT2());
                pedido.put("provas", List.of(prova(p1, "P1"), prova(p2, "P2")));
                // As avaliações de MAC do plano: o motor usa-as e não volta a perguntá-las ao professor (schoolops#44).
                List<Map<String, Object>> mac = assessments.stream().filter(x -> x.kind().equals("MAC") && !x.status().equals("DISPENSED"))
                        .map(x -> Map.<String, Object>of("titulo", x.title(), "data", x.plannedDate().toString())).toList();
                if (!mac.isEmpty()) pedido.put("avaliacoesContinuas", mac);
                pedido.put("callback", Map.of("url", properties.getCallbackUrl()));
                return new Built(pedido, List.of(String.valueOf(p1.statementId()), String.valueOf(p2.statementId())));
            }));
        });
    }

    private static Map<String, Object> prova(Assessment a, String componente) {
        return Map.of("sourcedId", String.valueOf(a.statementId()), "componente", componente, "modalidade", a.roomId() != null ? "DIGITAL" : "PAPEL");
    }

    private Mono<Map<String, Object>> teacher(Map<String, Object> h) {
        return db.sql("""
                SELECT t.account_id, t.first_name, t.last_name FROM teaching_assignments ta JOIN teachers t ON t.id = ta.teacher_id
                WHERE ta.class_id = :c AND ta.subject_id = :s AND ta.deleted_at IS NULL AND t.account_id IS NOT NULL ORDER BY ta.id LIMIT 1
                """).bind("c", h.get("class_id")).bind("s", h.get("subject_id")).fetch().one()
                .map(r -> Map.<String, Object>of("sourcedId", String.valueOf(r.get("account_id")), "nome", (r.get("first_name") + " " + r.get("last_name")).trim()))
                .switchIfEmpty(Mono.error(new IllegalStateException("A turma e a disciplina não têm professor com conta")));
    }

    private Mono<List<Map<String, Object>>> students(Map<String, Object> h) {
        return db.sql("""
                SELECT a.id, TRIM(COALESCE(u.first_name, '') || ' ' || COALESCE(u.last_name, '')) AS name, a.username
                FROM enrollments e JOIN accounts a ON a.id = e.account_id LEFT JOIN users u ON u.account_id = a.id AND u.deleted_at IS NULL
                WHERE e.class_id = :c AND e.status = 'ACTIVE' AND e.deleted_at IS NULL AND a.deleted_at IS NULL
                ORDER BY LOWER(TRIM(COALESCE(u.first_name, '') || ' ' || COALESCE(u.last_name, ''))), a.id
                """).bind("c", h.get("class_id")).fetch().all().collectList().map(rows -> {
            List<Map<String, Object>> out = new ArrayList<>();
            int n = 1;
            for (Map<String, Object> r : rows) {
                String name = String.valueOf(r.get("name")).isBlank() ? String.valueOf(r.get("username")) : String.valueOf(r.get("name"));
                out.add(Map.of("sourcedId", String.valueOf(r.get("id")), "numero", n++, "nome", name));
            }
            return out;
        });
    }
}
