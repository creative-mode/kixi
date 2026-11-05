package ao.creativemode.kixi.register;

import ao.creativemode.kixi.exams.service.StatementReadScope;
import ao.creativemode.kixi.shared.exception.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Leitura das pautas trimestrais do registo escolar por quem não é o motor (kixi#506). O motor escreve em
 * {@code /school-register}; aqui lê-se o que ele guardou, já com o acesso decidido: o administrador vê tudo, o professor
 * as pautas da turma e da disciplina que leciona, o aluno só a sua linha e só de pautas fechadas. A turma e a
 * disciplina vêm do contexto da pauta ({@code turmaId} é "turma-disciplina", com um sufixo nos ensaios), por isso não há
 * migração. Uma pauta anulada não se mostra a ninguém.
 */
@Service
public class TermSheetReadService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final String VISIBLE = "status IN ('REGISTADA', 'FECHADA')";

    private final DatabaseClient db;
    private final ObjectMapper mapper;

    public TermSheetReadService(DatabaseClient db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    /** Quem lê: {@code scope} nulo é um administrador (vê tudo); um professor vê o que as suas atribuições cobrem. */
    public record Reader(StatementReadScope scope, java.util.Set<Long> coordinatorOf) {
        public Reader(StatementReadScope scope) {
            this(scope, java.util.Set.of());
        }

        boolean sees(Summary sheet) {
            if (scope == null) return true;
            // o coordenador vê as pautas da sua instituição (kixi#523)
            if (sheet.institutionId() != null && coordinatorOf.contains(sheet.institutionId())) return true;
            return sheet.classId() != null && sheet.subjectId() != null && scope.assignments().stream()
                    .anyMatch(pair -> sheet.classId().equals(pair.classId()) && sheet.subjectId().equals(pair.subjectId()));
        }
    }

    public record Statistics(Object media, Object avaliados, Object inscritos, Object ausentes, Object positivas,
                             Object negativas, Object notaMaxima, Object notaMinima) { }

    public record Summary(String sheetId, String status, Integer termNumber, String schoolYear, Long classId, String className,
                          Long institutionId, Object grade, String course, Long subjectId, String subjectName, String subjectCode,
                          Statistics statistics, LocalDateTime updatedAt) { }

    /** {@code processNumber}: o número de processo do aluno na escola da pauta (kixi#512), nulo se não tiver. */
    public record Row(Long accountId, Object number, String name, String processNumber, Object mac, Object p1, Object p2, Object mt,
                      String status, boolean adjusted) { }

    public record Detail(Summary sheet, List<Row> rows) { }

    public record Mine(String sheetId, Integer termNumber, String schoolYear, String subjectName, String subjectCode,
                       String className, String processNumber, OwnRow row) { }

    public record OwnRow(Object mac, Object p1, Object p2, Object mt, String status) { }

    public Mono<java.util.Set<Long>> coordinatorInstitutions(Long accountId) {
        return db.sql("SELECT institution_id FROM institution_coordinators WHERE account_id = :a").bind("a", accountId)
                .map((row, meta) -> row.get(0, Long.class)).all().collect(java.util.stream.Collectors.toSet());
    }

    public Flux<Summary> list(Reader reader, Long classId, Integer termNumber, Long subjectId) {
        return db.sql("SELECT sheet_id, status, CAST(context AS text) AS c, CAST(statistics AS text) AS s, updated_at"
                        + " FROM register_term_sheets WHERE " + VISIBLE + " ORDER BY updated_at DESC, sheet_id")
                .map((row, meta) -> summary(row.get("sheet_id", String.class), row.get("status", String.class),
                        row.get("c", String.class), row.get("s", String.class), row.get("updated_at", LocalDateTime.class)))
                .all()
                .filter(reader::sees)
                .filter(sheet -> classId == null || classId.equals(sheet.classId()))
                .filter(sheet -> termNumber == null || termNumber.equals(sheet.termNumber()))
                .filter(sheet -> subjectId == null || subjectId.equals(sheet.subjectId()));
    }

    /**
     * Uma pauta que não existe e uma que o leitor não pode ver dão a mesma resposta a quem não é administrador (403):
     * assim o professor não descobre que pautas de outras turmas existem.
     */
    public Mono<Detail> detail(Reader reader, String sheetId) {
        Mono<Detail> missing = reader.scope() == null
                ? Mono.error(ApiException.notFound("Term sheet not found: " + sheetId))
                : Mono.error(ApiException.forbidden("Teacher is not assigned to this term sheet"));
        return db.sql("SELECT sheet_id, status, CAST(context AS text) AS c, CAST(statistics AS text) AS s, updated_at"
                        + " FROM register_term_sheets WHERE sheet_id = :id AND " + VISIBLE)
                .bind("id", sheetId)
                .map((row, meta) -> summary(row.get("sheet_id", String.class), row.get("status", String.class),
                        row.get("c", String.class), row.get("s", String.class), row.get("updated_at", LocalDateTime.class)))
                .one()
                .filter(reader::sees)
                .flatMap(sheet -> rows(sheetId, sheet).collectList().map(rows -> new Detail(sheet, rows)))
                .switchIfEmpty(missing);
    }

    /** account_id → número de processo na escola, só dos alunos que o têm (kixi#512). Lê-se agora e não do payload do motor. */
    private Mono<Map<Long, String>> processNumbers(Long institutionId, java.util.Collection<Long> accountIds) {
        if (institutionId == null || accountIds.isEmpty()) return Mono.just(Map.of());
        return db.sql("SELECT u.account_id, ist.process_number FROM institution_students ist JOIN users u ON u.id = ist.user_id"
                        + " WHERE ist.institution_id = :i AND ist.deleted_at IS NULL AND ist.process_number IS NOT NULL AND u.account_id IN (:ids)")
                .bind("i", institutionId).bind("ids", accountIds)
                .map((row, meta) -> Map.entry(row.get("account_id", Long.class), row.get("process_number", String.class)))
                .all().collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    /** A escola da pauta: a do contexto do motor ({@code escolaId}) ou, se não vier, a da turma. */
    private Mono<Long> institutionOf(Summary sheet) {
        if (sheet.institutionId() != null) return Mono.just(sheet.institutionId());
        if (sheet.classId() == null) return Mono.empty();
        return db.sql("SELECT institution_id FROM classes WHERE id = :c").bind("c", sheet.classId())
                .map((row, meta) -> row.get("institution_id", Long.class)).one();
    }

    private Flux<Row> rows(String sheetId, Summary sheet) {
        return rowsOf(sheetId).collectList().flatMapMany(rows -> institutionOf(sheet).map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty()).flatMap(institution -> processNumbers(institution.orElse(null),
                        rows.stream().map(Row::accountId).filter(java.util.Objects::nonNull).toList())
                        .map(numbers -> rows.stream().map(r -> new Row(r.accountId(), r.number(), r.name(),
                        numbers.get(r.accountId()), r.mac(), r.p1(), r.p2(), r.mt(), r.status(), r.adjusted())).toList()))
                .flatMapMany(Flux::fromIterable));
    }

    private Flux<Row> rowsOf(String sheetId) {
        return db.sql("SELECT account_id, score, status, CAST(payload AS text) AS p FROM register_term_entries"
                        + " WHERE sheet_id = :id AND component = 'MT'")
                .bind("id", sheetId)
                .map((row, meta) -> {
                    Map<String, Object> payload = parse(row.get("p", String.class));
                    Object mt = payload.get("mt") != null ? payload.get("mt") : row.get("score", java.math.BigDecimal.class);
                    return new Row(row.get("account_id", Long.class), payload.get("numero"), text(payload.get("nome")), null,
                            payload.get("mac"), payload.get("npp"), payload.get("npt"), mt, row.get("status", String.class),
                            Boolean.TRUE.equals(payload.get("ajustada")));
                })
                .all()
                .sort(java.util.Comparator.comparing((Row r) -> r.number() instanceof Number n ? n.intValue() : Integer.MAX_VALUE)
                        .thenComparing(r -> r.name() == null ? "" : r.name()));
    }

    /** As linhas do próprio aluno em pautas fechadas: nada de outros alunos nem de estatísticas da turma. */
    public Flux<Mine> mine(Long accountId) {
        return db.sql("SELECT s.sheet_id, s.status, CAST(s.context AS text) AS c, CAST(e.payload AS text) AS p, e.score, e.status AS es, s.updated_at"
                        + " FROM register_term_sheets s JOIN register_term_entries e ON e.sheet_id = s.sheet_id"
                        + " WHERE e.account_id = :a AND e.component = 'MT' AND s.status = 'FECHADA' ORDER BY s.updated_at DESC, s.sheet_id")
                .bind("a", accountId)
                .map((row, meta) -> {
                    Summary sheet = summary(row.get("sheet_id", String.class), row.get("status", String.class),
                            row.get("c", String.class), "{}", row.get("updated_at", LocalDateTime.class));
                    Map<String, Object> payload = parse(row.get("p", String.class));
                    Object mt = payload.get("mt") != null ? payload.get("mt") : row.get("score", java.math.BigDecimal.class);
                    return new Mine(sheet.sheetId(), sheet.termNumber(), sheet.schoolYear(), sheet.subjectName(), sheet.subjectCode(),
                            sheet.className(), null, new OwnRow(payload.get("mac"), payload.get("npp"), payload.get("npt"), mt,
                            row.get("es", String.class)));
                })
                .all()
                .concatMap(mine -> ownProcessNumber(accountId, mine));
    }

    /** O número de processo do próprio aluno na escola desta pauta (kixi#512); sem número, a linha segue sem ele. */
    private Mono<Mine> ownProcessNumber(Long accountId, Mine mine) {
        return db.sql("SELECT ist.process_number FROM register_term_sheets s"
                        + " JOIN institution_students ist ON ist.institution_id = COALESCE(CAST(NULLIF(s.context ->> 'escolaId', '') AS BIGINT),"
                        + " (SELECT c.institution_id FROM classes c WHERE c.id = CAST(NULLIF(split_part(s.context ->> 'turmaId', '-', 1), '') AS BIGINT)))"
                        + " JOIN users u ON u.id = ist.user_id AND u.account_id = :a"
                        + " WHERE s.sheet_id = :s AND ist.deleted_at IS NULL AND ist.process_number IS NOT NULL")
                .bind("a", accountId).bind("s", mine.sheetId())
                .map((row, meta) -> row.get("process_number", String.class)).one()
                .map(number -> new Mine(mine.sheetId(), mine.termNumber(), mine.schoolYear(), mine.subjectName(), mine.subjectCode(),
                        mine.className(), number, mine.row()))
                .defaultIfEmpty(mine);
    }

    private Summary summary(String sheetId, String status, String contextJson, String statsJson, LocalDateTime updatedAt) {
        Map<String, Object> c = parse(contextJson);
        Map<String, Object> s = parse(statsJson);
        String course = text(c.get("cursoNome")) != null ? text(c.get("cursoNome")) : text(c.get("curso"));
        return new Summary(sheetId, status, integer(c.get("trimestre")), text(c.get("anoLectivo")), classIdOf(c.get("turmaId")),
                text(c.get("turma")), longOf(c.get("escolaId")), c.get("classe"), course, longOf(c.get("disciplinaId")), text(c.get("disciplina")),
                text(c.get("disciplinaSigla")),
                new Statistics(s.get("media"), s.get("avaliados"), s.get("inscritos"), s.get("ausentes"), s.get("positivas"),
                        s.get("negativas"), s.get("notaMaxima"), s.get("notaMinima")), updatedAt);
    }

    /** {@code turmaId} é "turma-disciplina" (com um sufixo nos ensaios): a turma é o que vem antes do primeiro hífen. */
    private static Long classIdOf(Object turmaId) {
        String text = text(turmaId);
        return text == null ? null : longOf(text.split("-", 2)[0]);
    }

    private static Long longOf(Object value) {
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static Integer integer(Object value) {
        Long number = longOf(value);
        return number == null ? null : number.intValue();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> parse(String json) {
        try {
            return json == null ? new LinkedHashMap<>() : mapper.readValue(json, MAP);
        } catch (Exception error) {
            return new LinkedHashMap<>();
        }
    }
}
