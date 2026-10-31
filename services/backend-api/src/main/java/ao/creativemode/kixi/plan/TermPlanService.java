package ao.creativemode.kixi.plan;

import ao.creativemode.kixi.exams.service.StatementReadAccessService;
import ao.creativemode.kixi.exams.service.StatementReadScope;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * O plano do trimestre (kixi#523): 1 P1, 1 P2 e X avaliações de MAC por turma, disciplina e trimestre. O estado de cada
 * avaliação é <b>derivado</b> do enunciado, da sala e das respostas (nunca guardado), por isso não se desatualiza; só a
 * dispensa e a conclusão manual da MAC se guardam. O plano fica pronto para a pauta quando todas as avaliações estão
 * corrigidas ou dispensadas. Quem vê o quê: o administrador tudo; o professor os planos da turma e disciplina que leciona
 * (e só ele os edita); o coordenador os da sua instituição (e só ele, ou o administrador, aprova e dispensa).
 */
@Service
public class TermPlanService {

    public record Access(Long accountId, boolean admin, StatementReadScope teacher, Set<Long> coordinatorOf) {
        boolean teaches(Long classId, Long subjectId) {
            return teacher != null && teacher.assignments().stream()
                    .anyMatch(p -> classId.equals(p.classId()) && subjectId.equals(p.subjectId()));
        }
    }

    public record AssessmentInput(String kind, Integer seq, String title, LocalDate plannedDate) { }

    public record PlanInput(Long classId, Long subjectId, Long termId, LocalDate p1Date, LocalDate p2Date, Integer macCount,
                            List<LocalDate> macDates, List<String> macTitles) { }

    public record Assessment(Long id, String kind, int seq, String title, LocalDate plannedDate, Long statementId, Long roomId,
                             String status, String dispensedReason, int daysLeft) { }

    public record Plan(Long id, Long classId, String className, Long subjectId, String subjectName, Long termId, Integer termNumber,
                       Long institutionId, boolean approved, boolean readyForPauta, boolean pautaOpened, String pautaRef,
                       List<Assessment> assessments) { }

    public record Reminder(Long planId, Long assessmentId, String kind, String title, LocalDate plannedDate, int daysLeft,
                           String action, String level, String className, String subjectName) { }

    private static final String STATUS = """
            CASE WHEN a.dispensed_at IS NOT NULL THEN 'DISPENSED'
                 WHEN r.id IS NOT NULL AND r.status = 'CLOSED' AND EXISTS (SELECT 1 FROM simulation_answers sa JOIN simulations x ON x.id = sa.simulation_id
                        WHERE x.exam_room_id = r.id AND x.deleted_at IS NULL AND sa.deleted_at IS NULL AND sa.review_status = 'PENDING_REVIEW') THEN 'DONE'
                 WHEN r.id IS NOT NULL AND r.status = 'CLOSED' THEN 'GRADED'
                 WHEN r.id IS NOT NULL THEN 'ROOM_OPEN'
                 WHEN a.manual_done_at IS NOT NULL THEN 'GRADED'
                 WHEN a.statement_id IS NOT NULL THEN 'STATEMENT_READY'
                 ELSE 'PLANNED' END""";

    private final DatabaseClient db;
    private final CurrentAccountService current;
    private final StatementReadAccessService readAccess;

    public TermPlanService(DatabaseClient db, CurrentAccountService current, StatementReadAccessService readAccess) {
        this.db = db;
        this.current = current;
        this.readAccess = readAccess;
    }

    public Mono<Access> access() {
        return current.requiredAccountId().flatMap(account -> Mono.zip(current.hasAnyRole("ADMIN"), current.hasAnyRole("TEACHER"))
                .flatMap(roles -> {
                    Mono<StatementReadScope> teacher = roles.getT2() ? readAccess.teacherScope(account) : Mono.just(StatementReadScope.NO_ASSIGNMENTS);
                    Mono<Set<Long>> coordinator = db.sql("SELECT institution_id FROM institution_coordinators WHERE account_id = :a")
                            .bind("a", account).map((row, meta) -> row.get(0, Long.class)).all().collect(java.util.stream.Collectors.toSet());
                    return Mono.zip(teacher, coordinator).map(t -> new Access(account, roles.getT1(), t.getT1(), t.getT2()));
                }));
    }

    // ---- criar e alterar ----

    @Transactional
    public Mono<Plan> create(Access who, PlanInput in) {
        if (in.classId() == null || in.subjectId() == null || in.termId() == null || in.p1Date() == null || in.p2Date() == null) {
            return Mono.error(ApiException.badRequest("classId, subjectId, termId, p1Date and p2Date are required"));
        }
        int mac = in.macCount() == null ? 0 : in.macCount();
        if (mac < 0 || mac > 10) {
            return Mono.error(ApiException.badRequest("macCount must be between 0 and 10"));
        }
        if (!who.admin() && !who.teaches(in.classId(), in.subjectId())) {
            return Mono.error(ApiException.forbidden("Only the teacher of this class and subject plans its term"));
        }
        List<LocalDate> macDates = in.macDates() == null ? List.of() : in.macDates();
        if (macDates.size() != mac) {
            return Mono.error(ApiException.badRequest("macDates must have one date per MAC assessment"));
        }
        return db.sql("INSERT INTO term_plans (class_id, subject_id, term_id, created_by) VALUES (:c, :s, :t, :u) RETURNING id")
                .bind("c", in.classId()).bind("s", in.subjectId()).bind("t", in.termId()).bind("u", who.accountId())
                .map((row, meta) -> row.get(0, Long.class)).one()
                .onErrorMap(org.springframework.dao.DuplicateKeyException.class,
                        e -> ApiException.conflict("There is already a plan for this class, subject and term"))
                .onErrorMap(org.springframework.dao.DataIntegrityViolationException.class,
                        e -> e instanceof org.springframework.dao.DuplicateKeyException ? e : ApiException.badRequest("Unknown class, subject or term"))
                .flatMap(planId -> {
                    List<AssessmentInput> items = new ArrayList<>();
                    items.add(new AssessmentInput("P1", 1, "P1", in.p1Date()));
                    items.add(new AssessmentInput("P2", 1, "P2", in.p2Date()));
                    for (int i = 0; i < mac; i++) {
                        String title = in.macTitles() != null && i < in.macTitles().size() && in.macTitles().get(i) != null
                                && !in.macTitles().get(i).isBlank() ? in.macTitles().get(i) : "MAC " + (i + 1);
                        items.add(new AssessmentInput("MAC", i + 1, title, macDates.get(i)));
                    }
                    return Flux.fromIterable(items).concatMap(item -> db.sql(
                                    "INSERT INTO term_assessments (plan_id, kind, seq, title, planned_date) VALUES (:p, :k, :q, :t, :d)")
                                    .bind("p", planId).bind("k", item.kind()).bind("q", item.seq()).bind("t", item.title())
                                    .bind("d", item.plannedDate()).fetch().rowsUpdated()).then(plan(who, planId));
                });
    }

    /** Muda as datas e os títulos, e o número de avaliações de MAC (só acrescenta ou retira as ainda sem enunciado). */
    @Transactional
    public Mono<Plan> update(Access who, Long planId, PlanInput in) {
        return loadHeader(planId).flatMap(h -> requireEdit(who, h)).then(Mono.defer(() -> {
            Mono<Void> dates = Mono.empty();
            if (in.p1Date() != null) dates = dates.then(setDate(planId, "P1", 1, in.p1Date()));
            if (in.p2Date() != null) dates = dates.then(setDate(planId, "P2", 1, in.p2Date()));
            Mono<Void> macs = Mono.empty();
            if (in.macCount() != null) {
                int wanted = in.macCount();
                if (wanted < 0 || wanted > 10) return Mono.error(ApiException.badRequest("macCount must be between 0 and 10"));
                List<LocalDate> macDates = in.macDates() == null ? List.of() : in.macDates();
                macs = db.sql("SELECT seq FROM term_assessments WHERE plan_id = :p AND kind = 'MAC' ORDER BY seq").bind("p", planId)
                        .map((row, meta) -> row.get(0, Integer.class)).all().collectList().flatMap(existing -> {
                            int have = existing.size();
                            Mono<Void> work = Mono.empty();
                            for (int seq = have + 1; seq <= wanted; seq++) {
                                int index = seq - 1;
                                LocalDate date = index < macDates.size() ? macDates.get(index) : null;
                                if (date == null) return Mono.error(ApiException.badRequest("A date is required for each new MAC assessment"));
                                String title = in.macTitles() != null && index < in.macTitles().size() && in.macTitles().get(index) != null
                                        ? in.macTitles().get(index) : "MAC " + seq;
                                final int s = seq;
                                work = work.then(db.sql("INSERT INTO term_assessments (plan_id, kind, seq, title, planned_date) VALUES (:p, 'MAC', :q, :t, :d)")
                                        .bind("p", planId).bind("q", s).bind("t", title).bind("d", date).fetch().rowsUpdated().then());
                            }
                            if (wanted < have) {
                                work = work.then(db.sql("""
                                        DELETE FROM term_assessments WHERE plan_id = :p AND kind = 'MAC' AND seq > :n
                                          AND statement_id IS NULL AND room_id IS NULL AND manual_done_at IS NULL
                                        """).bind("p", planId).bind("n", wanted).fetch().rowsUpdated().flatMap(removed -> removed == have - wanted
                                        ? Mono.<Void>empty()
                                        : Mono.error(ApiException.conflict("Only MAC assessments with no statement, room or result can be removed"))));
                            }
                            return work;
                        });
            }
            return dates.then(macs).then(touch(planId)).then(plan(who, planId));
        }));
    }

    private Mono<Void> setDate(Long planId, String kind, int seq, LocalDate date) {
        return db.sql("UPDATE term_assessments SET planned_date = :d WHERE plan_id = :p AND kind = :k AND seq = :q")
                .bind("d", date).bind("p", planId).bind("k", kind).bind("q", seq).fetch().rowsUpdated().then();
    }

    /** Liga o enunciado e/ou a sala à avaliação: valida a turma e a disciplina. Só quem edita o plano. */
    @Transactional
    public Mono<Plan> link(Access who, Long planId, Long assessmentId, Long statementId, Long roomId, String title, LocalDate date) {
        return loadHeader(planId).flatMap(h -> requireEdit(who, h).thenReturn(h)).flatMap(h -> {
            Mono<Void> check = Mono.empty();
            if (statementId != null) {
                check = check.then(db.sql("SELECT 1 FROM statements WHERE id = :s AND deleted_at IS NULL AND (class_id IS NULL OR class_id = :c) AND (subject_id IS NULL OR subject_id = :u)")
                        .bind("s", statementId).bind("c", h.classId()).bind("u", h.subjectId()).fetch().first()
                        .switchIfEmpty(Mono.error(ApiException.badRequest("The statement does not belong to this class and subject"))).then());
            }
            if (roomId != null) {
                check = check.then(db.sql("SELECT 1 FROM exam_rooms r JOIN statements s ON s.id = r.statement_id WHERE r.id = :r AND r.class_id = :c AND s.subject_id = :u")
                        .bind("r", roomId).bind("c", h.classId()).bind("u", h.subjectId()).fetch().first()
                        .switchIfEmpty(Mono.error(ApiException.badRequest("The room does not belong to this class and subject"))).then());
            }
            DatabaseClient.GenericExecuteSpec update = db.sql("""
                    UPDATE term_assessments SET statement_id = COALESCE(:s, statement_id), room_id = COALESCE(:r, room_id),
                        title = COALESCE(:t, title), planned_date = COALESCE(:d, planned_date)
                    WHERE id = :id AND plan_id = :p
                    """).bind("id", assessmentId).bind("p", planId);
            update = nullable(nullable(nullable(nullable(update, "s", statementId, Long.class), "r", roomId, Long.class),
                    "t", title, String.class), "d", date, LocalDate.class);
            return check.then(update
                    .fetch().rowsUpdated()).flatMap(n -> n == 0 ? Mono.error(ApiException.notFound("Assessment not found")) : touch(planId)
                    .then(plan(who, planId)));
        });
    }

    /** A MAC que não tem sala (um trabalho, uma ficha) o professor marca-a como concluída. */
    @Transactional
    public Mono<Plan> markDone(Access who, Long planId, Long assessmentId) {
        return loadHeader(planId).flatMap(h -> requireEdit(who, h)).then(db.sql(
                "UPDATE term_assessments SET manual_done_at = CURRENT_TIMESTAMP WHERE id = :id AND plan_id = :p AND kind = 'MAC' AND room_id IS NULL")
                .bind("id", assessmentId).bind("p", planId).fetch().rowsUpdated())
                .flatMap(n -> n == 0 ? Mono.error(ApiException.badRequest("Only a MAC assessment with no room can be marked done"))
                        : touch(planId).then(plan(who, planId)));
    }

    /** O coordenador (ou o administrador) dispensa uma componente, com motivo. */
    @Transactional
    public Mono<Plan> dispense(Access who, Long planId, Long assessmentId, String reason) {
        if (reason == null || reason.isBlank()) {
            return Mono.error(ApiException.badRequest("A reason is required to dispense an assessment"));
        }
        return loadHeader(planId).flatMap(h -> requireCoordinator(who, h)).then(db.sql("""
                UPDATE term_assessments SET dispensed_by = :u, dispensed_at = CURRENT_TIMESTAMP, dispensed_reason = :r
                WHERE id = :id AND plan_id = :p AND dispensed_at IS NULL
                """).bind("u", who.accountId()).bind("r", reason.trim()).bind("id", assessmentId).bind("p", planId).fetch().rowsUpdated())
                .flatMap(n -> n == 0 ? Mono.error(ApiException.conflict("Assessment not found or already dispensed"))
                        : touch(planId).then(plan(who, planId)));
    }

    @Transactional
    public Mono<Plan> approve(Access who, Long planId) {
        return loadHeader(planId).flatMap(h -> requireCoordinator(who, h)).then(db.sql(
                "UPDATE term_plans SET approved_by = :u, approved_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = :p")
                .bind("u", who.accountId()).bind("p", planId).fetch().rowsUpdated()).then(plan(who, planId));
    }

    private static <T> DatabaseClient.GenericExecuteSpec nullable(DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private Mono<Void> touch(Long planId) {
        return db.sql("UPDATE term_plans SET updated_at = CURRENT_TIMESTAMP WHERE id = :p").bind("p", planId).fetch().rowsUpdated().then();
    }

    // ---- ler ----

    public Flux<Plan> list(Access who, Long classId, Long subjectId, Long termId) {
        return db.sql("SELECT id FROM term_plans ORDER BY created_at DESC, id")
                .map((row, meta) -> row.get(0, Long.class)).all().concatMap(id -> plan(who, id).onErrorResume(ApiException.class, e -> Mono.empty()))
                .filter(p -> classId == null || classId.equals(p.classId()))
                .filter(p -> subjectId == null || subjectId.equals(p.subjectId()))
                .filter(p -> termId == null || termId.equals(p.termId()));
    }

    public Mono<Plan> get(Access who, Long planId) {
        return plan(who, planId);
    }

    /** Os lembretes de quem planeia: montar o enunciado, abrir a sala e planos por fazer (14, 7 e 2 dias antes e em atraso). */
    public Flux<Reminder> reminders(Access who, LocalDate today) {
        return list(who, null, null, null).filter(p -> who.admin() || who.teaches(p.classId(), p.subjectId()))
                .flatMapIterable(plan -> {
                    List<Reminder> out = new ArrayList<>();
                    for (Assessment a : plan.assessments()) {
                        int days = (int) ChronoUnit.DAYS.between(today, a.plannedDate());
                        String action = switch (a.status()) {
                            case "PLANNED" -> "BUILD_STATEMENT";
                            case "STATEMENT_READY" -> "OPEN_ROOM";
                            default -> null;
                        };
                        if (action == null) continue;
                        if (a.kind().equals("MAC") && a.status().equals("PLANNED") && days > 2) continue; // uma MAC pode não ser prova
                        String level = days < 0 ? "LATE" : days <= 2 ? "D2" : days <= 7 ? "D7" : days <= 14 ? "D14" : null;
                        if (level == null) continue;
                        out.add(new Reminder(plan.id(), a.id(), a.kind(), a.title(), a.plannedDate(), days, action, level,
                                plan.className(), plan.subjectName()));
                    }
                    return out;
                });
    }

    /** Os planos prontos para abrir a pauta no motor e ainda não abertos (para o gatilho da etapa B). */
    public Flux<Long> readyAndNotOpened() {
        return db.sql("SELECT id FROM term_plans WHERE pauta_opened_at IS NULL").map((row, meta) -> row.get(0, Long.class)).all()
                .filterWhen(id -> assessments(id).collectList().map(TermPlanService::allCleared));
    }

    static boolean allCleared(List<Assessment> assessments) {
        return !assessments.isEmpty() && assessments.stream().allMatch(a -> a.status().equals("GRADED") || a.status().equals("DISPENSED"));
    }

    private record Header(Long id, Long classId, String className, Long subjectId, String subjectName, Long termId, Integer termNumber,
                          Long institutionId, boolean approved, LocalDateTime pautaOpenedAt, String pautaRef) { }

    private Mono<Header> loadHeader(Long planId) {
        return db.sql("""
                SELECT p.id, p.class_id, c.code AS class_code, p.subject_id, s.name AS subject_name, p.term_id, t.number AS term_number,
                       c.institution_id, p.approved_at IS NOT NULL AS approved, p.pauta_opened_at, p.pauta_ref
                FROM term_plans p JOIN classes c ON c.id = p.class_id JOIN subjects s ON s.id = p.subject_id JOIN terms t ON t.id = p.term_id
                WHERE p.id = :p
                """).bind("p", planId).map((row, meta) -> new Header(row.get("id", Long.class), row.get("class_id", Long.class),
                        row.get("class_code", String.class), row.get("subject_id", Long.class), row.get("subject_name", String.class),
                        row.get("term_id", Long.class), row.get("term_number", Integer.class), row.get("institution_id", Long.class),
                        Boolean.TRUE.equals(row.get("approved", Boolean.class)), row.get("pauta_opened_at", LocalDateTime.class),
                        row.get("pauta_ref", String.class))).one()
                .switchIfEmpty(Mono.error(ApiException.notFound("Term plan not found")));
    }

    private static boolean canRead(Access who, Header h) {
        return who.admin() || who.teaches(h.classId(), h.subjectId()) || h.institutionId() != null && who.coordinatorOf().contains(h.institutionId());
    }

    /** Nega com 403, tanto o que não existe como o que não se pode ver, para não revelar planos de outras turmas. */
    private Mono<Void> requireEdit(Access who, Header h) {
        return who.admin() || who.teaches(h.classId(), h.subjectId()) ? Mono.empty()
                : Mono.error(ApiException.forbidden("Only the teacher of this class and subject edits its plan"));
    }

    private Mono<Void> requireCoordinator(Access who, Header h) {
        return who.admin() || h.institutionId() != null && who.coordinatorOf().contains(h.institutionId()) ? Mono.empty()
                : Mono.error(ApiException.forbidden("Only the coordinator of the institution approves or dispenses"));
    }

    private Mono<Plan> plan(Access who, Long planId) {
        return loadHeader(planId).flatMap(h -> {
            if (!canRead(who, h)) return Mono.error(ApiException.forbidden("Not allowed to read this term plan"));
            return assessments(planId).collectList().map(list -> new Plan(h.id(), h.classId(), h.className(), h.subjectId(), h.subjectName(),
                    h.termId(), h.termNumber(), h.institutionId(), h.approved(), allCleared(list), h.pautaOpenedAt() != null, h.pautaRef(), list));
        });
    }

    public Flux<Assessment> assessmentsOf(Long planId) {
        return assessments(planId);
    }

    private Flux<Assessment> assessments(Long planId) {
        LocalDate today = LocalDate.now();
        return db.sql("SELECT a.id, a.kind, a.seq, a.title, a.planned_date, a.statement_id, a.room_id, a.dispensed_reason, " + STATUS
                        + " AS status FROM term_assessments a LEFT JOIN exam_rooms r ON r.id = a.room_id WHERE a.plan_id = :p "
                        + "ORDER BY CASE a.kind WHEN 'P1' THEN 1 WHEN 'P2' THEN 2 ELSE 3 END, a.seq")
                .bind("p", planId).map((row, meta) -> {
                    LocalDate date = row.get("planned_date", LocalDate.class);
                    return new Assessment(row.get("id", Long.class), row.get("kind", String.class), row.get("seq", Integer.class),
                            row.get("title", String.class), date, row.get("statement_id", Long.class), row.get("room_id", Long.class),
                            row.get("status", String.class), row.get("dispensed_reason", String.class),
                            (int) ChronoUnit.DAYS.between(today, date));
                }).all();
    }
}
