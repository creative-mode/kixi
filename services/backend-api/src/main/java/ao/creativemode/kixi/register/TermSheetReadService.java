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
    public record Reader(StatementReadScope scope) {
        boolean sees(Summary sheet) {
            if (scope == null) return true;
            return sheet.classId() != null && sheet.subjectId() != null && scope.assignments().stream()
                    .anyMatch(pair -> sheet.classId().equals(pair.classId()) && sheet.subjectId().equals(pair.subjectId()));
        }
    }

    public record Statistics(Object media, Object avaliados, Object inscritos, Object ausentes, Object positivas,
                             Object negativas, Object notaMaxima, Object notaMinima) { }

    public record Summary(String sheetId, String status, Integer termNumber, String schoolYear, Long classId, String className,
                          Object grade, String course, Long subjectId, String subjectName, String subjectCode,
                          Statistics statistics, LocalDateTime updatedAt) { }

    public record Row(Long accountId, Object number, String name, Object mac, Object p1, Object p2, Object mt, String status,
                      boolean adjusted) { }

    public record Detail(Summary sheet, List<Row> rows) { }

    public record Mine(String sheetId, Integer termNumber, String schoolYear, String subjectName, String subjectCode,
                       String className, OwnRow row) { }

    public record OwnRow(Object mac, Object p1, Object p2, Object mt, String status) { }

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
                .flatMap(sheet -> rows(sheetId).collectList().map(rows -> new Detail(sheet, rows)))
                .switchIfEmpty(missing);
    }

    private Flux<Row> rows(String sheetId) {
        return db.sql("SELECT account_id, score, status, CAST(payload AS text) AS p FROM register_term_entries"
                        + " WHERE sheet_id = :id AND component = 'MT'")
                .bind("id", sheetId)
                .map((row, meta) -> {
                    Map<String, Object> payload = parse(row.get("p", String.class));
                    Object mt = payload.get("mt") != null ? payload.get("mt") : row.get("score", java.math.BigDecimal.class);
                    return new Row(row.get("account_id", Long.class), payload.get("numero"), text(payload.get("nome")),
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
                            sheet.className(), new OwnRow(payload.get("mac"), payload.get("npp"), payload.get("npt"), mt,
                            row.get("es", String.class)));
                })
                .all();
    }

    private Summary summary(String sheetId, String status, String contextJson, String statsJson, LocalDateTime updatedAt) {
        Map<String, Object> c = parse(contextJson);
        Map<String, Object> s = parse(statsJson);
        String course = text(c.get("cursoNome")) != null ? text(c.get("cursoNome")) : text(c.get("curso"));
        return new Summary(sheetId, status, integer(c.get("trimestre")), text(c.get("anoLectivo")), classIdOf(c.get("turmaId")),
                text(c.get("turma")), c.get("classe"), course, longOf(c.get("disciplinaId")), text(c.get("disciplina")),
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
