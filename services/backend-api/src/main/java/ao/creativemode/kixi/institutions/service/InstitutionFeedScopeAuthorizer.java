package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.model.Enrollment;
import ao.creativemode.kixi.institutions.model.TeachingAssignment;
import ao.creativemode.kixi.institutions.repository.EnrollmentRepository;
import ao.creativemode.kixi.institutions.repository.TeachingAssignmentRepository;
import ao.creativemode.kixi.shared.service.FeedScopeAuthorizer;
import ao.creativemode.kixi.shared.service.FeedScopeAuthorizer.Placement;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link FeedScopeAuthorizer} over the enrollments, teaching assignments and
 * classes of this module.
 *
 * <p>An account can hold both active enrollments and active teaching
 * assignments at once (a teacher who is also a parent-governor enrolled
 * nowhere is not a real case here, but a teacher is never prevented from also
 * being enrolled), so the two sources are combined rather than one taking
 * priority over the other.</p>
 */
@Service
public class InstitutionFeedScopeAuthorizer implements FeedScopeAuthorizer {

    private final EnrollmentRepository enrollments;
    private final TeachingAssignmentRepository teachingAssignments;
    private final TeacherRepository teachers;
    private final ClassRepository classes;

    public InstitutionFeedScopeAuthorizer(
            EnrollmentRepository enrollments,
            TeachingAssignmentRepository teachingAssignments,
            TeacherRepository teachers,
            ClassRepository classes) {
        this.enrollments = enrollments;
        this.teachingAssignments = teachingAssignments;
        this.teachers = teachers;
        this.classes = classes;
    }

    @Override
    public Mono<FeedScope> scopeOf(Long accountId) {
        Flux<Long> enrolledClassIds = enrollments.findAllByAccountIdAndDeletedAtIsNull(accountId)
                .filter(enrollment -> !enrollment.isDeleted())
                .map(Enrollment::getClassId);

        return Flux.concat(enrolledClassIds, classesTaughtBy(accountId))
                .collect(java.util.stream.Collectors.toCollection(HashSet::new))
                .flatMap(this::scopeFromClassIds);
    }

    @Override
    public Mono<Set<Long>> taughtClassesOf(Long accountId) {
        return classesTaughtBy(accountId)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    }

    /**
     * {@code teaching_assignments.teacher_id} points at {@code teachers.id}, not
     * at the account directly (the same indirection {@code TeachingAssignmentService}
     * goes through) - an account with no teacher profile simply teaches nothing.
     */
    private Flux<Long> classesTaughtBy(Long accountId) {
        return teachers.findByAccountIdAndDeletedAtIsNull(accountId)
                .flatMapMany(teacher -> teachingAssignments.findAllByTeacherIdAndDeletedAtIsNull(teacher.getId()))
                .map(TeachingAssignment::getClassId);
    }

    @Override
    public Mono<ClassScope> classScope(Long accountId, Long classId) {
        return scopeOf(accountId)
                .filter(scope -> scope.classIds().contains(classId))
                .flatMap(scope -> classes.findByIdAndDeletedAtIsNull(classId))
                .map(klass -> new ClassScope(
                        klass.getId(), klass.getInstitutionId(), klass.getCourseId(), klass.getSchoolYearId()));
    }

    private Mono<FeedScope> scopeFromClassIds(Set<Long> classIds) {
        if (classIds.isEmpty()) {
            return Mono.just(FeedScope.NONE);
        }
        return classes.findAllById(classIds)
                .filter(klass -> klass.getDeletedAt() == null)
                .collectList()
                .map(this::toScope);
    }

    private FeedScope toScope(List<Class> classesHeld) {
        Set<Long> institutionIds = new HashSet<>();
        Set<Long> classIds = new HashSet<>();
        Set<Long> courseIds = new HashSet<>();
        Set<Long> schoolYearIds = new HashSet<>();
        Set<Placement> placements = new HashSet<>();
        for (Class klass : classesHeld) {
            classIds.add(klass.getId());
            if (klass.getInstitutionId() != null) {
                institutionIds.add(klass.getInstitutionId());
            }
            if (klass.getCourseId() != null) {
                courseIds.add(klass.getCourseId());
            }
            if (klass.getSchoolYearId() != null) {
                schoolYearIds.add(klass.getSchoolYearId());
            }
            placements.add(new Placement(klass.getInstitutionId(), klass.getCourseId(), klass.getSchoolYearId()));
        }
        if (classIds.isEmpty()) {
            return FeedScope.NONE;
        }
        return new FeedScope(institutionIds, classIds, courseIds, schoolYearIds, placements);
    }
}
