package ao.creativemode.kixi.institutions.repository;

import ao.creativemode.kixi.shared.service.AcademicDependencyReader;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Counts of what hangs from an academic record (issue #253). Enrollments and teaching assignments
 * live here, the structure being protected in {@code academic}, hence {@link AcademicDependencyReader}.
 */
@Repository
public class AcademicDependencyQueries implements AcademicDependencyReader {

    private final DatabaseClient db;

    public AcademicDependencyQueries(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Long> activeEnrollmentsOfClass(Long classId) {
        return count(db.sql("""
            SELECT count(*) FROM enrollments
            WHERE class_id = :id AND deleted_at IS NULL AND status = 'ACTIVE'
            """).bind("id", classId));
    }

    @Override
    public Mono<Long> activeAssignmentsOfClass(Long classId) {
        return count(db.sql("""
            SELECT count(*) FROM teaching_assignments
            WHERE class_id = :id AND deleted_at IS NULL
            """).bind("id", classId));
    }

    @Override
    public Mono<Long> activeAssignmentsOfSubject(Long subjectId) {
        return count(db.sql("""
            SELECT count(*) FROM teaching_assignments
            WHERE subject_id = :id AND deleted_at IS NULL
            """).bind("id", subjectId));
    }

    @Override
    public Mono<Long> assignmentsDependingOnCurriculum(
        Long courseId, Long subjectId, Integer grade, Long ignoredLinkId
    ) {
        String gradeFilter = grade == null ? "" : " AND c.grade = :grade";
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
            SELECT count(*) FROM teaching_assignments ta
            JOIN classes c ON c.id = ta.class_id AND c.deleted_at IS NULL
            WHERE ta.deleted_at IS NULL AND ta.subject_id = :subject AND c.course_id = :course
            """ + gradeFilter + """
             AND NOT EXISTS (
                SELECT 1 FROM course_subjects o
                WHERE o.course_id = :course AND o.subject_id = :subject AND o.deleted_at IS NULL
                  AND o.id <> :ignored AND (o.grade IS NULL OR o.grade = c.grade))
            """)
            .bind("subject", subjectId)
            .bind("course", courseId)
            .bind("ignored", ignoredLinkId == null ? -1L : ignoredLinkId);
        if (grade != null) {
            spec = spec.bind("grade", grade);
        }
        return count(spec);
    }

    /** Active curriculum entries, in courses of the school, that use the subject. */
    public Mono<Long> curriculumEntriesOfSchoolSubject(Long institutionId, Long subjectId) {
        return count(db.sql("""
            SELECT count(*) FROM course_subjects cs
            JOIN courses c ON c.id = cs.course_id AND c.deleted_at IS NULL
            WHERE c.institution_id = :school AND cs.subject_id = :subject AND cs.deleted_at IS NULL
            """).bind("school", institutionId).bind("subject", subjectId));
    }

    /** Active enrollments per class, in one query. Classes without any are absent from the map. */
    public Mono<Map<Long, Long>> activeEnrollmentCounts(Collection<Long> classIds) {
        if (classIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        return db.sql("""
                SELECT class_id, count(*) AS total FROM enrollments
                WHERE class_id = ANY(:ids) AND deleted_at IS NULL AND status = 'ACTIVE'
                GROUP BY class_id
                """)
            .bind("ids", classIds.toArray(new Long[0]))
            .map(row -> Map.entry(row.get("class_id", Long.class), row.get("total", Long.class)))
            .all()
            .collect(HashMap<Long, Long>::new, (map, e) -> map.put(e.getKey(), e.getValue()))
            .map(m -> (Map<Long, Long>) m);
    }

    private static Mono<Long> count(DatabaseClient.GenericExecuteSpec spec) {
        return spec.map(row -> row.get(0, Long.class)).one().defaultIfEmpty(0L);
    }
}
