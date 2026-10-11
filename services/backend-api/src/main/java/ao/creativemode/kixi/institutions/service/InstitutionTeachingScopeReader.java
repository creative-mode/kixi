package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.repository.TeachingAssignmentRepository;
import ao.creativemode.kixi.shared.service.TeachingScopeReader;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

/**
 * {@link TeachingScopeReader} over the teacher profile and the teaching assignments.
 *
 * <p>An account without a teacher profile teaches nothing, which is what an
 * administrator account does: it administers the school, it does not answer for a class.
 * Such an account is served by the dashboard's own administrative scope instead.</p>
 */
@Service
public class InstitutionTeachingScopeReader implements TeachingScopeReader {

    private final TeacherRepository teachers;
    private final TeachingAssignmentRepository assignments;

    public InstitutionTeachingScopeReader(
            TeacherRepository teachers, TeachingAssignmentRepository assignments) {
        this.teachers = teachers;
        this.assignments = assignments;
    }

    @Override
    public Flux<Long> classIdsTaughtBy(Long accountId) {
        return teachers.findByAccountIdAndDeletedAtIsNull(accountId)
                .flatMapMany(teacher -> assignments.findAllByTeacherIdAndDeletedAtIsNull(teacher.getId()))
                .map(assignment -> assignment.getClassId())
                .distinct();
    }
}