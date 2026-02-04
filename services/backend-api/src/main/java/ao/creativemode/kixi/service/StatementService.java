package ao.creativemode.kixi.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.accounts.AccountBasicResponse;
import ao.creativemode.kixi.dto.classe.ClassResponse;
import ao.creativemode.kixi.dto.courses.CourseResponse;
import ao.creativemode.kixi.dto.schoolyears.SchoolYearResponse;
import ao.creativemode.kixi.dto.statement.StatementRequest;
import ao.creativemode.kixi.dto.statement.StatementResponse;
import ao.creativemode.kixi.dto.subject.SubjectResponse;
import ao.creativemode.kixi.dto.term.TermResponse;
import ao.creativemode.kixi.model.Account;
import ao.creativemode.kixi.model.Class;
import ao.creativemode.kixi.model.Course;
import ao.creativemode.kixi.model.SchoolYear;
import ao.creativemode.kixi.model.Statement;
import ao.creativemode.kixi.model.Subject;
import ao.creativemode.kixi.model.Term;
import ao.creativemode.kixi.repository.AccountRepository;
import ao.creativemode.kixi.repository.ClassRepository;
import ao.creativemode.kixi.repository.CourseRepository;
import ao.creativemode.kixi.repository.SchoolYearRepository;
import ao.creativemode.kixi.repository.StatementRepository;
import ao.creativemode.kixi.repository.SubjectRepository;
import ao.creativemode.kixi.repository.TermRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class StatementService {
    private final StatementRepository repository;
    private final SchoolYearRepository schoolYearRepository;
    private final TermRepository termRepository;
    private final SubjectRepository subjectRepository;
    private final ClassRepository classRepository;
    private final CourseRepository courseRepository;
    private final AccountRepository accountRepository;

    public StatementService(
            StatementRepository repository,
            SchoolYearRepository schoolYearRepository,
            TermRepository termRepository,
            SubjectRepository subjectRepository,
            ClassRepository classRepository,
            CourseRepository courseRepository,
            AccountRepository accountRepository
    ) {
        this.repository = repository;
        this.schoolYearRepository = schoolYearRepository;
        this.termRepository = termRepository;
        this.subjectRepository = subjectRepository;
        this.classRepository = classRepository;
        this.courseRepository = courseRepository;
        this.accountRepository = accountRepository;
    }

    public Flux<StatementResponse> listAllActive() {
        return repository.findByDeletedAtIsNull()
                .flatMap(this::toResponse)
                .onErrorResume(e -> Flux.error(
                        ApiException.badRequest("Error listing statements: " + e.getMessage())
                ));
    }

    public Flux<StatementResponse> listTrashed() {
        return repository.findByDeletedAtIsNotNull()
                .flatMap(this::toResponse)
                .onErrorResume(e -> Flux.error(
                        ApiException.badRequest("Error listing deleted statements: " + e.getMessage())
                ));
    }

    public Mono<StatementResponse> getById(Long id) {
        if (id == null || id <= 0) {
            return Mono.error(ApiException.badRequest("Statement ID is required and must be greater than zero"));
        }

        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(
                        ApiException.notFound("Statement with ID " + id + " not found")
                ))
                .flatMap(this::toResponse);
    }

    public Mono<StatementResponse> update(Long id, StatementRequest request) {
        if (id == null || id <= 0) {
            return Mono.error(ApiException.badRequest("Statement ID is required and must be greater than zero"));
        }

        return validateRequest(request)
                .then(repository.findByIdAndDeletedAtIsNull(id))
                .switchIfEmpty(Mono.error(
                        ApiException.notFound("Statement with ID " + id + " not found for update")
                ))
                .flatMap(statement -> {
                    statement.setTitle(request.title());
                    statement.setExamType(request.examType());
                    statement.setDurationMinutes(request.durationMinutes());
                    statement.setVariant(request.variant());
                    statement.setInstructions(request.instructions());
                    statement.setTotalMaxScore(request.totalMaxScore());
                    statement.setSchoolYearId(request.schoolYearId());
                    statement.setTermId(request.termId());
                    statement.setSubjectId(request.subjectId());
                    statement.setClassId(request.classId());
                    statement.setCourseId(request.courseId());
                    statement.setVisible(request.visible());
                    statement.setUpdatedAt(LocalDateTime.now());
                    return repository.save(statement);
                })
                .flatMap(this::toResponse)
                .onErrorResume(ApiException.class, Mono::error)
                .onErrorResume(e -> Mono.error(
                        ApiException.badRequest("Error updating statement: " + e.getMessage())
                ));
    }

    public Mono<Void> softDelete(Long id) {
        if (id == null || id <= 0) {
            return Mono.error(ApiException.badRequest("Statement ID is required and must be greater than zero"));
        }

        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(
                        ApiException.notFound("Statement with ID " + id + " not found for deletion")
                ))
                .flatMap(statement -> {
                    statement.setDeletedAt(LocalDateTime.now());
                    return repository.save(statement);
                })
                .then()
                .onErrorResume(ApiException.class, Mono::error)
                .onErrorResume(e -> Mono.error(
                        ApiException.badRequest("Error deleting statement: " + e.getMessage())
                ));
    }

    public Mono<Void> restore(Long id) {
        if (id == null || id <= 0) {
            return Mono.error(ApiException.badRequest("Statement ID is required and must be greater than zero"));
        }

        return repository.findById(id)
                .switchIfEmpty(Mono.error(
                        ApiException.notFound("Statement with ID " + id + " not found")
                ))
                .filter(statement -> statement.getDeletedAt() != null)
                .switchIfEmpty(Mono.error(
                        ApiException.badRequest("Statement with ID " + id + " is not deleted and cannot be restored")
                ))
                .flatMap(statement -> {
                    statement.setDeletedAt(null);
                    return repository.save(statement);
                })
                .then()
                .onErrorResume(ApiException.class, Mono::error)
                .onErrorResume(e -> Mono.error(
                        ApiException.badRequest("Error restoring statement: " + e.getMessage())
                ));
    }

    public Mono<Void> hardDelete(Long id) {
        if (id == null || id <= 0) {
            return Mono.error(ApiException.badRequest("Statement ID is required and must be greater than zero"));
        }

        return repository.findById(id)
                .switchIfEmpty(Mono.error(
                        ApiException.notFound("Statement with ID " + id + " not found for permanent deletion")
                ))
                .flatMap(statement -> repository.deleteById(id))
                .onErrorResume(ApiException.class, Mono::error)
                .onErrorResume(e -> Mono.error(
                        ApiException.badRequest("Error permanently deleting statement: " + e.getMessage())
                ));
    }

    public Mono<StatementResponse> create(StatementRequest request) {
        return validateRequest(request)
                .then(Mono.defer(() -> {
                    Statement statement = new Statement();
                    statement.setTitle(request.title());
                    statement.setExamType(request.examType());
                    statement.setDurationMinutes(request.durationMinutes());
                    statement.setVariant(request.variant());
                    statement.setInstructions(request.instructions());
                    statement.setTotalMaxScore(request.totalMaxScore());
                    statement.setSchoolYearId(request.schoolYearId());
                    statement.setTermId(request.termId());
                    statement.setSubjectId(request.subjectId());
                    statement.setClassId(request.classId());
                    statement.setCourseId(request.courseId());
                    statement.setVisible(request.visible() != null ? request.visible() : false);
                    statement.setCreatedAt(LocalDateTime.now());

                    return repository.save(statement);
                }))
                .flatMap(this::toResponse)
                .onErrorResume(ApiException.class, Mono::error)
                .onErrorResume(e -> Mono.error(
                        ApiException.badRequest("Error creating statement: " + e.getMessage())
                ));
    }

    private Mono<Void> validateRequest(StatementRequest request) {
        List<String> errors = new ArrayList<>();

        if (request == null) {
            return Mono.error(ApiException.badRequest("Statement data is required"));
        }

        if (request.title() == null || request.title().isBlank()) {
            errors.add("Title is required");
        } else if (request.title().length() < 3) {
            errors.add("Title must have at least 3 characters");
        } else if (request.title().length() > 255) {
            errors.add("Title must have at most 255 characters");
        }

        if (request.examType() == null || request.examType().isBlank()) {
            errors.add("Exam type is required");
        }

        if (request.durationMinutes() != null && request.durationMinutes() <= 0) {
            errors.add("Duration must be greater than zero");
        }

        if (request.totalMaxScore() != null && request.totalMaxScore() < 0) {
            errors.add("Maximum score cannot be negative");
        }

        if (request.schoolYearId() == null) {
            errors.add("School year is required");
        }

        if (request.termId() == null) {
            errors.add("Term is required");
        }

        if (request.subjectId() == null) {
            errors.add("Subject is required");
        }

        if (request.classId() == null) {
            errors.add("Class is required");
        }

        if (!errors.isEmpty()) {
            String errorMessage = String.join("; ", errors);
            return Mono.error(ApiException.badRequest("Validation errors: " + errorMessage));
        }

        return Mono.empty();
    }

    private Mono<StatementResponse> toResponse(Statement statement) {
        Mono<SchoolYearResponse> schoolYearMono = statement.getSchoolYearId() != null
                ? schoolYearRepository.findById(statement.getSchoolYearId())
                    .map(this::toSchoolYearResponse)
                    .switchIfEmpty(Mono.just(new SchoolYearResponse(null, null, null, null, null, null)))
                : Mono.just(new SchoolYearResponse(null, null, null, null, null, null));

        Mono<TermResponse> termMono = statement.getTermId() != null
                ? termRepository.findById(statement.getTermId())
                    .map(this::toTermResponse)
                    .switchIfEmpty(Mono.just(new TermResponse(null, 0, null, null, null, null)))
                : Mono.just(new TermResponse(null, 0, null, null, null, null));

        Mono<SubjectResponse> subjectMono = statement.getSubjectId() != null
                ? subjectRepository.findById(statement.getSubjectId())
                    .map(this::toSubjectResponse)
                    .switchIfEmpty(Mono.just(new SubjectResponse(null, null, null, null, null, null, null)))
                : Mono.just(new SubjectResponse(null, null, null, null, null, null, null));

        Mono<ClassResponse> classMono = statement.getClassId() != null
                ? classRepository.findById(statement.getClassId())
                    .map(this::toClassResponse)
                    .switchIfEmpty(Mono.just(new ClassResponse(null, null, null, null, null, null, null, null)))
                : Mono.just(new ClassResponse(null, null, null, null, null, null, null, null));

        Mono<CourseResponse> courseMono = statement.getCourseId() != null
                ? courseRepository.findById(statement.getCourseId())
                    .map(this::toCourseResponse)
                    .switchIfEmpty(Mono.just(new CourseResponse(null, null, null, null, null, null, null)))
                : Mono.just(new CourseResponse(null, null, null, null, null, null, null));

        Mono<AccountBasicResponse> createdByMono = statement.getCreatedBy() != null
                ? accountRepository.findById(statement.getCreatedBy())
                    .map(this::toAccountResponse)
                    .switchIfEmpty(Mono.just(new AccountBasicResponse(null, null, null)))
                : Mono.just(new AccountBasicResponse(null, null, null));

        return Mono.zip(schoolYearMono, termMono, subjectMono, classMono, courseMono, createdByMono)
                .map(tuple -> new StatementResponse(
                        statement.getId(),
                        statement.getExamType(),
                        statement.getDurationMinutes(),
                        statement.getVariant(),
                        statement.getTitle(),
                        statement.getInstructions(),
                        statement.getTotalMaxScore(),
                        tuple.getT1(),
                        tuple.getT2(),
                        tuple.getT3(),
                        tuple.getT4(),
                        tuple.getT5(),
                        tuple.getT6(),
                        statement.getVisible(),
                        statement.getCreatedAt(),
                        statement.getUpdatedAt()
                ));
    }

    private SchoolYearResponse toSchoolYearResponse(SchoolYear sy) {
        return new SchoolYearResponse(
                sy.getId(), sy.getStartYear(), sy.getEndYear(),
                sy.getCreatedAt(), sy.getUpdatedAt(), sy.getDeletedAt()
        );
    }

    private TermResponse toTermResponse(Term term) {
        return new TermResponse(
                term.getId(), term.getNumber(), term.getName(),
                term.getCreatedAt(), term.getUpdatedAt(), term.getDeletedAt()
        );
    }

    private SubjectResponse toSubjectResponse(Subject subject) {
        return new SubjectResponse(
                subject.getId(), subject.getCode(), subject.getName(), subject.getShortName(),
                subject.getCreatedAt(), subject.getUpdatedAt(), subject.getDeletedAt()
        );
    }

    private ClassResponse toClassResponse(Class clazz) {
        return new ClassResponse(
                clazz.getId(), clazz.getCode(), clazz.getGrade(),
                null, null, // course e schoolYear serão null aqui para evitar recursão
                clazz.getCreatedAt(), clazz.getUpdatedAt(), clazz.getDeletedAt()
        );
    }

    private CourseResponse toCourseResponse(Course course) {
        return new CourseResponse(
                course.getId(), course.getCode(), course.getName(), course.getDescription(),
                course.getCreatedAt(), course.getUpdatedAt(), course.getDeletedAt()
        );
    }

    private AccountBasicResponse toAccountResponse(Account account) {
        return new AccountBasicResponse(
                account.getId(), account.getUsername(), account.getEmail()
        );
    }
}
