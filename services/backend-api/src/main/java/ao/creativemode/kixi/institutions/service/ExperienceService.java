package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.institutions.dto.experience.AcademicHistoryResponse;
import ao.creativemode.kixi.institutions.dto.experience.AcademicHistoryResponse.ExamResult;
import ao.creativemode.kixi.institutions.dto.experience.AcademicHistoryResponse.SubjectResult;
import ao.creativemode.kixi.institutions.dto.experience.AcademicHistoryResponse.Totals;
import ao.creativemode.kixi.institutions.dto.experience.ExperienceResponse;
import ao.creativemode.kixi.institutions.dto.experience.ExperienceResponse.ClassRef;
import ao.creativemode.kixi.institutions.dto.experience.ExperienceResponse.CourseRef;
import ao.creativemode.kixi.institutions.dto.experience.ExperienceResponse.Entry;
import ao.creativemode.kixi.institutions.dto.experience.ExperienceResponse.InstitutionRef;
import ao.creativemode.kixi.institutions.dto.experience.ExperienceResponse.SchoolYearRef;
import ao.creativemode.kixi.institutions.dto.experience.ExperienceResponse.SubjectRef;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.AcademicResultsReader;
import ao.creativemode.kixi.shared.service.AcademicResultsReader.Result;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * The «Experiência» of a profile and the private academic history behind each school year.
 *
 * <p><b>Who sees what.</b> The timeline (places, courses, classes, subjects taught) is public to
 * whoever may see the person's profile: it is gated by {@link PersonProfileService}, so a person
 * who keeps their profile private only shows name and photo and the timeline comes back empty.
 * Results are a different thing: {@link #historyOf} takes only the caller's own account id, there
 * is no route that takes another one, and administrators get no exception.</p>
 *
 * <p>Results come through {@link AcademicResultsReader}, whose rules for which school year a
 * result belongs to are documented there; the SQL over the simulation tables lives in the
 * simulations module, which institutions may not depend on.</p>
 */
@Service
public class ExperienceService {

    private final DatabaseClient database;
    private final PersonProfileService people;
    private final AcademicResultsReader resultsReader;

    public ExperienceService(
        DatabaseClient database, PersonProfileService people, AcademicResultsReader resultsReader
    ) {
        this.database = database;
        this.people = people;
        this.resultsReader = resultsReader;
    }

    /** The timeline of {@code accountId}, as {@code viewer} may see it. */
    public Mono<ExperienceResponse> experience(Long viewerAccountId, boolean viewerIsAdmin, Long accountId) {
        return people.personByAccount(viewerAccountId, viewerIsAdmin, accountId).flatMap(profile -> {
            String role = "teacher".equals(profile.kind()) ? "TEACHER" : "STUDENT";
            if (profile.restricted()) {
                return Mono.just(new ExperienceResponse(role, true, List.of()));
            }
            return ("TEACHER".equals(role) ? teacherEntries(accountId) : studentEntries(accountId))
                .map(entries -> new ExperienceResponse(role, false, entries));
        });
    }

    private Mono<List<Entry>> studentEntries(Long accountId) {
        return database.sql("""
                SELECT sy.id AS year_id, sy.start_year, sy.end_year,
                       i.id AS inst_id, i.name AS inst_name, i.code AS inst_code,
                       co.id AS course_id, co.name AS course_name,
                       c.id AS class_id, c.code AS class_code, c.grade
                  FROM enrollments e
                  JOIN classes c ON c.id = e.class_id
                  JOIN courses co ON co.id = c.course_id
                  JOIN institutions i ON i.id = co.institution_id AND i.deleted_at IS NULL
                  JOIN school_years sy ON sy.id = e.school_year_id
                 WHERE e.account_id = :account AND e.deleted_at IS NULL AND e.status = 'ACTIVE'
                 ORDER BY sy.start_year DESC, i.name
                """)
            .bind("account", accountId)
            .map(row -> new Row(
                row.get("year_id", Long.class), row.get("start_year", Integer.class), row.get("end_year", Integer.class),
                row.get("inst_id", Long.class), row.get("inst_name", String.class), row.get("inst_code", String.class),
                row.get("course_id", Long.class), row.get("course_name", String.class),
                row.get("class_id", Long.class), row.get("class_code", String.class), row.get("grade", Integer.class),
                null, null))
            .all().collectList().map(ExperienceService::group);
    }

    private Mono<List<Entry>> teacherEntries(Long accountId) {
        return database.sql("""
                SELECT sy.id AS year_id, sy.start_year, sy.end_year,
                       i.id AS inst_id, i.name AS inst_name, i.code AS inst_code,
                       co.id AS course_id, co.name AS course_name,
                       c.id AS class_id, c.code AS class_code, c.grade,
                       s.id AS subject_id, s.name AS subject_name
                  FROM teaching_assignments ta
                  JOIN teachers t ON t.id = ta.teacher_id AND t.deleted_at IS NULL
                  JOIN classes c ON c.id = ta.class_id
                  JOIN courses co ON co.id = c.course_id
                  JOIN institutions i ON i.id = co.institution_id AND i.deleted_at IS NULL
                  JOIN subjects s ON s.id = ta.subject_id
                  JOIN school_years sy ON sy.id = ta.school_year_id
                 WHERE t.account_id = :account AND ta.deleted_at IS NULL
                 ORDER BY sy.start_year DESC, i.name, s.name
                """)
            .bind("account", accountId)
            .map(row -> new Row(
                row.get("year_id", Long.class), row.get("start_year", Integer.class), row.get("end_year", Integer.class),
                row.get("inst_id", Long.class), row.get("inst_name", String.class), row.get("inst_code", String.class),
                row.get("course_id", Long.class), row.get("course_name", String.class),
                row.get("class_id", Long.class), row.get("class_code", String.class), row.get("grade", Integer.class),
                row.get("subject_id", Long.class), row.get("subject_name", String.class)))
            .all().collectList().map(ExperienceService::group);
    }

    private record Row(
            Long yearId, Integer start, Integer end, Long instId, String instName, String instCode,
            Long courseId, String courseName, Long classId, String classCode, Integer grade,
            Long subjectId, String subjectName) { }

    /** One entry per school year and school, keeping the order the rows came in (year descending). */
    static List<Entry> group(List<Row> rows) {
        int currentStart = currentSchoolYearStart(LocalDate.now());
        Map<String, List<Row>> byKey = new LinkedHashMap<>();
        for (Row r : rows) {
            byKey.computeIfAbsent(r.yearId() + ":" + r.instId(), k -> new ArrayList<>()).add(r);
        }
        List<Entry> entries = new ArrayList<>();
        for (List<Row> group : byKey.values()) {
            Row first = group.get(0);
            Map<Long, ClassRef> classes = new LinkedHashMap<>();
            Map<Long, SubjectRef> subjects = new LinkedHashMap<>();
            for (Row r : group) {
                classes.putIfAbsent(r.classId(), new ClassRef(r.classId(), r.classCode(), r.grade()));
                if (r.subjectId() != null) {
                    subjects.putIfAbsent(r.subjectId(), new SubjectRef(r.subjectId(), r.subjectName()));
                }
            }
            long courses = group.stream().map(Row::courseId).distinct().count();
            entries.add(new Entry(
                new SchoolYearRef(first.yearId(), first.start() + "/" + first.end()),
                new InstitutionRef(first.instId(), first.instName(), first.instCode()),
                courses == 1 ? new CourseRef(first.courseId(), first.courseName()) : null,
                classes.values().stream().sorted(java.util.Comparator
                    .comparing(ClassRef::grade, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                    .thenComparing(ClassRef::name, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))).toList(),
                List.copyOf(subjects.values()),
                first.start() == currentStart));
        }
        return entries;
    }

    /** The Angolan school year starts in September: from then on it is the year that began this calendar year. */
    static int currentSchoolYearStart(LocalDate today) {
        return today.getMonthValue() >= 9 ? today.getYear() : today.getYear() - 1;
    }

    /**
     * The caller's own results in {@code schoolYearId}. 404 when the account has no enrollment in
     * that year (teachers have none), so nothing is learned about years that are not theirs.
     */
    public Mono<AcademicHistoryResponse> historyOf(Long accountId, Long schoolYearId) {
        return database.sql("""
                SELECT sy.id AS year_id, sy.start_year, sy.end_year,
                       i.id AS inst_id, i.name AS inst_name, i.code AS inst_code,
                       c.id AS class_id, c.code AS class_code, c.grade
                  FROM enrollments e
                  JOIN classes c ON c.id = e.class_id
                  JOIN courses co ON co.id = c.course_id
                  JOIN institutions i ON i.id = co.institution_id
                  JOIN school_years sy ON sy.id = e.school_year_id
                 WHERE e.account_id = :account AND e.school_year_id = :year
                   AND e.deleted_at IS NULL AND e.status = 'ACTIVE'
                """)
            .bind("account", accountId).bind("year", schoolYearId)
            .map(row -> new Object[] {
                new SchoolYearRef(row.get("year_id", Long.class), row.get("start_year", Integer.class) + "/" + row.get("end_year", Integer.class)),
                new InstitutionRef(row.get("inst_id", Long.class), row.get("inst_name", String.class), row.get("inst_code", String.class)),
                new ClassRef(row.get("class_id", Long.class), row.get("class_code", String.class), row.get("grade", Integer.class))})
            .one()
            .switchIfEmpty(Mono.error(ApiException.notFound("No academic history for that school year")))
            .flatMap(base -> resultsReader.finishedIn(accountId, schoolYearId).map(results -> {
                List<Result> all = results;
                return new AcademicHistoryResponse(
                    (SchoolYearRef) base[0], (InstitutionRef) base[1], (ClassRef) base[2],
                    subjectsOf(all), examsOf(all), totalsOf(all));
            }));
    }

    static List<SubjectResult> subjectsOf(List<Result> results) {
        Map<String, List<Result>> bySubject = new LinkedHashMap<>();
        for (Result r : results) {
            bySubject.computeIfAbsent(String.valueOf(r.subjectId()), k -> new ArrayList<>()).add(r);
        }
        List<SubjectResult> out = new ArrayList<>();
        for (List<Result> group : bySubject.values()) {
            List<Double> pct = group.stream().map(Result::percent).filter(p -> p != null).toList();
            Result last = group.get(group.size() - 1);
            out.add(new SubjectResult(
                last.subjectId(), last.subject() == null ? "Sem disciplina" : last.subject(), group.size(),
                pct.isEmpty() ? null : round(pct.stream().mapToDouble(Double::doubleValue).average().orElse(0)),
                pct.isEmpty() ? null : pct.stream().mapToDouble(Double::doubleValue).max().orElse(0),
                last.percent()));
        }
        return out;
    }

    static List<ExamResult> examsOf(List<Result> results) {
        return results.stream().filter(Result::inRoom).map(r -> new ExamResult(
            r.id(), r.title(), r.subject(), r.score(), r.max(), r.date(), "FINISHED")).toList();
    }

    static Totals totalsOf(List<Result> results) {
        List<Double> pct = results.stream().map(Result::percent).filter(p -> p != null).toList();
        return new Totals(results.size(), results.stream().filter(Result::inRoom).count(),
            pct.isEmpty() ? null : round(pct.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
