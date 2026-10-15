package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.model.Course;
import ao.creativemode.kixi.academic.model.CourseSubject;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.academic.model.Subject;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.CourseRepository;
import ao.creativemode.kixi.academic.repository.CourseSubjectRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import ao.creativemode.kixi.academic.service.CurriculumService;
import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.dto.structure.AcademicRefs.AssignedTeacher;
import ao.creativemode.kixi.institutions.dto.structure.AcademicRefs.ClassSubject;
import ao.creativemode.kixi.institutions.dto.structure.AcademicRefs.SchoolYearRef;
import ao.creativemode.kixi.institutions.dto.structure.InstitutionStructureResponse;
import ao.creativemode.kixi.institutions.dto.structure.InstitutionStructureResponse.ClassNode;
import ao.creativemode.kixi.institutions.dto.structure.InstitutionStructureResponse.Counts;
import ao.creativemode.kixi.institutions.dto.structure.InstitutionStructureResponse.CourseNode;
import ao.creativemode.kixi.institutions.dto.structure.InstitutionStructureResponse.CurriculumEntry;
import ao.creativemode.kixi.institutions.dto.structure.InstitutionStructureResponse.InstitutionRef;
import ao.creativemode.kixi.institutions.dto.structure.InstitutionStructureResponse.TeacherNode;
import ao.creativemode.kixi.institutions.dto.structure.InstitutionStructureResponse.Warning;
import ao.creativemode.kixi.institutions.dto.structure.SubjectOfferingsResponse;
import ao.creativemode.kixi.institutions.dto.structure.TeacherOverviewResponse;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.model.InstitutionTeacher;
import ao.creativemode.kixi.institutions.model.TeachingAssignment;
import ao.creativemode.kixi.institutions.repository.AcademicDependencyQueries;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.institutions.repository.TeachingAssignmentRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The read models of the academic structure (issue #253): the school as a tree, where a teacher
 * teaches, where a subject is taught and what a class has. Each one loads the rows it needs in a few
 * grouped queries and assembles the tree in memory, so the cost does not grow with the number of
 * classes the way a query per node would.
 */
@Service
public class AcademicOverviewService {

    private final InstitutionRepository institutions;
    private final CourseRepository courses;
    private final ClassRepository classes;
    private final CourseSubjectRepository curriculumRows;
    private final SubjectRepository subjects;
    private final SchoolYearRepository schoolYears;
    private final TeacherRepository teachers;
    private final InstitutionTeacherRepository teacherLinks;
    private final TeachingAssignmentRepository assignments;
    private final AcademicDependencyQueries dependencies;
    private final CurriculumService curriculum;

    public AcademicOverviewService(
        InstitutionRepository institutions,
        CourseRepository courses,
        ClassRepository classes,
        CourseSubjectRepository curriculumRows,
        SubjectRepository subjects,
        SchoolYearRepository schoolYears,
        TeacherRepository teachers,
        InstitutionTeacherRepository teacherLinks,
        TeachingAssignmentRepository assignments,
        AcademicDependencyQueries dependencies,
        CurriculumService curriculum
    ) {
        this.institutions = institutions;
        this.courses = courses;
        this.classes = classes;
        this.curriculumRows = curriculumRows;
        this.subjects = subjects;
        this.schoolYears = schoolYears;
        this.teachers = teachers;
        this.teacherLinks = teacherLinks;
        this.assignments = assignments;
        this.dependencies = dependencies;
        this.curriculum = curriculum;
    }

    // ── What a class has ────────────────────────────────────────────────────

    /**
     * The subjects of a class (its course's curriculum for its grade). Who teaches them is only
     * loaded for callers that may see it; a student gets the subjects alone.
     */
    public Mono<List<ClassSubject>> classSubjects(Long classId, boolean includeTeachers) {
        return classes.findByIdAndDeletedAtIsNull(classId)
            .switchIfEmpty(Mono.error(ApiException.notFound("Class not found")))
            .flatMap(klass -> curriculum.subjectsOfClass(klass).flatMap(found -> {
                if (!includeTeachers || found.isEmpty()) {
                    return Mono.just(found.stream().map(s -> classSubject(s, List.of())).toList());
                }
                return teachersByClassAndSubject(List.of(klass)).map(byKey -> found.stream()
                    .map(s -> classSubject(s, byKey.getOrDefault(key(klass.getId(), s.getId()), List.of())))
                    .toList());
            }));
    }

    // ── The school as a tree ────────────────────────────────────────────────

    public Mono<InstitutionStructureResponse> structure(Long institutionId, Long requestedYearId) {
        return institutions.findByIdAndDeletedAtIsNull(institutionId)
            .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found")))
            .flatMap(institution -> Mono.zip(
                    courses.findAllByInstitutionIdAndDeletedAtIsNull(institutionId).collectList(),
                    classes.findAllByInstitutionIdAndDeletedAtIsNull(institutionId).collectList(),
                    teacherLinks.findAllByInstitutionIdAndDeletedAtIsNull(institutionId).collectList())
                .flatMap(t -> assemble(institution, t.getT1(), t.getT2(), t.getT3(), requestedYearId)));
    }

    private Mono<InstitutionStructureResponse> assemble(
        Institution institution, List<Course> courseList, List<Class> allClasses,
        List<InstitutionTeacher> links, Long requestedYearId
    ) {
        Set<Long> yearIds = allClasses.stream().map(Class::getSchoolYearId).collect(Collectors.toSet());
        if (requestedYearId != null) {
            yearIds.add(requestedYearId);
        }
        return schoolYears.findAllById(yearIds).filter(y -> y.getDeletedAt() == null).collectMap(SchoolYear::getId)
            .flatMap(years -> {
                SchoolYear shown = pickYear(years, allClasses, requestedYearId);
                List<Class> yearClasses = shown == null ? List.of() : allClasses.stream()
                    .filter(c -> c.getSchoolYearId().equals(shown.getId()))
                    .sorted(Comparator.comparing(Class::getGrade).thenComparing(Class::getCode)).toList();
                Set<Long> classIds = yearClasses.stream().map(Class::getId).collect(Collectors.toSet());
                Set<Long> courseIds = courseList.stream().map(Course::getId).collect(Collectors.toSet());
                Set<Long> teacherIds = links.stream().map(InstitutionTeacher::getTeacherId).collect(Collectors.toSet());

                Mono<List<CourseSubject>> rows = courseIds.isEmpty() ? Mono.just(List.of())
                    : curriculumRows.findAllActiveByCourseIds(courseIds).collectList();
                Mono<List<TeachingAssignment>> given = classIds.isEmpty() ? Mono.just(List.of())
                    : assignments.findAllActiveByClassIds(classIds).collectList();
                Mono<Map<Long, Long>> students = dependencies.activeEnrollmentCounts(classIds);

                return Mono.zip(rows, given, students,
                        teachers.findAllById(teacherIds).filter(t -> t.getDeletedAt() == null).collectMap(Teacher::getId))
                    .flatMap(t -> {
                        Set<Long> subjectIds = t.getT1().stream().map(CourseSubject::getSubjectId).collect(Collectors.toSet());
                        return subjects.findAllById(subjectIds).filter(s -> s.getDeletedAt() == null)
                            .collectMap(Subject::getId)
                            .map(subjectMap -> build(institution, shown, courseList, yearClasses, links,
                                t.getT1(), t.getT2(), t.getT3(), t.getT4(), subjectMap));
                    });
            });
    }

    /** The requested year, or else the most recent one in which the school has classes. */
    private static SchoolYear pickYear(Map<Long, SchoolYear> years, List<Class> allClasses, Long requestedYearId) {
        if (requestedYearId != null) {
            return years.get(requestedYearId);
        }
        return allClasses.stream().map(c -> years.get(c.getSchoolYearId())).filter(Objects::nonNull)
            .max(Comparator.comparing(SchoolYear::getStartYear).thenComparing(SchoolYear::getId)).orElse(null);
    }

    private static InstitutionStructureResponse build(
        Institution institution, SchoolYear shown, List<Course> courseList, List<Class> yearClasses,
        List<InstitutionTeacher> links, List<CourseSubject> rows, List<TeachingAssignment> given,
        Map<Long, Long> studentsByClass, Map<Long, Teacher> teacherMap, Map<Long, Subject> subjectMap
    ) {
        Map<Long, List<CourseSubject>> rowsByCourse = CurriculumService.byCourse(rows);
        Map<Long, List<Class>> classesByCourse = yearClasses.stream().collect(Collectors.groupingBy(Class::getCourseId));
        Map<String, List<AssignedTeacher>> teachersBySlot = teachersOf(given, teacherMap);
        Map<Long, Long> assignmentsByTeacher = given.stream()
            .collect(Collectors.groupingBy(TeachingAssignment::getTeacherId, Collectors.counting()));

        List<Warning> warnings = new ArrayList<>();
        Set<Long> subjectsInUse = new HashSet<>();
        List<CourseNode> courseNodes = new ArrayList<>();
        long students = 0;

        for (Course course : courseList.stream().sorted(Comparator.comparing(Course::getCode)).toList()) {
            List<CourseSubject> courseRows = rowsByCourse.getOrDefault(course.getId(), List.of());
            List<CurriculumEntry> entries = courseRows.stream()
                .filter(r -> subjectMap.containsKey(r.getSubjectId()))
                .sorted(Comparator.comparing((CourseSubject r) -> subjectMap.get(r.getSubjectId()).getName(),
                    String.CASE_INSENSITIVE_ORDER).thenComparing(r -> r.getGrade() == null ? 0 : r.getGrade()))
                .map(r -> {
                    Subject s = subjectMap.get(r.getSubjectId());
                    subjectsInUse.add(s.getId());
                    return new CurriculumEntry(r.getId(), s.getId(), s.getCode(), s.getName(), s.getShortName(), r.getGrade());
                }).toList();
            if (entries.isEmpty()) {
                warnings.add(new Warning("COURSE_WITHOUT_CURRICULUM",
                    "The course " + course.getCode() + " has no subjects in its curriculum",
                    course.getId(), null, null, null));
            }
            List<ClassNode> classNodes = new ArrayList<>();
            for (Class klass : classesByCourse.getOrDefault(course.getId(), List.of())) {
                List<ClassSubject> classSubjects = entries.stream()
                    .filter(e -> e.grade() == null || e.grade().equals(klass.getGrade()))
                    .map(CurriculumEntry::subjectId).distinct()
                    .map(subjectMap::get)
                    .sorted(Comparator.comparing(Subject::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(s -> classSubject(s, teachersBySlot.getOrDefault(key(klass.getId(), s.getId()), List.of())))
                    .toList();
                for (ClassSubject cs : classSubjects) {
                    if (cs.teachers().isEmpty()) {
                        warnings.add(new Warning("CLASS_SUBJECT_WITHOUT_TEACHER",
                            "The subject " + cs.code() + " of class " + klass.getCode() + " has no teacher",
                            course.getId(), klass.getId(), cs.subjectId(), null));
                    }
                }
                long count = studentsByClass.getOrDefault(klass.getId(), 0L);
                students += count;
                classNodes.add(new ClassNode(klass.getId(), klass.getCode(), klass.getGrade(), count, classSubjects));
            }
            courseNodes.add(new CourseNode(course.getId(), course.getCode(), course.getName(),
                course.getDescription(), entries, classNodes));
        }

        List<TeacherNode> teacherNodes = links.stream().map(InstitutionTeacher::getTeacherId).distinct()
            .map(teacherMap::get).filter(Objects::nonNull)
            .sorted(Comparator.comparing(Teacher::getFirstName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Teacher::getLastName, String.CASE_INSENSITIVE_ORDER))
            .map(t -> new TeacherNode(t.getId(), t.getFirstName(), t.getLastName(), t.getEmail(),
                assignmentsByTeacher.getOrDefault(t.getId(), 0L)))
            .toList();
        for (TeacherNode teacher : teacherNodes) {
            if (teacher.assignments() == 0) {
                warnings.add(new Warning("TEACHER_WITHOUT_ASSIGNMENTS",
                    teacher.firstName() + " " + teacher.lastName() + " is affiliated but teaches nothing",
                    null, null, null, teacher.id()));
            }
        }

        return new InstitutionStructureResponse(
            new InstitutionRef(institution.getId(), institution.getCode(), institution.getName(), institution.getShortName()),
            shown == null ? null : new SchoolYearRef(shown.getId(), shown.getStartYear(), shown.getEndYear()),
            courseNodes, teacherNodes,
            new Counts(courseList.size(), yearClasses.size(), subjectsInUse.size(), teacherNodes.size(), students),
            warnings);
    }

    // ── Where a teacher teaches ─────────────────────────────────────────────

    public Mono<TeacherOverviewResponse> teacherOverview(Long teacherId) {
        return teachers.findByIdAndDeletedAtIsNull(teacherId)
            .switchIfEmpty(Mono.error(ApiException.notFound("Teacher not found")))
            .flatMap(this::overviewOf);
    }

    public Mono<TeacherOverviewResponse> teacherOverviewForAccount(Long accountId) {
        return teachers.findByAccountIdAndDeletedAtIsNull(accountId)
            .switchIfEmpty(Mono.error(ApiException.notFound("The account has no teacher profile")))
            .flatMap(this::overviewOf);
    }

    private Mono<TeacherOverviewResponse> overviewOf(Teacher teacher) {
        return Mono.zip(
                teacherLinks.findAllByTeacherIdAndDeletedAtIsNull(teacher.getId()).collectList(),
                assignments.findAllByTeacherIdAndDeletedAtIsNull(teacher.getId()).collectList())
            .flatMap(t -> {
                List<TeachingAssignment> given = t.getT2();
                Set<Long> classIds = given.stream().map(TeachingAssignment::getClassId).collect(Collectors.toSet());
                return classes.findAllById(classIds).filter(c -> c.getDeletedAt() == null)
                    .collectMap(Class::getId).flatMap(classMap -> {
                        Set<Long> courseIds = classMap.values().stream().map(Class::getCourseId).collect(Collectors.toSet());
                        Set<Long> institutionIds = new HashSet<>(
                            t.getT1().stream().map(InstitutionTeacher::getInstitutionId).toList());
                        classMap.values().forEach(c -> institutionIds.add(c.getInstitutionId()));
                        Set<Long> subjectIds = given.stream().map(TeachingAssignment::getSubjectId).collect(Collectors.toSet());
                        Set<Long> yearIds = given.stream().map(TeachingAssignment::getSchoolYearId).collect(Collectors.toSet());
                        return Mono.zip(
                                institutions.findAllById(institutionIds).filter(i -> i.getDeletedAt() == null).collectMap(Institution::getId),
                                courses.findAllById(courseIds).collectMap(Course::getId),
                                subjects.findAllById(subjectIds).collectMap(Subject::getId),
                                schoolYears.findAllById(yearIds).collectMap(SchoolYear::getId))
                            .map(refs -> overview(teacher, given, classMap, refs.getT1(), refs.getT2(), refs.getT3(), refs.getT4()));
                    });
            });
    }

    private static TeacherOverviewResponse overview(
        Teacher teacher, List<TeachingAssignment> given, Map<Long, Class> classMap,
        Map<Long, Institution> institutionMap, Map<Long, Course> courseMap,
        Map<Long, Subject> subjectMap, Map<Long, SchoolYear> yearMap
    ) {
        Map<Long, List<TeacherOverviewResponse.AssignmentNode>> byInstitution = new LinkedHashMap<>();
        institutionMap.keySet().stream().sorted().forEach(id -> byInstitution.put(id, new ArrayList<>()));
        for (TeachingAssignment a : given) {
            Class klass = classMap.get(a.getClassId());
            Subject subject = subjectMap.get(a.getSubjectId());
            SchoolYear year = yearMap.get(a.getSchoolYearId());
            Course course = klass == null ? null : courseMap.get(klass.getCourseId());
            if (klass == null || subject == null || year == null || course == null
                || !byInstitution.containsKey(klass.getInstitutionId())) {
                continue;
            }
            byInstitution.get(klass.getInstitutionId()).add(new TeacherOverviewResponse.AssignmentNode(
                a.getId(), klass.getId(), klass.getCode(), klass.getGrade(), course.getId(), course.getName(),
                subject.getId(), subject.getCode(), subject.getName(),
                new SchoolYearRef(year.getId(), year.getStartYear(), year.getEndYear())));
        }
        List<TeacherOverviewResponse.InstitutionNode> nodes = byInstitution.entrySet().stream()
            .map(e -> {
                Institution i = institutionMap.get(e.getKey());
                List<TeacherOverviewResponse.AssignmentNode> list = e.getValue().stream()
                    .sorted(Comparator.comparing((TeacherOverviewResponse.AssignmentNode n) -> n.schoolYear().startYear()).reversed()
                        .thenComparing(TeacherOverviewResponse.AssignmentNode::classCode)
                        .thenComparing(TeacherOverviewResponse.AssignmentNode::subjectName)).toList();
                return new TeacherOverviewResponse.InstitutionNode(i.getId(), i.getCode(), i.getName(), list);
            })
            .sorted(Comparator.comparing(TeacherOverviewResponse.InstitutionNode::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
        return new TeacherOverviewResponse(
            new TeacherOverviewResponse.TeacherRef(teacher.getId(), teacher.getFirstName(), teacher.getLastName()), nodes);
    }

    // ── Where a subject is taught ───────────────────────────────────────────

    public Mono<SubjectOfferingsResponse> subjectOfferings(String code) {
        return subjects.findByCodeAndDeletedAtIsNull(code)
            .switchIfEmpty(Mono.error(ApiException.notFound("Subject not found")))
            .flatMap(subject -> curriculumRows.findActiveBySubject(subject.getId()).collectList().flatMap(rows -> {
                Set<Long> courseIds = rows.stream().map(CourseSubject::getCourseId).collect(Collectors.toSet());
                if (courseIds.isEmpty()) {
                    return Mono.just(offerings(subject, rows, Map.of(), Map.of(), List.of()));
                }
                return courses.findAllById(courseIds).filter(c -> c.getDeletedAt() == null).collectMap(Course::getId)
                    .flatMap(courseMap -> Mono.zip(
                            institutions.findAllById(courseMap.values().stream().map(Course::getInstitutionId).collect(Collectors.toSet()))
                                .filter(i -> i.getDeletedAt() == null).collectMap(Institution::getId),
                            classes.findAllActiveByCourseIds(courseMap.keySet()).collectList())
                        .map(t -> offerings(subject, rows, courseMap, t.getT1(), t.getT2())));
            }));
    }

    private static SubjectOfferingsResponse offerings(
        Subject subject, List<CourseSubject> rows, Map<Long, Course> courseMap,
        Map<Long, Institution> institutionMap, List<Class> classList
    ) {
        Map<Long, List<Class>> classesByCourse = classList.stream().collect(Collectors.groupingBy(Class::getCourseId));
        Map<Long, List<SubjectOfferingsResponse.CourseNode>> byInstitution = new LinkedHashMap<>();
        for (CourseSubject row : rows) {
            Course course = courseMap.get(row.getCourseId());
            if (course == null || !institutionMap.containsKey(course.getInstitutionId())) {
                continue;
            }
            List<SubjectOfferingsResponse.ClassRef> applicable = classesByCourse.getOrDefault(course.getId(), List.of()).stream()
                .filter(c -> row.getGrade() == null || row.getGrade().equals(c.getGrade()))
                .map(c -> new SubjectOfferingsResponse.ClassRef(c.getId(), c.getCode(), c.getGrade())).toList();
            byInstitution.computeIfAbsent(course.getInstitutionId(), k -> new ArrayList<>())
                .add(new SubjectOfferingsResponse.CourseNode(
                    course.getId(), course.getCode(), course.getName(), row.getGrade(), applicable));
        }
        List<SubjectOfferingsResponse.InstitutionNode> nodes = byInstitution.entrySet().stream()
            .map(e -> {
                Institution i = institutionMap.get(e.getKey());
                return new SubjectOfferingsResponse.InstitutionNode(i.getId(), i.getCode(), i.getName(), e.getValue());
            })
            .sorted(Comparator.comparing(SubjectOfferingsResponse.InstitutionNode::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
        return new SubjectOfferingsResponse(
            new SubjectOfferingsResponse.SubjectRef(subject.getId(), subject.getCode(), subject.getName()), nodes);
    }

    // ── Shared pieces ───────────────────────────────────────────────────────

    private Mono<Map<String, List<AssignedTeacher>>> teachersByClassAndSubject(Collection<Class> classList) {
        Set<Long> classIds = classList.stream().map(Class::getId).collect(Collectors.toSet());
        Map<Long, Class> byId = classList.stream().collect(Collectors.toMap(Class::getId, Function.identity()));
        return assignments.findAllActiveByClassIds(classIds)
            // An assignment of another year is not who teaches the class now.
            .filter(a -> byId.get(a.getClassId()).getSchoolYearId().equals(a.getSchoolYearId()))
            .collectList()
            .flatMap(given -> teachers.findAllById(given.stream().map(TeachingAssignment::getTeacherId).collect(Collectors.toSet()))
                .filter(t -> t.getDeletedAt() == null).collectMap(Teacher::getId)
                .map(teacherMap -> teachersOf(given, teacherMap)));
    }

    private static Map<String, List<AssignedTeacher>> teachersOf(List<TeachingAssignment> given, Map<Long, Teacher> teacherMap) {
        Map<String, List<AssignedTeacher>> result = new LinkedHashMap<>();
        for (TeachingAssignment a : given) {
            Teacher teacher = teacherMap.get(a.getTeacherId());
            if (teacher != null) {
                result.computeIfAbsent(key(a.getClassId(), a.getSubjectId()), k -> new ArrayList<>())
                    .add(new AssignedTeacher(a.getId(), teacher.getId(), teacher.getFirstName(), teacher.getLastName()));
            }
        }
        return result;
    }

    private static ClassSubject classSubject(Subject subject, List<AssignedTeacher> teachers) {
        return new ClassSubject(subject.getId(), subject.getCode(), subject.getName(), teachers);
    }

    private static String key(Long classId, Long subjectId) {
        return classId + ":" + subjectId;
    }
}
